package com.dadaops.cardreader;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static com.dadaops.cardreader.Hex.hex;

/** Firme HMAC, identificativi pseudonimi (chiaveAnagrafica/tokenCarta) e gestione nonce. */
final class Signer {
    private Signer() {}

    static String hmac(String msg) { return hmacWith(AppContext.SIGN_KEY, msg); }

    static String hmacWith(String key, String msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(msg.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { return ""; }
    }

    /** Identificativo anagrafico pseudonimo: HMAC(chiave crittografica, CF). Non reversibile. */
    static String idAnagrafico(String cf) {
        return cf == null ? null : hmacWith(AppContext.CRYPTO_KEY, cf.toUpperCase());
    }

    static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    static void registraNonce(String nonce) {
        synchronized (AppContext.NONCE_EMESSI) {
            AppContext.NONCE_EMESSI.add(nonce);
            if (AppContext.NONCE_EMESSI.size() > AppContext.NONCE_MAX) {
                Iterator<String> it = AppContext.NONCE_EMESSI.iterator();
                if (it.hasNext()) { it.next(); it.remove(); }
            }
        }
    }

    /** Token stabile e pseudonimo (HMAC) per usare la carta come "tessera" di accesso. */
    static String tokenCarta(String fonte, String materiale) {
        return hmacWith(AppContext.CRYPTO_KEY, "token:" + fonte + ":" + materiale);
    }

    /** UID casuale (anti-tracciamento): 4 byte con primo byte 0x08 -> NON stabile tra un tap e l'altro. */
    static boolean uidCasuale(String uidHex) {
        return uidHex != null && uidHex.length() == 8 && uidHex.toUpperCase().startsWith("08");
    }
}
