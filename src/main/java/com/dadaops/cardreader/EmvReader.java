package com.dadaops.cardreader;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.dadaops.cardreader.Apdu.data;
import static com.dadaops.cardreader.Apdu.sw;
import static com.dadaops.cardreader.Ber.tlvFind;
import static com.dadaops.cardreader.DocumentBuilder.finalizzaJson;
import static com.dadaops.cardreader.Hex.hex;
import static com.dadaops.cardreader.Json.jstr;
import static com.dadaops.cardreader.Text.putIf;

/**
 * Carta di pagamento contactless (EMV): PPSE -> SELECT AID -> GPO -> READ RECORD, con estrazione
 * di PAN/scadenza (sola lettura dei dati pubblici: niente CVV2/PIN, niente pagamenti). PCI-DSS.
 */
final class EmvReader {
    private EmvReader() {}

    private static final byte[][] AID_PAGAMENTO = {
            {(byte) 0xA0, 0, 0, 0, 0x03, 0x10, 0x10},                   // Visa
            {(byte) 0xA0, 0, 0, 0, 0x04, 0x10, 0x10},                   // Mastercard
            {(byte) 0xA0, 0, 0, 0, 0x04, 0x30, 0x60},                   // Maestro
            {(byte) 0xA0, 0, 0, 0, 0x25, 0x01},                         // American Express
            {(byte) 0xA0, 0, 0, 0, 0x65, 0x10, 0x10},                   // JCB
            {(byte) 0xA0, 0, 0, 0x03, 0x33, 0x01, 0x01},                // UnionPay
    };

    static String leggiCartaCredito(CardLink link, String uid, boolean debug) {
        Map<String, String> dati = emvLeggiDati(link, uid, debug);
        if (dati.containsKey("errore") && dati.size() <= 2)
            return "{\"tipo\":\"Carta di credito\",\"errore\":" + jstr(dati.get("errore")) + "}";
        return finalizzaJson("Carta di credito", dati);
    }

    /**
     * Core EMV (riutilizzato da /check e /identify). Se il PDOL richiede il TTQ (9F66) e il PAN
     * non esce al primo colpo, riprova il GPO con valori di TTQ diversi (carte pignole, es. Amex).
     */
    static Map<String, String> emvLeggiDati(CardLink link, String uid, boolean debug) {
        Map<String, String> dati = new LinkedHashMap<>();
        if (uid != null) dati.put("uidCarta", uid);

        byte[] ppse = emvSelect(link, AppContext.PPSE);
        byte[] aid = ppse != null ? tlvFind(ppse, 0x4F) : null;
        if (aid == null) for (byte[] cand : AID_PAGAMENTO) if (emvSelect(link, cand) != null) { aid = cand; break; }
        if (aid == null) { dati.put("errore", "Applicazione EMV non trovata"); return dati; }

        byte[] fci = emvSelect(link, aid);
        dati.put("circuito", circuitoDaAid(aid));
        dati.put("aid", hex(aid));
        byte[] pdol = fci != null ? tlvFind(fci, 0x9F38) : null;

        int[] ttqDaProvare = pdolRichiede(pdol, 0x9F66)
                ? new int[]{0x36, 0x27, 0x86, 0xB6, 0x84} : new int[]{-1};
        byte[] gpoUsato = null; List<byte[]> recordUsati = new ArrayList<>();
        for (int ttq : ttqDaProvare) {
            emvSelect(link, aid);                     // reset stato applicazione prima di ogni GPO
            byte[] gpo = emvGpo(link, costruisciDatiGpo(pdol, ttq));
            List<byte[]> records = new ArrayList<>();
            byte[] afl = gpo != null ? estraiAfl(gpo) : null;
            if (afl != null) leggiRecordDaAfl(link, afl, records);
            if (records.isEmpty()) leggiRecordScansione(link, records);
            if (gpo != null) records.add(gpo);        // alcune carte (MSD) mettono la traccia nel GPO
            gpoUsato = gpo; recordUsati = records;
            estraiPanScadenza(records, dati);
            if (ttq >= 0) dati.put("ttqUsato", String.format("%02X000000", ttq));
            if (dati.containsKey("numeroCarta")) break;   // PAN trovato: basta
        }

        if (debug) {
            putIf(dati, "debugPPSE", ppse != null ? hex(ppse) : null);
            putIf(dati, "debugFCI", fci != null ? hex(fci) : null);
            putIf(dati, "debugPDOL", pdol != null ? hex(pdol) : null);
            putIf(dati, "debugGPO", gpoUsato != null ? hex(gpoUsato) : null);
            StringBuilder rb = new StringBuilder();
            for (byte[] rec : recordUsati) { if (rb.length() > 0) rb.append(" | "); rb.append(hex(rec)); }
            if (rb.length() > 0) dati.put("debugRecords", rb.toString());
        }
        if (!dati.containsKey("numeroCarta"))
            dati.put("nota", "PAN non estratto (carta tokenizzata/wallet, o PDOL specifico). Prova \"debug\":true.");
        else if (ttqDaProvare.length == 1) dati.remove("ttqUsato");
        return dati;
    }

    private static void estraiPanScadenza(List<byte[]> records, Map<String, String> dati) {
        for (byte[] rec : records) {
            byte[] t57 = tlvFind(rec, 0x57);
            if (t57 != null) parseTrack2(t57, dati);
            if (!dati.containsKey("numeroCarta")) {           // Amex/MSD: traccia in 9F6B
                byte[] t6b = tlvFind(rec, 0x9F6B);
                if (t6b != null) parseTrack2(t6b, dati);
            }
            if (!dati.containsKey("numeroCarta")) {
                byte[] pan = tlvFind(rec, 0x5A);
                if (pan != null) dati.put("numeroCarta", raggruppaPan(hex(pan).toUpperCase().replace("F", "")));
            }
            if (!dati.containsKey("scadenza")) {
                byte[] exp = tlvFind(rec, 0x5F24);
                if (exp != null) putIf(dati, "scadenza", scadenzaDaYYMM(hex(exp)));
            }
            byte[] nome = tlvFind(rec, 0x5F20);
            if (nome != null) putIf(dati, "titolare", new String(nome, StandardCharsets.US_ASCII).replace("/", " ").trim());
            byte[] seq = tlvFind(rec, 0x5F34);
            if (seq != null && !dati.containsKey("panSequence")) dati.put("panSequence", hex(seq));
        }
    }

    private static boolean pdolRichiede(byte[] pdol, int wantTag) {
        if (pdol == null) return false;
        int i = 0;
        while (i < pdol.length) {
            int tag = pdol[i] & 0xFF; i++;
            if ((tag & 0x1F) == 0x1F && i < pdol.length) { tag = (tag << 8) | (pdol[i] & 0xFF); i++; }
            if (tag == wantTag) return true;
            if (i < pdol.length) i++;   // salta la lunghezza
        }
        return false;
    }

    private static String circuitoDaAid(byte[] aid) {
        String h = hex(aid).toUpperCase();
        if (h.startsWith("A0000000031010")) return "VISA";
        if (h.startsWith("A0000000041010")) return "MASTERCARD";
        if (h.startsWith("A0000000043060")) return "MAESTRO";
        if (h.startsWith("A00000002501")) return "AMERICAN EXPRESS";
        if (h.startsWith("A0000000651010")) return "JCB";
        if (h.startsWith("A000000333")) return "UNIONPAY";
        if (h.startsWith("A0000001523010") || h.startsWith("A0000001524010")) return "DISCOVER";
        return "SCONOSCIUTO";
    }

    // ---- APDU EMV ----
    static byte[] emvSelect(CardLink link, byte[] name) {
        byte[] apdu = new byte[5 + name.length + 1];
        apdu[0] = 0x00; apdu[1] = (byte) 0xA4; apdu[2] = 0x04; apdu[3] = 0x00; apdu[4] = (byte) name.length;
        System.arraycopy(name, 0, apdu, 5, name.length);   // Le = 0x00 in coda
        return emvTransmit(link, apdu);
    }

    private static byte[] emvGpo(CardLink link, byte[] data) {
        byte[] apdu = new byte[6 + data.length + 1];
        apdu[0] = (byte) 0x80; apdu[1] = (byte) 0xA8; apdu[2] = 0x00; apdu[3] = 0x00; apdu[4] = (byte) data.length;
        System.arraycopy(data, 0, apdu, 5, data.length);
        return emvTransmit(link, apdu);
    }

    /** Trasmette gestendo 0x61xx (GET RESPONSE) e 0x6Cxx (Le corretto). null se non 0x9000. */
    private static byte[] emvTransmit(CardLink link, byte[] apdu) {
        try {
            byte[] r = link.transmit(apdu);
            if (((sw(r) >> 8) & 0xFF) == 0x6C) { apdu[apdu.length - 1] = (byte) (sw(r) & 0xFF); r = link.transmit(apdu); }
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.writeBytes(data(r));
            while (((sw(r) >> 8) & 0xFF) == 0x61) {
                r = link.transmit(new byte[]{0x00, (byte) 0xC0, 0x00, 0x00, (byte) (sw(r) & 0xFF)});
                body.writeBytes(data(r));
            }
            return sw(r) == 0x9000 ? body.toByteArray() : null;
        } catch (Exception e) { return null; }
    }

    /** Dati del GPO: '83 Lc <PDOL valorizzato>' con default, o '83 00' se nessun PDOL. */
    private static byte[] costruisciDatiGpo(byte[] pdol, int ttq) {
        if (pdol == null || pdol.length == 0) return new byte[]{(byte) 0x83, 0x00};
        ByteArrayOutputStream val = new ByteArrayOutputStream();
        int i = 0;
        while (i < pdol.length) {
            int tag = pdol[i] & 0xFF; i++;
            if ((tag & 0x1F) == 0x1F && i < pdol.length) { tag = (tag << 8) | (pdol[i] & 0xFF); i++; }
            if (i >= pdol.length) break;
            int len = pdol[i] & 0xFF; i++;
            val.writeBytes(valoreDefaultPdol(tag, len, ttq));
        }
        byte[] v = val.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x83);
        out.write(v.length);
        out.writeBytes(v);
        return out.toByteArray();
    }

    private static byte[] valoreDefaultPdol(int tag, int len, int ttq) {
        byte[] b = new byte[len];
        switch (tag) {
            case 0x9F66: if (len > 0) b[0] = (byte) (ttq >= 0 ? ttq : 0x36); break;   // TTQ (qVSDC/contactless)
            case 0x9F1A: if (len >= 2) { b[len - 2] = 0x08; b[len - 1] = 0x40; } break; // country 0840
            case 0x5F2A: if (len >= 2) { b[len - 2] = 0x09; b[len - 1] = 0x78; } break; // currency 0978 (EUR)
            case 0x9F35: if (len > 0) b[len - 1] = 0x22; break;            // terminal type
            case 0x9C:   if (len > 0) b[len - 1] = 0x00; break;            // transaction type = purchase
            default: /* zeri */ break;                                     // amount/UN/date/TVR ecc.
        }
        return b;
    }

    private static byte[] estraiAfl(byte[] gpo) {
        byte[] afl = tlvFind(gpo, 0x94);
        if (afl != null) return afl;
        byte[] msg80 = tlvFind(gpo, 0x80);
        if (msg80 != null && msg80.length > 2) return Arrays.copyOfRange(msg80, 2, msg80.length); // salta i 2 byte AIP
        return null;
    }

    private static void leggiRecordDaAfl(CardLink link, byte[] afl, List<byte[]> out) {
        for (int i = 0; i + 4 <= afl.length; i += 4) {
            int sfi = (afl[i] & 0xFF) >> 3;
            int primo = afl[i + 1] & 0xFF, ultimo = afl[i + 2] & 0xFF;
            for (int rec = primo; rec <= ultimo && rec > 0; rec++) {
                byte[] r = emvTransmit(link, new byte[]{0x00, (byte) 0xB2, (byte) rec, (byte) ((sfi << 3) | 0x04), 0x00});
                if (r != null && r.length > 0) out.add(r);
            }
        }
    }

    private static void leggiRecordScansione(CardLink link, List<byte[]> out) {
        for (int sfi = 1; sfi <= 10; sfi++)
            for (int rec = 1; rec <= 16; rec++) {
                byte[] r = emvTransmit(link, new byte[]{0x00, (byte) 0xB2, (byte) rec, (byte) ((sfi << 3) | 0x04), 0x00});
                if (r != null && r.length > 0) out.add(r);
            }
    }

    private static void parseTrack2(byte[] t57, Map<String, String> dati) {
        String s = hex(t57).toUpperCase();
        int d = s.indexOf('D');
        String pan = (d >= 0 ? s.substring(0, d) : s).replace("F", "");
        if (!pan.isEmpty()) dati.put("numeroCarta", raggruppaPan(pan));
        if (d >= 0 && s.length() >= d + 5) putIf(dati, "scadenza", scadenzaDaYYMM(s.substring(d + 1, d + 5)));
    }

    private static String scadenzaDaYYMM(String hexBcd) {
        String s = hexBcd.replaceAll("[^0-9]", "");
        if (s.length() < 4) return null;
        return s.substring(2, 4) + "/20" + s.substring(0, 2);
    }

    private static String raggruppaPan(String pan) {
        return pan.replaceAll("(.{4})(?=.)", "$1 ");
    }
}
