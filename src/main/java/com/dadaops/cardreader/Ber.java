package com.dadaops.cardreader;

import java.util.Arrays;

/** Ricerca BER-TLV (usata dal parsing EMV). */
final class Ber {
    private Ber() {}

    static byte[] tlvFind(byte[] data, int wantTag) {
        return data == null ? null : tlvFind(data, 0, data.length, wantTag);
    }

    static byte[] tlvFind(byte[] d, int start, int end, int wantTag) {
        int i = start;
        while (i < end) {
            int b0 = d[i] & 0xFF;
            if (b0 == 0x00 || b0 == 0xFF) { i++; continue; }   // padding
            int tag = b0; i++;
            if ((b0 & 0x1F) == 0x1F)
                do { if (i >= end) return null; tag = (tag << 8) | (d[i] & 0xFF); } while ((d[i++] & 0x80) != 0 && i < end);
            if (i >= end) break;
            int len = d[i] & 0xFF; i++;
            if ((len & 0x80) != 0) {
                int n = len & 0x7F; len = 0;
                for (int k = 0; k < n && i < end; k++) len = (len << 8) | (d[i++] & 0xFF);
            }
            if (len < 0 || i + len > end) break;
            if (tag == wantTag) return Arrays.copyOfRange(d, i, i + len);
            if ((b0 & 0x20) != 0) {   // costruito: cerca dentro
                byte[] r = tlvFind(d, i, i + len, wantTag);
                if (r != null) return r;
            }
            i += len;
        }
        return null;
    }
}
