package com.dadaops.cardreader.docs.ua;

import java.util.Map;
import java.util.Set;

import com.dadaops.cardreader.docs.DocumentParser;
import com.dadaops.cardreader.docs.DocumentProfile;

/**
 * Ucraina (UKR). Il "numero personale" del DG11 e' tipicamente il numero del registro/record
 * (RNTRC / codice fiscale individuale). I nomi sono traslitterati in latino nella MRZ.
 */
public final class UcrainaParser implements DocumentParser {
    @Override public Set<String> paesi() { return Set.of("UKR"); }

    @Override public void arricchisci(DocumentProfile profilo, Map<String, String> dati) {
        String pn = dati.get("numeroPersonale");
        if (pn != null) { dati.put("numeroNazionale", pn); dati.put("rntrc", pn); }
        dati.put("nomiTraslitterati", "true");
    }
}
