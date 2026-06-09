package com.dadaops.cardreader;

import java.io.IOException;
import java.security.Security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 * Avvio in modalità SERVER (desktop). Configura la piattaforma con PC/SC + decoder ImageIO,
 * avvia il server HTTP su 127.0.0.1 e l'icona nel tray.
 *
 * <p>Per usare lo stesso codice come LIBRERIA (es. Android) NON si usa questa classe: si chiama
 * {@link Platform#configure(CardSource, ImageDecoder)} con un {@link CardSource} su IsoDep e poi
 * {@link CardReaderApi} (programmatico) o {@link ApiRouter} (dietro un server HTTP locale).
 */
public final class IdentityServer {
    private IdentityServer() {}

    public static void main(String[] args) throws IOException {
        Security.addProvider(new BouncyCastleProvider());
        AppContext.init();
        Platform.configure(new PcscCardSource(), new DesktopImageDecoder());

        // Modalità: "server" (default) | "gui"/"standalone". Es: java -jar cie-cns-wedge.jar gui
        String modo = (args.length > 0 ? args[0] : System.getProperty("mode", "server"))
                .toLowerCase().replaceFirst("^--", "");
        if (modo.equals("gui") || modo.equals("standalone")) {
            System.out.println("Avvio in modalità GUI standalone.");
            StandaloneGui.launch();
            return;
        }

        ApiServer.start();

        System.out.println("Servizio identita' avviato su http://localhost:" + AppContext.PORT);
        System.out.println("CORS origin consentito: " + AppContext.ALLOWED_ORIGIN);
        chiave("API key", AppContext.API_KEY_GENERATA, AppContext.API_KEY, "-Dapikey=... o IDENTITY_APIKEY");
        chiave("Chiave di firma (checksum)", AppContext.SIGN_KEY_GENERATA, AppContext.SIGN_KEY, "-Dsignkey=... o IDENTITY_SIGNKEY");
        chiave("Chiave crittografica (token)", AppContext.CRYPTO_KEY_GENERATA, AppContext.CRYPTO_KEY, "-Dcryptokey=... o IDENTITY_CRYPTOKEY");
        System.out.println("Console di debug (pagina '/' e dump debug): " + (AppContext.CONSOLE_ENABLED ? "ABILITATA" : "disabilitata"));

        TrayUi.setup();
    }

    private static void chiave(String nome, boolean generata, String valore, String come) {
        if (generata) {
            System.out.println("ATTENZIONE: " + nome + " generata automaticamente (impostane una stabile con " + come + ").");
            System.out.println("  Valore attuale: " + valore);
        } else {
            System.out.println(nome + ": configurata.");
        }
    }
}
