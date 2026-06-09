package com.dadaops.cardreader.docs.world;

import java.util.Map;
import java.util.Set;

import com.dadaops.cardreader.docs.DocumentParser;
import com.dadaops.cardreader.docs.DocumentProfile;

/** Parser generico (fallback): mappa il "numero personale" del DG11 a un numero nazionale. */
public final class GenericParser implements DocumentParser {
    @Override public Set<String> paesi() { return Set.of(); }

    @Override public void arricchisci(DocumentProfile profilo, Map<String, String> dati) {
        String pn = dati.get("numeroPersonale");
        if (pn != null && !dati.containsKey("numeroNazionale")) dati.put("numeroNazionale", pn);
    }
}
