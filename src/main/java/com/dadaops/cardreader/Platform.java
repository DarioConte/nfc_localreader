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

    /**
     * Bootstrap completo per l'uso come libreria (es. Android): carica config/registri (comuni,
     * stati esteri, identity.properties bundled) e configura il trasporto. Le chiavi si possono
     * poi impostare con {@link #setKeys(String, String, String)}.
     */
    public static void boot(CardSource source, ImageDecoder decoder) {
        AppContext.init();
        configure(source, decoder);
    }

    /** Imposta le chiavi a runtime (Android: tipicamente da BuildConfig/prefs, non da file). */
    public static void setKeys(String apiKey, String signKey, String cryptoKey) {
        if (apiKey != null) AppContext.API_KEY = apiKey;
        if (signKey != null) AppContext.SIGN_KEY = signKey;
        if (cryptoKey != null) AppContext.CRYPTO_KEY = cryptoKey;
    }

    /** Abilita/disabilita la console di test ('/' e i dump debug). In produzione: false. */
    public static void setConsoleEnabled(boolean enabled) { AppContext.CONSOLE_ENABLED = enabled; }

    /** Porta configurata (utile all'app per avviare il proprio server HTTP locale). */
    public static int port() { return AppContext.PORT; }

    static CardSource source() {
        if (cardSource == null) throw new IllegalStateException("CardSource non configurato: chiamare Platform.configure(...)");
        return cardSource;
    }

    static ImageDecoder decoder() { return imageDecoder; }
}
