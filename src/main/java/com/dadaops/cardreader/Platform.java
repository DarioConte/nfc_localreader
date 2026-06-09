package com.dadaops.cardreader;

/**
 * Punto di iniezione della piattaforma. La logica di lettura (neutra) usa la sorgente carte e il
 * decoder immagini configurati qui all'avvio:
 *  - desktop: {@code Platform.configure(new PcscCardSource(), new DesktopImageDecoder())}
 *  - Android: {@code Platform.configure(isoDepCardSource, jp2DecoderOrPassthrough)}
 */
public final class Platform {
    private Platform() {}

    private static volatile CardSource cardSource;
    private static volatile ImageDecoder imageDecoder = ImageDecoder.PASSTHROUGH;

    /** Configura la piattaforma. Va chiamato una volta prima di leggere le carte. */
    public static void configure(CardSource source, ImageDecoder decoder) {
        cardSource = source;
        if (decoder != null) imageDecoder = decoder;
    }

    static CardSource source() {
        if (cardSource == null) throw new IllegalStateException("CardSource non configurato: chiamare Platform.configure(...)");
        return cardSource;
    }

    static ImageDecoder decoder() { return imageDecoder; }
}
