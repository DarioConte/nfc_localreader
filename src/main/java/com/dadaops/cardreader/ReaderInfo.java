package com.dadaops.cardreader;

/** Descrittore di un lettore di carte. */
public final class ReaderInfo {
    public final int index;
    public final String name;
    public final boolean cardPresent;

    public ReaderInfo(int index, String name, boolean cardPresent) {
        this.index = index;
        this.name = name;
        this.cardPresent = cardPresent;
    }
}
