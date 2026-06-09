package com.dadaops.cardreader.android;

import android.nfc.Tag;
import android.nfc.tech.IsoDep;

import com.dadaops.cardreader.CardLink;
import com.dadaops.cardreader.CardLinkException;

import java.io.IOException;

/** {@link CardLink} su NFC IsoDep (ISO 14443-4) — Android. */
public class IsoDepLink implements CardLink {
    private final IsoDep iso;

    public IsoDepLink(Tag tag) throws CardLinkException {
        iso = IsoDep.get(tag);
        if (iso == null) throw new CardLinkException("Tag non IsoDep/ISO14443-4");
        try { iso.connect(); iso.setTimeout(8000); }
        catch (IOException e) { throw new CardLinkException("connect", e); }
    }

    @Override public byte[] transmit(byte[] apdu) throws CardLinkException {
        try { return iso.transceive(apdu); }
        catch (IOException e) { throw new CardLinkException("transceive", e); }
    }

    @Override public byte[] atr() {
        byte[] h = iso.getHistoricalBytes();
        return h != null ? h : iso.getHiLayerResponse();
    }

    @Override public void disconnect(boolean reset) {
        try { iso.close(); } catch (IOException ignored) {}
    }
}
