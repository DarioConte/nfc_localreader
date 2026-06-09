package com.dadaops.cardreader;

/** Errore di trasporto/lettura indipendente dalla piattaforma. */
public class CardLinkException extends Exception {
    public CardLinkException(String message) { super(message); }
    public CardLinkException(String message, Throwable cause) { super(message, cause); }
}
