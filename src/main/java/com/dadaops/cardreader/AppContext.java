package com.dadaops.cardreader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Stato condiviso e configurazione del servizio: porta, chiavi, flag console, registri
 * (comuni/stati esteri), nonce. Caricato una volta a bootstrap da {@link #init()}.
 *
 * Precedenza configurazione: system property -D > variabile d'ambiente > file
 * identity.properties (bundled nel jar + eventuale esterno) > default.
 */
final class AppContext {
    private AppContext() {}

    static int PORT = 8765;
    static String API_KEY;
    static boolean API_KEY_GENERATA = false;
    static String SIGN_KEY;
    static boolean SIGN_KEY_GENERATA = false;
    static String CRYPTO_KEY;
    static boolean CRYPTO_KEY_GENERATA = false;
    static String ALLOWED_ORIGIN = "*";
    /** Console di test/debug (pagina '/' e dump 'debug'): disattivabile via config o dal tray. */
    static volatile boolean CONSOLE_ENABLED = true;

    static final int NONCE_MAX = 50000;
    static final Set<String> NONCE_EMESSI = Collections.synchronizedSet(new LinkedHashSet<>());

    static final Properties CONFIG = new Properties();

    static final Pattern CF = Pattern.compile(
            "[A-Z]{6}[0-9LMNPQRSTUV]{2}[ABCDEHLMPRST][0-9LMNPQRSTUV]{2}[A-Z][0-9LMNPQRSTUV]{3}[A-Z]");

    static final byte[] AID_EMRTD = {(byte) 0xA0, 0x00, 0x00, 0x02, 0x47, 0x10, 0x01};
    /** PPSE: il "Payment System Environment" contactless, elenca le applicazioni EMV. */
    static final byte[] PPSE = "2PAY.SYS.DDF01".getBytes(StandardCharsets.US_ASCII);

    /** Codice catastale (Belfiore) -> [comune, provincia, regione]. */
    static final Map<String, String[]> COMUNI = new HashMap<>();
    /** Nome comune normalizzato -> codice catastale (per calcolare il CF dal luogo di nascita). */
    static final Map<String, String> COMUNI_PER_NOME = new HashMap<>();
    /** Codice catastale estero (Z...) -> nome Stato (per i nati all'estero). */
    static final Map<String, String> STATI = new HashMap<>();

    static final String[] ETICHETTE_CNS = {
            "codiceEmettitore", "dataEmissione", "dataScadenza",
            "cognome", "nome", "dataNascita", "sesso", "statura",
            "codiceFiscale", "cittadinanza", "comuneNascita", "provinciaNascita"
    };

    /** Carica config + registri + risolve porta/chiavi/flag. Da chiamare per primo a bootstrap. */
    static void init() {
        caricaConfig();
        caricaComuni();
        caricaStati();
        PORT = resolveInt("port", "IDENTITY_PORT", 8765);
        ALLOWED_ORIGIN = orDefault(prop("origin", "IDENTITY_ORIGIN"), "*");
        CONSOLE_ENABLED = boolProp("console.enabled", "IDENTITY_CONSOLE", true);
        API_KEY = prop("apikey", "IDENTITY_APIKEY");
        if (API_KEY == null) { API_KEY = randomKey(); API_KEY_GENERATA = true; }
        SIGN_KEY = prop("signkey", "IDENTITY_SIGNKEY");
        if (SIGN_KEY == null) { SIGN_KEY = randomKey(); SIGN_KEY_GENERATA = true; }
        CRYPTO_KEY = prop("cryptokey", "IDENTITY_CRYPTOKEY");
        if (CRYPTO_KEY == null) { CRYPTO_KEY = randomKey(); CRYPTO_KEY_GENERATA = true; }
    }

    private static String randomKey() { return UUID.randomUUID().toString().replace("-", ""); }

    private static void caricaConfig() {
        try (InputStream is = AppContext.class.getResourceAsStream("/identity.properties")) {
            if (is != null) CONFIG.load(is);
        } catch (Exception ignored) {}
        List<File> candidati = new ArrayList<>();
        String ext = System.getProperty("config");
        if (ext == null || ext.isBlank()) ext = System.getenv("IDENTITY_CONFIG");
        if (ext != null && !ext.isBlank()) candidati.add(new File(ext));
        candidati.add(new File("identity.properties"));
        try {
            File jar = new File(AppContext.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (jar.getParentFile() != null) candidati.add(new File(jar.getParentFile(), "identity.properties"));
        } catch (Exception ignored) {}
        for (File f : candidati) {
            if (f.isFile()) {
                try (InputStream is = new FileInputStream(f)) {
                    CONFIG.load(is);
                    System.out.println("Config esterna caricata: " + f.getAbsolutePath());
                    break;
                } catch (Exception ignored) {}
            }
        }
    }

    /** Carica comuni.csv (codicecatastale:comune:provincia:regione) dal classpath. */
    private static void caricaComuni() {
        try (InputStream is = AppContext.class.getResourceAsStream("/comuni.csv")) {
            if (is == null) { System.out.println("ATTENZIONE: comuni.csv non trovato nel classpath."); return; }
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String line; int n = 0;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] p = line.split(":", 4);
                if (p.length >= 4 && !p[0].isBlank()) {
                    String cod = p[0].trim().toUpperCase();
                    COMUNI.put(cod, new String[]{p[1].trim(), p[2].trim(), p[3].trim()});
                    COMUNI_PER_NOME.putIfAbsent(FiscalCode.normalizzaNome(p[1]), cod);
                    n++;
                }
            }
            System.out.println("Comuni caricati in memoria: " + n);
        } catch (Exception e) {
            System.out.println("Errore caricamento comuni: " + e.getMessage());
        }
    }

    /** Carica stati.csv (codicecatastale:stato) dal classpath; serve ai nati all'estero. */
    private static void caricaStati() {
        try (InputStream is = AppContext.class.getResourceAsStream("/stati.csv")) {
            if (is == null) { System.out.println("ATTENZIONE: stati.csv non trovato nel classpath."); return; }
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String line; int n = 0;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split(":", 2);
                if (p.length == 2 && !p[0].isBlank() && !p[1].isBlank()) {
                    STATI.put(p[0].trim().toUpperCase(), p[1].trim());
                    n++;
                }
            }
            System.out.println("Stati esteri caricati in memoria: " + n);
        } catch (Exception e) {
            System.out.println("Errore caricamento stati: " + e.getMessage());
        }
    }

    static String prop(String sys, String env) {
        String v = System.getProperty(sys);
        if (v == null || v.isBlank()) v = System.getenv(env);
        if (v == null || v.isBlank()) v = CONFIG.getProperty(sys);
        return (v == null || v.isBlank()) ? null : v;
    }

    static boolean boolProp(String sys, String env, boolean def) {
        String v = prop(sys, env);
        if (v == null) return def;
        return v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("si") || v.equalsIgnoreCase("yes");
    }

    static String orDefault(String v, String d) { return v == null ? d : v; }

    static int resolveInt(String sys, String env, int def) {
        String v = prop(sys, env);
        try { return v != null ? Integer.parseInt(v) : def; } catch (Exception e) { return def; }
    }
}
