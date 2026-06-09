package com.dadaops.cardreader;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.security.spec.AlgorithmParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.sf.scuba.smartcards.CardService;
import org.jmrtd.BACKey;
import org.jmrtd.PACEKeySpec;
import org.jmrtd.PassportService;
import org.jmrtd.lds.AbstractImageInfo;
import org.jmrtd.lds.CardAccessFile;
import org.jmrtd.lds.DisplayedImageInfo;
import org.jmrtd.lds.PACEInfo;
import org.jmrtd.lds.SODFile;
import org.jmrtd.lds.SecurityInfo;
import org.jmrtd.lds.icao.COMFile;
import org.jmrtd.lds.icao.DG2File;
import org.jmrtd.lds.icao.DG7File;
import org.jmrtd.lds.icao.DG11File;
import org.jmrtd.lds.icao.DG12File;
import org.jmrtd.lds.icao.DG14File;
import org.jmrtd.lds.icao.DG15File;
import org.jmrtd.lds.iso19794.FaceImageInfo;
import org.jmrtd.lds.iso19794.FaceInfo;

import static com.dadaops.cardreader.Apdu.readAll;
import static com.dadaops.cardreader.Hex.hex;
import static com.dadaops.cardreader.Json.jsonArr;
import static com.dadaops.cardreader.Json.jstr;
import static com.dadaops.cardreader.Mrz.estraiMrz;
import static com.dadaops.cardreader.Mrz.parseMrz;
import static com.dadaops.cardreader.Signer.hmacWith;
import static com.dadaops.cardreader.Signer.sha256;
import static com.dadaops.cardreader.Text.isoDaCompatta;
import static com.dadaops.cardreader.Text.pulisci;
import static com.dadaops.cardreader.Text.putIf;
import static com.dadaops.cardreader.Text.unisciLista;

/**
 * Documenti ICAO eMRTD: CIE (PACE con CAN), passaporto e carte d'identità estere (PACE/BAC con
 * MRZ). Usa il {@link CardService} fornito da {@link Platform#source()} (PC/SC o IsoDep su Android).
 */
final class EmrtdReader {
    private EmrtdReader() {}

    static final class DatiMrz {
        final String documentNumber, dateOfBirth, dateOfExpiry;
        DatiMrz(String d, String b, String e) { documentNumber = d; dateOfBirth = b; dateOfExpiry = e; }
    }

    static final class CanException extends Exception {
        CanException(String m) { super(m); }
    }

    // ---- CIE (PACE con CAN) ----
    static Map<String, String> leggiCie(String reader, String can, boolean includiFoto) throws Exception {
        CardService cs = Platform.source().cardService(reader);
        PassportService ps = new PassportService(
                cs, PassportService.NORMAL_MAX_TRANCEIVE_LENGTH, PassportService.DEFAULT_MAX_BLOCKSIZE, false, false);
        try {
            ps.open();
            try {
                cs.transmit(new net.sf.scuba.smartcards.CommandAPDU(0x00, 0xA4, 0x00, 0x0C, new byte[]{0x3F, 0x00}));
            } catch (Exception ignoreMf) {}

            CardAccessFile cardAccess = new CardAccessFile(ps.getInputStream(PassportService.EF_CARD_ACCESS));
            PACEInfo pace = null;
            for (SecurityInfo si : cardAccess.getSecurityInfos()) {
                if (si instanceof PACEInfo) { pace = (PACEInfo) si; break; }
            }
            if (pace == null) throw new IllegalStateException("PACEInfo non trovato in EF.CardAccess");

            AlgorithmParameterSpec params = PACEInfo.toParameterSpec(pace.getParameterId());
            try {
                ps.doPACE(PACEKeySpec.createCANKey(can), pace.getObjectIdentifier(), params, pace.getParameterId());
            } catch (Exception pe) {
                String m = String.valueOf(pe.getMessage());
                String ml = m.toLowerCase();
                boolean canErrato = ml.contains("authentication token") || ml.contains("mutual")
                        || m.contains("6982") || m.contains("step: 4");
                if (canErrato) throw new CanException(m);
                throw new RuntimeException(m);
            }
            ps.sendSelectApplet(true);
            return leggiDatiEmrtd(ps, includiFoto);
        } finally {
            try { ps.close(); } catch (Exception ignored) {}
        }
    }

    // ---- Passaporto / ID estero (PACE-MRZ con fallback BAC) ----
    static Map<String, String> leggiPassaporto(String reader, DatiMrz mrz, boolean includiFoto) throws Exception {
        BACKey bacKey = new BACKey(mrz.documentNumber, mrz.dateOfBirth, mrz.dateOfExpiry);

        PassportService ps = nuovoPassportService(Platform.source().cardService(reader));
        try {
            ps.open();
            PACEInfo pace = leggiPaceInfo(ps);
            if (pace != null) {
                AlgorithmParameterSpec params = PACEInfo.toParameterSpec(pace.getParameterId());
                ps.doPACE(PACEKeySpec.createMRZKey(bacKey), pace.getObjectIdentifier(), params, pace.getParameterId());
                ps.sendSelectApplet(true);
                return leggiDatiEmrtd(ps, includiFoto);
            }
        } catch (Exception e) {
            /* PACE fallito: si prova BAC (per i dettagli usare la modalita' debug) */
        } finally {
            try { ps.close(); } catch (Exception ignored) {}
        }

        PassportService ps2 = nuovoPassportService(Platform.source().cardService(reader));
        try {
            ps2.open();
            ps2.sendSelectApplet(false);
            ps2.doBAC(bacKey);
            return leggiDatiEmrtd(ps2, includiFoto);
        } finally {
            try { ps2.close(); } catch (Exception ignored) {}
        }
    }

    // PACE-DH a 2048 bit (es. passaporto italiano): serve APDU a lunghezza ESTESA.
    private static PassportService nuovoPassportService(CardService cs) {
        return new PassportService(
                cs, PassportService.EXTENDED_MAX_TRANCEIVE_LENGTH, PassportService.DEFAULT_MAX_BLOCKSIZE, false, false);
    }

    private static PACEInfo leggiPaceInfo(PassportService ps) {
        try {
            CardAccessFile cardAccess = new CardAccessFile(ps.getInputStream(PassportService.EF_CARD_ACCESS));
            for (SecurityInfo si : cardAccess.getSecurityInfos())
                if (si instanceof PACEInfo) return (PACEInfo) si;
        } catch (Exception ignored) {}
        return null;
    }

    /** Lettura uniforme: DG1 (MRZ), DG11 (anagrafica estesa), DG12 (documento), DG2 (foto). */
    static Map<String, String> leggiDatiEmrtd(PassportService ps, boolean includiFoto) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();

        byte[] dg1 = readAll(ps.getInputStream(PassportService.EF_DG1));
        String mrzStr = estraiMrz(dg1);
        if (mrzStr != null) parseMrz(mrzStr, out);

        try {
            DG11File dg11 = new DG11File(ps.getInputStream(PassportService.EF_DG11));
            leggiDg11(dg11, out, mrzStr);
        } catch (Exception ignored) { /* DG11 assente */ }

        try {
            DG12File dg12 = new DG12File(ps.getInputStream(PassportService.EF_DG12));
            putIf(out, "autoritaEmittente", pulisci(dg12.getIssuingAuthority()));
            putIf(out, "dataEmissione", isoDaCompatta(dg12.getDateOfIssue()));
        } catch (Exception ignored) { /* DG12 assente */ }

        leggiDatiAggiuntivi(ps, out, includiFoto);
        if (includiFoto) leggiFotoDg2(ps, out);
        return out;
    }

    private static void leggiDatiAggiuntivi(PassportService ps, Map<String, String> out, boolean includiFoto) {
        try {
            COMFile com = new COMFile(ps.getInputStream(PassportService.EF_COM));
            List<Integer> dgs = new ArrayList<>();
            for (int tag : com.getTagList()) { int n = dgDaTag(tag); if (n > 0) dgs.add(n); }
            Collections.sort(dgs);
            if (!dgs.isEmpty()) out.put("dataGroupPresenti", dgs.toString());
            putIf(out, "versioneLDS", com.getLDSVersion());
        } catch (Exception ignored) {}

        if (includiFoto) try {
            DG7File dg7 = new DG7File(ps.getInputStream(PassportService.EF_DG7));
            for (DisplayedImageInfo img : dg7.getImages()) { aggiungiImmagine(out, "firma", img); break; }
        } catch (Exception ignored) {}

        try {
            DG14File dg14 = new DG14File(ps.getInputStream(PassportService.EF_DG14));
            if (!dg14.getChipAuthenticationPublicKeyInfos().isEmpty()) out.put("chipAuthentication", "true");
        } catch (Exception ignored) {}

        try {
            DG15File dg15 = new DG15File(ps.getInputStream(PassportService.EF_DG15));
            byte[] enc = dg15.getPublicKey().getEncoded();
            if (enc != null) {
                out.put("activeAuthentication", "true");
                out.put("idChip", hex(sha256(enc)).substring(0, 32));   // hash chiave AA, stabile e non PII
            }
        } catch (Exception ignored) {}

        try {
            SODFile sod = new SODFile(ps.getInputStream(PassportService.EF_SOD));
            byte[] firma = sod.getEncryptedDigest();
            if (firma != null && firma.length > 0)
                out.put("idDocumento", hmacWith(AppContext.CRYPTO_KEY, "sod:" + hex(sha256(firma))));
            try { putIf(out, "algoritmoHashSOD", sod.getDigestAlgorithm()); } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    private static int dgDaTag(int tag) {
        switch (tag) {
            case 0x61: return 1;  case 0x75: return 2;  case 0x63: return 3;  case 0x76: return 4;
            case 0x65: return 5;  case 0x66: return 6;  case 0x67: return 7;  case 0x68: return 8;
            case 0x69: return 9;  case 0x6A: return 10; case 0x6B: return 11; case 0x6C: return 12;
            case 0x6D: return 13; case 0x6E: return 14; case 0x6F: return 15; case 0x70: return 16;
            default: return 0;
        }
    }

    private static void leggiDg11(DG11File dg11, Map<String, String> out, String mrz) {
        String pn = pulisci(dg11.getPersonalNumber());
        String cf = FiscalCode.matchCf(pn);
        if (cf == null && mrz != null) cf = FiscalCode.matchCf(mrz);
        if (cf != null) out.put("codiceFiscale", cf);
        else if (Text.notBlank(pn)) putIf(out, "numeroPersonale", pn);

        putIf(out, "nomeCompleto", pulisci(dg11.getNameOfHolder()));
        putIf(out, "altriNomi", unisciLista(dg11.getOtherNames()));
        putIf(out, "luogoNascita", unisciLista(dg11.getPlaceOfBirth()));
        putIf(out, "indirizzo", unisciLista(dg11.getPermanentAddress()));
        putIf(out, "telefono", pulisci(dg11.getTelephone()));
        putIf(out, "professione", pulisci(dg11.getProfession()));
        putIf(out, "titolo", pulisci(dg11.getTitle()));
        String fdob = isoDaCompatta(dg11.getFullDateOfBirth());
        if (fdob != null) out.put("dataNascita", fdob);
    }

    private static void leggiFotoDg2(PassportService ps, Map<String, String> out) {
        try {
            byte[] dg2 = readAll(ps.getInputStream(PassportService.EF_DG2));
            DG2File file = new DG2File(new ByteArrayInputStream(dg2));
            for (FaceInfo fi : file.getFaceInfos())
                for (FaceImageInfo img : fi.getFaceImageInfos()) { aggiungiImmagine(out, "foto", img); return; }
        } catch (Exception e) {
            out.put("fotoErrore", String.valueOf(e.getMessage()));
        }
    }

    /** Estrae un'immagine LDS come data URL; converte in PNG tramite il decoder iniettato, se disponibile. */
    private static void aggiungiImmagine(Map<String, String> out, String prefisso, AbstractImageInfo img) throws Exception {
        int len = img.getImageLength();
        byte[] buf = new byte[len];
        try (DataInputStream din = new DataInputStream(img.getImageInputStream())) {
            din.readFully(buf);
        }
        String mime = img.getMimeType();
        if (mime == null || mime.isBlank()) mime = "image/jp2";
        out.put(prefisso + "MimeOriginale", mime);
        out.put(prefisso + "Larghezza", String.valueOf(img.getWidth()));
        out.put(prefisso + "Altezza", String.valueOf(img.getHeight()));
        byte[] png = Platform.decoder().toPng(buf, mime);
        if (png != null) {
            out.put(prefisso + "Formato", "png");
            out.put(prefisso + "Base64", "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
            return;
        }
        out.put(prefisso + "Formato", "originale");
        out.put(prefisso + "Base64", "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(buf));
    }

    // ===================== Diagnostica eMRTD (debug) =====================
    static String diagnosticaEmrtd(String reader, String can, DatiMrz mrz, String uid) {
        StringBuilder sb = new StringBuilder("{\"tipo\":\"eMRTD-debug\"");
        if (uid != null) sb.append(",\"uidCarta\":").append(jstr(uid));

        try (CardLink l = Platform.source().connect(reader)) {
            byte[] atr = l.atr();
            sb.append(",\"atr\":").append(jstr(atr != null ? hex(atr) : null));
        } catch (Exception e) {
            sb.append(",\"atrErrore\":").append(jstr(String.valueOf(e.getMessage())));
        }

        LogCattura cap = new LogCattura();
        String[] nomiLog = {"org.jmrtd", "net.sf.scuba.smartcards", "net.sf.scuba"};
        java.util.logging.Level[] vecchi = new java.util.logging.Level[nomiLog.length];
        for (int i = 0; i < nomiLog.length; i++) {
            java.util.logging.Logger lg = java.util.logging.Logger.getLogger(nomiLog[i]);
            vecchi[i] = lg.getLevel();
            lg.setLevel(java.util.logging.Level.ALL);
            lg.addHandler(cap);
        }

        PACEInfo pace = null;
        List<String> apduPace = Collections.synchronizedList(new ArrayList<>());
        List<String> apduBac = Collections.synchronizedList(new ArrayList<>());
        boolean extSupported = false;
        try {
            CardService csPace = cardServiceConLog(reader, apduPace);
            PassportService ps = nuovoPassportService(csPace);
            ps.open();
            try { extSupported = csPace.isExtendedAPDULengthSupported(); } catch (Exception ignored) {}
            try {
                try {
                    CardAccessFile ca = new CardAccessFile(ps.getInputStream(PassportService.EF_CARD_ACCESS));
                    sb.append(",\"cardAccessHex\":").append(jstr(hex(ca.getEncoded())));
                    sb.append(",\"securityInfos\":[");
                    boolean first = true;
                    for (SecurityInfo si : ca.getSecurityInfos()) {
                        if (!first) sb.append(",");
                        first = false;
                        sb.append(securityInfoJson(si));
                        if (si instanceof PACEInfo && pace == null) pace = (PACEInfo) si;
                    }
                    sb.append("]");
                } catch (Exception ce) {
                    sb.append(",\"cardAccessErrore\":").append(jstr(String.valueOf(ce.getMessage())));
                }

                sb.append(",\"tentativoPACE\":");
                boolean haveKey = (can != null && !can.isBlank()) || mrz != null;
                if (pace == null) sb.append("{\"eseguito\":false,\"motivo\":\"nessun PACEInfo in EF.CardAccess\"}");
                else if (!haveKey) sb.append("{\"eseguito\":false,\"motivo\":\"nessuna chiave (CAN o MRZ)\"}");
                else {
                    try {
                        AlgorithmParameterSpec params = PACEInfo.toParameterSpec(pace.getParameterId());
                        PACEKeySpec ks = (can != null && !can.isBlank())
                                ? PACEKeySpec.createCANKey(can.trim())
                                : PACEKeySpec.createMRZKey(new BACKey(mrz.documentNumber, mrz.dateOfBirth, mrz.dateOfExpiry));
                        ps.doPACE(ks, pace.getObjectIdentifier(), params, pace.getParameterId());
                        sb.append("{\"eseguito\":true,\"ok\":true,\"chiave\":")
                                .append(jstr(can != null && !can.isBlank() ? "CAN" : "MRZ")).append("}");
                    } catch (Exception pe) {
                        sb.append("{\"eseguito\":true,\"ok\":false,\"sw\":").append(jstr(swDaMessaggio(pe)))
                                .append(",\"errore\":").append(jstr(String.valueOf(pe.getMessage()))).append("}");
                    }
                }
            } finally { try { ps.close(); } catch (Exception ignored) {} }
        } catch (Exception e) {
            sb.append(",\"sessioneErrore\":").append(jstr(String.valueOf(e.getMessage())));
        }
        sb.append(",\"extendedAPDUSupported\":").append(extSupported);

        sb.append(",\"tentativoBAC\":");
        if (mrz == null) sb.append("{\"eseguito\":false,\"motivo\":\"nessun dato MRZ\"}");
        else {
            try {
                PassportService ps2 = nuovoPassportService(cardServiceConLog(reader, apduBac));
                ps2.open();
                try {
                    ps2.sendSelectApplet(false);
                    ps2.doBAC(new BACKey(mrz.documentNumber, mrz.dateOfBirth, mrz.dateOfExpiry));
                    sb.append("{\"eseguito\":true,\"ok\":true}");
                } finally { try { ps2.close(); } catch (Exception ignored) {} }
            } catch (Exception be) {
                sb.append("{\"eseguito\":true,\"ok\":false,\"sw\":").append(jstr(swDaMessaggio(be)))
                        .append(",\"errore\":").append(jstr(String.valueOf(be.getMessage()))).append("}");
            }
        }

        for (int i = 0; i < nomiLog.length; i++) {
            java.util.logging.Logger lg = java.util.logging.Logger.getLogger(nomiLog[i]);
            lg.removeHandler(cap);
            lg.setLevel(vecchi[i]);
        }
        sb.append(",\"apduPace\":").append(jsonArr(apduPace));
        sb.append(",\"apduBac\":").append(jsonArr(apduBac));
        sb.append(",\"log\":[");
        List<String> righe = cap.ultime(150);
        for (int i = 0; i < righe.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(jstr(righe.get(i)));
        }
        sb.append("]");

        return sb.append("}").toString();
    }

    private static CardService cardServiceConLog(String reader, List<String> dest) throws CardLinkException {
        CardService cs = Platform.source().cardService(reader);
        cs.addAPDUListener(ev -> {
            net.sf.scuba.smartcards.CommandAPDU c = ev.getCommandAPDU();
            net.sf.scuba.smartcards.ResponseAPDU r = ev.getResponseAPDU();
            dest.add(">> " + (c != null ? hex(c.getBytes()) : ""));
            dest.add("<< " + (r != null ? hex(r.getBytes()) : ""));
        });
        return cs;
    }

    private static String securityInfoJson(SecurityInfo si) {
        StringBuilder b = new StringBuilder("{\"classe\":").append(jstr(si.getClass().getSimpleName()));
        b.append(",\"oid\":").append(jstr(si.getObjectIdentifier()));
        try { b.append(",\"protocollo\":").append(jstr(si.getProtocolOIDString())); } catch (Exception ignored) {}
        if (si instanceof PACEInfo) {
            PACEInfo p = (PACEInfo) si;
            String oid = p.getObjectIdentifier();
            b.append(",\"version\":").append(p.getVersion());
            try { b.append(",\"parameterId\":").append(p.getParameterId()); } catch (Exception ignored) {}
            try { b.append(",\"paramStandard\":").append(jstr(PACEInfo.toStandardizedParamIdString(p.getParameterId()))); } catch (Exception ignored) {}
            try { b.append(",\"mapping\":").append(jstr(String.valueOf(PACEInfo.toMappingType(oid)))); } catch (Exception ignored) {}
            try { b.append(",\"keyAgreement\":").append(jstr(PACEInfo.toKeyAgreementAlgorithm(oid))); } catch (Exception ignored) {}
            try { b.append(",\"cipher\":").append(jstr(PACEInfo.toCipherAlgorithm(oid))); } catch (Exception ignored) {}
            try { b.append(",\"digest\":").append(jstr(PACEInfo.toDigestAlgorithm(oid))); } catch (Exception ignored) {}
            try { b.append(",\"keyLength\":").append(PACEInfo.toKeyLength(oid)); } catch (Exception ignored) {}
        }
        return b.append("}").toString();
    }

    private static String swDaMessaggio(Throwable t) {
        String m = String.valueOf(t.getMessage());
        Matcher mm = Pattern.compile("0x([0-9A-Fa-f]{4})").matcher(m);
        return mm.find() ? mm.group(1).toUpperCase() : "";
    }

    static final class LogCattura extends java.util.logging.Handler {
        private final List<String> righe = Collections.synchronizedList(new ArrayList<>());
        @Override public void publish(java.util.logging.LogRecord r) {
            if (r == null || r.getMessage() == null) return;
            righe.add(r.getLoggerName() + ": " + r.getMessage());
        }
        @Override public void flush() {}
        @Override public void close() {}
        List<String> ultime(int n) {
            synchronized (righe) {
                int from = Math.max(0, righe.size() - n);
                return new ArrayList<>(righe.subList(from, righe.size()));
            }
        }
    }
}
