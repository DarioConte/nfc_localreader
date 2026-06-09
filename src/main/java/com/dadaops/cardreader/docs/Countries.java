package com.dadaops.cardreader.docs;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Anagrafica Stati: ISO 3166-1 alpha-3 (codice MRZ) -> nome italiano, e appartenenza UE. */
final class Countries {
    private Countries() {}

    private static final Map<String, String> NOMI = new HashMap<>();

    /** Stati membri dell'Unione Europea (ISO3). */
    private static final Set<String> UE = Set.of(
            "AUT", "BEL", "BGR", "HRV", "CYP", "CZE", "DNK", "EST", "FIN", "FRA", "DEU", "GRC",
            "HUN", "IRL", "ITA", "LVA", "LTU", "LUX", "MLT", "NLD", "POL", "PRT", "ROU", "SVK",
            "SVN", "ESP", "SWE");

    static {
        try (InputStream is = Countries.class.getResourceAsStream("/paesi.csv")) {
            if (is != null) {
                BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] p = line.split(":", 2);
                    if (p.length == 2 && !p[0].isBlank() && !p[1].isBlank())
                        NOMI.put(p[0].trim().toUpperCase(), p[1].trim());
                }
            }
        } catch (Exception ignored) {}
    }

    /** Nome italiano dello Stato dal codice ISO3 (es. "FRA" -> "FRANCIA"); null se ignoto. */
    static String nome(String iso3) {
        return iso3 == null ? null : NOMI.get(iso3.trim().toUpperCase());
    }

    static boolean unioneEuropea(String iso3) {
        return iso3 != null && UE.contains(iso3.trim().toUpperCase());
    }
}
