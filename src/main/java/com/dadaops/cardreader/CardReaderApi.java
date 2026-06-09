package com.dadaops.cardreader;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import com.dadaops.cardreader.EmrtdReader.DatiMrz;

import static com.dadaops.cardreader.Json.canonicalMessage;
import static com.dadaops.cardreader.Json.jstr;
import static com.dadaops.cardreader.Json.parseFlatJsonOrdered;
import static com.dadaops.cardreader.Signer.hmac;
import static com.dadaops.cardreader.Text.normalizzaDataMrz;
import static com.dadaops.cardreader.Text.notBlank;

/**
 * Facade programmatica e indipendente dalla piattaforma: tutti i metodi ritornano JSON pronto.
 * Un'app Android la chiama direttamente (dopo {@code Platform.configure(...)}); il server HTTP
 * desktop la usa tramite {@link ApiRouter}. Cosi' la stessa logica gira come server o come libreria.
 */
public final class CardReaderApi {
    private CardReaderApi() {}

    public static String readers() {
        try { return "{\"readers\":" + CardDispatcher.readersArrayJson(Platform.source()) + "}"; }
        catch (Exception e) { return "{\"readers\":[],\"errore\":" + jstr(String.valueOf(e.getMessage())) + "}"; }
    }

    public static String status(String reader) {
        try { return CardDispatcher.stato(reader); }
        catch (Exception e) { return "{\"reader\":null,\"cardPresent\":false,\"errore\":" + jstr(String.valueOf(e.getMessage())) + "}"; }
    }

    public static String check(String can, String reader, boolean foto,
                               String documentNumber, String dateOfBirth, String dateOfExpiry, boolean debug) {
        DatiMrz mrz = (notBlank(documentNumber) && notBlank(dateOfBirth) && notBlank(dateOfExpiry))
                ? new DatiMrz(documentNumber.trim(), normalizzaDataMrz(dateOfBirth), normalizzaDataMrz(dateOfExpiry)) : null;
        try { return CardDispatcher.leggiCartaJson(can, reader, foto, mrz, debug); }
        catch (Exception e) { return "{\"errore\":" + jstr(String.valueOf(e.getMessage())) + "}"; }
    }

    public static String identify(String reader) {
        try { return CardDispatcher.identifica(reader); }
        catch (Exception e) { return "{\"errore\":" + jstr(String.valueOf(e.getMessage())) + "}"; }
    }

    public static String write(String reader, String text, String hex, Integer page, Integer block, String key,
                               String chiaveAnagrafica, String codiceSocio, String codiceFamiliare, String familiari) {
        if (text == null && hex == null
                && (chiaveAnagrafica != null || codiceSocio != null || familiari != null || codiceFamiliare != null))
            text = NfcReader.costruisciRecordSocio(chiaveAnagrafica, codiceSocio, codiceFamiliare, familiari);
        try { return CardDispatcher.scrivi(reader, text, hex, page, block, key); }
        catch (Exception e) { return "{\"errore\":" + jstr(String.valueOf(e.getMessage())) + "}"; }
    }

    public static String verify(String jsonBody) {
        LinkedHashMap<String, String> campi = parseFlatJsonOrdered(jsonBody);
        String checksum = campi.get("checksum");
        if (checksum == null) return "{\"valido\":false,\"motivo\":\"checksum assente\"}";
        LinkedHashMap<String, String> senza = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : campi.entrySet())
            if (!e.getKey().equals("checksum")) senza.put(e.getKey(), e.getValue());
        String atteso = hmac(canonicalMessage(senza));
        boolean valido = atteso.equalsIgnoreCase(checksum);
        String nonce = campi.get("nonce");
        boolean nonceConosciuto = nonce != null && AppContext.NONCE_EMESSI.contains(nonce);
        return "{\"valido\":" + valido + ",\"nonceConosciuto\":" + nonceConosciuto
                + ",\"checksumAtteso\":" + jstr(atteso) + "}";
    }

    /**
     * Unisce due letture (Tessera Sanitaria + passaporto/CIE) in un'unica identità. Il documento con
     * foto è il PRINCIPALE (anagrafica, date, riferimenti alla radice, come una lettura singola); la
     * Tessera Sanitaria è secondaria nel sotto-oggetto {@code tesseraSanitaria}. Vedi {@link IdentityMerge}.
     */
    public static String merge(String jsonDocumentoA, String jsonDocumentoB) {
        try { return IdentityMerge.mergeJson(jsonDocumentoA, jsonDocumentoB); }
        catch (Exception e) { return "{\"errore\":" + jstr(String.valueOf(e.getMessage())) + "}"; }
    }

    /**
     * Flow identità — passo 1: legge la PRIMA carta (tipicamente la Tessera Sanitaria) e la tiene in
     * memoria server-side in una sessione, da unire poi con la seconda. Se la lettura fallisce NON
     * apre alcuna sessione e ritorna l'errore. Risposta: {@code {session, scadeTraSecondi, primaLettura}}.
     */
    public static String identityBegin(String can, String reader, boolean foto,
                                       String documentNumber, String dateOfBirth, String dateOfExpiry) {
        String prima = check(can, reader, foto, documentNumber, dateOfBirth, dateOfExpiry, false);
        if (Json.jsonField(prima, "errore") != null) return prima;
        String id = IdentitySession.crea(prima);
        return "{\"session\":" + jstr(id) + ",\"scadeTraSecondi\":" + IdentitySession.TTL_SEC
                + ",\"primaLettura\":" + prima + "}";
    }

    /**
     * Flow identità — passo 2: legge la SECONDA carta (es. passaporto/CIE) e la unisce alla prima
     * tenuta in {@code session}. La data di nascita per la chiave MRZ, se non passata, viene ripresa
     * dalla prima lettura. La sessione si chiude solo se la seconda lettura riesce (così è ripetibile
     * in caso di errore). Ritorna l'identità unita, o un errore se la sessione è scaduta/inesistente.
     */
    public static String identityComplete(String session, String can, String reader, boolean foto,
                                          String documentNumber, String dateOfBirth, String dateOfExpiry) {
        String prima = IdentitySession.leggi(session);
        if (prima == null) return "{\"errore\":\"sessione assente o scaduta\"}";
        if (!notBlank(dateOfBirth)) dateOfBirth = Json.jsonField(prima, "dataNascita");
        String seconda = check(can, reader, foto, documentNumber, dateOfBirth, dateOfExpiry, false);
        if (Json.jsonField(seconda, "errore") != null) return seconda;   // sessione conservata: riprovabile
        IdentitySession.consuma(session);
        try { return IdentityMerge.mergeJson(prima, seconda); }
        catch (Exception e) { return "{\"errore\":" + jstr(String.valueOf(e.getMessage())) + "}"; }
    }

    /** Flow identità — annulla una sessione aperta (scarta la prima lettura in memoria). */
    public static String identityCancel(String session) {
        boolean c = IdentitySession.annulla(session);
        return "{\"annullata\":" + c + "}";
    }

    /** Contenuto di una risorsa bundled (es. "/console.html", "/openapi.yaml"); null se assente. */
    public static byte[] resource(String name) {
        try (InputStream is = CardReaderApi.class.getResourceAsStream(name)) {
            return is == null ? null : is.readAllBytes();
        } catch (Exception e) { return null; }
    }
}
