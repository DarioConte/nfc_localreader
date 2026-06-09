import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.jmrtd.PACEKeySpec;
import org.jmrtd.PassportService;
import org.jmrtd.lds.CardAccessFile;
import org.jmrtd.lds.PACEInfo;
import org.jmrtd.lds.SecurityInfo;
import org.jmrtd.lds.icao.DG11File;
import net.sf.scuba.smartcards.CardService;
import net.sf.scuba.smartcards.TerminalCardService;

import javax.smartcardio.*;
import javax.swing.*;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wedge codice fiscale per TS-CNS e CIE.
 *
 *   TS-CNS : file "Dati personali" leggibile senza PIN -> CF letto e digitato (automatico).
 *   CIE    : dati protetti da PACE. Al rilevamento appare un popup che chiede il CAN
 *            (6 cifre stampate sulla carta); con il CAN si apre il canale sicuro,
 *            si legge il DG11 e si estrae il CF, che viene poi digitato.
 *
 * Il CF viene digitato nel campo attualmente a fuoco, come una tastiera.
 *
 * BUILD ED ESECUZIONE
 *   mvn clean package
 *   java --add-modules java.smartcardio -jar target/cie-cns-wedge.jar [enter|tab|none] [indiceLettore]
 *   In background senza finestra: javaw (Windows) / nohup (Linux) / launchd (macOS).
 *
 * AVVISI
 *   - Richiede ambiente grafico (Robot + popup CAN).
 *   - I punti marcati "// VERIFICA" dipendono dalla versione di jMRTD: allineali se la
 *     compilazione segnala firme diverse.
 *   - GDPR: lettura/registrazione del CF e' trattamento di dati personali.
 */
public class CardWedgeFull {

    private static final Pattern CF = Pattern.compile(
            "[A-Z]{6}[0-9LMNPQRSTUV]{2}[ABCDEHLMPRST][0-9LMNPQRSTUV]{2}[A-Z][0-9LMNPQRSTUV]{3}[A-Z]");

    private static final byte[] AID_EMRTD = {(byte) 0xA0, 0x00, 0x00, 0x02, 0x47, 0x10, 0x01};

    enum Terminatore { ENTER, TAB, NONE }
    enum Tipo { TS_CNS, CIE, SCONOSCIUTA }

    public static void main(String[] args) {
        Security.addProvider(new BouncyCastleProvider());

        Terminatore term = Terminatore.ENTER;
        int readerIndex = 0;
        if (args.length > 0) {
            switch (args[0].toLowerCase()) {
                case "tab":  term = Terminatore.TAB;  break;
                case "none": term = Terminatore.NONE; break;
                default:     term = Terminatore.ENTER;
            }
        }
        if (args.length > 1) {
            try { readerIndex = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
        }

        Robot robot;
        try {
            robot = new Robot();
            robot.setAutoDelay(8);
        } catch (Exception e) {
            System.out.println("Tastiera virtuale non inizializzabile: " + e.getMessage());
            return;
        }

        CardTerminal terminal;
        try {
            List<CardTerminal> terminals = TerminalFactory.getDefault().terminals().list();
            if (terminals.isEmpty()) { System.out.println("Nessun lettore PC/SC trovato."); return; }
            if (readerIndex < 0 || readerIndex >= terminals.size()) {
                System.out.printf("Indice lettore %d non valido (lettori: %d).%n", readerIndex, terminals.size());
                return;
            }
            terminal = terminals.get(readerIndex);
        } catch (CardException e) {
            System.out.println("Errore elenco lettori: " + e.getMessage());
            return;
        }

        System.out.println("Wedge CF attivo (TS-CNS automatico + CIE con CAN).");
        System.out.println("Lettore     : " + terminal.getName());
        System.out.println("Terminatore : " + term);
        System.out.println("Inserisci la carta. Ctrl+C per uscire.");
        System.out.println("------------------------------------------------------------");

        while (true) {
            try {
                terminal.waitForCardPresent(0);

                Tipo tipo = rilevaTipo(terminal);
                String cf = null;

                if (tipo == Tipo.TS_CNS) {
                    cf = leggiCfCns(terminal);
                    System.out.println(cf != null ? "TS-CNS OK -> " + cf
                            : "TS-CNS rilevata ma CF non estratto.");
                } else if (tipo == Tipo.CIE) {
                    String can = chiediCan();
                    if (can != null && !can.isBlank()) {
                        cf = leggiCfCie(terminal, can.trim());
                        System.out.println(cf != null ? "CIE OK -> " + cf
                                : "CIE: lettura fallita (CAN errato o DG11 non leggibile).");
                    } else {
                        System.out.println("CIE: inserimento CAN annullato.");
                    }
                } else {
                    System.out.println("Carta non riconosciuta (ne' TS-CNS ne' CIE).");
                }

                if (cf != null) digita(robot, cf, term);

                terminal.waitForCardAbsent(0);
            } catch (CardException e) {
                System.out.println("Avviso: " + e.getMessage() + " - riprovo...");
                sleep(500);
            }
        }
    }

    // =================== Rilevamento tipo carta ===================
    private static Tipo rilevaTipo(CardTerminal terminal) {
        Card card = null;
        try {
            card = terminal.connect("*");
            CardChannel ch = card.getBasicChannel();
            if (select(ch, (byte) 0x3F, (byte) 0x00)
                    && select(ch, (byte) 0x11, (byte) 0x00)
                    && select(ch, (byte) 0x11, (byte) 0x02)) {
                return Tipo.TS_CNS;
            }
            if (selectAid(ch, AID_EMRTD)) return Tipo.CIE;
            return Tipo.SCONOSCIUTA;
        } catch (CardException e) {
            return Tipo.SCONOSCIUTA;
        } finally {
            if (card != null) try { card.disconnect(false); } catch (CardException ignored) {}
        }
    }

    // =================== TS-CNS: lettura libera ===================
    private static String leggiCfCns(CardTerminal terminal) {
        Card card = null;
        try {
            card = terminal.connect("*");
            CardChannel ch = card.getBasicChannel();
            if (!(select(ch, (byte) 0x3F, (byte) 0x00)
                    && select(ch, (byte) 0x11, (byte) 0x00)
                    && select(ch, (byte) 0x11, (byte) 0x02))) return null;
            byte[] data = readBinary(ch);
            return matchCf(new String(data, StandardCharsets.ISO_8859_1));
        } catch (CardException e) {
            return null;
        } finally {
            if (card != null) try { card.disconnect(false); } catch (CardException ignored) {}
        }
    }

    // =================== CIE: PACE con CAN + lettura DG11 ===================
    private static String leggiCfCie(CardTerminal terminal, String can) {
        PassportService ps = null;
        try {
            CardService cs = new TerminalCardService(terminal);
            // VERIFICA firma del costruttore secondo la versione di jMRTD.
            ps = new PassportService(
                    cs,
                    PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
                    PassportService.DEFAULT_MAX_BLOCKSIZE,
                    false,   // isSFIEnabled
                    false);  // shouldCheckMAC
            ps.open();
            ps.sendSelectApplet(false);

            // 1) Leggo EF.CardAccess per ottenere i parametri PACE.
            CardAccessFile cardAccess =
                    new CardAccessFile(ps.getInputStream(PassportService.EF_CARD_ACCESS));
            Collection<SecurityInfo> infos = cardAccess.getSecurityInfos();

            boolean paceOk = false;
            for (SecurityInfo si : infos) {
                if (si instanceof PACEInfo) {
                    PACEInfo pace = (PACEInfo) si;
                    BigInteger paramId = pace.getParameterId();
                    AlgorithmParameterSpec params = PACEInfo.toParameterSpec(paramId);
                    PACEKeySpec key = PACEKeySpec.createCANKey(can);
                    // VERIFICA firma doPACE: in alcune versioni manca l'ultimo argomento.
                    ps.doPACE(key, pace.getObjectIdentifier(), params, paramId);
                    paceOk = true;
                    break;
                }
            }
            if (!paceOk) return null;

            // Dopo PACE riseleziono l'applet in modalita' canale sicuro.
            ps.sendSelectApplet(true);

            // 2) Leggo DG11 (dati personali aggiuntivi) ed estraggo il CF.
            byte[] dg11bytes = readAll(ps.getInputStream(PassportService.EF_DG11));
            String cf = null;

            try {
                DG11File dg11 = new DG11File(new ByteArrayInputStream(dg11bytes));
                cf = matchCf(safe(dg11.getPersonalNumber()));
            } catch (Exception ignored) { /* fallback sotto */ }

            // Fallback robusto: scansiono i byte grezzi del DG11 cercando il pattern del CF.
            if (cf == null) cf = matchCf(new String(dg11bytes, StandardCharsets.ISO_8859_1));

            return cf;
        } catch (Exception e) {
            System.out.println("  Dettaglio CIE: " + e.getMessage());
            return null;
        } finally {
            if (ps != null) try { ps.close(); } catch (Exception ignored) {}
        }
    }

    // =================== APDU di base (TS-CNS) ===================
    private static boolean select(CardChannel ch, byte hi, byte lo) throws CardException {
        byte[] apdu = {0x00, (byte) 0xA4, 0x00, 0x0C, 0x02, hi, lo};
        return ch.transmit(new CommandAPDU(apdu)).getSW() == 0x9000;
    }

    private static boolean selectAid(CardChannel ch, byte[] aid) throws CardException {
        byte[] apdu = new byte[5 + aid.length];
        apdu[0] = 0x00; apdu[1] = (byte) 0xA4; apdu[2] = 0x04; apdu[3] = 0x0C;
        apdu[4] = (byte) aid.length;
        System.arraycopy(aid, 0, apdu, 5, aid.length);
        return ch.transmit(new CommandAPDU(apdu)).getSW() == 0x9000;
    }

    private static byte[] readBinary(CardChannel ch) throws CardException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int offset = 0; final int chunk = 0xFF;
        while (offset < 0x7FFF) {
            byte[] apdu = {0x00, (byte) 0xB0,
                    (byte) ((offset >> 8) & 0xFF), (byte) (offset & 0xFF), (byte) chunk};
            ResponseAPDU r = ch.transmit(new CommandAPDU(apdu));
            int sw = r.getSW(); byte[] body = r.getData();
            if (body.length > 0) { out.write(body, 0, body.length); offset += body.length; }
            if (sw == 0x6B00 || sw == 0x6282) break;
            if (sw != 0x9000) break;
            if (body.length < chunk) break;
        }
        return out.toByteArray();
    }

    // =================== Utilita' ===================
    private static String matchCf(String s) {
        if (s == null) return null;
        Matcher m = CF.matcher(s.toUpperCase());
        return m.find() ? m.group() : null;
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[512];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static String chiediCan() {
        final String[] result = new String[1];
        try {
            JFrame parent = new JFrame();
            parent.setAlwaysOnTop(true);
            parent.setUndecorated(true);
            parent.setLocationRelativeTo(null);
            parent.setVisible(true);
            JPasswordField field = new JPasswordField();
            int ok = JOptionPane.showConfirmDialog(parent, field,
                    "CIE rilevata - inserisci il CAN (6 cifre sulla carta)",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (ok == JOptionPane.OK_OPTION) result[0] = new String(field.getPassword());
            parent.dispose();
        } catch (Exception e) {
            // ambiente senza GUI: niente CAN
        }
        return result[0];
    }

    // =================== Digitazione come tastiera ===================
    private static void digita(Robot robot, String cf, Terminatore term) {
        sleep(120);
        for (char c : cf.toCharArray()) typeChar(robot, c);
        switch (term) {
            case ENTER: tap(robot, KeyEvent.VK_ENTER); break;
            case TAB:   tap(robot, KeyEvent.VK_TAB);   break;
            default: break;
        }
    }

    private static void typeChar(Robot robot, char c) {
        if (c >= 'A' && c <= 'Z') {
            int vk = KeyEvent.VK_A + (c - 'A');
            robot.keyPress(KeyEvent.VK_SHIFT);
            robot.keyPress(vk); robot.keyRelease(vk);
            robot.keyRelease(KeyEvent.VK_SHIFT);
        } else if (c >= '0' && c <= '9') {
            int vk = KeyEvent.VK_0 + (c - '0');
            robot.keyPress(vk); robot.keyRelease(vk);
        }
    }

    private static void tap(Robot robot, int vk) { robot.keyPress(vk); robot.keyRelease(vk); }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }
}
