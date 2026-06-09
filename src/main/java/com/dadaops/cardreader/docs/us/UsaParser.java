package com.dadaops.cardreader.docs.us;

import java.util.Map;
import java.util.Set;

import com.dadaops.cardreader.docs.DocumentParser;
import com.dadaops.cardreader.docs.DocumentProfile;

/**
 * Stati Uniti (USA). Passaporto/passport card ICAO. Non espone un numero nazionale (niente SSN
 * nel chip); i dati utili sono cognome/nome, numero documento e date dalla MRZ.
 */
public final class UsaParser implements DocumentParser {
    @Override public Set<String> paesi() { return Set.of("USA"); }

    @Override public void arricchisci(DocumentProfile profilo, Map<String, String> dati) {
        // Nessun numero nazionale sul chip US.
    }
}
