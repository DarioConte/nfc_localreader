package com.dadaops.cardreader;

/** Conversioni esadecimali. */
final class Hex {
    private Hex() {}

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    static byte[] parseHex(String s) {
        s = s.replaceAll("[^0-9A-Fa-f]", "");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++)
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}
