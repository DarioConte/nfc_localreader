package com.dadaops.cardreader;

/**
 * Connessione aperta verso una carta, indipendente dalla piattaforma.
 * Desktop: implementata su PC/SC (javax.smartcardio); Android: su NFC IsoDep.
 */
public interface CardLink extends AutoCloseable {

    /** Trasmette una APDU e ritorna la risposta completa (dati + 2 byte di Status Word). */
    byte[] transmit(byte[] commandApdu) throws CardLinkException;

    /** ATR / historical bytes della carta, o null se non disponibile. */
    byte[] atr();

    /** Chiude la connessione; reset=true esegue un reset della carta (utile prima di PACE). */
    void disconnect(boolean reset);

    @Override default void close() { disconnect(false); }
}
