package com.dadaops.cardreader;

import static com.dadaops.cardreader.Apdu.getUid;
import static com.dadaops.cardreader.Apdu.select;
import static com.dadaops.cardreader.Apdu.selectAid;
import static com.dadaops.cardreader.Apdu.swHex;
import static com.dadaops.cardreader.Hex.hex;
import static com.dadaops.cardreader.Json.jstr;

/** Rileva il tipo di carta presente (TS-CNS, CIE/eMRTD, EMV, NFC generico) su una connessione. */
final class Detector {
    private Detector() {}

    // EID = famiglia eMRTD (CIE, passaporto, carta d'identità estera): indistinguibili pre-auth.
    enum Tipo { TS_CNS, EID, EMV, NFC, SCONOSCIUTA }

    /** Esito del rilevamento: tipo carta e UID NFC se disponibile (solo contactless). */
    static final class Rilevazione {
        final Tipo tipo; final String uid;
        Rilevazione(Tipo t, String u) { tipo = t; uid = u; }
    }

    /** Rileva il tipo sulla connessione aperta. Non chiude il link (lo gestisce il chiamante). */
    static Rilevazione rileva(CardLink link) {
        try {
            byte[] u = getUid(link);                     // UID ISO-14443 (solo contactless)
            String uid = (u != null) ? hex(u) : null;
            Tipo tipo;
            if (select(link, (byte) 0x3F, (byte) 0x00)
                    && select(link, (byte) 0x11, (byte) 0x00)
                    && select(link, (byte) 0x11, (byte) 0x02)) tipo = Tipo.TS_CNS;
            else if (selectAid(link, AppContext.AID_EMRTD)) tipo = Tipo.EID;
            else if (EmvReader.emvSelect(link, AppContext.PPSE) != null) tipo = Tipo.EMV;
            else if (u != null) tipo = Tipo.NFC;
            else tipo = Tipo.SCONOSCIUTA;
            return new Rilevazione(tipo, uid);
        } catch (CardLinkException e) {
            return new Rilevazione(Tipo.SCONOSCIUTA, null);
        }
    }

    /** Dump diagnostico di una carta non riconosciuta. */
    static String diagnostica(CardLink link) {
        try {
            StringBuilder sb = new StringBuilder("{\"tipo\":\"sconosciuta\",\"diagnostica\":{");
            byte[] atr = link.atr();
            sb.append("\"atr\":").append(jstr(atr != null ? hex(atr) : null));
            sb.append(",\"selectMF_3F00\":").append(jstr(swHex(link, new byte[]{0x00, (byte) 0xA4, 0x00, 0x00, 0x02, 0x3F, 0x00})));
            sb.append(",\"selectDF_1100\":").append(jstr(swHex(link, new byte[]{0x00, (byte) 0xA4, 0x00, 0x00, 0x02, 0x11, 0x00})));
            sb.append(",\"selectEF_1102\":").append(jstr(swHex(link, new byte[]{0x00, (byte) 0xA4, 0x00, 0x00, 0x02, 0x11, 0x02})));
            byte[] aid = AppContext.AID_EMRTD;
            byte[] aidSel = new byte[5 + aid.length];
            aidSel[0] = 0x00; aidSel[1] = (byte) 0xA4; aidSel[2] = 0x04; aidSel[3] = 0x00; aidSel[4] = (byte) aid.length;
            System.arraycopy(aid, 0, aidSel, 5, aid.length);
            sb.append(",\"selectAID_eMRTD\":").append(jstr(swHex(link, aidSel)));
            sb.append("}}");
            return sb.toString();
        } catch (CardLinkException e) {
            return "{\"tipo\":\"sconosciuta\",\"errore\":" + jstr(String.valueOf(e.getMessage())) + "}";
        }
    }

    static String tipoString(Tipo t) {
        switch (t) {
            case TS_CNS: return "TS-CNS";
            case EID: return "Documento elettronico";
            case EMV: return "Carta di credito";
            case NFC: return "NFC";
            default: return "sconosciuta";
        }
    }
}
