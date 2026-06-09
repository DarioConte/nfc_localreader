package com.dadaops.cardreader;

import java.text.Normalizer;
import java.util.regex.Matcher;

/** Codice fiscale: estrazione/match, codice catastale (de-omocodato) e calcolo dai dati anagrafici. */
final class FiscalCode {
    private FiscalCode() {}

    private static final String CF_MESI = "ABCDEHLMPRST";
    private static final int[] CF_DISP_NUM = {1, 0, 5, 7, 9, 13, 15, 17, 19, 21};
    private static final int[] CF_DISP_LET = {1, 0, 5, 7, 9, 13, 15, 17, 19, 21, 2, 4, 18, 20, 11, 3, 6, 8, 12, 14, 16, 10, 22, 25, 24, 23};

    static String matchCf(String s) {
        if (s == null) return null;
        Matcher m = AppContext.CF.matcher(s.toUpperCase());
        return m.find() ? m.group() : null;
    }

    /** Estrae il codice catastale (Belfiore) dal CF, ripristinando l'omocodia sulle 3 cifre. */
    static String catastaleFromCf(String cf) {
        if (cf == null || cf.length() < 15) return null;
        String code = cf.substring(11, 15).toUpperCase();
        final String omo = "LMNPQRSTUV"; // L=0, M=1, ... V=9
        StringBuilder sb = new StringBuilder();
        sb.append(code.charAt(0)); // lettera di provincia: invariata
        for (int i = 1; i < 4; i++) {
            char c = code.charAt(i);
            int idx = omo.indexOf(c);
            sb.append(idx >= 0 ? (char) ('0' + idx) : c); // riconverte eventuale omocodia
        }
        return sb.toString();
    }

    static String normalizzaNome(String s) {
        if (s == null) return "";
        String u = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toUpperCase();
        StringBuilder b = new StringBuilder();
        for (char c : u.toCharArray()) if (c >= 'A' && c <= 'Z') b.append(c);
        return b.toString();
    }

    private static String cfConsonanti(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) if ("AEIOU".indexOf(c) < 0) b.append(c);
        return b.toString();
    }

    private static String cfVocali(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) if ("AEIOU".indexOf(c) >= 0) b.append(c);
        return b.toString();
    }

    private static String cfCognome(String cognome) {
        String s = normalizzaNome(cognome);
        return (cfConsonanti(s) + cfVocali(s) + "XXX").substring(0, 3);
    }

    private static String cfNome(String nome) {
        String s = normalizzaNome(nome);
        String co = cfConsonanti(s);
        if (co.length() >= 4) return "" + co.charAt(0) + co.charAt(2) + co.charAt(3);
        return (co + cfVocali(s) + "XXX").substring(0, 3);
    }

    private static char cfCheck(String x) {
        int s = 0;
        for (int i = 0; i < 15; i++) {
            char c = x.charAt(i);
            boolean dispari = (i % 2 == 0);   // posizioni 1,3,5... (1-indexed)
            int v = (c >= '0' && c <= '9')
                    ? (dispari ? CF_DISP_NUM[c - '0'] : (c - '0'))
                    : (dispari ? CF_DISP_LET[c - 'A'] : (c - 'A'));
            s += v;
        }
        return (char) ('A' + s % 26);
    }

    static String calcolaCodiceFiscale(String cognome, String nome, String dataIso, String sesso, String catastale) {
        if (dataIso == null || !dataIso.matches("\\d{4}-\\d{2}-\\d{2}")) return null;
        int yy = Integer.parseInt(dataIso.substring(0, 4));
        int mm = Integer.parseInt(dataIso.substring(5, 7));
        int dd = Integer.parseInt(dataIso.substring(8, 10));
        if (mm < 1 || mm > 12 || catastale == null || catastale.length() != 4) return null;
        boolean femmina = "F".equalsIgnoreCase(sesso);
        String p = cfCognome(cognome) + cfNome(nome)
                + String.format("%02d", yy % 100)
                + CF_MESI.charAt(mm - 1)
                + String.format("%02d", dd + (femmina ? 40 : 0))
                + catastale.toUpperCase();
        if (p.length() != 15) return null;
        return p + cfCheck(p);
    }
}
