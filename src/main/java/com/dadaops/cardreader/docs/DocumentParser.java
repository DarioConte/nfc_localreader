package com.dadaops.cardreader.docs;

import java.util.Map;
import java.util.Set;

/**
 * Parser specifico di un documento/Stato: arricchisce i dati estratti (uniformi, da DG1/DG11) con
 * informazioni proprie del paese (es. numero nazionale, decodifiche). Implementazioni nei
 * sotto-package {@code docs.it}, {@code docs.ro}, {@code docs.ua}, {@code docs.ru}, {@code docs.us}, ...
 */
public interface DocumentParser {

    /** Codici ISO3 gestiti da questo parser (vuoto = generico/fallback). */
    Set<String> paesi();

    /** Arricchisce la mappa dati con campi specifici del documento. */
    void arricchisci(DocumentProfile profilo, Map<String, String> dati);
}
