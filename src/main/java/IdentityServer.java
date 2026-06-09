import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.jmrtd.PACEKeySpec;
import org.jmrtd.PassportService;
import org.jmrtd.lds.CardAccessFile;
import org.jmrtd.lds.PACEInfo;
import org.jmrtd.lds.SecurityInfo;
import net.sf.scuba.smartcards.CardService;
import net.sf.scuba.smartcards.TerminalCardService;

import javax.smartcardio.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.security.spec.AlgorithmParameterSpec;
import java.time.Instant;
import java.time.Year;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Servizio locale di verifica identita' da Tessera Sanitaria (TS-CNS) e CIE.
 *
 * Endpoint:
 *   GET  /actuator/health   -> {"status":"UP"}                 (no auth)
 *   GET  /actuator/status   -> {"reader":..,"cardPresent":bool}(no auth, non legge i dati)
 *   POST /check             -> legge la carta e ritorna il JSON (richiede API key)
 *                              CAN nel body JSON {"can":"123456"} o form can=...
 *                              API key nell'header X-API-Key
 *   GET  /                  -> pagina di test
 *
 * Avvio: mostra un'icona nel system tray su desktop (Windows); su sistemi headless
 * (es. Raspberry) gira in background senza tray.
 *
 * Configurazione (system property -D oppure variabile d'ambiente):
 *   porta     -Dport=8765        / IDENTITY_PORT
 *   api key   -Dapikey=...       / IDENTITY_APIKEY    (se assente ne genera una e la stampa)
 *   origine   -Dorigin=https://gestionale.local / IDENTITY_ORIGIN  (CORS, default *)
 *
 * GDPR: tratta dati personali. Vedi note di sicurezza nel README.
 */
public class IdentityServer {

    private static int PORT = 8765;
    private static String API_KEY;
    private static boolean API_KEY_GENERATA = false;
    private static String SIGN_KEY;
    private static boolean SIGN_KEY_GENERATA = false;
    private static String CRYPTO_KEY;
    private static boolean CRYPTO_KEY_GENERATA = false;
    private static final int NONCE_MAX = 50000;
    private static final Set<String> NONCE_EMESSI = Collections.synchronizedSet(new LinkedHashSet<>());
    private static String ALLOWED_ORIGIN = "*";
    private static TrayIcon trayIcon;
    private static HttpServer server;
    private static ExecutorService executor;

    private static final Pattern CF = Pattern.compile(
            "[A-Z]{6}[0-9LMNPQRSTUV]{2}[ABCDEHLMPRST][0-9LMNPQRSTUV]{2}[A-Z][0-9LMNPQRSTUV]{3}[A-Z]");

    private static final byte[] AID_EMRTD = {(byte) 0xA0, 0x00, 0x00, 0x02, 0x47, 0x10, 0x01};

    /** Codice catastale (Belfiore) -> [comune, provincia, regione], caricato al bootstrap. */
    private static final Map<String, String[]> COMUNI = new HashMap<>();

    private static final String[] ETICHETTE_CNS = {
            "codiceEmettitore", "dataEmissione", "dataScadenza",
            "cognome", "nome", "dataNascita", "sesso", "statura",
            "codiceFiscale", "cittadinanza", "comuneNascita", "provinciaNascita"
    };

    // ===================== Avvio =====================
    public static void main(String[] args) throws IOException {
        Security.addProvider(new BouncyCastleProvider());
        caricaComuni();

        PORT = resolveInt("port", "IDENTITY_PORT", 8765);
        ALLOWED_ORIGIN = orDefault(prop("origin", "IDENTITY_ORIGIN"), "*");
        API_KEY = prop("apikey", "IDENTITY_APIKEY");
        if (API_KEY == null) {
            API_KEY = UUID.randomUUID().toString().replace("-", "");
            API_KEY_GENERATA = true;
        }
        SIGN_KEY = prop("signkey", "IDENTITY_SIGNKEY");
        if (SIGN_KEY == null) {
            SIGN_KEY = UUID.randomUUID().toString().replace("-", "");
            SIGN_KEY_GENERATA = true;
        }
        CRYPTO_KEY = prop("cryptokey", "IDENTITY_CRYPTOKEY");
        if (CRYPTO_KEY == null) {
            CRYPTO_KEY = UUID.randomUUID().toString().replace("-", "");
            CRYPTO_KEY_GENERATA = true;
        }

        avviaServer();

        System.out.println("Servizio identita' avviato su http://localhost:" + PORT);
        System.out.println("CORS origin consentito: " + ALLOWED_ORIGIN);
        if (API_KEY_GENERATA) {
            System.out.println("ATTENZIONE: API key generata automaticamente (impostane una stabile");
            System.out.println("con -Dapikey=... o IDENTITY_APIKEY). Valore attuale: " + API_KEY);
        } else {
            System.out.println("API key: configurata.");
        }
        if (SIGN_KEY_GENERATA) {
            System.out.println("ATTENZIONE: chiave di firma generata automaticamente. Per verificare il");
            System.out.println("checksum lato gestionale impostane una stabile e condivisa (-Dsignkey=...");
            System.out.println("o IDENTITY_SIGNKEY). Valore attuale: " + SIGN_KEY);
        } else {
            System.out.println("Chiave di firma (checksum): configurata.");
        }
        if (CRYPTO_KEY_GENERATA) {
            System.out.println("ATTENZIONE: chiave crittografica (chiave anagrafica) generata automaticamente.");
            System.out.println("Impostane una stabile (-Dcryptokey=... o IDENTITY_CRYPTOKEY), altrimenti gli");
            System.out.println("identificativi cambiano ad ogni riavvio. Valore attuale: " + CRYPTO_KEY);
        } else {
            System.out.println("Chiave crittografica (chiave anagrafica): configurata.");
        }
        setupTray();
    }

    private static void avviaServer() throws IOException {
        executor = Executors.newFixedThreadPool(4);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        server.createContext("/actuator/health", IdentityServer::handleHealth);
        server.createContext("/actuator/status", IdentityServer::handleStatus);
        server.createContext("/actuator/readers", IdentityServer::handleReaders);
        server.createContext("/check", IdentityServer::handleCheck);
        server.createContext("/write", IdentityServer::handleWrite);
        server.createContext("/verify", IdentityServer::handleVerify);
        server.createContext("/", IdentityServer::handleRoot);
        server.setExecutor(executor);
        server.start();
    }

    /** Ferma e ricrea il server HTTP in-process. Gira su un thread a parte. */
    private static void riavviaServer() {
        new Thread(() -> {
            try {
                if (server != null) server.stop(1);
                if (executor != null) executor.shutdownNow();
            } catch (Exception ignored) {}
            IOException ultimo = null;
            for (int i = 0; i < 10; i++) {   // la porta puo' restare occupata qualche istante
                try { avviaServer(); ultimo = null; break; }
                catch (IOException e) {
                    ultimo = e;
                    try { Thread.sleep(300); } catch (InterruptedException ie) { break; }
                }
            }
            String msg = (ultimo == null) ? "Servizio riavviato sulla porta " + PORT
                    : "Riavvio fallito: " + ultimo.getMessage();
            System.out.println(msg);
            if (trayIcon != null)
                trayIcon.displayMessage("Servizio", msg,
                        ultimo == null ? TrayIcon.MessageType.INFO : TrayIcon.MessageType.ERROR);
        }, "restart").start();
    }

    // ===================== Endpoint =====================
    private static void handleHealth(HttpExchange ex) throws IOException {
        addCors(ex);
        if (preflight(ex)) return;
        send(ex, 200, "{\"status\":\"UP\"}");
    }

    private static void handleStatus(HttpExchange ex) throws IOException {
        addCors(ex);
        if (preflight(ex)) return;
        String sel = queryParam(ex.getRequestURI(), "reader");
        CardTerminal t;
        try { t = risolviTerminal(sel); }
        catch (CardException e) {
            send(ex, 200, "{\"reader\":null,\"cardPresent\":false,\"errore\":" + jstr(e.getMessage()) + "}");
            return;
        }
        if (t == null) { send(ex, 200, "{\"reader\":null,\"cardPresent\":false,\"tipo\":null}"); return; }
        boolean present = false;
        try { present = t.isCardPresent(); } catch (CardException ignored) {}
        String tipo = present ? tipoString(rileva(t).tipo) : null;
        send(ex, 200, "{\"reader\":" + jstr(t.getName()) + ",\"cardPresent\":" + present
                + ",\"tipo\":" + jstr(tipo) + "}");
    }

    private static void handleReaders(HttpExchange ex) throws IOException {
        addCors(ex);
        if (preflight(ex)) return;
        try {
            send(ex, 200, "{\"readers\":" + readersArrayJson() + "}");
        } catch (CardException e) {
            send(ex, 200, "{\"readers\":[],\"errore\":" + jstr(e.getMessage()) + "}");
        }
    }

    private static String readersArrayJson() throws CardException {
        List<CardTerminal> ts = TerminalFactory.getDefault().terminals().list();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ts.size(); i++) {
            boolean p = false;
            try { p = ts.get(i).isCardPresent(); } catch (CardException ignored) {}
            if (i > 0) sb.append(",");
            sb.append("{\"index\":").append(i)
                    .append(",\"name\":").append(jstr(ts.get(i).getName()))
                    .append(",\"cardPresent\":").append(p).append("}");
        }
        return sb.append("]").toString();
    }

    /** Se nessun lettore e' specificato e ci sono piu' carte inserite, ritorna un errore di scelta. */
    private static String controllaAmbiguita(String sel) throws CardException {
        if (sel != null && !sel.isBlank()) return null;
        List<CardTerminal> ts = TerminalFactory.getDefault().terminals().list();
        int conCarta = 0;
        for (CardTerminal t : ts) { try { if (t.isCardPresent()) conCarta++; } catch (CardException ignored) {} }
        if (conCarta > 1)
            return "{\"errore\":\"Piu' carte inserite: specifica il lettore con 'reader'\",\"ambiguo\":true,\"readers\":"
                    + readersArrayJson() + "}";
        return null;
    }

    private static void handleCheck(HttpExchange ex) throws IOException {
        addCors(ex);
        if (preflight(ex)) return;
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "{\"errore\":\"Usare POST\"}");
            return;
        }
        String key = ex.getRequestHeaders().getFirst("X-API-Key");
        if (key == null) key = queryParam(ex.getRequestURI(), "apikey");
        if (API_KEY == null || !API_KEY.equals(key)) {
            send(ex, 401, "{\"errore\":\"API key non valida\"}");
            return;
        }
        String body = readBody(ex);
        String can = extractCan(body, ex.getRequestURI());
        String reader = jsonField(body, "reader");
        if (reader == null) reader = queryParam(ex.getRequestURI(), "reader");
        String json;
        try { json = leggiCartaJson(can, reader); }
        catch (Exception e) { json = "{\"errore\":" + jstr(String.valueOf(e.getMessage())) + "}"; }
        send(ex, 200, json);
    }

    private static void handleWrite(HttpExchange ex) throws IOException {
        addCors(ex);
        if (preflight(ex)) return;
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "{\"errore\":\"Usare POST\"}");
            return;
        }
        String key = ex.getRequestHeaders().getFirst("X-API-Key");
        if (key == null) key = queryParam(ex.getRequestURI(), "apikey");
        if (API_KEY == null || !API_KEY.equals(key)) {
            send(ex, 401, "{\"errore\":\"API key non valida\"}");
            return;
        }
        String body = readBody(ex);
        String text = jsonField(body, "text");
        String hexData = jsonField(body, "hex");
        Integer page = jsonInt(body, "page");
        Integer block = jsonInt(body, "block");
        String mkey = jsonField(body, "key");
        String reader = jsonField(body, "reader");
        if (reader == null) reader = queryParam(ex.getRequestURI(), "reader");
        // Modalita' "tessera socio": se non e' stato passato un testo grezzo ne' hex,
        // costruisco un record strutturato dai campi socio e lo scrivo come testo NDEF.
        if (text == null && hexData == null) {
            String chiave = jsonField(body, "chiaveAnagrafica");
            String socio = jsonField(body, "codiceSocio");
            String familiari = jsonField(body, "familiari");
            String codFam = jsonField(body, "codiceFamiliare");
            if (chiave != null || socio != null || familiari != null || codFam != null)
                text = costruisciRecordSocio(chiave, socio, codFam, familiari);
        }
        String json;
        try { json = scriviNfc(text, hexData, page, block, mkey, reader); }
        catch (Exception e) { json = "{\"errore\":" + jstr(String.valueOf(e.getMessage())) + "}"; }
        send(ex, 200, json);
    }

    /**
     * Costruisce il record "tessera socio" come JSON compatto da scrivere sul tag NDEF.
     * Include solo i campi valorizzati. I familiari restano una lista separata da virgola
     * (normalizzata: spazi rimossi, voci vuote scartate).
     * Esempio: {"chiaveAnagrafica":"9b3f...","codiceSocio":"00123","codiceFamiliare":"01","familiari":"01,02,03"}
     */
    private static String costruisciRecordSocio(String chiave, String socio, String codFam, String familiari) {
        LinkedHashMap<String, String> r = new LinkedHashMap<>();
        if (chiave != null && !chiave.isBlank()) r.put("chiaveAnagrafica", chiave.trim());
        if (socio != null && !socio.isBlank()) r.put("codiceSocio", socio.trim());
        if (codFam != null && !codFam.isBlank()) r.put("codiceFamiliare", codFam.trim());
        if (familiari != null && !familiari.isBlank()) {
            StringBuilder fb = new StringBuilder();
            for (String p : familiari.split(",")) {
                String v = p.trim();
                if (v.isEmpty()) continue;
                if (fb.length() > 0) fb.append(",");
                fb.append(v);
            }
            if (fb.length() > 0) r.put("familiari", fb.toString());
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : r.entrySet()) {
            if (!first) sb.append(",");
            sb.append(jstr(e.getKey())).append(":").append(jstr(e.getValue()));
            first = false;
        }
        return sb.append("}").toString();
    }
    private static void handleVerify(HttpExchange ex) throws IOException {
        addCors(ex);
        if (preflight(ex)) return;
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { send(ex, 405, "{\"errore\":\"Usare POST\"}"); return; }
        String key = ex.getRequestHeaders().getFirst("X-API-Key");
        if (key == null) key = queryParam(ex.getRequestURI(), "apikey");
        if (API_KEY == null || !API_KEY.equals(key)) { send(ex, 401, "{\"errore\":\"API key non valida\"}"); return; }

        String body = readBody(ex);
        LinkedHashMap<String, String> campi = parseFlatJsonOrdered(body);
        String checksum = campi.get("checksum");
        if (checksum == null) { send(ex, 200, "{\"valido\":false,\"motivo\":\"checksum assente\"}"); return; }

        LinkedHashMap<String, String> senza = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : campi.entrySet())
            if (!e.getKey().equals("checksum")) senza.put(e.getKey(), e.getValue());
        String atteso = hmac(canonicalMessage(senza));
        boolean valido = atteso.equalsIgnoreCase(checksum);
        String nonce = campi.get("nonce");
        boolean nonceConosciuto = nonce != null && NONCE_EMESSI.contains(nonce);

        send(ex, 200, "{\"valido\":" + valido + ",\"nonceConosciuto\":" + nonceConosciuto
                + ",\"checksumAtteso\":" + jstr(atteso) + "}");
    }

    // ===================== Lettura carta =====================
    private static String leggiCartaJson(String can, String readerSel) throws CardException {
        String amb = controllaAmbiguita(readerSel);
        if (amb != null) return amb;
        CardTerminal terminal = risolviTerminal(readerSel);
        if (terminal == null)
            return (readerSel != null && !readerSel.isBlank())
                    ? "{\"errore\":\"Lettore non trovato\",\"reader\":" + jstr(readerSel) + "}"
                    : "{\"errore\":\"Nessun lettore con carta inserita\"}";
        try {
            if (!terminal.isCardPresent())
                return "{\"errore\":\"Nessuna carta sul lettore\",\"reader\":" + jstr(terminal.getName()) + "}";
        } catch (CardException ignored) {}

        Rilevazione r = rileva(terminal);
        Tipo tipo = r.tipo;
        String uid = r.uid;
        if (tipo == Tipo.TS_CNS) {
            Map<String, String> dati = leggiTsCns(terminal);
            if (dati == null) return "{\"errore\":\"Dati personali non selezionabili\"}";
            if (uid != null) dati.put("uidCarta", uid);
            return finalizzaJson("TS-CNS", dati);
        } else if (tipo == Tipo.CIE) {
            if (can == null || can.isBlank()) return "{\"tipo\":\"CIE\",\"errore\":\"CAN mancante\"}";
            try {
                Map<String, String> dati = leggiCie(terminal, can.trim());
                if (uid != null) dati.put("uidCarta", uid);
                return finalizzaJson("CIE", dati);
            } catch (CanException ce) {
                return "{\"tipo\":\"CIE\",\"canErrato\":true,\"messaggio\":\"Codice CAN Errato\",\"errore\":"
                        + jstr(ce.getMessage()) + "}";
            } catch (Exception e) {
                // Errore non riconducibile al CAN (es. 6986 di stato): un retry dopo reset.
                try {
                    Thread.sleep(150);
                    Map<String, String> dati = leggiCie(terminal, can.trim());
                    if (uid != null) dati.put("uidCarta", uid);
                    return finalizzaJson("CIE", dati);
                } catch (CanException ce2) {
                    return "{\"tipo\":\"CIE\",\"canErrato\":true,\"messaggio\":\"Codice CAN Errato\",\"errore\":"
                            + jstr(ce2.getMessage()) + "}";
                } catch (Exception e2) {
                    return "{\"tipo\":\"CIE\",\"errore\":" + jstr("Lettura fallita: " + e2.getMessage()) + "}";
                }
            }
        } else if (tipo == Tipo.NFC) {
            return leggiNfc(terminal);
        }
        return diagnostica(terminal);
    }

    enum Tipo { TS_CNS, CIE, NFC, SCONOSCIUTA }

    /** Esito del rilevamento: tipo carta e UID NFC se disponibile (solo contactless). */
    static class Rilevazione {
        final Tipo tipo; final String uid;
        Rilevazione(Tipo t, String u) { tipo = t; uid = u; }
    }

    /** Segnala un fallimento in fase PACE, riconducibile a un CAN errato. */
    static class CanException extends Exception {
        CanException(String m) { super(m); }
    }

    private static Card connectBest(CardTerminal t) throws CardException {
        try { return t.connect("T=1"); } catch (CardException e) { return t.connect("*"); }
    }

    /** Risolve il lettore: per indice ("0","1") o parte del nome; senza selettore, il primo con carta. */
    private static CardTerminal risolviTerminal(String sel) throws CardException {
        List<CardTerminal> ts = TerminalFactory.getDefault().terminals().list();
        if (ts.isEmpty()) return null;
        if (sel != null && !sel.isBlank()) {
            sel = sel.trim();
            try {
                int i = Integer.parseInt(sel);
                return (i >= 0 && i < ts.size()) ? ts.get(i) : null;
            } catch (NumberFormatException ignore) {}
            for (CardTerminal t : ts)
                if (t.getName().toLowerCase().contains(sel.toLowerCase())) return t;
            return null;
        }
        for (CardTerminal t : ts) {
            try { if (t.isCardPresent()) return t; } catch (CardException ignored) {}
        }
        return null;
    }

    private static Rilevazione rileva(CardTerminal terminal) {
        Card card = null;
        try {
            card = connectBest(terminal);
            CardChannel ch = card.getBasicChannel();
            byte[] u = getUid(ch);                     // UID ISO-14443 (solo contactless)
            String uid = (u != null) ? hex(u) : null;
            Tipo tipo;
            if (select(ch, (byte) 0x3F, (byte) 0x00)
                    && select(ch, (byte) 0x11, (byte) 0x00)
                    && select(ch, (byte) 0x11, (byte) 0x02)) tipo = Tipo.TS_CNS;
            else if (selectAid(ch, AID_EMRTD)) tipo = Tipo.CIE;
            else if (u != null) tipo = Tipo.NFC;
            else tipo = Tipo.SCONOSCIUTA;
            return new Rilevazione(tipo, uid);
        } catch (CardException e) {
            return new Rilevazione(Tipo.SCONOSCIUTA, null);
        } finally {
            // reset (true): lascia la carta in stato pulito per la successiva sessione PACE
            if (card != null) try { card.disconnect(true); } catch (CardException ignored) {}
        }
    }

    // ---- Diagnostica ----
    private static String diagnostica(CardTerminal terminal) {
        Card card = null;
        try {
            card = connectBest(terminal);
            CardChannel ch = card.getBasicChannel();
            StringBuilder sb = new StringBuilder("{\"tipo\":\"sconosciuta\",\"diagnostica\":{");
            sb.append("\"atr\":").append(jstr(hex(card.getATR().getBytes())));
            sb.append(",\"selectMF_3F00\":").append(jstr(swHex(ch, new byte[]{0x00, (byte) 0xA4, 0x00, 0x00, 0x02, 0x3F, 0x00})));
            sb.append(",\"selectDF_1100\":").append(jstr(swHex(ch, new byte[]{0x00, (byte) 0xA4, 0x00, 0x00, 0x02, 0x11, 0x00})));
            sb.append(",\"selectEF_1102\":").append(jstr(swHex(ch, new byte[]{0x00, (byte) 0xA4, 0x00, 0x00, 0x02, 0x11, 0x02})));
            byte[] aidSel = new byte[5 + AID_EMRTD.length];
            aidSel[0] = 0x00; aidSel[1] = (byte) 0xA4; aidSel[2] = 0x04; aidSel[3] = 0x00; aidSel[4] = (byte) AID_EMRTD.length;
            System.arraycopy(AID_EMRTD, 0, aidSel, 5, AID_EMRTD.length);
            sb.append(",\"selectAID_eMRTD\":").append(jstr(swHex(ch, aidSel)));
            sb.append("}}");
            return sb.toString();
        } catch (CardException e) {
            return "{\"tipo\":\"sconosciuta\",\"errore\":" + jstr(String.valueOf(e.getMessage())) + "}";
        } finally {
            if (card != null) try { card.disconnect(false); } catch (CardException ignored) {}
        }
    }

    // ---- TS-CNS ----
    private static Map<String, String> leggiTsCns(CardTerminal terminal) throws CardException {
        Card card = connectBest(terminal);
        try {
            CardChannel ch = card.getBasicChannel();
            if (!(select(ch, (byte) 0x3F, (byte) 0x00)
                    && select(ch, (byte) 0x11, (byte) 0x00)
                    && select(ch, (byte) 0x11, (byte) 0x02))) return null;
            byte[] data = readBinary(ch);

            List<String> grezzi = campiGrezzi(data);
            Map<String, String> raw = new LinkedHashMap<>();
            for (int i = 0; i < grezzi.size() && i < ETICHETTE_CNS.length; i++) {
                String v = grezzi.get(i).trim();
                if (!v.isEmpty()) raw.put(ETICHETTE_CNS[i], v);
            }

            Map<String, String> dati = new LinkedHashMap<>();
            String cf = matchCf(new String(data, StandardCharsets.ISO_8859_1));
            putIf(dati, "cognome", raw.get("cognome"));
            putIf(dati, "nome", raw.get("nome"));
            putIf(dati, "codiceFiscale", cf != null ? cf : raw.get("codiceFiscale"));
            putIf(dati, "sesso", raw.get("sesso"));
            putIf(dati, "dataNascita", isoDaGGMMAAAA(raw.get("dataNascita")));
            putIf(dati, "cittadinanza", raw.get("cittadinanza"));
            putIf(dati, "dataScadenza", isoDaGGMMAAAA(raw.get("dataScadenza")));
            // campi specifici TS-CNS
            putIf(dati, "comuneNascita", raw.get("comuneNascita"));
            putIf(dati, "provinciaNascita", raw.get("provinciaNascita"));
            putIf(dati, "codiceEmettitore", raw.get("codiceEmettitore"));
            putIf(dati, "dataEmissione", isoDaGGMMAAAA(raw.get("dataEmissione")));
            putIf(dati, "statura", raw.get("statura"));

            return dati;
        } finally {
            try { card.disconnect(false); } catch (CardException ignored) {}
        }
    }

    // ---- CIE (jMRTD per PACE, parsing a mano) ----
    private static Map<String, String> leggiCie(CardTerminal terminal, String can) throws Exception {
        CardService cs = new TerminalCardService(terminal);
        PassportService ps = new PassportService(
                cs, PassportService.NORMAL_MAX_TRANCEIVE_LENGTH, PassportService.DEFAULT_MAX_BLOCKSIZE, false, false);
        try {
            ps.open();
            try {
                cs.transmit(new net.sf.scuba.smartcards.CommandAPDU(0x00, 0xA4, 0x00, 0x0C, new byte[]{0x3F, 0x00}));
            } catch (Exception ignoreMf) {}

            CardAccessFile cardAccess = new CardAccessFile(ps.getInputStream(PassportService.EF_CARD_ACCESS));
            PACEInfo pace = null;
            for (SecurityInfo si : cardAccess.getSecurityInfos()) {
                if (si instanceof PACEInfo) { pace = (PACEInfo) si; break; }
            }
            if (pace == null) throw new IllegalStateException("PACEInfo non trovato in EF.CardAccess");

            AlgorithmParameterSpec params = PACEInfo.toParameterSpec(pace.getParameterId());
            try {
                ps.doPACE(PACEKeySpec.createCANKey(can), pace.getObjectIdentifier(), params, pace.getParameterId());
            } catch (Exception pe) {
                String m = String.valueOf(pe.getMessage());
                String ml = m.toLowerCase();
                // Vero CAN errato: fallimento al passo del token/mutua autenticazione (SW 6982).
                boolean canErrato = ml.contains("authentication token") || ml.contains("mutual")
                        || m.contains("6982") || m.contains("step: 4");
                if (canErrato) throw new CanException(m);
                // Altri errori PACE (es. 6986 allo step 0) = stato transitorio, non CAN.
                throw new RuntimeException(m);
            }
            ps.sendSelectApplet(true);

            byte[] dg1 = readAll(ps.getInputStream(PassportService.EF_DG1));
            byte[] dg11 = readAll(ps.getInputStream(PassportService.EF_DG11));

            Map<String, String> out = new LinkedHashMap<>();
            String mrz = estraiMrz(dg1);
            if (mrz != null) parseMrzTd1(mrz, out);
            String cf = matchCf(new String(dg11, StandardCharsets.ISO_8859_1));
            if (cf == null && mrz != null) cf = matchCf(mrz);
            if (cf != null) out.put("codiceFiscale", cf);
            return out;
        } finally {
            try { ps.close(); } catch (Exception ignored) {}
        }
    }

    // ===================== Tag NFC generici =====================
    private static byte[] getUid(CardChannel ch) {
        try {
            ResponseAPDU r = ch.transmit(new CommandAPDU(new byte[]{(byte) 0xFF, (byte) 0xCA, 0x00, 0x00, 0x00}));
            return r.getSW() == 0x9000 ? r.getData() : null;
        } catch (CardException e) { return null; }
    }

    private static String leggiNfc(CardTerminal terminal) throws CardException {
        Card card = connectBest(terminal);
        try {
            CardChannel ch = card.getBasicChannel();
            Map<String, String> m = new LinkedHashMap<>();
            m.put("tipo", "NFC");
            byte[] uid = getUid(ch);
            m.put("uid", uid != null ? hex(uid) : "");
            m.put("atr", hex(card.getATR().getBytes()));

            // Lettura a pagine (NTAG/Ultralight): FF B0 00 <page> 04
            ByteArrayOutputStream mem = new ByteArrayOutputStream();
            boolean pageOk = false;
            for (int page = 0; page < 135; page++) {
                ResponseAPDU r = ch.transmit(new CommandAPDU(
                        new byte[]{(byte) 0xFF, (byte) 0xB0, 0x00, (byte) page, 0x04}));
                if (r.getSW() != 0x9000) break;
                byte[] d = r.getData();
                if (d.length == 0) break;
                mem.write(d, 0, d.length);
                pageOk = true;
            }

            if (pageOk) {
                byte[] data = mem.toByteArray();
                m.put("famiglia", "NTAG/Ultralight");
                m.put("memoriaHex", hex(data));
                String token = parseNdefToken(data);
                if (token != null) {
                    m.put("ndefText", token);
                    String t = token.trim();
                    // se il tag contiene un record "tessera socio" (JSON), espongo i campi
                    if (t.startsWith("{") && t.endsWith("}")) {
                        for (Map.Entry<String, String> e : parseFlatJsonOrdered(t).entrySet())
                            m.putIfAbsent(e.getKey(), e.getValue());
                    }
                }
            } else {
                m.put("famiglia", "MIFARE Classic (o non leggibile a pagine)");
                String blocchi = leggiClassicDefault(ch);
                if (blocchi != null) m.put("memoriaHex", blocchi);
                else m.put("nota", "Memoria protetta: servono le chiavi del settore. UID comunque disponibile.");
            }
            return mapToJson(m, null);
        } finally {
            try { card.disconnect(false); } catch (CardException ignored) {}
        }
    }

    /** Tentativo MIFARE Classic con chiave A di default FFFFFFFFFFFF sui blocchi del settore 1. */
    private static String leggiClassicDefault(CardChannel ch) {
        try {
            // FF 82 00 00 06 <chiave> : carica la chiave nello slot volatile 0
            ch.transmit(new CommandAPDU(new byte[]{(byte) 0xFF, (byte) 0x82, 0x00, 0x00, 0x06,
                    (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            boolean any = false;
            for (int block : new int[]{4, 5, 6}) {
                // FF 86 00 00 05 01 00 <block> 60(=keyA) 00(=slot)
                byte[] auth = {(byte) 0xFF, (byte) 0x86, 0x00, 0x00, 0x05, 0x01, 0x00, (byte) block, 0x60, 0x00};
                if (ch.transmit(new CommandAPDU(auth)).getSW() != 0x9000) continue;
                ResponseAPDU r = ch.transmit(new CommandAPDU(
                        new byte[]{(byte) 0xFF, (byte) 0xB0, 0x00, (byte) block, 0x10}));
                if (r.getSW() == 0x9000) { out.write(r.getData(), 0, r.getData().length); any = true; }
            }
            return any ? hex(out.toByteArray()) : null;
        } catch (Exception e) { return null; }
    }

    // ===================== Scrittura tag NFC =====================
    private static String scriviNfc(String text, String hexData, Integer page, Integer block, String key, String readerSel) throws CardException {
        String amb = controllaAmbiguita(readerSel);
        if (amb != null) return amb;
        CardTerminal terminal = risolviTerminal(readerSel);
        if (terminal == null)
            return (readerSel != null && !readerSel.isBlank())
                    ? "{\"errore\":\"Lettore non trovato\",\"reader\":" + jstr(readerSel) + "}"
                    : "{\"errore\":\"Nessun lettore con carta inserita\"}";
        try {
            if (!terminal.isCardPresent())
                return "{\"errore\":\"Nessuna carta sul lettore\",\"reader\":" + jstr(terminal.getName()) + "}";
        } catch (CardException ignored) {}

        Card card = connectBest(terminal);
        try {
            CardChannel ch = card.getBasicChannel();
            byte[] uid = getUid(ch);
            if (uid == null) return "{\"errore\":\"Non e' un tag NFC contactless scrivibile\"}";
            String u = jstr(hex(uid));

            if (text != null) {
                byte[] tlv = buildNdefTextTlv(text);
                if (tlv == null) return "{\"errore\":\"Testo troppo lungo per il formato corto\",\"uid\":" + u + "}";
                int cap = capacitaNtag(ch);
                if (cap > 0 && tlv.length > cap)
                    return "{\"errore\":\"Dati troppo grandi per il tag (max " + cap + " byte)\",\"uid\":" + u + "}";
                String w = scriviPagine(ch, 4, tlv);
                if (w != null) return "{\"tipo\":\"NFC\",\"uid\":" + u + ",\"errore\":" + jstr(w) + "}";
                return "{\"tipo\":\"NFC\",\"esito\":\"scritto\",\"modalita\":\"ndef-text\",\"uid\":" + u + ",\"byteScritti\":" + tlv.length + "}";
            } else if (hexData != null && block != null) {
                byte[] data = parseHex(hexData);
                if (data.length != 16) return "{\"errore\":\"Per MIFARE Classic servono 16 byte (32 hex)\",\"uid\":" + u + "}";
                String w = scriviClassicBlock(ch, block, data, key);
                if (w != null) return "{\"tipo\":\"NFC\",\"uid\":" + u + ",\"errore\":" + jstr(w) + "}";
                return "{\"tipo\":\"NFC\",\"esito\":\"scritto\",\"modalita\":\"classic-block\",\"uid\":" + u + ",\"block\":" + block + "}";
            } else if (hexData != null && page != null) {
                byte[] data = parseHex(hexData);
                if (data.length % 4 != 0) data = Arrays.copyOf(data, ((data.length / 4) + 1) * 4);
                String w = scriviPagine(ch, page, data);
                if (w != null) return "{\"tipo\":\"NFC\",\"uid\":" + u + ",\"errore\":" + jstr(w) + "}";
                return "{\"tipo\":\"NFC\",\"esito\":\"scritto\",\"modalita\":\"raw-page\",\"uid\":" + u + ",\"page\":" + page + ",\"byteScritti\":" + data.length + "}";
            }
            return "{\"errore\":\"Specifica 'text', oppure 'hex'+'page', oppure 'hex'+'block'\",\"uid\":" + u + "}";
        } finally {
            try { card.disconnect(false); } catch (CardException ignored) {}
        }
    }

    /** Scrive pagina per pagina (4 byte) via FF D6. Ritorna null se ok, altrimenti il messaggio d'errore. */
    private static String scriviPagine(CardChannel ch, int startPage, byte[] data) throws CardException {
        int page = startPage;
        for (int off = 0; off < data.length; off += 4, page++) {
            byte[] c = new byte[4];
            System.arraycopy(data, off, c, 0, Math.min(4, data.length - off));
            byte[] apdu = {(byte) 0xFF, (byte) 0xD6, 0x00, (byte) page, 0x04, c[0], c[1], c[2], c[3]};
            int sw = ch.transmit(new CommandAPDU(apdu)).getSW();
            if (sw != 0x9000) {
                if (sw == 0x6D00 || sw == 0x6A81)
                    return "Scrittura non supportata dal lettore o tag in sola lettura (SW " + String.format("%04X", sw) + ")";
                return "Errore in scrittura pagina " + page + " (SW " + String.format("%04X", sw) + ")";
            }
        }
        return null;
    }

    /** Capacita' utile (byte) da Capability Container (pagina 3, byte 2 * 8). -1 se ignota. */
    private static int capacitaNtag(CardChannel ch) {
        try {
            ResponseAPDU r = ch.transmit(new CommandAPDU(new byte[]{(byte) 0xFF, (byte) 0xB0, 0x00, 0x03, 0x04}));
            if (r.getSW() == 0x9000 && r.getData().length >= 3) return (r.getData()[2] & 0xFF) * 8;
        } catch (CardException ignored) {}
        return -1;
    }

    /** Scrive un blocco MIFARE Classic (16 byte) con chiave A (default FFFFFFFFFFFF). */
    private static String scriviClassicBlock(CardChannel ch, int block, byte[] data, String key) throws CardException {
        byte[] k = (key != null && key.replaceAll("[^0-9A-Fa-f]", "").length() == 12)
                ? parseHex(key)
                : new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        ch.transmit(new CommandAPDU(new byte[]{(byte) 0xFF, (byte) 0x82, 0x00, 0x00, 0x06,
                k[0], k[1], k[2], k[3], k[4], k[5]}));
        byte[] auth = {(byte) 0xFF, (byte) 0x86, 0x00, 0x00, 0x05, 0x01, 0x00, (byte) block, 0x60, 0x00};
        if (ch.transmit(new CommandAPDU(auth)).getSW() != 0x9000)
            return "Autenticazione settore fallita (chiave errata?)";
        byte[] w = new byte[5 + 16];
        w[0] = (byte) 0xFF; w[1] = (byte) 0xD6; w[2] = 0x00; w[3] = (byte) block; w[4] = 0x10;
        System.arraycopy(data, 0, w, 5, 16);
        int sw = ch.transmit(new CommandAPDU(w)).getSW();
        if (sw != 0x9000) return "Errore in scrittura blocco " + block + " (SW " + String.format("%04X", sw) + ")";
        return null;
    }

    /** Costruisce TLV NDEF con un singolo record di testo (formato corto), pronto per la scrittura. */
    private static byte[] buildNdefTextTlv(String text) {
        try {
            byte[] t = text.getBytes(StandardCharsets.UTF_8);
            byte[] lang = "en".getBytes(StandardCharsets.US_ASCII);
            int payloadLen = 1 + lang.length + t.length;
            ByteArrayOutputStream ndef = new ByteArrayOutputStream();
            ndef.write(0xD1);                 // MB+ME+SR, TNF=0x01 (well-known)
            ndef.write(0x01);                 // type length
            ndef.write(payloadLen);           // payload length (short record)
            ndef.write('T');                  // type = Text
            ndef.write(lang.length & 0x3F);   // status: UTF-8 + lunghezza lingua
            ndef.write(lang);
            ndef.write(t);
            byte[] msg = ndef.toByteArray();
            if (msg.length > 254) return null;
            ByteArrayOutputStream tlv = new ByteArrayOutputStream();
            tlv.write(0x03);                  // NDEF Message TLV
            tlv.write(msg.length);
            tlv.write(msg);
            tlv.write(0xFE);                  // Terminator TLV
            byte[] out = tlv.toByteArray();
            if (out.length % 4 != 0) out = Arrays.copyOf(out, ((out.length / 4) + 1) * 4); // pad a pagine
            return out;
        } catch (Exception e) { return null; }
    }

    private static byte[] parseHex(String s) {
        s = s.replaceAll("[^0-9A-Fa-f]", "");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++)
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    private static String jsonField(String body, String name) {
        if (body == null) return null;
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private static Integer jsonInt(String body, String name) {
        if (body == null) return null;
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(\\d+)").matcher(body);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    /** Cerca il TLV NDEF (0x03) nell'area utente e ritorna il testo del primo record. */
    private static String parseNdefToken(byte[] data) {
        int start = Math.min(16, data.length); // area utente da pagina 4 (offset 16)
        for (int i = start; i < data.length; ) {
            int t = data[i] & 0xFF;
            if (t == 0x00) { i++; continue; }
            if (t == 0xFE) break;
            if (i + 1 >= data.length) break;
            int len = data[i + 1] & 0xFF; int vs = i + 2;
            if (len == 0xFF) {
                if (i + 3 >= data.length) break;
                len = ((data[i + 2] & 0xFF) << 8) | (data[i + 3] & 0xFF); vs = i + 4;
            }
            if (t == 0x03) {
                int end = Math.min(vs + len, data.length);
                return primoRecordTesto(Arrays.copyOfRange(data, vs, end));
            }
            i = vs + len;
        }
        return null;
    }

    private static String primoRecordTesto(byte[] msg) {
        if (msg.length < 3) return null;
        int header = msg[0] & 0xFF;
        boolean sr = (header & 0x10) != 0;
        boolean il = (header & 0x08) != 0;
        int p = 1;
        int typeLen = msg[p++] & 0xFF;
        long plen;
        if (sr) plen = msg[p++] & 0xFF;
        else {
            plen = ((long) (msg[p] & 0xFF) << 24) | ((msg[p + 1] & 0xFF) << 16)
                    | ((msg[p + 2] & 0xFF) << 8) | (msg[p + 3] & 0xFF);
            p += 4;
        }
        int idLen = il ? (msg[p++] & 0xFF) : 0;
        if (p + typeLen > msg.length) return null;
        String type = new String(msg, p, typeLen, StandardCharsets.US_ASCII);
        p += typeLen + idLen;
        int pe = (int) Math.min(p + plen, msg.length);
        if (p > pe) return null;
        byte[] payload = Arrays.copyOfRange(msg, p, pe);
        if ("T".equals(type)) {
            if (payload.length == 0) return "";
            int status = payload[0] & 0xFF;
            int langLen = status & 0x3F;
            int ts = 1 + langLen;
            if (ts > payload.length) return "";
            return new String(payload, ts, payload.length - ts,
                    (status & 0x80) != 0 ? StandardCharsets.UTF_16 : StandardCharsets.UTF_8);
        } else if ("U".equals(type)) {
            return payload.length == 0 ? "" : new String(payload, 1, payload.length - 1, StandardCharsets.UTF_8);
        }
        return new String(payload, StandardCharsets.UTF_8);
    }

    private static String tipoString(Tipo t) {
        switch (t) {
            case TS_CNS: return "TS-CNS";
            case CIE: return "CIE";
            case NFC: return "NFC";
            default: return "sconosciuta";
        }
    }

    // ===================== MRZ TD1 =====================
    private static String estraiMrz(byte[] dg1) {
        for (int i = 0; i + 1 < dg1.length; i++) {
            if ((dg1[i] & 0xFF) == 0x5F && (dg1[i + 1] & 0xFF) == 0x1F) {
                int j = i + 2;
                if (j >= dg1.length) break;
                int len = dg1[j] & 0xFF; j++;
                if (len == 0x81) { len = dg1[j] & 0xFF; j++; }
                else if (len == 0x82) { len = ((dg1[j] & 0xFF) << 8) | (dg1[j + 1] & 0xFF); j += 2; }
                if (j + len <= dg1.length) return new String(dg1, j, len, StandardCharsets.ISO_8859_1);
            }
        }
        Matcher m = Pattern.compile("[A-Z0-9<]{30,}").matcher(new String(dg1, StandardCharsets.ISO_8859_1));
        String best = null;
        while (m.find()) if (best == null || m.group().length() > best.length()) best = m.group();
        if (best != null && best.length() > 90) best = best.substring(best.length() - 90);
        return best;
    }

    private static void parseMrzTd1(String mrz, Map<String, String> out) {
        if (mrz.length() < 90) return;
        String l1 = mrz.substring(0, 30);
        String l2 = mrz.substring(30, 60);
        String l3 = mrz.substring(60, 90);
        char sesso = l2.charAt(7);
        String[] nomi = l3.split("<<", 2);
        putIf(out, "cognome", nomi[0].replace('<', ' ').trim());
        putIf(out, "nome", nomi.length > 1 ? nomi[1].replace('<', ' ').trim() : "");
        if (sesso == 'M' || sesso == 'F') out.put("sesso", String.valueOf(sesso));
        putIf(out, "dataNascita", isoDaMrz(l2.substring(0, 6), false));
        putIf(out, "cittadinanza", l2.substring(15, 18).replace("<", ""));
        putIf(out, "dataScadenza", isoDaMrz(l2.substring(8, 14), true));
        putIf(out, "numeroDocumento", l1.substring(5, 14).replace("<", "").trim());
    }

    private static String isoDaMrz(String yymmdd, boolean futuro) {
        if (yymmdd == null || !yymmdd.matches("\\d{6}")) return "";
        int yy = Integer.parseInt(yymmdd.substring(0, 2));
        int mm = Integer.parseInt(yymmdd.substring(2, 4));
        int dd = Integer.parseInt(yymmdd.substring(4, 6));
        int curYY = Year.now().getValue() % 100;
        int year = futuro ? 2000 + yy : (yy <= curYY ? 2000 + yy : 1900 + yy);
        return String.format("%04d-%02d-%02d", year, mm, dd);
    }

    // ===================== CNS helpers =====================
    private static List<String> campiGrezzi(byte[] data) {
        List<String> fields = new ArrayList<>();
        if (data.length < 6) return fields;
        int pos = 6;
        while (pos + 2 <= data.length) {
            int len;
            try { len = Integer.parseInt(new String(data, pos, 2, StandardCharsets.US_ASCII), 16); }
            catch (Exception e) { break; }
            pos += 2;
            if (len == 0 || pos + len > data.length) break;
            fields.add(new String(data, pos, len, StandardCharsets.ISO_8859_1));
            pos += len;
        }
        return fields;
    }

    // ===================== APDU =====================
    private static boolean okSw(int sw) { return sw == 0x9000 || (sw & 0xFF00) == 0x6100; }

    private static boolean select(CardChannel ch, byte hi, byte lo) throws CardException {
        for (int p2 : new int[]{0x0C, 0x00}) {
            int sw = ch.transmit(new CommandAPDU(new byte[]{0x00, (byte) 0xA4, 0x00, (byte) p2, 0x02, hi, lo})).getSW();
            if (okSw(sw)) return true;
        }
        return false;
    }

    private static boolean selectAid(CardChannel ch, byte[] aid) throws CardException {
        for (int p2 : new int[]{0x0C, 0x00}) {
            byte[] apdu = new byte[5 + aid.length];
            apdu[0] = 0x00; apdu[1] = (byte) 0xA4; apdu[2] = 0x04; apdu[3] = (byte) p2; apdu[4] = (byte) aid.length;
            System.arraycopy(aid, 0, apdu, 5, aid.length);
            if (okSw(ch.transmit(new CommandAPDU(apdu)).getSW())) return true;
        }
        return false;
    }

    private static byte[] readBinary(CardChannel ch) throws CardException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int offset = 0; final int chunk = 0xFF;
        while (offset < 0x7FFF) {
            byte[] apdu = {0x00, (byte) 0xB0, (byte) ((offset >> 8) & 0xFF), (byte) (offset & 0xFF), (byte) chunk};
            ResponseAPDU r = ch.transmit(new CommandAPDU(apdu));
            int sw = r.getSW(); byte[] body = r.getData();
            if (body.length > 0) { out.write(body, 0, body.length); offset += body.length; }
            if (sw == 0x6B00 || sw == 0x6282) break;
            if (sw != 0x9000) break;
            if (body.length < chunk) break;
        }
        return out.toByteArray();
    }

    // ===================== util =====================
    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[512]; int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static String matchCf(String s) {
        if (s == null) return null;
        Matcher m = CF.matcher(s.toUpperCase());
        return m.find() ? m.group() : null;
    }

    private static String swHex(CardChannel ch, byte[] apdu) throws CardException {
        return String.format("%04X", ch.transmit(new CommandAPDU(apdu)).getSW());
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    /** Carica comuni.csv (codicecatastale:comune:provincia:regione) dal classpath. */
    private static void caricaComuni() {
        try (InputStream is = IdentityServer.class.getResourceAsStream("/comuni.csv")) {
            if (is == null) { System.out.println("ATTENZIONE: comuni.csv non trovato nel classpath."); return; }
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String line; int n = 0;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] p = line.split(":", 4);
                if (p.length >= 4 && !p[0].isBlank()) {
                    COMUNI.put(p[0].trim().toUpperCase(), new String[]{p[1].trim(), p[2].trim(), p[3].trim()});
                    n++;
                }
            }
            System.out.println("Comuni caricati in memoria: " + n);
        } catch (Exception e) {
            System.out.println("Errore caricamento comuni: " + e.getMessage());
        }
    }

    /** Estrae il codice catastale (Belfiore) dal CF, ripristinando l'omocodia sulle 3 cifre. */
    private static String catastaleFromCf(String cf) {
        if (cf == null || cf.length() < 15) return null;
        String code = cf.substring(11, 15).toUpperCase();
        final String omo = "LMNPQRSTUV"; // L=0, M=1, ... V=9
        StringBuilder sb = new StringBuilder();
        sb.append(code.charAt(0)); // lettera di provincia: invariata
        for (int i = 1; i < 4; i++) {
            char c = code.charAt(i);
            int idx = omo.indexOf(c);
            sb.append(idx >= 0 ? (char) ('0' + idx) : c); // riconverte eventuale omocodia
        }
        return sb.toString();
    }

    /** Schema uniforme: tipo, campi canonici, campi specifici, metadati e checksum HMAC. */
    private static String finalizzaJson(String tipo, Map<String, String> dati) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        out.put("tipo", tipo);
        String[] canonici = {"cognome", "nome", "codiceFiscale", "sesso", "dataNascita", "cittadinanza", "dataScadenza"};
        for (String k : canonici) if (dati.containsKey(k)) out.put(k, dati.get(k));
        // Luogo di nascita derivato dal codice fiscale (codice catastale -> comune/provincia/regione)
        String cf = out.get("codiceFiscale");
        if (cf != null) {
            putIf(out, "chiaveAnagrafica", idAnagrafico(cf));
            String cat = catastaleFromCf(cf);
            if (cat != null) {
                out.put("codiceCatastale", cat);
                String[] info = COMUNI.get(cat);
                if (info != null) {
                    putIf(out, "comuneNascita", info[0]);
                    putIf(out, "provinciaNascita", info[1]);
                    putIf(out, "regione", info[2]);
                }
            }
        }
        for (Map.Entry<String, String> e : dati.entrySet())
            if (!out.containsKey(e.getKey())) out.put(e.getKey(), e.getValue()); // campi specifici
        out.put("letturaTimestamp", Instant.now().toString());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        out.put("nonce", nonce);
        registraNonce(nonce);
        out.put("checksum", hmac(canonicalMessage(out)));   // HMAC su tutto cio' che precede
        return mapToJson(out, null);
    }

    /** Messaggio canonico per la firma: "chiave=valore\n" nell'ordine di inserimento. */
    private static String canonicalMessage(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet())
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        return sb.toString();
    }

    private static String hmac(String msg) { return hmacWith(SIGN_KEY, msg); }

    private static String hmacWith(String key, String msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(msg.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { return ""; }
    }

    /** Identificativo anagrafico pseudonimo: HMAC(chiave crittografica, CF). Deterministico, non reversibile. */
    private static String idAnagrafico(String cf) {
        return cf == null ? null : hmacWith(CRYPTO_KEY, cf.toUpperCase());
    }

    private static void registraNonce(String nonce) {
        synchronized (NONCE_EMESSI) {
            NONCE_EMESSI.add(nonce);
            if (NONCE_EMESSI.size() > NONCE_MAX) {
                Iterator<String> it = NONCE_EMESSI.iterator();
                if (it.hasNext()) { it.next(); it.remove(); }
            }
        }
    }

    /** Parser piatto che preserva l'ordine dei campi stringa (sufficiente per i payload emessi). */
    private static LinkedHashMap<String, String> parseFlatJsonOrdered(String body) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        if (body == null) return m;
        Matcher mm = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(body);
        while (mm.find()) m.put(mm.group(1), unescapeJson(mm.group(2)));
        return m;
    }

    private static String unescapeJson(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'u':
                        if (i + 4 < s.length()) { sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; }
                        break;
                    default: sb.append(n);
                }
            } else sb.append(c);
        }
        return sb.toString();
    }

    private static void putIf(Map<String, String> m, String k, String v) {
        if (v != null && !v.isBlank()) m.put(k, v);
    }

    /** Data CNS GGMMAAAA -> ISO YYYY-MM-DD; se non e' nel formato atteso la lascia invariata. */
    private static String isoDaGGMMAAAA(String d) {
        if (d == null || !d.matches("\\d{8}")) return d;
        return d.substring(4, 8) + "-" + d.substring(2, 4) + "-" + d.substring(0, 2);
    }

    private static String mapToJson(Map<String, String> m, List<String> grezzi) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (!first) sb.append(",");
            sb.append(jstr(e.getKey())).append(":").append(jstr(e.getValue()));
            first = false;
        }
        if (grezzi != null) {
            sb.append(",\"campiGrezzi\":[");
            for (int i = 0; i < grezzi.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(jstr(grezzi.get(i).trim()));
            }
            sb.append("]");
        }
        return sb.append("}").toString();
    }

    private static String extractCan(String body, URI uri) {
        if (body != null && !body.isBlank()) {
            Matcher mj = Pattern.compile("\"can\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
            if (mj.find()) return mj.group(1).trim();
            Matcher mf = Pattern.compile("(?:^|&)can=([^&]*)").matcher(body);
            if (mf.find()) return java.net.URLDecoder.decode(mf.group(1), StandardCharsets.UTF_8).trim();
        }
        String q = queryParam(uri, "can");
        return q == null ? null : q.trim();
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String queryParam(URI uri, String name) {
        String q = uri.getRawQuery();
        if (q == null) return null;
        for (String p : q.split("&")) {
            int i = p.indexOf('=');
            if (i > 0 && p.substring(0, i).equals(name))
                return java.net.URLDecoder.decode(p.substring(i + 1), StandardCharsets.UTF_8);
        }
        return null;
    }

    private static boolean preflight(HttpExchange ex) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1); ex.close(); return true;
        }
        return false;
    }

    private static void addCors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", ALLOWED_ORIGIN);
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, X-API-Key");
    }

    private static void send(HttpExchange ex, int code, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    private static String jstr(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default: if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
            }
        }
        return sb.append("\"").toString();
    }

    private static String prop(String sys, String env) {
        String v = System.getProperty(sys);
        if (v == null || v.isBlank()) v = System.getenv(env);
        return (v == null || v.isBlank()) ? null : v;
    }

    private static String orDefault(String v, String d) { return v == null ? d : v; }

    private static int resolveInt(String sys, String env, int def) {
        String v = prop(sys, env);
        try { return v != null ? Integer.parseInt(v) : def; } catch (Exception e) { return def; }
    }

    // ===================== System tray =====================
    private static void setupTray() {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            System.out.println("Modalita' background (nessun system tray disponibile).");
            return;
        }
        try {
            SystemTray tray = SystemTray.getSystemTray();
            PopupMenu menu = new PopupMenu();

            MenuItem apri = new MenuItem("Apri console web (test)");
            apri.addActionListener(e -> browse("http://localhost:" + PORT));
            MenuItem stato = new MenuItem("Stato lettore");
            stato.addActionListener(e -> mostraStato());
            MenuItem riavvia = new MenuItem("Riavvia servizio");
            riavvia.addActionListener(e -> riavviaServer());
            MenuItem esci = new MenuItem("Esci");
            esci.addActionListener(e -> { tray.remove(trayIcon); System.exit(0); });

            menu.add(apri); menu.add(stato); menu.add(riavvia); menu.addSeparator(); menu.add(esci);

            trayIcon = new TrayIcon(creaIcona(), "Servizio identita' CIE/TS-CNS - porta " + PORT, menu);
            trayIcon.setImageAutoSize(true);
            tray.add(trayIcon);
            System.out.println("Icona presente nel system tray.");
        } catch (Exception e) {
            System.out.println("Tray non disponibile: " + e.getMessage());
        }
    }

    private static void mostraStato() {
        boolean present = false;
        String reader = "(nessun lettore)";
        try {
            for (CardTerminal t : TerminalFactory.getDefault().terminals().list()) {
                reader = t.getName();
                if (t.isCardPresent()) { present = true; break; }
            }
        } catch (CardException ignored) {}
        if (trayIcon != null)
            trayIcon.displayMessage("Stato", "Lettore: " + reader + "\nCarta presente: " + (present ? "si" : "no"),
                    TrayIcon.MessageType.INFO);
    }

    private static void browse(String url) {
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception ignored) {}
    }

    private static Image creaIcona() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x1F6FEB));
        g.fillRoundRect(0, 0, 16, 16, 5, 5);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 9));
        g.drawString("ID", 2, 12);
        g.dispose();
        return img;
    }

    // ===================== pagina test =====================
    private static void handleRoot(HttpExchange ex) throws IOException {
        addCors(ex);
        if (preflight(ex)) return;
        byte[] body = PAGE.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    private static final String PAGE =
            "<!doctype html><html lang='it'><head><meta charset='utf-8'>"
                    + "<meta name='viewport' content='width=device-width,initial-scale=1'><title>Verifica identita'</title>"
                    + "<style>body{font-family:system-ui,Arial,sans-serif;max-width:680px;margin:40px auto;padding:0 16px;color:#1a1a1a}"
                    + "h1{font-size:1.3rem}input,button,select{font-size:1rem;padding:.55rem .7rem;border-radius:8px;border:1px solid #ccc}"
                    + "button{background:#1f6feb;color:#fff;border:none;cursor:pointer}button:hover{background:#1759c2}"
                    + "label{display:block;margin:.6rem 0 .2rem;font-weight:600}"
                    + "pre{background:#0d1117;color:#c9d1d9;padding:14px;border-radius:10px;overflow:auto;white-space:pre-wrap}"
                    + ".row{display:flex;gap:.5rem;align-items:end;flex-wrap:wrap}.muted{color:#666;font-size:.9rem}</style></head><body>"
                    + "<h1>Verifica identita' da carta</h1>"
                    + "<p class='muted'>Pagina di test. In produzione chiama POST /check dal gestionale con header X-API-Key.</p>"
                    + "<div class='row'><div><label>Lettore</label><select id='reader'></select></div>"
                    + "<button onclick='caricaLettori()' style='background:#444'>Aggiorna lettori</button></div>"
                    + "<div class='row'><div><label>API key</label><input id='key' placeholder='X-API-Key'></div>"
                    + "<div><label>CAN (solo CIE)</label><input id='can' inputmode='numeric' maxlength='6' placeholder='6 cifre'></div>"
                    + "<button onclick='leggi()'>Leggi carta</button>"
                    + "<button onclick='stato()' style='background:#444'>Stato</button></div>"
                    + "<div class='row'><div><label>Testo da scrivere su tag NFC</label>"
                    + "<input id='wtext' placeholder='es. SOCIO-000123'></div>"
                    + "<button onclick='scrivi()' style='background:#0a7d33'>Scrivi NFC</button></div>"
                    + "<fieldset style='border:1px solid #333;border-radius:8px;margin-top:10px'>"
                    + "<legend>Tessera socio (scrittura strutturata)</legend>"
                    + "<div class='row'><div><label>Chiave anagrafica</label><input id='s_ka' placeholder='HMAC 64 hex'></div>"
                    + "<div><label>Codice socio</label><input id='s_cs' placeholder='es. 000123'></div></div>"
                    + "<div class='row'><div><label>Codice familiare</label><input id='s_cf' placeholder='es. 01'></div>"
                    + "<div><label>Familiari abilitati al bar (separati da virgola)</label><input id='s_fam' placeholder='es. 01,02,03'></div></div>"
                    + "<button onclick='scriviSocio()' style='background:#0a7d33'>Scrivi tessera socio</button></fieldset>"
                    + "<label>Risultato</label><pre id='out'>—</pre>"
                    + "<script>"
                    + "function key(){return document.getElementById('key').value;}"
                    + "function reader(){return document.getElementById('reader').value;}"
                    + "function q(){const v=reader();return v?('?reader='+encodeURIComponent(v)):'';}"
                    + "async function caricaLettori(){try{const r=await fetch('/actuator/readers');const j=await r.json();"
                    + "const sel=document.getElementById('reader');sel.innerHTML='';"
                    + "const o0=document.createElement('option');o0.value='';o0.textContent='auto (prima con carta)';sel.appendChild(o0);"
                    + "(j.readers||[]).forEach(rd=>{const o=document.createElement('option');o.value=rd.index;"
                    + "o.textContent='['+rd.index+'] '+rd.name+(rd.cardPresent?'  [carta]':'');sel.appendChild(o);});}catch(e){}}"
                    + "async function leggi(){const out=document.getElementById('out');out.textContent='Lettura...';"
                    + "try{const r=await fetch('/check'+q(),{method:'POST',headers:{'Content-Type':'application/json','X-API-Key':key()},"
                    + "body:JSON.stringify({can:document.getElementById('can').value.trim()})});"
                    + "out.textContent=JSON.stringify(await r.json(),null,2);}catch(e){out.textContent='Errore: '+e;}}"
                    + "async function scrivi(){const out=document.getElementById('out');out.textContent='Scrittura...';"
                    + "try{const r=await fetch('/write'+q(),{method:'POST',headers:{'Content-Type':'application/json','X-API-Key':key()},"
                    + "body:JSON.stringify({text:document.getElementById('wtext').value})});"
                    + "out.textContent=JSON.stringify(await r.json(),null,2);}catch(e){out.textContent='Errore: '+e;}}"
                    + "async function scriviSocio(){const out=document.getElementById('out');out.textContent='Scrittura...';"
                    + "const b={chiaveAnagrafica:document.getElementById('s_ka').value.trim(),"
                    + "codiceSocio:document.getElementById('s_cs').value.trim(),"
                    + "codiceFamiliare:document.getElementById('s_cf').value.trim(),"
                    + "familiari:document.getElementById('s_fam').value.trim()};"
                    + "try{const r=await fetch('/write'+q(),{method:'POST',headers:{'Content-Type':'application/json','X-API-Key':key()},"
                    + "body:JSON.stringify(b)});"
                    + "out.textContent=JSON.stringify(await r.json(),null,2);}catch(e){out.textContent='Errore: '+e;}}"
                    + "async function stato(){const out=document.getElementById('out');"
                    + "try{const r=await fetch('/actuator/status'+q());out.textContent=JSON.stringify(await r.json(),null,2);}catch(e){out.textContent='Errore: '+e;}}"
                    + "window.addEventListener('load',caricaLettori);"
                    + "</script></body></html>";
}