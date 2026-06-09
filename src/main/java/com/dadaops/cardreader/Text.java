package com.dadaops.cardreader;

import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Piccoli helper su stringhe, mappe e date (riusabili dai vari reader). */
final class Text {
    private Text() {}

    static void putIf(Map<String, String> m, String k, String v) {
        if (v != null && !v.isBlank()) m.put(k, v);
    }

    static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    /** Ripulisce un campo testuale eMRTD: '<' -> spazio, trim e spazi multipli compressi. */
    static String pulisci(String s) {
        if (s == null) return null;
        String v = s.replace('<', ' ').trim().replaceAll("\\s+", " ");
        return v.isEmpty() ? null : v;
    }

    static String unisciLista(List<String> xs) {
        if (xs == null || xs.isEmpty()) return null;
        List<String> parti = new ArrayList<>();
        for (String x : xs) { String p = pulisci(x); if (p != null) parti.add(p); }
        return parti.isEmpty() ? null : String.join(", ", parti);
    }

    /** Data compatta AAAAMMGG (o AAAA-MM-GG) -> ISO AAAA-MM-GG; null se non riconosciuta. */
    static String isoDaCompatta(String d) {
        if (d == null) return null;
        String s = d.replaceAll("[^0-9]", "");
        if (s.length() == 8) return s.substring(0, 4) + "-" + s.substring(4, 6) + "-" + s.substring(6, 8);
        return null;
    }

    /** Data CNS GGMMAAAA -> ISO YYYY-MM-DD; se non e' nel formato atteso la lascia invariata. */
    static String isoDaGGMMAAAA(String d) {
        if (d == null || !d.matches("\\d{8}")) return d;
        return d.substring(4, 8) + "-" + d.substring(2, 4) + "-" + d.substring(0, 2);
    }

    static String isoDaMrz(String yymmdd, boolean futuro) {
        if (yymmdd == null || !yymmdd.matches("\\d{6}")) return "";
        int yy = Integer.parseInt(yymmdd.substring(0, 2));
        int mm = Integer.parseInt(yymmdd.substring(2, 4));
        int dd = Integer.parseInt(yymmdd.substring(4, 6));
        int curYY = Year.now().getValue() % 100;
        int year = futuro ? 2000 + yy : (yy <= curYY ? 2000 + yy : 1900 + yy);
        return String.format("%04d-%02d-%02d", year, mm, dd);
    }

    /** Normalizza una data per la chiave MRZ a YYMMDD: accetta gia' YYMMDD o ISO YYYY-MM-DD. */
    static String normalizzaDataMrz(String d) {
        if (d == null) return null;
        String s = d.trim();
        if (s.matches("\\d{6}")) return s;
        Matcher m = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})").matcher(s);
        if (m.find()) return m.group(1).substring(2) + m.group(2) + m.group(3);
        return s.replaceAll("[^0-9]", "");
    }
}
