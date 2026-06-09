package com.dadaops.cardreader.android;

import android.nfc.Tag;

import com.dadaops.cardreader.CardLink;
import com.dadaops.cardreader.CardLinkException;
import com.dadaops.cardreader.CardSource;
import com.dadaops.cardreader.ReaderInfo;

import net.sf.scuba.smartcards.CardService;
import net.sf.scuba.smartcards.CardServiceException;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link CardSource} su NFC Android. L'Activity chiama {@link #setTag(Tag)} a ogni tap; il singolo
 * "lettore" e' l'antenna NFC del tablet.
 */
public class IsoDepCardSource implements CardSource {
    private volatile Tag tag;

    /** Da chiamare in NfcAdapter.ReaderCallback#onTagDiscovered. */
    public void setTag(Tag t) { this.tag = t; }

    @Override public List<ReaderInfo> readers() {
        List<ReaderInfo> l = new ArrayList<>();
        l.add(new ReaderInfo(0, "NFC", tag != null));
        return l;
    }

    @Override public String resolve(String sel) { return tag != null ? "NFC" : null; }

    @Override public boolean isCardPresent(String name) { return tag != null; }

    @Override public int countWithCard() { return tag != null ? 1 : 0; }

    @Override public CardLink connect(String name) throws CardLinkException {
        return new IsoDepLink(requireTag());
    }

    @Override public CardService cardService(String name) throws CardLinkException {
        try { return new IsoDepCardService(requireTag()); }
        catch (CardServiceException e) { throw new CardLinkException("cardService", e); }
    }

    private Tag requireTag() throws CardLinkException {
        Tag t = tag;
        if (t == null) throw new CardLinkException("Nessun tag NFC vicino: avvicina il documento");
        return t;
    }
}
