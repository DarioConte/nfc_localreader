package com.dadaops.cardreader;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.dadaops.cardreader.FiscalCode.calcolaCodiceFiscale;
import static com.dadaops.cardreader.FiscalCode.catastaleFromCf;
import static com.dadaops.cardreader.Json.canonicalMessage;
import static com.dadaops.cardreader.Json.mapToJson;
import static com.dadaops.cardreader.Signer.hmac;
import static com.dadaops.cardreader.Signer.idAnagrafico;
import static com.dadaops.cardreader.Signer.registraNonce;
import static com.dadaops.cardreader.Signer.tokenCarta;
import static com.dadaops.cardreader.Signer.uidCasuale;
import static com.dadaops.cardreader.Territory.risolviCatastale;
import static com.dadaops.cardreader.Territory.risolviLuogoNascita;
import static com.dadaops.cardreader.Text.putIf;

/** Costruisce la risposta uniforme: classificazione tipo, campi canonici, token e checksum HMAC. */
final class DocumentBuilder {
    private DocumentBuilder() {}

    /**
     * Classifica il documento eMRTD e lo arricchisce con paese/UE/categoria + campi specifici del
     * paese (vedi {@link com.dadaops.cardreader.docs.DocumentRegistry} e i parser per paese).
     */
    static String classificaTipo(Map<String, String> dati, String fallback) {
        return com.dadaops.cardreader.docs.DocumentRegistry.classifica(dati, fallback);
    }

    /** Schema uniforme: tipo, campi canonici, campi specifici, token, metadati e checksum HMAC. */
    static String finalizzaJson(String tipo, Map<String, String> dati) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        out.put("tipo", tipo);
        String[] canonici = {"cognome", "nome", "nomeCompleto", "codiceFiscale", "sesso",
                "dataNascita", "luogoNascita", "cittadinanza", "numeroDocumento",
                "dataScadenza", "dataEmissione", "autoritaEmittente", "indirizzo"};
        for (String k : canonici) if (dati.containsKey(k)) out.put(k, dati.get(k));

        String cf = out.get("codiceFiscale");
        if (cf != null) {
            out.put("codiceFiscaleCertified", "true");
            putIf(out, "chiaveAnagrafica", idAnagrafico(cf));
            String cat = catastaleFromCf(cf);   // gia' de-omocodato (vale anche per i codici esteri Z...)
            if (cat != null) {
                out.put("codiceCatastale", cat);
                risolviLuogoNascita(out, cat);
            }
        } else {
            provaCalcoloCF(out);
        }

        for (Map.Entry<String, String> e : dati.entrySet())
            if (!out.containsKey(e.getKey())) out.put(e.getKey(), e.getValue()); // campi specifici

        for (String k : new String[]{"tipo", "luogoNascita", "comuneNascita", "provinciaNascita", "regione", "statoNascita", "paese"})
            if (out.containsKey(k) && out.get(k) != null) out.put(k, out.get(k).toUpperCase());

        aggiungiTokenCarta(out);

        out.put("letturaTimestamp", Instant.now().toString());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        out.put("nonce", nonce);
        registraNonce(nonce);
        out.put("checksum", hmac(canonicalMessage(out)));   // HMAC su tutto cio' che precede
        return mapToJson(out, null);
    }

    /**
     * Calcola il CF dai dati del documento quando non e' presente (passaporto): servono cognome,
     * nome, data di nascita, sesso, cittadinanza ITA e un luogo risolvibile a comune italiano.
     */
    static void provaCalcoloCF(Map<String, String> out) {
        String cognome = out.get("cognome"), nome = out.get("nome"),
                nascita = out.get("dataNascita"), sesso = out.get("sesso"),
                luogo = out.get("luogoNascita");
        String citt = out.get("cittadinanza");
        if (citt != null && !citt.equalsIgnoreCase("ITA")) return;
        if (cognome == null || nome == null || nascita == null || sesso == null || luogo == null) return;
        String cat = risolviCatastale(luogo);
        if (cat == null) return;
        String calc = calcolaCodiceFiscale(cognome, nome, nascita, sesso, cat);
        if (calc == null) return;
        out.put("codiceFiscale", calc);                 // pronto da inserire in una form
        out.put("codiceFiscaleCalcolato", calc);        // nome esplicito: valore calcolato (fittizio)
        out.put("codiceFiscaleCertified", "false");
        out.put("codiceFiscaleDaVerificare", "true");
        out.put("codiceFiscaleNota", "Codice fiscale CALCOLATO dai dati del documento (non presente sul passaporto): "
                + "DA VERIFICARE (es. leggendo poi la Tessera Sanitaria). Non risolve l'omocodia.");
        out.put("codiceCatastale", cat);
        risolviLuogoNascita(out, cat);
    }

    /**
     * Token uniforme di riconoscimento (apertura cancelli) dalla fonte stabile migliore:
     * CF certificato (persona) > PAN > id documento SOD > id chip > numero documento > UID.
     */
    static void aggiungiTokenCarta(Map<String, String> out) {
        if (out.containsKey("tokenCarta")) return;
        String fonte = null, materiale = null;
        if ("true".equals(out.get("codiceFiscaleCertified")) && out.get("codiceFiscale") != null) {
            fonte = "cf"; materiale = out.get("codiceFiscale");
        } else if (out.get("numeroCarta") != null) {
            fonte = "pan"; materiale = out.get("numeroCarta").replaceAll("\\s", "");
        } else if (out.get("idDocumento") != null) {
            fonte = "sod"; materiale = out.get("idDocumento");
        } else if (out.get("idChip") != null) {
            fonte = "chip"; materiale = out.get("idChip");
        } else if (out.get("numeroDocumento") != null) {
            fonte = "numeroDocumento"; materiale = out.getOrDefault("statoEmissione", "") + ":" + out.get("numeroDocumento");
        } else if (out.get("uidCarta") != null) {
            fonte = "uid"; materiale = out.get("uidCarta");
        }
        if (materiale != null) {
            out.put("tokenCarta", tokenCarta(fonte, materiale));
            out.put("tokenCartaFonte", fonte);
            if ("uid".equals(fonte) && uidCasuale(materiale)) out.put("uidCasuale", "true");
        }
    }
}
