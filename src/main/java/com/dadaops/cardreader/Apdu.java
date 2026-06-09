package com.dadaops.cardreader;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;

/** Helper APDU di basso livello sopra il trasporto neutro {@link CardLink}. */
final class Apdu {
    private Apdu() {}

    /** Status Word (ultimi 2 byte della risposta). */
    static int sw(byte[] resp) {
        return (resp == null || resp.length < 2) ? 0 : ((resp[resp.length - 2] & 0xFF) << 8) | (resp[resp.length - 1] & 0xFF);
    }

    /** Dati della risposta (tutto tranne i 2 byte di SW). */
    static byte[] data(byte[] resp) {
        return (resp == null || resp.length < 2) ? new byte[0] : Arrays.copyOf(resp, resp.length - 2);
    }

    static boolean okSw(int sw) { return sw == 0x9000 || (sw & 0xFF00) == 0x6100; }

    static byte[] getUid(CardLink link) {
        try {
            byte[] r = link.transmit(new byte[]{(byte) 0xFF, (byte) 0xCA, 0x00, 0x00, 0x00});
            return sw(r) == 0x9000 ? data(r) : null;
        } catch (CardLinkException e) { return null; }
    }

    static boolean select(CardLink link, byte hi, byte lo) throws CardLinkException {
        for (int p2 : new int[]{0x0C, 0x00}) {
            int sw = sw(link.transmit(new byte[]{0x00, (byte) 0xA4, 0x00, (byte) p2, 0x02, hi, lo}));
            if (okSw(sw)) return true;
        }
        return false;
    }

    static boolean selectAid(CardLink link, byte[] aid) throws CardLinkException {
        for (int p2 : new int[]{0x0C, 0x00}) {
            byte[] apdu = new byte[5 + aid.length];
            apdu[0] = 0x00; apdu[1] = (byte) 0xA4; apdu[2] = 0x04; apdu[3] = (byte) p2; apdu[4] = (byte) aid.length;
            System.arraycopy(aid, 0, apdu, 5, aid.length);
            if (okSw(sw(link.transmit(apdu)))) return true;
        }
        return false;
    }

    static byte[] readBinary(CardLink link) throws CardLinkException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int offset = 0; final int chunk = 0xFF;
        while (offset < 0x7FFF) {
            byte[] apdu = {0x00, (byte) 0xB0, (byte) ((offset >> 8) & 0xFF), (byte) (offset & 0xFF), (byte) chunk};
            byte[] r = link.transmit(apdu);
            int sw = sw(r); byte[] body = data(r);
            if (body.length > 0) { out.write(body, 0, body.length); offset += body.length; }
            if (sw == 0x6B00 || sw == 0x6282) break;
            if (sw != 0x9000) break;
            if (body.length < chunk) break;
        }
        return out.toByteArray();
    }

    static String swHex(CardLink link, byte[] apdu) throws CardLinkException {
        return String.format("%04X", sw(link.transmit(apdu)));
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[512]; int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
