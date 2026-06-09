package com.dadaops.cardreader.android;

import android.app.Activity;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.widget.TextView;

import com.dadaops.cardreader.ImageDecoder;
import com.dadaops.cardreader.Platform;

import java.security.Security;

/**
 * Esempio minimo: avvia il server HTTP locale e abilita la lettura NFC. La web-app nel browser del
 * tablet chiama http://localhost:8765/check, /identify, ecc. esattamente come col server desktop.
 */
public class MainActivity extends Activity implements NfcAdapter.ReaderCallback {

    private NfcAdapter nfc;
    private final IsoDepCardSource source = new IsoDepCardSource();
    private LocalHttpServer http;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("Servizio NFC su http://localhost:8765 — avvicina un documento.");
        setContentView(tv);

        // BouncyCastle: sostituisci il provider ridotto di Android con quello completo (serve a PACE).
        Security.removeProvider("BC");
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());

        // Bootstrap della libreria: carica registri (comuni/stati) e configura il trasporto NFC.
        Platform.boot(source, ImageDecoder.PASSTHROUGH);     // foto JP2 non convertita (vedi README)
        Platform.setKeys("LA_TUA_API_KEY", "LA_TUA_SIGN_KEY", "LA_TUA_CRYPTO_KEY");  // come il desktop
        Platform.setConsoleEnabled(false);                   // produzione

        try { http = new LocalHttpServer(8765); http.start(); }
        catch (Exception e) { tv.setText("Errore avvio server: " + e.getMessage()); }

        nfc = NfcAdapter.getDefaultAdapter(this);
    }

    @Override protected void onResume() {
        super.onResume();
        if (nfc != null) nfc.enableReaderMode(this, this,
                NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null);
    }

    @Override protected void onPause() {
        super.onPause();
        if (nfc != null) nfc.disableReaderMode(this);
    }

    /** A ogni documento avvicinato: il tag diventa la "carta corrente" per le chiamate REST. */
    @Override public void onTagDiscovered(Tag tag) { source.setTag(tag); }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (http != null) http.stop();
    }
}
