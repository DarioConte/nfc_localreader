package com.dadaops.cardreader.docs.it;

import java.util.Map;
import java.util.Set;

import com.dadaops.cardreader.docs.DocumentParser;
import com.dadaops.cardreader.docs.DocumentProfile;

/** Italia (ITA): CIE/passaporto. Il codice fiscale e il luogo di nascita sono gestiti a valle. */
public final class ItaliaParser implements DocumentParser {
    @Override public Set<String> paesi() { return Set.of("ITA"); }

    @Override public void arricchisci(DocumentProfile profilo, Map<String, String> dati) {
        // Nessun arricchimento specifico: CF (DG11) e calcolo luogo di nascita sono in DocumentBuilder.
    }
}
