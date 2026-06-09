package com.dadaops.cardreader;

/**
 * Conversione immagini (es. JPEG 2000 del volto -> PNG), iniettata dalla piattaforma.
 * Desktop: ImageIO + jai-imageio. Android: una libreria JP2 o il decoder di sistema; in mancanza
 * si usa {@link #PASSTHROUGH} e l'immagine viene restituita nel formato originale.
 */
public interface ImageDecoder {

    /** Converte in PNG; ritorna null se non in grado (l'immagine resta nel formato originale). */
    byte[] toPng(byte[] data, String mime);

    /** Nessuna conversione (default sicuro per le piattaforme senza decoder JPEG 2000). */
    ImageDecoder PASSTHROUGH = (data, mime) -> null;
}
