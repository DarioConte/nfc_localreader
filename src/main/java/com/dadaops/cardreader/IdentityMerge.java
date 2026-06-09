package com.dadaops.cardreader;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dadaops.cardreader.FiscalCode.normalizzaNome;
import static com.dadaops.cardreader.Json.canonicalMessage;
import static com.dadaops.cardreader.Json.mapToJson;
import static com.dadaops.cardreader.Json.parseFlatJsonOrdered;
import static com.dadaops.cardreader.Signer.hmac;
import static com.dadaops.cardreader.Signer.registraNonce;

/**
 * Unisce DUE letture (CARTA D'IDENTITÀ/PASSAPORTO + TESSERA SANITARIA) in un'unica identità.
 * <p>Il <b>documento d'identità con foto è il PRINCIPALE</b>: anagrafica, date e tutti i riferimenti
 * (numero documento, scadenza, emissione, cittadinanza, foto) vengono da lì e stanno alla radice del
 * JSON, identici a una lettura singola — così il CRM mappa gli stessi campi standard. La Tessera
 * Sanitaria è <b>secondaria</b> e i suoi dati (CF certificato, comune/provincia di nascita, ecc.)
 * vengono aggregati nel sotto-oggetto {@code tesseraSanitaria}. La corrispondenza tra le due carte è
 * verificata per cognome/nome/nascita.
 */
final class IdentityMerge {
    private IdentityMerge() {}

    /** Metadati di una singola lettura, da non riportare (né in radice né nel sotto-oggetto TS). */
    private static final Set<String> META = Set.of(
            "tipo", "checksum", "nonce", "letturaTimestamp", "avvisoChiave", "errore", "messaggio",
            "suggerimento", "canErrato", "ambiguo", "readers", "richiedeAutenticazione",
            "debugPPSE", "debugFCI", "debugPDOL", "debugGPO", "debugRecords");

    static String mergeJson(String jsonA, String jsonB) {
        Map<String, String> a = parseFlatJsonOrdered(jsonA), b = parseFlatJsonOrdered(jsonB);
        // La TS-CNS è quella col CF certificato (o tipo TS-CNS); l'altra è il documento principale.
        Map<String, String> ts = isTs(a) ? a : (isTs(b) ? b : a);
        Map<String, String> doc = (ts == a) ? b : a;

        LinkedHashMap<String, String> out = radice(doc, ts);
        LinkedHashMap<String, String> tsSub = sottoOggettoTs(ts);

        // checksum sui soli campi di radice (documento principale + metadati di unione)
        out.put("checksum", hmac(canonicalMessage(out)));

        String flat = mapToJson(out, null);                     // {...} del documento principale
        return flat.substring(0, flat.length() - 1)
                + ",\"tesseraSanitaria\":" + mapToJson(tsSub, null) + "}";
    }

    /** Radice = documento principale (con foto) + esito match + metadati di unione. */
    private static LinkedHashMap<String, String> radice(Map<String, String> doc, Map<String, String> ts) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        out.put("tipo", "IDENTITÀ UNITA");
        out.put("documentoPrincipale", doc.getOrDefault("tipo", "documento"));

        // ---- match per nome/cognome/nascita (con tolleranza per i nomi troncati in MRZ) ----
        boolean mCog = nomeMatch(ts.get("cognome"), doc.get("cognome"));
        boolean mNom = nomeMatch(ts.get("nome"), doc.get("nome"));
        boolean haNascita = ts.get("dataNascita") != null && doc.get("dataNascita") != null;
        boolean mNasc = haNascita && ts.get("dataNascita").equals(doc.get("dataNascita"));
        boolean mSex = ts.get("sesso") != null && ts.get("sesso").equals(doc.get("sesso"));
        out.put("corrispondenza", (mCog && mNom && (mNasc || !haNascita)) ? "true" : "false");
        // confronto CF informativo (non entra in 'corrispondenza': il CF calcolato non risolve l'omocodia)
        String cfTs = cfNorm(ts.get("codiceFiscale")), cfDoc = cfNorm(doc.get("codiceFiscale"));
        String cfDett = (cfTs != null && cfDoc != null) ? " · codice fiscale " + segno(cfTs.equals(cfDoc)) : "";
        out.put("matchDettaglio", "cognome " + segno(mCog) + " · nome " + segno(mNom)
                + " · data nascita " + (haNascita ? segno(mNasc) : "n/d") + " · sesso " + segno(mSex) + cfDett);

        // ---- tutti i campi del documento principale (anagrafica, date, riferimenti, foto) ----
        for (Map.Entry<String, String> e : doc.entrySet())
            if (!META.contains(e.getKey()) && !out.containsKey(e.getKey())
                    && e.getValue() != null && !e.getValue().isBlank())
                out.put(e.getKey(), e.getValue());
        DocumentBuilder.normalizzaLuogoNascita(out);            // luogoNascita canonico dal documento

        out.put("documentiUniti", doc.getOrDefault("tipo", "documento") + " + TS-CNS");
        out.put("letturaTimestamp", Instant.now().toString());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        out.put("nonce", nonce);
        registraNonce(nonce);
        return out;
    }

    /** Sotto-oggetto secondario con i dati della Tessera Sanitaria (CF certificato, luogo nascita…). */
    private static LinkedHashMap<String, String> sottoOggettoTs(Map<String, String> ts) {
        LinkedHashMap<String, String> sub = new LinkedHashMap<>();
        sub.put("tipo", "TS-CNS");
        for (Map.Entry<String, String> e : ts.entrySet()) {
            String k = e.getKey();
            if (META.contains(k) || k.startsWith("foto") || k.startsWith("firma")) continue;
            if (e.getValue() != null && !e.getValue().isBlank()) sub.put(k, e.getValue());
        }
        DocumentBuilder.normalizzaLuogoNascita(sub);            // luogoNascita anche nel ramo TS
        return sub;
    }

    private static boolean isTs(Map<String, String> m) {
        return "TS-CNS".equals(m.get("tipo")) || "true".equals(m.get("codiceFiscaleCertified"));
    }

    /** Match nomi con tolleranza per il troncamento MRZ: uguali o uno prefisso dell'altro. */
    private static boolean nomeMatch(String x, String y) {
        String a = normalizzaNome(x), c = normalizzaNome(y);
        if (a.isEmpty() || c.isEmpty()) return false;
        return a.equals(c) || a.startsWith(c) || c.startsWith(a);
    }

    private static String cfNorm(String s) {
        if (s == null) return null;
        String t = s.trim().toUpperCase();
        return t.isEmpty() ? null : t;
    }

    private static String segno(boolean b) { return b ? "✓" : "✗"; }
}
