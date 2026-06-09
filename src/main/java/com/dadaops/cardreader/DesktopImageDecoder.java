package com.dadaops.cardreader;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

/** Conversione immagini via ImageIO (+ plugin jai-imageio per JPEG 2000) — desktop. */
public final class DesktopImageDecoder implements ImageDecoder {
    @Override public byte[] toPng(byte[] data, String mime) {
        try {
            BufferedImage bi = ImageIO.read(new ByteArrayInputStream(data));
            if (bi == null) return null;
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(bi, "png", png);
            return png.toByteArray();
        } catch (Exception e) { return null; }
    }
}
