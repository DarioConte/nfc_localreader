package com.dadaops.cardreader.docs.ru;

import java.util.Map;
import java.util.Set;

import com.dadaops.cardreader.docs.DocumentParser;
import com.dadaops.cardreader.docs.DocumentProfile;

/**
 * Federazione Russa (RUS). Passaporto ICAO standard; nomi traslitterati in latino nella MRZ
 * (il cirillico originale non e' nel DG1). Numero documento e date dalla MRZ.
 */
public final class RussiaParser implements DocumentParser {
    @Override public Set<String> paesi() { return Set.of("RUS"); }

    @Override public void arricchisci(DocumentProfile profilo, Map<String, String> dati) {
        String pn = dati.get("numeroPersonale");
        if (pn != null) dati.putIfAbsent("numeroNazionale", pn);
        dati.put("nomiTraslitterati", "true");
    }
}
