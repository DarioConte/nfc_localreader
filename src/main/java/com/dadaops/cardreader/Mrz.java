package com.dadaops.cardreader;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.dadaops.cardreader.Text.isoDaMrz;
import static com.dadaops.cardreader.Text.putIf;

/** Estrazione e parsing della MRZ (TD1 carta d'identità, TD3 passaporto). */
final class Mrz {
    private Mrz() {}

    static String estraiMrz(byte[] dg1) {
        for (int i = 0; i + 1 < dg1.length; i++) {
            if ((dg1[i] & 0xFF) == 0x5F && (dg1[i + 1] & 0xFF) == 0x1F) {
                int j = i + 2;
                if (j >= dg1.length) break;
                int len = dg1[j] & 0xFF; j++;
                if (len == 0x81) { len = dg1[j] & 0xFF; j++; }
                else if (len == 0x82) { len = ((dg1[j] & 0xFF) << 8) | (dg1[j + 1] & 0xFF); j += 2; }
                if (j + len <= dg1.length) return new String(dg1, j, len, StandardCharsets.ISO_8859_1);
            }
        }
        Matcher m = Pattern.compile("[A-Z0-9<]{30,}").matcher(new String(dg1, StandardCharsets.ISO_8859_1));
        String best = null;
        while (m.find()) if (best == null || m.group().length() > best.length()) best = m.group();
        if (best != null && best.length() > 90) best = best.substring(best.length() - 90);
        return best;
    }

    /** Sceglie il parser in base al formato: TD3 (passaporto, 2x44) o TD1 (ID, 3x30). */
    static void parseMrz(String mrz, Map<String, String> out) {
        if (mrz == null) return;
        String m = mrz.replace("\n", "").replace("\r", "");
        if (m.length() >= 88 && m.length() < 90) parseMrzTd3(m, out);   // 2x44 = 88
        else parseMrzTd1(m, out);                                       // 3x30 = 90
    }

    /** MRZ TD3 (passaporto): 2 righe da 44. */
    static void parseMrzTd3(String mrz, Map<String, String> out) {
        if (mrz.length() < 88) return;
        String l1 = mrz.substring(0, 44);
        String l2 = mrz.substring(44, 88);
        String[] nomi = l1.substring(5).split("<<", 2);
        putIf(out, "cognome", nomi[0].replace('<', ' ').trim());
        putIf(out, "nome", nomi.length > 1 ? nomi[1].replace('<', ' ').trim() : "");
        putIf(out, "numeroDocumento", l2.substring(0, 9).replace("<", "").trim());
        putIf(out, "cittadinanza", l2.substring(10, 13).replace("<", ""));
        putIf(out, "dataNascita", isoDaMrz(l2.substring(13, 19), false));
        char sesso = l2.charAt(20);
        if (sesso == 'M' || sesso == 'F') out.put("sesso", String.valueOf(sesso));
        putIf(out, "dataScadenza", isoDaMrz(l2.substring(21, 27), true));
        putIf(out, "statoEmissione", l1.substring(2, 5).replace("<", ""));
        putIf(out, "tipoDocumento", l1.substring(0, 2).replace("<", "").trim());
    }

    /** MRZ TD1 (carta d'identità): 3 righe da 30. */
    static void parseMrzTd1(String mrz, Map<String, String> out) {
        if (mrz.length() < 90) return;
        String l1 = mrz.substring(0, 30);
        String l2 = mrz.substring(30, 60);
        String l3 = mrz.substring(60, 90);
        char sesso = l2.charAt(7);
        String[] nomi = l3.split("<<", 2);
        putIf(out, "cognome", nomi[0].replace('<', ' ').trim());
        putIf(out, "nome", nomi.length > 1 ? nomi[1].replace('<', ' ').trim() : "");
        if (sesso == 'M' || sesso == 'F') out.put("sesso", String.valueOf(sesso));
        putIf(out, "dataNascita", isoDaMrz(l2.substring(0, 6), false));
        putIf(out, "cittadinanza", l2.substring(15, 18).replace("<", ""));
        putIf(out, "dataScadenza", isoDaMrz(l2.substring(8, 14), true));
        putIf(out, "numeroDocumento", l1.substring(5, 14).replace("<", "").trim());
        putIf(out, "statoEmissione", l1.substring(2, 5).replace("<", ""));
        putIf(out, "tipoDocumento", l1.substring(0, 2).replace("<", "").trim());
    }
}
