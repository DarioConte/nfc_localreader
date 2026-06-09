package com.dadaops.cardreader.docs;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.dadaops.cardreader.docs.al.AlbaniaParser;
import com.dadaops.cardreader.docs.it.ItaliaParser;
import com.dadaops.cardreader.docs.ro.RomaniaParser;
import com.dadaops.cardreader.docs.ru.RussiaParser;
import com.dadaops.cardreader.docs.ua.UcrainaParser;
import com.dadaops.cardreader.docs.us.UsaParser;
import com.dadaops.cardreader.docs.world.GenericParser;

/**
 * Identifica il documento dalla MRZ (Stato emittente + tipo) e applica il parser specifico del
 * paese. Copre tutti gli eMRTD ICAO (passaporti e carte d'identità di USA, Russia, UE, Albania,
 * Romania, Ucraina, ...). I parser dei paesi senza una classe dedicata usano il {@link GenericParser}.
 */
public final class DocumentRegistry {
    private DocumentRegistry() {}

    private static final DocumentParser GENERICO = new GenericParser();
    private static final Map<String, DocumentParser> PER_PAESE = new HashMap<>();

    static {
        for (DocumentParser p : List.of(new ItaliaParser(), new RomaniaParser(), new UcrainaParser(),
                new RussiaParser(), new UsaParser(), new AlbaniaParser()))
            for (String iso : p.paesi()) PER_PAESE.put(iso, p);
    }

    /**
     * Classifica e arricchisce 'dati' (aggiunge paese, unioneEuropea, categoriaDocumento e campi
     * specifici del paese) e ritorna l'etichetta 'tipo'. 'fallback' se la MRZ manca.
     */
    public static String classifica(Map<String, String> dati, String fallback) {
        String dt = dati.getOrDefault("tipoDocumento", "");
        String iso = dati.getOrDefault("statoEmissione", "");

        DocumentProfile p = new DocumentProfile();
        p.paeseCodice = iso;
        p.paese = Countries.nome(iso);
        p.unioneEuropea = Countries.unioneEuropea(iso);
        p.categoria = categoria(dt);
        p.tipo = etichetta(dt, iso, fallback);

        DocumentParser parser = iso.isBlank() ? GENERICO : PER_PAESE.getOrDefault(iso.toUpperCase(), GENERICO);
        parser.arricchisci(p, dati);

        if (p.paese != null) dati.put("paese", p.paese);
        if (!iso.isBlank()) dati.put("unioneEuropea", p.unioneEuropea ? "true" : "false");
        if (p.categoria != null) dati.put("categoriaDocumento", p.categoria);
        return p.tipo;
    }

    private static String categoria(String dt) {
        if (dt == null) return null;
        if (dt.startsWith("P")) return "passaporto";
        if (dt.startsWith("I") || dt.startsWith("C") || dt.startsWith("A")) return "carta d'identità";
        return null;
    }

    private static String etichetta(String dt, String iso, String fallback) {
        if (dt != null && dt.startsWith("P")) return "PASSAPORTO";
        if ("ITA".equalsIgnoreCase(iso)) return "CIE";
        if (iso != null && !iso.isBlank()) return "CARTA D'IDENTITA";
        return fallback;
    }
}
