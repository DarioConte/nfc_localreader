package com.dadaops.cardreader;

import java.util.ArrayList;
import java.util.List;
import javax.smartcardio.Card;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.TerminalFactory;

import net.sf.scuba.smartcards.TerminalCardService;

/** {@link CardSource} su PC/SC (lettori smart card del sistema) — desktop. */
public final class PcscCardSource implements CardSource {

    private List<CardTerminal> list() throws CardException {
        return TerminalFactory.getDefault().terminals().list();
    }

    @Override public List<ReaderInfo> readers() throws CardLinkException {
        try {
            List<CardTerminal> ts = list();
            List<ReaderInfo> out = new ArrayList<>();
            for (int i = 0; i < ts.size(); i++) {
                boolean p = false;
                try { p = ts.get(i).isCardPresent(); } catch (Exception ignored) {}
                out.add(new ReaderInfo(i, ts.get(i).getName(), p));
            }
            return out;
        } catch (CardException e) { throw new CardLinkException("readers", e); }
    }

    @Override public String resolve(String sel) throws CardLinkException {
        try {
            List<CardTerminal> ts = list();
            if (ts.isEmpty()) return null;
            if (sel != null && !sel.isBlank()) {
                sel = sel.trim();
                try { int i = Integer.parseInt(sel); return (i >= 0 && i < ts.size()) ? ts.get(i).getName() : null; }
                catch (NumberFormatException ignore) {}
                for (CardTerminal t : ts) if (t.getName().toLowerCase().contains(sel.toLowerCase())) return t.getName();
                return null;
            }
            for (CardTerminal t : ts) try { if (t.isCardPresent()) return t.getName(); } catch (Exception ignored) {}
            return null;
        } catch (CardException e) { throw new CardLinkException("resolve", e); }
    }

    @Override public boolean isCardPresent(String name) throws CardLinkException {
        try {
            CardTerminal t = byName(name);
            return t != null && t.isCardPresent();
        } catch (CardException e) { throw new CardLinkException("isCardPresent", e); }
    }

    @Override public int countWithCard() throws CardLinkException {
        try {
            int n = 0;
            for (CardTerminal t : list()) try { if (t.isCardPresent()) n++; } catch (Exception ignored) {}
            return n;
        } catch (CardException e) { throw new CardLinkException("countWithCard", e); }
    }

    @Override public CardLink connect(String name) throws CardLinkException {
        try {
            CardTerminal t = byName(name);
            if (t == null) throw new CardLinkException("Lettore non trovato: " + name);
            Card c;
            try { c = t.connect("T=1"); } catch (CardException e) { c = t.connect("*"); }
            return new PcscCardLink(c);
        } catch (CardException e) { throw new CardLinkException("connect", e); }
    }

    @Override public net.sf.scuba.smartcards.CardService cardService(String name) throws CardLinkException {
        try {
            CardTerminal t = byName(name);
            if (t == null) throw new CardLinkException("Lettore non trovato: " + name);
            return new TerminalCardService(t);
        } catch (CardException e) { throw new CardLinkException("cardService", e); }
    }

    private CardTerminal byName(String name) throws CardException {
        for (CardTerminal t : list()) if (t.getName().equals(name)) return t;
        return null;
    }
}
