package com.dadaops.cardreader.android;

import android.nfc.Tag;
import android.nfc.tech.IsoDep;

import net.sf.scuba.smartcards.APDUEvent;
import net.sf.scuba.smartcards.APDUListener;
import net.sf.scuba.smartcards.CardService;
import net.sf.scuba.smartcards.CardServiceException;
import net.sf.scuba.smartcards.CommandAPDU;
import net.sf.scuba.smartcards.ResponseAPDU;

import java.io.IOException;

/**
 * CardService scuba su NFC IsoDep, usato da jMRTD per PACE/BAC su Android (equivalente del
 * TerminalCardService desktop). Inoltra le APDU a {@link IsoDep#transceive(byte[])}.
 */
public class IsoDepCardService extends CardService {
    private final IsoDep iso;
    private boolean open;
    private int seq;

    public IsoDepCardService(Tag tag) throws CardServiceException {
        iso = IsoDep.get(tag);
        if (iso == null) throw new CardServiceException("Tag non IsoDep/ISO14443-4");
    }

    @Override public void open() throws CardServiceException {
        try { if (!iso.isConnected()) iso.connect(); iso.setTimeout(8000); open = true; }
        catch (IOException e) { throw new CardServiceException(e.getMessage()); }
    }

    @Override public boolean isOpen() { return open; }

    @Override public ResponseAPDU transmit(CommandAPDU command) throws CardServiceException {
        try {
            ResponseAPDU response = new ResponseAPDU(iso.transceive(command.getBytes()));
            if (!getAPDUListeners().isEmpty()) {
                APDUEvent ev = new APDUEvent(this, "ISO7816", ++seq, command, response);
                for (APDUListener l : getAPDUListeners()) l.exchangedAPDU(ev);
            }
            return response;
        } catch (IOException e) { throw new CardServiceException(e.getMessage()); }
    }

    @Override public byte[] getATR() {
        byte[] h = iso.getHistoricalBytes();
        return h != null ? h : iso.getHiLayerResponse();
    }

    @Override public void close() {
        try { iso.close(); } catch (IOException ignored) {}
        open = false;
    }

    @Override public boolean isConnectionLost(Exception e) { return !iso.isConnected(); }

    /** Le carte PACE-DH a 2048 bit (es. passaporto italiano) richiedono le APDU estese. */
    @Override public boolean isExtendedAPDULengthSupported() { return iso.isExtendedLengthApduSupported(); }
}
