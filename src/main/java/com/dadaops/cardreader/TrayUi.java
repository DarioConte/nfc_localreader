package com.dadaops.cardreader;

import java.awt.CheckboxMenuItem;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.net.URI;

/** Icona nel system tray (desktop): apertura console, stato, toggle console, riavvio. */
final class TrayUi {
    private TrayUi() {}

    private static TrayIcon trayIcon;

    static void setup() {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            System.out.println("Modalita' background (nessun system tray disponibile).");
            return;
        }
        try {
            SystemTray tray = SystemTray.getSystemTray();
            PopupMenu menu = new PopupMenu();

            MenuItem apri = new MenuItem("Apri console web (test)");
            apri.addActionListener(e -> browse("http://localhost:" + AppContext.PORT));
            MenuItem stato = new MenuItem("Stato lettore");
            stato.addActionListener(e -> mostraStato());
            CheckboxMenuItem console = new CheckboxMenuItem("Console di debug abilitata", AppContext.CONSOLE_ENABLED);
            console.addItemListener(e -> {
                AppContext.CONSOLE_ENABLED = (e.getStateChange() == java.awt.event.ItemEvent.SELECTED);
                if (trayIcon != null) trayIcon.displayMessage("Console",
                        AppContext.CONSOLE_ENABLED ? "Console di debug ABILITATA" : "Console di debug disabilitata",
                        TrayIcon.MessageType.INFO);
            });
            MenuItem riavvia = new MenuItem("Riavvia servizio");
            riavvia.addActionListener(e -> ApiServer.riavvia());
            MenuItem esci = new MenuItem("Esci");
            esci.addActionListener(e -> { tray.remove(trayIcon); System.exit(0); });

            menu.add(apri); menu.add(stato); menu.add(console); menu.add(riavvia); menu.addSeparator(); menu.add(esci);

            trayIcon = new TrayIcon(creaIcona(), "Servizio identita' CIE/TS-CNS - porta " + AppContext.PORT, menu);
            trayIcon.setImageAutoSize(true);
            tray.add(trayIcon);
            System.out.println("Icona presente nel system tray.");
        } catch (Exception e) {
            System.out.println("Tray non disponibile: " + e.getMessage());
        }
    }

    private static void mostraStato() {
        if (trayIcon != null) trayIcon.displayMessage("Stato", CardReaderApi.readers(), TrayIcon.MessageType.INFO);
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
}
