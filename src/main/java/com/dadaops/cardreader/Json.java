package com.dadaops.cardreader;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Costruzione e parsing minimale di JSON (senza dipendenze esterne). */
final class Json {
    private Json() {}

    /** Campi emessi come booleani veri (non tra virgolette). Valore interno "true"/"false". */
    static final Set<String> BOOL_KEYS = Set.of("codiceFiscaleCertified", "natoEstero",
            "chipAuthentication", "activeAuthentication", "uidCasuale");

    static String jstr(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default: if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
            }
        }
        return sb.append("\"").toString();
    }

    static String mapToJson(Map<String, String> m, List<String> grezzi) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (!first) sb.append(",");
            sb.append(jstr(e.getKey())).append(":");
            String v = e.getValue();
            if (BOOL_KEYS.contains(e.getKey()) && ("true".equals(v) || "false".equals(v)))
                sb.append(v);                 // booleano JSON, senza virgolette
            else sb.append(jstr(v));
            first = false;
        }
        if (grezzi != null) {
            sb.append(",\"campiGrezzi\":[");
            for (int i = 0; i < grezzi.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(jstr(grezzi.get(i).trim()));
            }
            sb.append("]");
        }
        return sb.append("}").toString();
    }

    static String jsonArr(List<String> xs) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < xs.size(); i++) { if (i > 0) b.append(","); b.append(jstr(xs.get(i))); }
        return b.append("]").toString();
    }

    /** Messaggio canonico per la firma: "chiave=valore\n" nell'ordine di inserimento. */
    static String canonicalMessage(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet())
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        return sb.toString();
    }

    /** Parser piatto che preserva l'ordine dei campi (stringa o booleano nudo). */
    static LinkedHashMap<String, String> parseFlatJsonOrdered(String body) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        if (body == null) return m;
        Matcher mm = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(?:\"((?:\\\\.|[^\"\\\\])*)\"|(true|false))").matcher(body);
        while (mm.find())
            m.put(mm.group(1), mm.group(2) != null ? unescapeJson(mm.group(2)) : mm.group(3));
        return m;
    }

    static String unescapeJson(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'u':
                        if (i + 4 < s.length()) { sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; }
                        break;
                    default: sb.append(n);
                }
            } else sb.append(c);
        }
        return sb.toString();
    }

    static String jsonField(String body, String name) {
        if (body == null) return null;
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        return m.find() ? m.group(1) : null;
    }

    static Integer jsonInt(String body, String name) {
        if (body == null) return null;
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(\\d+)").matcher(body);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    static boolean jsonBool(String body, String name) {
        if (body == null) return false;
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(true|1)\\b").matcher(body);
        return m.find();
    }
}
