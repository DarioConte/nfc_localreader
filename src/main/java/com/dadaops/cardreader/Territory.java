package com.dadaops.cardreader;

import java.util.Map;

import static com.dadaops.cardreader.FiscalCode.normalizzaNome;
import static com.dadaops.cardreader.Text.putIf;

/** Risoluzione del luogo di nascita (comune italiano o Stato estero) dal codice catastale. */
final class Territory {
    private Territory() {}

    /** Risolve un luogo testuale al codice catastale (best-effort, solo comuni italiani). */
    static String risolviCatastale(String luogo) {
        if (luogo == null) return null;
        String primo = luogo.split("[(,/<]")[0];
        String cod = AppContext.COMUNI_PER_NOME.get(normalizzaNome(primo));
        if (cod == null) cod = AppContext.COMUNI_PER_NOME.get(normalizzaNome(luogo));
        return cod;
    }

    /**
     * Dal codice catastale (gia' de-omocodato) popola il luogo di nascita: per i comuni italiani
     * comune/provincia/regione; per i codici esteri (Z...) lo Stato e il flag 'natoEstero'.
     */
    static void risolviLuogoNascita(Map<String, String> out, String cat) {
        if (cat == null) return;
        if (cat.toUpperCase().startsWith("Z")) {
            out.put("natoEstero", "true");
            putIf(out, "statoNascita", AppContext.STATI.get(cat.toUpperCase()));
        } else {
            String[] info = AppContext.COMUNI.get(cat);
            if (info != null) {
                putIf(out, "comuneNascita", info[0]);
                putIf(out, "provinciaNascita", info[1]);
                putIf(out, "regione", info[2]);
            }
        }
    }
}
