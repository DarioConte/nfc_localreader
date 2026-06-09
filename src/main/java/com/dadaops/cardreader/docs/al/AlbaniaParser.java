package com.dadaops.cardreader.docs.al;

import java.util.Map;
import java.util.Set;

import com.dadaops.cardreader.docs.DocumentParser;
import com.dadaops.cardreader.docs.DocumentProfile;

/**
 * Albania (ALB). Carta d'identita'/passaporto biometrici. Il numero personale (NID) e' un
 * identificativo di 10 caratteri (es. I05101999I): lo esponiamo come numero nazionale.
 */
public final class AlbaniaParser implements DocumentParser {
    @Override public Set<String> paesi() { return Set.of("ALB"); }

    @Override public void arricchisci(DocumentProfile profilo, Map<String, String> dati) {
        String pn = dati.get("numeroPersonale");
        if (pn != null) { dati.put("numeroNazionale", pn); dati.put("nid", pn); }
    }
}
