package com.dadaops.cardreader;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Memoria temporanea (server-side) per il flow di identificazione a 2 documenti via REST: la PRIMA
 * lettura (es. Tessera Sanitaria) viene messa in sessione e conservata finche' non arriva la seconda
 * carta da unire, oppure finche' non scade il TTL. Cosi' il client non deve trasportare il JSON della
 * prima carta tra le due chiamate. I dati contengono PII (foto): scadono in fretta e si purgano.
 */
final class IdentitySession {
    private IdentitySession() {}

    /** Durata massima di una sessione aperta (default 10 minuti), sovrascrivibile da env. */
    static final long TTL_MS = ttlFromEnv();
    static final long TTL_SEC = TTL_MS / 1000;

    private static final ConcurrentHashMap<String, Voce> VOCI = new ConcurrentHashMap<>();

    private static final class Voce {
        final String json;
        final long scadeMs;
        Voce(String json, long scadeMs) { this.json = json; this.scadeMs = scadeMs; }
    }

    private static long ttlFromEnv() {
        try {
            String v = System.getenv("IDENTITY_SESSION_TTL_SEC");
            if (v != null && !v.isBlank()) return Math.max(30, Long.parseLong(v.trim())) * 1000L;
        } catch (Exception ignored) {}
        return 10 * 60 * 1000L;
    }

    /** Crea una sessione con la prima lettura; ritorna l'id sessione. */
    static String crea(String primaLettura) {
        purga();
        String id = UUID.randomUUID().toString().replace("-", "");
        VOCI.put(id, new Voce(primaLettura, System.currentTimeMillis() + TTL_MS));
        return id;
    }

    /** Prima lettura della sessione SENZA consumarla (null se assente/scaduta). */
    static String leggi(String id) {
        purga();
        if (id == null) return null;
        Voce v = VOCI.get(id);
        return v == null ? null : v.json;
    }

    /** Chiude la sessione e ritorna la prima lettura (null se assente/scaduta). */
    static String consuma(String id) {
        purga();
        if (id == null) return null;
        Voce v = VOCI.remove(id);
        return v == null ? null : v.json;
    }

    /** Annulla una sessione; true se esisteva. */
    static boolean annulla(String id) {
        return id != null && VOCI.remove(id) != null;
    }

    private static void purga() {
        long ora = System.currentTimeMillis();
        for (Iterator<Map.Entry<String, Voce>> it = VOCI.entrySet().iterator(); it.hasNext(); )
            if (it.next().getValue().scadeMs <= ora) it.remove();
    }
}
