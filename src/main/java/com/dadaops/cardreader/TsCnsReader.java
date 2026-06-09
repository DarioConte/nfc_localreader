package com.dadaops.cardreader;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.dadaops.cardreader.Apdu.readBinary;
import static com.dadaops.cardreader.Apdu.select;
import static com.dadaops.cardreader.FiscalCode.matchCf;
import static com.dadaops.cardreader.Text.isoDaGGMMAAAA;
import static com.dadaops.cardreader.Text.putIf;

/** Lettura della Tessera Sanitaria / CNS (file Dati personali, lettura libera senza CAN). */
final class TsCnsReader {
    private TsCnsReader() {}

    static Map<String, String> leggiTsCns(CardLink link) throws CardLinkException {
        if (!(select(link, (byte) 0x3F, (byte) 0x00)
                && select(link, (byte) 0x11, (byte) 0x00)
                && select(link, (byte) 0x11, (byte) 0x02))) return null;
        byte[] data = readBinary(link);

        List<String> grezzi = campiGrezzi(data);
        Map<String, String> raw = new LinkedHashMap<>();
        for (int i = 0; i < grezzi.size() && i < AppContext.ETICHETTE_CNS.length; i++) {
            String v = grezzi.get(i).trim();
            if (!v.isEmpty()) raw.put(AppContext.ETICHETTE_CNS[i], v);
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
    }

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
}
