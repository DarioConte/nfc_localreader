package com.dadaops.cardreader;

import java.util.List;

/**
 * Sorgente di carte (insieme di lettori), iniettata dalla piattaforma:
 * desktop -> PC/SC ({@code PcscCardSource}); Android -> NFC IsoDep (adapter nell'app).
 * Tutta la logica di lettura usa solo questa interfaccia, quindi e' riusabile ovunque.
 */
public interface CardSource {

    /** Elenco dei lettori disponibili. */
    List<ReaderInfo> readers() throws CardLinkException;

    /** Risolve il selettore (indice "0"/"1" o parte del nome) al nome del lettore; null se nessuno. */
    String resolve(String sel) throws CardLinkException;

    boolean isCardPresent(String readerName) throws CardLinkException;

    /** Numero di lettori con una carta inserita (per disambiguare). */
    int countWithCard() throws CardLinkException;

    /** Apre una connessione alla carta sul lettore indicato. */
    CardLink connect(String readerName) throws CardLinkException;

    /**
     * CardService scuba per jMRTD (PACE/BAC) sul lettore indicato. Su Android va implementato
     * con un CardService che incapsula IsoDep; su desktop usa TerminalCardService.
     */
    net.sf.scuba.smartcards.CardService cardService(String readerName) throws CardLinkException;
}
