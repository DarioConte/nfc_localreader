package com.dadaops.cardreader.docs;

/** Profilo del documento riconosciuto: categoria, paese emittente, etichetta tipo. */
public final class DocumentProfile {
    /** "passaporto" | "carta d'identità" | "permesso di soggiorno" | null. */
    public String categoria;
    /** Etichetta del campo "tipo" nella risposta (es. PASSAPORTO, CIE, CARTA D'IDENTITA). */
    public String tipo;
    /** ISO3 dello Stato emittente (dalla MRZ). */
    public String paeseCodice;
    /** Nome italiano dello Stato emittente. */
    public String paese;
    public boolean unioneEuropea;
}
