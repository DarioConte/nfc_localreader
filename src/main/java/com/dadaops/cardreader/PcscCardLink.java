package com.dadaops.cardreader;

import javax.smartcardio.Card;
import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;

/** {@link CardLink} su PC/SC (javax.smartcardio) — desktop. */
final class PcscCardLink implements CardLink {
    private final Card card;

    PcscCardLink(Card card) { this.card = card; }

    @Override public byte[] transmit(byte[] apdu) throws CardLinkException {
        try {
            return card.getBasicChannel().transmit(new CommandAPDU(apdu)).getBytes();
        } catch (CardException e) {
            throw new CardLinkException("transmit fallita", e);
        }
    }

    @Override public byte[] atr() {
        try { return card.getATR().getBytes(); } catch (Exception e) { return null; }
    }

    @Override public void disconnect(boolean reset) {
        try { card.disconnect(reset); } catch (Exception ignored) {}
    }
}
