package com.dadaops.cardreader;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.dadaops.cardreader.Apdu.data;
import static com.dadaops.cardreader.Apdu.getUid;
import static com.dadaops.cardreader.Apdu.sw;
import static com.dadaops.cardreader.Hex.hex;
import static com.dadaops.cardreader.Hex.parseHex;
import static com.dadaops.cardreader.Json.jstr;
import static com.dadaops.cardreader.Json.mapToJson;
import static com.dadaops.cardreader.Json.parseFlatJsonOrdered;
import static com.dadaops.cardreader.Signer.tokenCarta;

/** Lettura/scrittura di tag NFC generici (NTAG/Ultralight, MIFARE Classic) e NDEF, sopra CardLink. */
final class NfcReader {
    private NfcReader() {}

    static String leggiNfc(CardLink link) throws CardLinkException {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("tipo", "NFC");
        byte[] uid = getUid(link);
        m.put("uid", uid != null ? hex(uid) : "");
        byte[] atr = link.atr();
        m.put("atr", atr != null ? hex(atr) : "");
        // Token uniforme dall'UID (i tag NTAG/Mifare hanno UID stabile -> ottimo fob da cancello).
        if (uid != null) { m.put("tokenCarta", tokenCarta("uid", hex(uid))); m.put("tokenCartaFonte", "uid"); }

        // Lettura a pagine (NTAG/Ultralight): FF B0 00 <page> 04
        ByteArrayOutputStream mem = new ByteArrayOutputStream();
        boolean pageOk = false;
        for (int page = 0; page < 135; page++) {
            byte[] r = link.transmit(new byte[]{(byte) 0xFF, (byte) 0xB0, 0x00, (byte) page, 0x04});
            if (sw(r) != 0x9000) break;
            byte[] d = data(r);
            if (d.length == 0) break;
            mem.write(d, 0, d.length);
            pageOk = true;
        }

        if (pageOk) {
            byte[] dataBytes = mem.toByteArray();
            m.put("famiglia", "NTAG/Ultralight");
            m.put("memoriaHex", hex(dataBytes));
            String token = parseNdefToken(dataBytes);
            if (token != null) {
                m.put("ndefText", token);
                String t = token.trim();
                if (t.startsWith("{") && t.endsWith("}")) {
                    for (Map.Entry<String, String> e : parseFlatJsonOrdered(t).entrySet())
                        m.putIfAbsent(e.getKey(), e.getValue());
                }
            }
        } else {
            m.put("famiglia", "MIFARE Classic (o non leggibile a pagine)");
            String blocchi = leggiClassicDefault(link);
            if (blocchi != null) m.put("memoriaHex", blocchi);
            else m.put("nota", "Memoria protetta: servono le chiavi del settore. UID comunque disponibile.");
        }
        return mapToJson(m, null);
    }

    private static String leggiClassicDefault(CardLink link) {
        try {
            link.transmit(new byte[]{(byte) 0xFF, (byte) 0x82, 0x00, 0x00, 0x06,
                    (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            boolean any = false;
            for (int block : new int[]{4, 5, 6}) {
                byte[] auth = {(byte) 0xFF, (byte) 0x86, 0x00, 0x00, 0x05, 0x01, 0x00, (byte) block, 0x60, 0x00};
                if (sw(link.transmit(auth)) != 0x9000) continue;
                byte[] r = link.transmit(new byte[]{(byte) 0xFF, (byte) 0xB0, 0x00, (byte) block, 0x10});
                if (sw(r) == 0x9000) { byte[] d = data(r); out.write(d, 0, d.length); any = true; }
            }
            return any ? hex(out.toByteArray()) : null;
        } catch (Exception e) { return null; }
    }

    static String scriviNfc(CardLink link, String text, String hexData, Integer page, Integer block, String key) throws CardLinkException {
        byte[] uid = getUid(link);
        if (uid == null) return "{\"errore\":\"Non e' un tag NFC contactless scrivibile\"}";
        String u = jstr(hex(uid));

        if (text != null) {
            byte[] tlv = buildNdefTextTlv(text);
            if (tlv == null) return "{\"errore\":\"Testo troppo lungo per il formato corto\",\"uid\":" + u + "}";
            int cap = capacitaNtag(link);
            if (cap > 0 && tlv.length > cap)
                return "{\"errore\":\"Dati troppo grandi per il tag (max " + cap + " byte)\",\"uid\":" + u + "}";
            String w = scriviPagine(link, 4, tlv);
            if (w != null) return "{\"tipo\":\"NFC\",\"uid\":" + u + ",\"errore\":" + jstr(w) + "}";
            return "{\"tipo\":\"NFC\",\"esito\":\"scritto\",\"modalita\":\"ndef-text\",\"uid\":" + u + ",\"byteScritti\":" + tlv.length + "}";
        } else if (hexData != null && block != null) {
            byte[] dataB = parseHex(hexData);
            if (dataB.length != 16) return "{\"errore\":\"Per MIFARE Classic servono 16 byte (32 hex)\",\"uid\":" + u + "}";
            String w = scriviClassicBlock(link, block, dataB, key);
            if (w != null) return "{\"tipo\":\"NFC\",\"uid\":" + u + ",\"errore\":" + jstr(w) + "}";
            return "{\"tipo\":\"NFC\",\"esito\":\"scritto\",\"modalita\":\"classic-block\",\"uid\":" + u + ",\"block\":" + block + "}";
        } else if (hexData != null && page != null) {
            byte[] dataB = parseHex(hexData);
            if (dataB.length % 4 != 0) dataB = Arrays.copyOf(dataB, ((dataB.length / 4) + 1) * 4);
            String w = scriviPagine(link, page, dataB);
            if (w != null) return "{\"tipo\":\"NFC\",\"uid\":" + u + ",\"errore\":" + jstr(w) + "}";
            return "{\"tipo\":\"NFC\",\"esito\":\"scritto\",\"modalita\":\"raw-page\",\"uid\":" + u + ",\"page\":" + page + ",\"byteScritti\":" + dataB.length + "}";
        }
        return "{\"errore\":\"Specifica 'text', oppure 'hex'+'page', oppure 'hex'+'block'\",\"uid\":" + u + "}";
    }

    /**
     * Costruisce il record "tessera socio" come JSON compatto da scrivere sul tag NDEF.
     * Esempio: {"chiaveAnagrafica":"9b3f...","codiceSocio":"00123","codiceFamiliare":"01","familiari":"01,02,03"}
     */
    static String costruisciRecordSocio(String chiave, String socio, String codFam, String familiari) {
        LinkedHashMap<String, String> r = new LinkedHashMap<>();
        if (chiave != null && !chiave.isBlank()) r.put("chiaveAnagrafica", chiave.trim());
        if (socio != null && !socio.isBlank()) r.put("codiceSocio", socio.trim());
        if (codFam != null && !codFam.isBlank()) r.put("codiceFamiliare", codFam.trim());
        if (familiari != null && !familiari.isBlank()) {
            StringBuilder fb = new StringBuilder();
            for (String p : familiari.split(",")) {
                String v = p.trim();
                if (v.isEmpty()) continue;
                if (fb.length() > 0) fb.append(",");
                fb.append(v);
            }
            if (fb.length() > 0) r.put("familiari", fb.toString());
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : r.entrySet()) {
            if (!first) sb.append(",");
            sb.append(jstr(e.getKey())).append(":").append(jstr(e.getValue()));
            first = false;
        }
        return sb.append("}").toString();
    }

    private static String scriviPagine(CardLink link, int startPage, byte[] data) throws CardLinkException {
        int page = startPage;
        for (int off = 0; off < data.length; off += 4, page++) {
            byte[] c = new byte[4];
            System.arraycopy(data, off, c, 0, Math.min(4, data.length - off));
            // 1) Update Binary PC/SC (storage card): funziona su molti lettori per le pagine NTAG/Ultralight.
            byte[] apdu = {(byte) 0xFF, (byte) 0xD6, 0x00, (byte) page, 0x04, c[0], c[1], c[2], c[3]};
            int s = sw(link.transmit(apdu));
            if (s == 0x9000) continue;
            // 2) Fallback ACR122U/PN532: comando nativo Type-2 WRITE (0xA2) via pseudo-APDU pass-through.
            //    Su molti ACR122U FF D6 ritorna 6300 sugli NTAG/Ultralight: il write nativo invece passa.
            int sn = scriviPaginaNativa(link, page, c);
            if (sn == 0x9000) continue;
            int shown = (sn == 0x6D00 || sn == 0x6A81 || s == 0x6D00 || s == 0x6A81) ? 0x6D00 : s;
            if (shown == 0x6D00)
                return "Scrittura non supportata dal lettore o tag in sola lettura (SW " + String.format("%04X", s) + ")";
            return "Errore in scrittura pagina " + page + " (SW " + String.format("%04X", s)
                    + ", fallback nativo SW " + String.format("%04X", sn) + ")";
        }
        return null;
    }

    /**
     * Scrittura di una pagina col comando nativo Type-2 {@code 0xA2 <page> <4 byte>}, incapsulato nel
     * pass-through diretto dell'ACR122U/PN532: {@code FF 00 00 00 Lc | D4 40 01 (InDataExchange, target 1) | A2 ...}.
     * Ritorna 0x9000 se il PN532 conferma l'esecuzione (risposta {@code D5 41 00}), altrimenti la SW grezza.
     */
    private static int scriviPaginaNativa(CardLink link, int page, byte[] c) throws CardLinkException {
        byte[] apdu = {(byte) 0xFF, 0x00, 0x00, 0x00, 0x09,
                (byte) 0xD4, 0x40, 0x01, (byte) 0xA2, (byte) page, c[0], c[1], c[2], c[3]};
        byte[] r = link.transmit(apdu);
        int sw = sw(r);
        if (sw != 0x9000) return sw;
        byte[] d = data(r);                       // atteso: D5 41 <status>; status 00 = ok
        if (d.length >= 3 && (d[0] & 0xFF) == 0xD5 && (d[1] & 0xFF) == 0x41 && (d[2] & 0xFF) == 0x00)
            return 0x9000;
        return 0x6300;                            // pass-through eseguito ma scrittura non confermata
    }

    private static int capacitaNtag(CardLink link) {
        try {
            byte[] r = link.transmit(new byte[]{(byte) 0xFF, (byte) 0xB0, 0x00, 0x03, 0x04});
            byte[] d = data(r);
            if (sw(r) == 0x9000 && d.length >= 3) return (d[2] & 0xFF) * 8;
        } catch (CardLinkException ignored) {}
        return -1;
    }

    private static String scriviClassicBlock(CardLink link, int block, byte[] data, String key) throws CardLinkException {
        byte[] k = (key != null && key.replaceAll("[^0-9A-Fa-f]", "").length() == 12)
                ? parseHex(key)
                : new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        link.transmit(new byte[]{(byte) 0xFF, (byte) 0x82, 0x00, 0x00, 0x06, k[0], k[1], k[2], k[3], k[4], k[5]});
        byte[] auth = {(byte) 0xFF, (byte) 0x86, 0x00, 0x00, 0x05, 0x01, 0x00, (byte) block, 0x60, 0x00};
        if (sw(link.transmit(auth)) != 0x9000)
            return "Autenticazione settore fallita (chiave errata?)";
        byte[] w = new byte[5 + 16];
        w[0] = (byte) 0xFF; w[1] = (byte) 0xD6; w[2] = 0x00; w[3] = (byte) block; w[4] = 0x10;
        System.arraycopy(data, 0, w, 5, 16);
        int s = sw(link.transmit(w));
        if (s != 0x9000) return "Errore in scrittura blocco " + block + " (SW " + String.format("%04X", s) + ")";
        return null;
    }

    private static byte[] buildNdefTextTlv(String text) {
        try {
            byte[] t = text.getBytes(StandardCharsets.UTF_8);
            byte[] lang = "en".getBytes(StandardCharsets.US_ASCII);
            int payloadLen = 1 + lang.length + t.length;
            ByteArrayOutputStream ndef = new ByteArrayOutputStream();
            ndef.write(0xD1);                 // MB+ME+SR, TNF=0x01 (well-known)
            ndef.write(0x01);                 // type length
            ndef.write(payloadLen);           // payload length (short record)
            ndef.write('T');                  // type = Text
            ndef.write(lang.length & 0x3F);   // status: UTF-8 + lunghezza lingua
            ndef.write(lang);
            ndef.write(t);
            byte[] msg = ndef.toByteArray();
            if (msg.length > 254) return null;
            ByteArrayOutputStream tlv = new ByteArrayOutputStream();
            tlv.write(0x03);                  // NDEF Message TLV
            tlv.write(msg.length);
            tlv.write(msg);
            tlv.write(0xFE);                  // Terminator TLV
            byte[] out = tlv.toByteArray();
            if (out.length % 4 != 0) out = Arrays.copyOf(out, ((out.length / 4) + 1) * 4); // pad a pagine
            return out;
        } catch (Exception e) { return null; }
    }

    private static String parseNdefToken(byte[] data) {
        int start = Math.min(16, data.length); // area utente da pagina 4 (offset 16)
        for (int i = start; i < data.length; ) {
            int t = data[i] & 0xFF;
            if (t == 0x00) { i++; continue; }
            if (t == 0xFE) break;
            if (i + 1 >= data.length) break;
            int len = data[i + 1] & 0xFF; int vs = i + 2;
            if (len == 0xFF) {
                if (i + 3 >= data.length) break;
                len = ((data[i + 2] & 0xFF) << 8) | (data[i + 3] & 0xFF); vs = i + 4;
            }
            if (t == 0x03) {
                int end = Math.min(vs + len, data.length);
                return primoRecordTesto(Arrays.copyOfRange(data, vs, end));
            }
            i = vs + len;
        }
        return null;
    }

    private static String primoRecordTesto(byte[] msg) {
        if (msg.length < 3) return null;
        int header = msg[0] & 0xFF;
        boolean sr = (header & 0x10) != 0;
        boolean il = (header & 0x08) != 0;
        int p = 1;
        int typeLen = msg[p++] & 0xFF;
        long plen;
        if (sr) plen = msg[p++] & 0xFF;
        else {
            plen = ((long) (msg[p] & 0xFF) << 24) | ((msg[p + 1] & 0xFF) << 16)
                    | ((msg[p + 2] & 0xFF) << 8) | (msg[p + 3] & 0xFF);
            p += 4;
        }
        int idLen = il ? (msg[p++] & 0xFF) : 0;
        if (p + typeLen > msg.length) return null;
        String type = new String(msg, p, typeLen, StandardCharsets.US_ASCII);
        p += typeLen + idLen;
        int pe = (int) Math.min(p + plen, msg.length);
        if (p > pe) return null;
        byte[] payload = Arrays.copyOfRange(msg, p, pe);
        if ("T".equals(type)) {
            if (payload.length == 0) return "";
            int status = payload[0] & 0xFF;
            int langLen = status & 0x3F;
            int ts = 1 + langLen;
            if (ts > payload.length) return "";
            return new String(payload, ts, payload.length - ts,
                    (status & 0x80) != 0 ? StandardCharsets.UTF_16 : StandardCharsets.UTF_8);
        } else if ("U".equals(type)) {
            return payload.length == 0 ? "" : new String(payload, 1, payload.length - 1, StandardCharsets.UTF_8);
        }
        return new String(payload, StandardCharsets.UTF_8);
    }
}
