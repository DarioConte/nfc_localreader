package com.dadaops.cardreader.docs.ro;

import java.util.Map;
import java.util.Set;

import com.dadaops.cardreader.docs.DocumentParser;
import com.dadaops.cardreader.docs.DocumentProfile;

/**
 * Romania (ROU). Il "numero personale" del DG11 e' il CNP (Cod Numeric Personal, 13 cifre):
 * S AA LL ZZ JJ NNN C — codifica sesso/secolo e data di nascita. Li decodifica come campi extra.
 */
public final class RomaniaParser implements DocumentParser {
    @Override public Set<String> paesi() { return Set.of("ROU"); }

    @Override public void arricchisci(DocumentProfile profilo, Map<String, String> dati) {
        String cnp = dati.get("numeroPersonale");
        if (cnp == null || !cnp.matches("\\d{13}")) return;
        dati.put("cnp", cnp);
        dati.putIfAbsent("numeroNazionale", cnp);

        int s = cnp.charAt(0) - '0';
        String sesso = (s == 1 || s == 3 || s == 5 || s == 7) ? "M"
                : (s == 2 || s == 4 || s == 6 || s == 8) ? "F" : null;
        if (sesso != null) dati.putIfAbsent("sesso", sesso);

        int secolo = (s == 1 || s == 2) ? 1900 : (s == 3 || s == 4) ? 1800 : (s == 5 || s == 6) ? 2000 : 1900;
        try {
            int anno = secolo + Integer.parseInt(cnp.substring(1, 3));
            String mm = cnp.substring(3, 5), gg = cnp.substring(5, 7);
            int m = Integer.parseInt(mm), g = Integer.parseInt(gg);
            if (m >= 1 && m <= 12 && g >= 1 && g <= 31)
                dati.putIfAbsent("dataNascita", String.format("%04d-%02d-%02d", anno, m, g));
        } catch (Exception ignored) {}
    }
}
