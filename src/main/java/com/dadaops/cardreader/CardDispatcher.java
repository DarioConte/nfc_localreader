package com.dadaops.cardreader;

import java.util.List;
import java.util.Map;

import com.dadaops.cardreader.Detector.Rilevazione;
import com.dadaops.cardreader.Detector.Tipo;
import com.dadaops.cardreader.EmrtdReader.CanException;
import com.dadaops.cardreader.EmrtdReader.DatiMrz;

import static com.dadaops.cardreader.Detector.diagnostica;
import static com.dadaops.cardreader.Detector.rileva;
import static com.dadaops.cardreader.Detector.tipoString;
import static com.dadaops.cardreader.DocumentBuilder.classificaTipo;
import static com.dadaops.cardreader.DocumentBuilder.finalizzaJson;
import static com.dadaops.cardreader.EmrtdReader.diagnosticaEmrtd;
import static com.dadaops.cardreader.EmrtdReader.leggiCie;
import static com.dadaops.cardreader.EmrtdReader.leggiPassaporto;
import static com.dadaops.cardreader.EmvReader.emvLeggiDati;
import static com.dadaops.cardreader.EmvReader.leggiCartaCredito;
import static com.dadaops.cardreader.Json.jstr;
import static com.dadaops.cardreader.NfcReader.leggiNfc;
import static com.dadaops.cardreader.Signer.tokenCarta;
import static com.dadaops.cardreader.Signer.uidCasuale;
import static com.dadaops.cardreader.TsCnsReader.leggiTsCns;

/** Smista la lettura al reader giusto in base al tipo di carta, usando la {@link CardSource} iniettata. */
final class CardDispatcher {
    private CardDispatcher() {}

    static String readersArrayJson(CardSource src) throws CardLinkException {
        StringBuilder sb = new StringBuilder("[");
        List<ReaderInfo> rs = src.readers();
        for (int i = 0; i < rs.size(); i++) {
            ReaderInfo r = rs.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"index\":").append(r.index)
                    .append(",\"name\":").append(jstr(r.name))
                    .append(",\"cardPresent\":").append(r.cardPresent).append("}");
        }
        return sb.append("]").toString();
    }

    /** /check: legge la carta e ritorna il JSON completo (richiede chiave per gli eID). */
    static String leggiCartaJson(String can, String readerSel, boolean includiFoto, DatiMrz mrz, boolean debug) throws Exception {
        CardSource src = Platform.source();
        boolean senzaSel = (readerSel == null || readerSel.isBlank());
        if (senzaSel && src.countWithCard() > 1)
            return "{\"errore\":\"Piu' carte inserite: specifica il lettore con 'reader'\",\"ambiguo\":true,\"readers\":"
                    + readersArrayJson(src) + "}";
        String reader = src.resolve(readerSel);
        if (reader == null)
            return senzaSel ? "{\"errore\":\"Nessun lettore con carta inserita\"}"
                    : "{\"errore\":\"Lettore non trovato\",\"reader\":" + jstr(readerSel) + "}";
        if (!src.isCardPresent(reader))
            return "{\"errore\":\"Nessuna carta sul lettore\",\"reader\":" + jstr(reader) + "}";

        Rilevazione r;
        CardLink link = src.connect(reader);
        try { r = rileva(link); } finally { link.disconnect(true); }   // reset, pulito per PACE
        Tipo tipo = r.tipo;
        String uid = r.uid;

        if (tipo == Tipo.TS_CNS) {
            try (CardLink l = src.connect(reader)) {
                Map<String, String> dati = leggiTsCns(l);
                if (dati == null) return "{\"errore\":\"Dati personali non selezionabili\"}";
                if (uid != null) dati.put("uidCarta", uid);
                return finalizzaJson("TS-CNS", dati);
            }
        } else if (tipo == Tipo.CIE) {
            if (debug) return diagnosticaEmrtd(reader, can, mrz, uid);
            if ((can == null || can.isBlank()) && mrz != null) {
                try {
                    Map<String, String> dati = leggiPassaporto(reader, mrz, includiFoto);
                    if (uid != null) dati.put("uidCarta", uid);
                    return finalizzaJson(classificaTipo(dati, "Passaporto"), dati);
                } catch (Exception e) {
                    return "{\"tipo\":\"Passaporto\",\"errore\":"
                            + jstr("Lettura fallita (verifica numero documento/date MRZ): " + e.getMessage()) + "}";
                }
            }
            if (can == null || can.isBlank())
                return "{\"tipo\":\"eMRTD\",\"errore\":\"Serve il CAN (CIE) o i dati MRZ "
                        + "(documentNumber, dateOfBirth, dateOfExpiry) per il passaporto\"}";
            try {
                Map<String, String> dati = leggiCie(reader, can.trim(), includiFoto);
                if (uid != null) dati.put("uidCarta", uid);
                return finalizzaJson(classificaTipo(dati, "CIE"), dati);
            } catch (CanException ce) {
                return "{\"tipo\":\"CIE\",\"canErrato\":true,\"messaggio\":\"Codice CAN Errato\",\"errore\":"
                        + jstr(ce.getMessage()) + "}";
            } catch (Exception e) {
                try {
                    Thread.sleep(150);
                    Map<String, String> dati = leggiCie(reader, can.trim(), includiFoto);
                    if (uid != null) dati.put("uidCarta", uid);
                    return finalizzaJson("CIE", dati);
                } catch (CanException ce2) {
                    return "{\"tipo\":\"CIE\",\"canErrato\":true,\"messaggio\":\"Codice CAN Errato\",\"errore\":"
                            + jstr(ce2.getMessage()) + "}";
                } catch (Exception e2) {
                    return "{\"tipo\":\"CIE\",\"errore\":" + jstr("Lettura fallita: " + e2.getMessage()) + "}";
                }
            }
        } else if (tipo == Tipo.EMV) {
            try (CardLink l = src.connect(reader)) { return leggiCartaCredito(l, uid, debug); }
        } else if (tipo == Tipo.NFC) {
            try (CardLink l = src.connect(reader)) { return leggiNfc(l); }
        }
        try (CardLink l = src.connect(reader)) { return diagnostica(l); }
    }

    /** /identify: preflight senza CAN/MRZ -> {tipo, tokenCarta, tokenCartaFonte}. */
    static String identifica(String readerSel) throws Exception {
        CardSource src = Platform.source();
        boolean senzaSel = (readerSel == null || readerSel.isBlank());
        if (senzaSel && src.countWithCard() > 1)
            return "{\"errore\":\"Piu' carte inserite: specifica il lettore con 'reader'\",\"ambiguo\":true,\"readers\":"
                    + readersArrayJson(src) + "}";
        String reader = src.resolve(readerSel);
        if (reader == null) return "{\"errore\":\"Nessun lettore con carta inserita\"}";
        if (!src.isCardPresent(reader)) return "{\"tipo\":null,\"cardPresent\":false}";

        Rilevazione r;
        CardLink link = src.connect(reader);
        try { r = rileva(link); } finally { link.disconnect(true); }
        String tipoStr = tipoString(r.tipo);
        String token = null, fonte = null;
        try {
            if (r.tipo == Tipo.TS_CNS) {
                try (CardLink l = src.connect(reader)) {
                    Map<String, String> d = leggiTsCns(l);
                    String cf = d != null ? d.get("codiceFiscale") : null;
                    if (cf != null) { token = tokenCarta("cf", cf); fonte = "cf"; }
                }
            } else if (r.tipo == Tipo.EMV) {
                try (CardLink l = src.connect(reader)) {
                    Map<String, String> d = emvLeggiDati(l, r.uid, false);
                    String pan = d.get("numeroCarta");
                    if (pan != null) { token = tokenCarta("pan", pan.replaceAll("\\s", "")); fonte = "pan"; }
                    else if (r.uid != null) { token = tokenCarta("uid", r.uid); fonte = "uid"; }
                }
            } else if (r.tipo == Tipo.NFC && r.uid != null) {
                token = tokenCarta("uid", r.uid); fonte = "uid";
            } else if (r.tipo == Tipo.CIE) {
                return "{\"tipo\":" + jstr(tipoStr) + ",\"tokenCarta\":null,\"richiedeAutenticazione\":true,"
                        + "\"messaggio\":\"Documento eID: serve CAN (CIE) o dati MRZ (passaporto), non identificabile in preflight\"}";
            }
        } catch (Exception e) {
            return "{\"tipo\":" + jstr(tipoStr) + ",\"tokenCarta\":null,\"errore\":" + jstr(String.valueOf(e.getMessage())) + "}";
        }
        if (token == null)
            return "{\"tipo\":" + jstr(tipoStr) + ",\"tokenCarta\":null}";
        String extra = ("uid".equals(fonte) && uidCasuale(r.uid))
                ? ",\"uidCasuale\":true,\"avviso\":\"UID casuale: token non stabile tra un tap e l'altro\"" : "";
        return "{\"tipo\":" + jstr(tipoStr) + ",\"tokenCarta\":" + jstr(token)
                + ",\"tokenCartaFonte\":" + jstr(fonte) + extra + "}";
    }

    /** /write: scrive un tag NFC sul lettore dato. */
    static String scrivi(String readerSel, String text, String hexData, Integer page, Integer block, String key) throws Exception {
        CardSource src = Platform.source();
        boolean senzaSel = (readerSel == null || readerSel.isBlank());
        if (senzaSel && src.countWithCard() > 1)
            return "{\"errore\":\"Piu' carte inserite: specifica il lettore con 'reader'\",\"ambiguo\":true,\"readers\":"
                    + readersArrayJson(src) + "}";
        String reader = src.resolve(readerSel);
        if (reader == null)
            return senzaSel ? "{\"errore\":\"Nessun lettore con carta inserita\"}"
                    : "{\"errore\":\"Lettore non trovato\",\"reader\":" + jstr(readerSel) + "}";
        if (!src.isCardPresent(reader))
            return "{\"errore\":\"Nessuna carta sul lettore\",\"reader\":" + jstr(reader) + "}";
        try (CardLink l = src.connect(reader)) {
            return NfcReader.scriviNfc(l, text, hexData, page, block, key);
        }
    }

    /** /actuator/status: tipo carta senza leggere i dati. */
    static String stato(String readerSel) throws CardLinkException {
        CardSource src = Platform.source();
        String reader = src.resolve(readerSel);
        if (reader == null) return "{\"reader\":null,\"cardPresent\":false,\"tipo\":null}";
        boolean present = src.isCardPresent(reader);
        String tipo = null;
        if (present) {
            CardLink link = src.connect(reader);
            try { tipo = tipoString(rileva(link).tipo); } finally { link.disconnect(true); }
        }
        return "{\"reader\":" + jstr(reader) + ",\"cardPresent\":" + present + ",\"tipo\":" + jstr(tipo) + "}";
    }
}
