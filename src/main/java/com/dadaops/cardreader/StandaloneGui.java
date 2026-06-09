package com.dadaops.cardreader;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.time.Year;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.AbstractCellEditor;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;

/**
 * Modalità STANDALONE (GUI Swing con FlatLaf): rileva la carta, guida l'utente per i documenti
 * elettronici, legge i campi e li mostra in tabella con copia per riga e salvataggio foto.
 * Avvio: {@code java -jar cie-cns-wedge.jar gui}.
 */
final class StandaloneGui {
    private StandaloneGui() {}

    private static final Color BG = new Color(0xF4F5F7), CARD = Color.WHITE, BORDER = new Color(0xE4E7EC);
    private static final Color MUTED = new Color(0x667085);

    private static volatile boolean busy = false;
    private static volatile boolean polling = false;
    private static volatile boolean dialogoAperto = false;   // un popup modale è aperto: niente auto-letture
    private static String ultimaPresenza = null;
    private static final String[] ultimoJson = {""};
    private static final List<String[]> righeRaw = new ArrayList<>();
    private static volatile BufferedImage fotoCorrente = null;

    private static final Map<String, String> ETICHETTE = new LinkedHashMap<>();
    static {
        ETICHETTE.put("tipo", "Tipo documento");
        ETICHETTE.put("cognome", "Cognome");
        ETICHETTE.put("nome", "Nome");
        ETICHETTE.put("nomeCompleto", "Nome completo");
        ETICHETTE.put("codiceFiscale", "Codice fiscale");
        ETICHETTE.put("codiceFiscaleCalcolato", "Codice fiscale (calcolato)");
        ETICHETTE.put("codiceFiscaleCertified", "CF certificato");
        ETICHETTE.put("codiceFiscaleDaVerificare", "CF da verificare");
        ETICHETTE.put("codiceFiscaleNota", "Nota codice fiscale");
        ETICHETTE.put("sesso", "Sesso");
        ETICHETTE.put("dataNascita", "Data di nascita");
        ETICHETTE.put("luogoNascita", "Luogo di nascita");
        ETICHETTE.put("comuneNascita", "Comune di nascita");
        ETICHETTE.put("provinciaNascita", "Provincia di nascita");
        ETICHETTE.put("regione", "Regione");
        ETICHETTE.put("statoNascita", "Stato di nascita");
        ETICHETTE.put("natoEstero", "Nato all'estero");
        ETICHETTE.put("codiceCatastale", "Codice catastale");
        ETICHETTE.put("cittadinanza", "Cittadinanza");
        ETICHETTE.put("paese", "Paese emittente");
        ETICHETTE.put("unioneEuropea", "Unione Europea");
        ETICHETTE.put("categoriaDocumento", "Categoria documento");
        ETICHETTE.put("numeroDocumento", "Numero documento");
        ETICHETTE.put("statoEmissione", "Stato emissione (ISO3)");
        ETICHETTE.put("tipoDocumento", "Tipo documento (MRZ)");
        ETICHETTE.put("dataScadenza", "Data di scadenza");
        ETICHETTE.put("dataEmissione", "Data di emissione");
        ETICHETTE.put("autoritaEmittente", "Autorità emittente");
        ETICHETTE.put("indirizzo", "Indirizzo");
        ETICHETTE.put("numeroPersonale", "Numero personale");
        ETICHETTE.put("numeroNazionale", "Numero nazionale");
        ETICHETTE.put("riassuntoPersonale", "Descrizione personale");
        ETICHETTE.put("custodia", "Custodia (minori)");
        ETICHETTE.put("altriDocumenti", "Altri documenti");
        ETICHETTE.put("dg13Presente", "Dati nazionali (DG13)");
        ETICHETTE.put("dg13Testo", "DG13 — testo estratto");
        ETICHETTE.put("notaChip", "Nota sul chip");
        ETICHETTE.put("chiaveAnagrafica", "Chiave anagrafica");
        ETICHETTE.put("tokenCarta", "Token carta");
        ETICHETTE.put("tokenCartaFonte", "Token carta (fonte)");
        ETICHETTE.put("uidCarta", "UID carta");
        ETICHETTE.put("uidCasuale", "UID casuale");
        ETICHETTE.put("numeroCarta", "Numero carta");
        ETICHETTE.put("scadenza", "Scadenza");
        ETICHETTE.put("circuito", "Circuito");
        ETICHETTE.put("titolare", "Titolare");
        ETICHETTE.put("idDocumento", "ID documento");
        ETICHETTE.put("idChip", "ID chip");
        ETICHETTE.put("dataGroupPresenti", "Data group presenti");
        ETICHETTE.put("letturaTimestamp", "Letto il");
        ETICHETTE.put("checksum", "Checksum");
        ETICHETTE.put("avvisoChiave", "Avviso");
        ETICHETTE.put("errore", "Errore");
        ETICHETTE.put("messaggio", "Messaggio");
        ETICHETTE.put("suggerimento", "Suggerimento");
    }

    static void launch() {
        try {
            System.setProperty("apple.laf.useScreenMenuBar", "true");
            System.setProperty("apple.awt.application.name", "Lettore documenti");
            laf();
        } catch (Throwable ignored) {}
        SwingUtilities.invokeLater(StandaloneGui::build);
    }

    private static void laf() {
        try { javax.swing.UIManager.setLookAndFeel(new com.formdev.flatlaf.themes.FlatMacLightLaf()); return; } catch (Throwable ignored) {}
        try { javax.swing.UIManager.setLookAndFeel(new com.formdev.flatlaf.FlatLightLaf()); return; } catch (Throwable ignored) {}
        try { javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
    }

    private static void build() {
        JFrame f = new JFrame("Lettore documenti");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.setSize(1000, 720);
        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBackground(BG);
        root.setBorder(new EmptyBorder(18, 22, 18, 22));
        f.setContentPane(root);

        JLabel titolo = new JLabel("Lettore documenti d'identità");
        titolo.setFont(titolo.getFont().deriveFont(Font.BOLD, 22f));
        JLabel pill = new JLabel("In attesa…", SwingConstants.CENTER);
        pill.setOpaque(true);
        pill.setBorder(new EmptyBorder(6, 16, 6, 16));
        stile(pill, "arc: 999");
        setPill(pill, "In attesa…", new Color(0xEEF1F6), new Color(0x475467));
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(titolo, BorderLayout.WEST);
        header.add(pill, BorderLayout.EAST);

        JTextField can = new JTextField(8), pdoc = new JTextField(11);
        int oggi = Year.now().getValue();
        DatePicker nascita = new DatePicker(1915, oggi);
        DatePicker scadenza = new DatePicker(oggi - 5, oggi + 15);
        JCheckBox foto = new JCheckBox("includi foto", true);
        JCheckBox auto = new JCheckBox("auto-leggi / guida all'inserimento", true);
        JButton leggi = new JButton("Leggi adesso");
        leggi.setFont(leggi.getFont().deriveFont(Font.BOLD));
        stile(leggi, "background: #2D7DF6; foreground: #ffffff; borderWidth: 0; focusWidth: 0; innerFocusWidth: 0; arc: 12");

        JPanel r1 = riga();
        r1.add(etic("CIE / carta — CAN")); r1.add(can); r1.add(foto);
        r1.add(Box.createHorizontalStrut(16)); r1.add(leggi); r1.add(auto);
        JPanel r2 = riga();
        r2.add(etic("Passaporto — N. documento")); r2.add(pdoc);
        r2.add(Box.createHorizontalStrut(10)); r2.add(etic("Data nascita")); r2.add(nascita);
        JPanel r3 = riga();
        r3.add(etic("Data scadenza")); r3.add(scadenza);
        JPanel inputBox = new JPanel();
        inputBox.setOpaque(false);
        inputBox.setLayout(new BoxLayout(inputBox, BoxLayout.Y_AXIS));
        for (JPanel p : new JPanel[]{r1, r2, r3}) { p.setAlignmentX(Component.LEFT_ALIGNMENT); inputBox.add(p); }

        JPanel north = new JPanel(new BorderLayout(0, 14));
        north.setOpaque(false);
        north.add(header, BorderLayout.NORTH);
        north.add(card(inputBox), BorderLayout.CENTER);

        DefaultTableModel model = new DefaultTableModel(new Object[]{"Campo", "Valore", ""}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return c == 2; }
        };
        JTable table = new JTable(model);
        table.setRowHeight(30);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        JTableHeader th = table.getTableHeader();
        th.setFont(th.getFont().deriveFont(Font.BOLD));
        table.getColumnModel().getColumn(0).setPreferredWidth(280);
        table.getColumnModel().getColumn(0).setMinWidth(240);
        table.getColumnModel().getColumn(1).setPreferredWidth(380);
        table.getColumnModel().getColumn(2).setMaxWidth(88);
        table.getColumnModel().getColumn(2).setMinWidth(80);
        DefaultTableCellRenderer rCampo = striping(); rCampo.setFont(rCampo.getFont().deriveFont(Font.BOLD));
        table.getColumnModel().getColumn(0).setCellRenderer(rCampo);
        table.getColumnModel().getColumn(1).setCellRenderer(striping());
        CopyButtonColumn copyCol = new CopyButtonColumn();
        table.getColumnModel().getColumn(2).setCellRenderer(copyCol);
        table.getColumnModel().getColumn(2).setCellEditor(copyCol);

        JLabel photo = new JLabel("nessuna foto", SwingConstants.CENTER);
        photo.setForeground(MUTED);
        photo.setPreferredSize(new Dimension(240, 340));
        JButton salvaFoto = new JButton("Salva foto…");
        salvaFoto.setEnabled(false);
        stile(salvaFoto, "arc: 10");
        JLabel capFoto = new JLabel("Fototessera");
        capFoto.setFont(capFoto.getFont().deriveFont(Font.BOLD));
        capFoto.setForeground(MUTED);
        JPanel fotoInner = new JPanel(new BorderLayout(0, 8));
        fotoInner.setOpaque(false);
        fotoInner.add(capFoto, BorderLayout.NORTH);
        fotoInner.add(photo, BorderLayout.CENTER);
        fotoInner.add(salvaFoto, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, card(new JScrollPane(table)), card(fotoInner));
        split.setOpaque(false); split.setBorder(null);
        split.setResizeWeight(0.74);
        split.setDividerSize(14);

        JButton copiaTsv = button("Copia tabella");
        JButton copiaJson = button("Copia JSON");
        JButton copiaToken = button("Copia token carta");
        JButton wizard = button("Identifica (2 documenti)");
        JLabel stato = new JLabel(" ");
        stato.setForeground(MUTED);
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        bottom.setOpaque(false);
        bottom.add(copiaTsv); bottom.add(copiaJson); bottom.add(copiaToken); bottom.add(wizard);
        bottom.add(Box.createHorizontalStrut(16)); bottom.add(stato);

        root.add(north, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);

        Runnable read = () -> {
            if (busy) return;
            busy = true;
            leggi.setEnabled(false); stato.setText("Lettura in corso…");
            new Thread(() -> {
                String resp = CardReaderApi.check(t(can), null, foto.isSelected(), t(pdoc), nascita.iso(), scadenza.iso(), false);
                Map<String, String> campi = Json.parseFlatJsonOrdered(resp);
                BufferedImage img = decodeFoto(campi.get("fotoBase64"));
                ImageIcon icona = img != null ? scala(img, 230, 320) : null;
                boolean jp2 = img == null && campi.get("fotoBase64") != null;
                SwingUtilities.invokeLater(() -> {
                    applica(campi, icona, jp2, model, photo);
                    fotoCorrente = img; salvaFoto.setEnabled(img != null);
                    ultimoJson[0] = resp;
                    stato.setText(campi.containsKey("errore") ? ("Esito: " + campi.get("errore")) : "Letto.");
                    leggi.setEnabled(true); busy = false;
                });
            }, "read").start();
        };
        leggi.addActionListener(e -> read.run());
        copiaJson.addActionListener(e -> { copia(ultimoJson[0]); stato.setText("JSON copiato."); });
        copiaTsv.addActionListener(e -> { copia(tsv()); stato.setText("Tabella copiata (TSV)."); });
        copiaToken.addActionListener(e -> { String tk = raw("tokenCarta");
            copia(tk == null ? "" : tk); stato.setText(tk == null ? "Nessun token carta." : "Token carta copiato."); });
        salvaFoto.addActionListener(e -> salvaFoto(f, stato));
        wizard.addActionListener(e -> apriWizard(f, model, photo, salvaFoto, stato));

        Timer timer = new Timer(1200, null);
        timer.addActionListener(ev -> {
            if (busy || polling || dialogoAperto) return;
            polling = true;
            new Thread(() -> {
                String st = CardReaderApi.status(null);
                Map<String, String> m = Json.parseFlatJsonOrdered(st);
                boolean present = "true".equals(m.get("cardPresent"));
                String tipo = m.get("tipo");
                SwingUtilities.invokeLater(() -> {
                    polling = false;
                    if (present) {
                        setPill(pill, (tipo != null ? tipo : "carta"), new Color(0xDCFCE7), new Color(0x166534));
                    } else {
                        setPill(pill, "In attesa di un documento…", new Color(0xEEF1F6), new Color(0x475467));
                    }
                    boolean fronte = present && (ultimaPresenza == null);
                    ultimaPresenza = present ? (tipo != null ? tipo : "?") : null;
                    if (fronte && auto.isSelected() && !busy) {
                        if ("Documento elettronico".equals(tipo)) apriGuida(f, can, pdoc, nascita, scadenza, read);
                        else read.run();   // TS-CNS / EMV / NFC: lettura diretta
                    }
                });
            }, "poll").start();
        });
        timer.start();

        f.setLocationRelativeTo(null);
        f.setVisible(true);
    }

    /** Popup guidato all'inserimento di un documento elettronico: scelta CIE/Passaporto e dati. */
    private static void apriGuida(JFrame parent, JTextField mainCan, JTextField mainDoc,
                                  DatePicker mainNascita, DatePicker mainScadenza, Runnable read) {
        JDialog dlg = new JDialog(parent, "Documento elettronico rilevato", true);
        CardLayout cl = new CardLayout();
        JPanel cards = new JPanel(cl);
        cards.setBorder(new EmptyBorder(18, 20, 18, 20));

        // --- scelta ---
        JButton bCie = new JButton("CIE / Carta d'identità");
        JButton bPass = new JButton("Passaporto");
        stile(bCie, "arc: 12"); stile(bPass, "arc: 12");
        bCie.setPreferredSize(new Dimension(220, 44)); bPass.setPreferredSize(new Dimension(220, 44));
        JPanel scelta = colonna(
                titoloDlg("È una CIE o un passaporto?"),
                etic("Avvicinato un documento elettronico. Scegli il tipo per i dati corretti."),
                Box.createVerticalStrut(8), bCie, Box.createVerticalStrut(6), bPass);

        // --- CIE ---
        JTextField canF = new JTextField(8);
        JButton cieLeggi = primario("Leggi");
        JButton cieBack = new JButton("Indietro");
        JPanel cieP = colonna(
                titoloDlg("Carta d'identità elettronica (CIE)"),
                etic("Indica il CAN: le 6 cifre stampate sul documento."),
                affianca(new JLabel("CAN:"), canF),
                Box.createVerticalStrut(10), affianca(cieBack, cieLeggi));

        // --- Passaporto (in ordine: numero, nascita, scadenza) ---
        JTextField docF = new JTextField(11);
        DatePicker dpN = new DatePicker(1915, Year.now().getValue());
        DatePicker dpS = new DatePicker(Year.now().getValue() - 5, Year.now().getValue() + 15);
        JButton passLeggi = primario("Leggi");
        JButton passBack = new JButton("Indietro");
        JPanel passP = colonna(
                titoloDlg("Passaporto"),
                etic("Inserisci nell'ordine i dati della pagina anagrafica (MRZ):"),
                affianca(passo("1"), new JLabel("Numero documento:"), docF),
                affianca(passo("2"), new JLabel("Data di nascita:"), dpN),
                affianca(passo("3"), new JLabel("Data di scadenza:"), dpS),
                Box.createVerticalStrut(10), affianca(passBack, passLeggi));

        cards.add(scelta, "scelta"); cards.add(cieP, "cie"); cards.add(passP, "pass");

        bCie.addActionListener(e -> cl.show(cards, "cie"));
        bPass.addActionListener(e -> cl.show(cards, "pass"));
        cieBack.addActionListener(e -> cl.show(cards, "scelta"));
        passBack.addActionListener(e -> cl.show(cards, "scelta"));
        cieLeggi.addActionListener(e -> {
            mainCan.setText(canF.getText().trim());
            mainDoc.setText("");                     // niente MRZ: percorso CIE (CAN)
            dlg.dispose(); read.run();
        });
        passLeggi.addActionListener(e -> {
            mainDoc.setText(docF.getText().trim());
            mainNascita.set(dpN.iso()); mainScadenza.set(dpS.iso());
            mainCan.setText("");                      // niente CAN: percorso passaporto (MRZ)
            dlg.dispose(); read.run();
        });

        dlg.setContentPane(cards);
        dlg.pack();
        dlg.setMinimumSize(new Dimension(420, dlg.getHeight()));
        dlg.setLocationRelativeTo(parent);
        dialogoAperto = true;
        try { dlg.setVisible(true); } finally { dialogoAperto = false; }
    }

    /** Mostra una risposta JSON arbitraria (anche l'identità unita) in tabella + foto. */
    private static void displayResponse(String resp, DefaultTableModel model, JLabel photo, JButton salvaFoto, JLabel stato) {
        // separa l'eventuale sotto-oggetto secondario "tesseraSanitaria" dai campi del documento principale
        String[] sp = Json.estraiOggetto(resp, "tesseraSanitaria");
        Map<String, String> campi = Json.parseFlatJsonOrdered(sp[0]);
        Map<String, String> ts = sp[1] != null ? Json.parseFlatJsonOrdered(sp[1]) : null;
        BufferedImage img = decodeFoto(campi.get("fotoBase64"));
        ImageIcon icona = img != null ? scala(img, 230, 320) : null;
        boolean jp2 = img == null && campi.get("fotoBase64") != null;
        applica(campi, icona, jp2, model, photo);
        appendGruppoTs(model, ts);
        fotoCorrente = img; salvaFoto.setEnabled(img != null);
        ultimoJson[0] = resp;
        if (stato != null) {
            boolean match = "true".equals(campi.get("corrispondenza"));
            stato.setText(campi.containsKey("corrispondenza")
                    ? ("Identità unita — corrispondenza: " + (match ? "✓ stessa persona" : "✗ DIVERSA"))
                    : (campi.containsKey("errore") ? "Esito: " + campi.get("errore") : "Letto."));
        }
    }

    /** Aggiunge in tabella i campi della Tessera Sanitaria (secondaria) sotto un separatore. */
    private static void appendGruppoTs(DefaultTableModel model, Map<String, String> ts) {
        if (ts == null || ts.isEmpty()) return;
        righeRaw.add(new String[]{"__sep_ts__", "— Tessera Sanitaria (secondaria) —", "", ""});
        model.addRow(new Object[]{"— Tessera Sanitaria (secondaria) —", "", ""});
        for (Map.Entry<String, String> e : ts.entrySet()) {
            String k = e.getKey();
            if (k.endsWith("Base64") && (k.startsWith("foto") || k.startsWith("firma"))) continue;
            String etich = etichetta(k);
            String vis = formattaValore(e.getValue());
            righeRaw.add(new String[]{k, etich, vis, e.getValue()});
            model.addRow(new Object[]{etich, vis, "Copia"});
        }
    }

    /**
     * Wizard di identificazione a 2 documenti. Si legge PRIMA la Tessera Sanitaria: da lì si ricava
     * già la data di nascita (e il CF certificato + luogo di nascita), così per il passaporto basta
     * chiedere numero documento e scadenza. Poi si unisce e si verifica la corrispondenza.
     */
    private static void apriWizard(JFrame parent, DefaultTableModel model, JLabel photo, JButton salvaFoto, JLabel stato) {
        JDialog dlg = new JDialog(parent, "Identificazione — 2 documenti", true);
        final String[] jTs = {null}, jDoc = {null}, nascitaTs = {null};

        JButton leggiTs = primario("Leggi Tessera Sanitaria");
        JLabel s1 = etic("non letta");
        JTextField wCan = new JTextField(8), wDoc = new JTextField(11);
        DatePicker wS = new DatePicker(Year.now().getValue() - 5, Year.now().getValue() + 15);
        JLabel nascitaLbl = etic("—");
        JButton leggiDoc = primario("Leggi documento");
        leggiDoc.setEnabled(false);                 // sbloccato solo dopo la TS (ci serve la nascita)
        JLabel s2 = etic("prima leggi la Tessera Sanitaria");
        JButton unisci = primario("Unisci e mostra");
        unisci.setEnabled(false);

        Runnable verificaPronto = () -> unisci.setEnabled(ok(jTs[0]) && ok(jDoc[0]));

        leggiTs.addActionListener(e -> {
            s1.setText("lettura…"); leggiTs.setEnabled(false);
            new Thread(() -> {
                String r = CardReaderApi.check(null, null, false, null, null, null, false);
                SwingUtilities.invokeLater(() -> {
                    jTs[0] = r;
                    Map<String, String> m = Json.parseFlatJsonOrdered(r);
                    nascitaTs[0] = m.get("dataNascita");
                    s1.setText(riassunto("TS", r));
                    leggiTs.setEnabled(true);
                    boolean tsOk = ok(r);
                    nascitaLbl.setText(nascitaTs[0] != null ? "dalla TS: " + formattaValore(nascitaTs[0])
                            : (tsOk ? "non presente nella TS" : "—"));
                    leggiDoc.setEnabled(tsOk);
                    s2.setText(tsOk ? "non letto" : "TS non letta: riprova");
                    verificaPronto.run();
                });
            }, "wiz-ts").start();
        });
        leggiDoc.addActionListener(e -> {
            s2.setText("lettura…"); leggiDoc.setEnabled(false);
            new Thread(() -> {
                String r = CardReaderApi.check(t(wCan), null, true, t(wDoc), nascitaTs[0], wS.iso(), false);
                SwingUtilities.invokeLater(() -> { jDoc[0] = r; s2.setText(riassunto("doc", r)); leggiDoc.setEnabled(true); verificaPronto.run(); });
            }, "wiz-doc").start();
        });
        unisci.addActionListener(e -> {
            String merged = CardReaderApi.merge(jTs[0], jDoc[0]);
            displayResponse(merged, model, photo, salvaFoto, stato);
            dlg.dispose();
        });

        JPanel slot1 = colonna(titoloDlg("1) Tessera Sanitaria"),
                etic("Appoggia la TS-CNS: prendo codice fiscale certificato, luogo e data di nascita."),
                affianca(leggiTs, s1));
        JPanel slot2 = colonna(titoloDlg("2) Documento con foto (CIE / Passaporto)"),
                etic("CIE → CAN. Passaporto → numero documento e scadenza (la nascita arriva dalla TS)."),
                affianca(new JLabel("Data di nascita:"), nascitaLbl),
                affianca(new JLabel("CAN (solo CIE):"), wCan),
                affianca(new JLabel("N. documento:"), wDoc, new JLabel("  Scadenza:"), wS),
                affianca(leggiDoc, s2));
        JPanel content = colonna(slot1, Box.createVerticalStrut(8), slot2, Box.createVerticalStrut(12), affianca(unisci));
        content.setBorder(new EmptyBorder(18, 20, 18, 20));
        dlg.setContentPane(content);
        dlg.pack();
        dlg.setMinimumSize(new Dimension(560, dlg.getHeight()));
        dlg.setLocationRelativeTo(parent);
        dialogoAperto = true;
        try { dlg.setVisible(true); } finally { dialogoAperto = false; }
    }

    private static boolean ok(String json) {
        return json != null && !Json.parseFlatJsonOrdered(json).containsKey("errore");
    }

    private static String riassunto(String pref, String json) {
        Map<String, String> m = Json.parseFlatJsonOrdered(json);
        if (m.containsKey("errore")) return "errore: " + m.get("errore");
        String n = (m.getOrDefault("cognome", "") + " " + m.getOrDefault("nome", "")).trim();
        return "letto: " + (n.isEmpty() ? m.getOrDefault("tipo", "?") : n);
    }

    private static void applica(Map<String, String> campi, ImageIcon icona, boolean jp2,
                                DefaultTableModel model, JLabel photo) {
        righeRaw.clear();
        model.setRowCount(0);
        for (Map.Entry<String, String> e : campi.entrySet()) {
            String k = e.getKey();
            if (k.endsWith("Base64") && (k.startsWith("foto") || k.startsWith("firma"))) continue;
            String etich = etichetta(k);
            String vis = formattaValore(e.getValue());
            righeRaw.add(new String[]{k, etich, vis, e.getValue()});
            model.addRow(new Object[]{etich, vis, "Copia"});
        }
        photo.setIcon(icona);
        photo.setText(icona != null ? "" : (jp2 ? "<html><center>foto in formato originale<br>(JPEG 2000)</center></html>" : "nessuna foto"));
    }

    // ===== helpers UI =====
    private static JPanel riga() { JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6)); p.setOpaque(false); return p; }

    private static JPanel colonna(Component... comps) {
        JPanel p = new JPanel(); p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        for (Component c : comps) { if (c instanceof JComponent) ((JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT); p.add(c); }
        return p;
    }

    private static JPanel affianca(Component... comps) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6)); p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (Component c : comps) p.add(c);
        return p;
    }

    private static JLabel titoloDlg(String s) { JLabel l = new JLabel(s); l.setFont(l.getFont().deriveFont(Font.BOLD, 15f)); return l; }
    private static JLabel passo(String n) {
        JLabel l = new JLabel(n, SwingConstants.CENTER); l.setOpaque(true);
        l.setBorder(new EmptyBorder(2, 8, 2, 8)); stile(l, "arc: 999");
        l.setBackground(new Color(0xE7EEFB)); l.setForeground(new Color(0x2D7DF6));
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }
    private static JButton primario(String s) {
        JButton b = new JButton(s); b.setFont(b.getFont().deriveFont(Font.BOLD));
        stile(b, "background: #2D7DF6; foreground: #ffffff; borderWidth: 0; focusWidth: 0; arc: 12");
        return b;
    }

    private static JLabel etic(String s) { JLabel l = new JLabel(s); l.setForeground(MUTED); return l; }
    private static JButton button(String s) { JButton b = new JButton(s); stile(b, "arc: 10"); return b; }

    private static JPanel card(JComponent inner) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(CARD);
        p.setBorder(new com.formdev.flatlaf.ui.FlatLineBorder(new Insets(14, 16, 14, 16), BORDER, 1f, 16));
        p.add(inner, BorderLayout.CENTER);
        return p;
    }

    private static void stile(JComponent c, String style) { try { c.putClientProperty("FlatLaf.style", style); } catch (Throwable ignored) {} }
    private static void setPill(JLabel pill, String text, Color bg, Color fg) { pill.setText(text); pill.setBackground(bg); pill.setForeground(fg); }

    private static String etichetta(String key) {
        String e = ETICHETTE.get(key);
        if (e != null) return e;
        String s = key.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2");
        return s.isEmpty() ? key : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Date ISO (AAAA-MM-GG) -> italiano GG/MM/AAAA; il resto invariato. */
    private static String formattaValore(String v) {
        if (v != null && v.matches("\\d{4}-\\d{2}-\\d{2}"))
            return v.substring(8, 10) + "/" + v.substring(5, 7) + "/" + v.substring(0, 4);
        return v;
    }

    private static DefaultTableCellRenderer striping() {
        return new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, v, sel, foc, row, col);
                if (!sel) c.setBackground(row % 2 == 0 ? Color.WHITE : new Color(0xF6F8FB));
                setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
                return c;
            }
        };
    }

    private static BufferedImage decodeFoto(String fb) {
        if (fb == null || !fb.startsWith("data:image/png")) return null;
        try { return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(fb.substring(fb.indexOf(",") + 1)))); }
        catch (Exception e) { return null; }
    }

    private static ImageIcon scala(BufferedImage img, int w, int h) {
        double r = Math.min((double) w / img.getWidth(), (double) h / img.getHeight());
        return new ImageIcon(img.getScaledInstance((int) (img.getWidth() * r), (int) (img.getHeight() * r), Image.SCALE_SMOOTH));
    }

    private static void salvaFoto(JFrame f, JLabel stato) {
        BufferedImage img = fotoCorrente;
        if (img == null) { JOptionPane.showMessageDialog(f, "Nessuna foto da salvare."); return; }
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("foto.png"));
        fc.setFileFilter(new FileNameExtensionFilter("Immagini PNG/JPG", "png", "jpg", "jpeg"));
        if (fc.showSaveDialog(f) != JFileChooser.APPROVE_OPTION) return;
        File file = fc.getSelectedFile();
        String name = file.getName().toLowerCase();
        String fmt = (name.endsWith(".jpg") || name.endsWith(".jpeg")) ? "jpg" : "png";
        if (!name.endsWith(".png") && !name.endsWith(".jpg") && !name.endsWith(".jpeg"))
            file = new File(file.getParentFile(), file.getName() + ".png");
        try {
            BufferedImage out = img;
            if (fmt.equals("jpg") && img.getTransparency() != BufferedImage.OPAQUE) {
                out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
                out.getGraphics().drawImage(img, 0, 0, Color.WHITE, null);
            }
            ImageIO.write(out, fmt, file);
            stato.setText("Foto salvata: " + file.getName());
        } catch (Exception e) { JOptionPane.showMessageDialog(f, "Errore salvataggio: " + e.getMessage()); }
    }

    private static String tsv() {
        StringBuilder sb = new StringBuilder();
        for (String[] r : righeRaw) sb.append(r[1]).append('\t').append(r[2]).append('\n');
        return sb.toString();
    }

    private static String raw(String key) {
        for (String[] r : righeRaw) if (key.equals(r[0])) return r[3];
        return null;
    }

    private static void copia(String s) { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s == null ? "" : s), null); }
    private static String t(JTextField f) { String s = f.getText().trim(); return s.isEmpty() ? null : s; }

    /** Date picker a 3 tendine in ordine GIORNO -> MESE -> ANNO. */
    private static final class DatePicker extends JPanel {
        private final JComboBox<String> gg = new JComboBox<>(), mm = new JComboBox<>(), aa = new JComboBox<>();
        DatePicker(int annoMin, int annoMax) {
            super(new FlowLayout(FlowLayout.LEFT, 3, 0));
            setOpaque(false);
            gg.addItem("gg"); for (int d = 1; d <= 31; d++) gg.addItem(String.format("%02d", d));
            mm.addItem("mm"); for (int m = 1; m <= 12; m++) mm.addItem(String.format("%02d", m));
            aa.addItem("anno"); for (int y = annoMax; y >= annoMin; y--) aa.addItem(String.valueOf(y));
            add(gg); add(mm); add(aa);
        }
        String iso() {
            if (gg.getSelectedIndex() <= 0 || mm.getSelectedIndex() <= 0 || aa.getSelectedIndex() <= 0) return null;
            return aa.getSelectedItem() + "-" + mm.getSelectedItem() + "-" + gg.getSelectedItem();
        }
        void set(String iso) {
            if (iso == null || !iso.matches("\\d{4}-\\d{2}-\\d{2}")) return;
            aa.setSelectedItem(iso.substring(0, 4)); mm.setSelectedItem(iso.substring(5, 7)); gg.setSelectedItem(iso.substring(8, 10));
        }
    }

    /** Colonna "Copia": un pulsante per riga che copia il valore RAW negli appunti. */
    private static final class CopyButtonColumn extends AbstractCellEditor implements TableCellRenderer, TableCellEditor, ActionListener {
        private final JButton render = new JButton("Copia");
        private final JButton edit = new JButton("Copia");
        private int row;
        CopyButtonColumn() { render.setFocusable(false); stile(render, "arc: 8"); stile(edit, "arc: 8"); edit.addActionListener(this); }
        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean fo, int r, int c) { return render; }
        @Override public Component getTableCellEditorComponent(JTable t, Object v, boolean s, int r, int c) { row = r; return edit; }
        @Override public Object getCellEditorValue() { return "Copia"; }
        @Override public void actionPerformed(ActionEvent e) {
            fireEditingStopped();
            if (row >= 0 && row < righeRaw.size()) copia(righeRaw.get(row)[3]);
        }
    }
}
