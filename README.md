# Servizio verifica identita CIE / TS-CNS

Servizio locale che legge la **Tessera Sanitaria (TS-CNS)** e la **Carta d'Identita
Elettronica (CIE)** e restituisce i dati anagrafici in JSON, pensato per girare sul PC
che esegue un gestionale frontend. Icona nel system tray su Windows; in background
(headless) su Linux/Raspberry.

## Endpoint

| Metodo | Path               | Auth        | Descrizione |
|--------|--------------------|-------------|-------------|
| GET    | /actuator/health   | no          | `{"status":"UP"}` per il monitoraggio |
| GET    | /actuator/status   | no          | `{"reader":..,"cardPresent":bool}` senza leggere i dati |
| POST   | /check             | X-API-Key   | Legge la carta. CIE: `{"can":"NNNNNN"}`. Passaporto/ID estero: `{"documentNumber","dateOfBirth","dateOfExpiry"}` (chiave MRZ). `foto:true` aggiunge la fotografia (DG2) |
| GET    | /identify          | X-API-Key   | **Preflight tornello**: senza CAN/MRZ ritorna `{tipo, tokenCarta, tokenCartaFonte}`. Funziona per carte di pagamento (PAN), fob NFC (UID) e TS-CNS (CF); per l'eID risponde `richiedeAutenticazione:true` |
| GET    | /                  | no          | Pagina di test |

Esempio chiamata dal gestionale (browser):

```js
const r = await fetch('http://localhost:8765/check', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', 'X-API-Key': 'LA_TUA_API_KEY' },
  body: JSON.stringify({ can: '123456', foto: true })   // can vuoto/assente per la TS-CNS
});
const dati = await r.json();   // { tipo, cognome, nome, codiceFiscale, dataNascitaISO, fotoBase64?, ... }
```

### Carte/documenti e chiave di accesso

| Documento | `tipo` | Chiave nel body |
|-----------|--------|-----------------|
| TS-CNS / CNS | `TS-CNS` | nessuna (lettura libera) |
| CIE | `CIE` | `can` (6 cifre stampate sulla carta) |
| Carta d'identità biometrica estera (es. francese CNIe) | `CARTA D'IDENTITA <PAESE>` | `can` **oppure** dati MRZ |
| Passaporto elettronico | `PASSAPORTO` | `documentNumber` + `dateOfBirth` + `dateOfExpiry` dalla MRZ |

Tutti i documenti con chip ICAO (CIE, passaporti, **carte d'identità biometriche di altri Stati**
— Francia, Germania, Spagna…) condividono lo stesso motore di lettura (DG1/DG11/DG12/DG2 via
PACE-CAN o PACE/BAC-MRZ). Il `tipo` viene dedotto dalla MRZ (Stato emittente + tipo documento):
la carta francese risulta `CARTA D'IDENTITA FRA`, con `statoEmissione` e `cittadinanza` valorizzati.
Il **codice fiscale calcolato** è prodotto solo per documenti di cittadini italiani.

Le date MRZ si accettano sia come `AAMMGG` sia come ISO `AAAA-MM-GG`. Per il passaporto il
servizio prova prima **PACE** (se la carta espone `EF.CardAccess`), altrimenti ripiega su
**BAC** (i dettagli del metodo/errore sono disponibili solo in modalità `debug`, non nel
flusso normale).

> **Lettore**: i passaporti **PACE-only con DH a 2048 bit** (es. quello italiano) inviano una
> APDU **a lunghezza estesa** (>255 byte). Serve un lettore che la supporti davvero (HID
> OMNIKEY 5022/5427, Identiv uTrust 3700F, Sony RC-S380, ACR1252U…). I lettori tipo **ACR122U
> non funzionano** (rispondono `0x6700` al map-nonce). La CIE usa ECDH (APDU corte) e va su
> qualsiasi lettore. Usa `"debug":true` per la diagnostica eMRTD (OID PACE, APDU, SW, ecc.).

### Dati estratti (schema uniforme)

Tutti i documenti restituiscono gli stessi campi canonici quando disponibili, nello stesso
ordine: `tipo`, `cognome`, `nome`, `nomeCompleto`, `codiceFiscale`, `sesso`, `dataNascita`,
`luogoNascita`, `cittadinanza`, `numeroDocumento`, `dataScadenza`, `dataEmissione`,
`autoritaEmittente`, `indirizzo`, più `comuneNascita`/`provinciaNascita`/`regione`/
`codiceCatastale` (da CF o da luogo di nascita), `chiaveAnagrafica`, e i campi specifici
(`telefono`, `professione`, `numeroPersonale`, …). I campi di **luogo** (`comuneNascita`,
`provinciaNascita`, `regione`, `luogoNascita`) e `tipo` sono sempre in **MAIUSCOLO**.

Per CIE/passaporto i dati arrivano, in **un'unica lettura**, da: **DG1** (MRZ), **DG11**
(anagrafica estesa: luogo di nascita, indirizzo, nome completo), **DG12** (autorità/data di
emissione), **DG2** (foto, con `foto:true`), **DG7** (firma autografa → `firmaBase64`, con
`foto:true`), **EF.COM** (`dataGroupPresenti`, `versioneLDS`), **DG14/DG15** (`chipAuthentication`,
`activeAuthentication` + `idChip`) ed **EF.SOD** (`idDocumento`, `algoritmoHashSOD`).
**DG3/DG4** (impronte/iride) **non sono leggibili**: richiedono EAC con certificato di Stato.

### Riconoscimento a letture successive (es. tornello del circolo)

**Non è possibile riconoscere il documento a un tap successivo senza autenticarsi** (senza
CAN/MRZ). I chip eMRTD (passaporto, CIE, carta francese) usano di proposito un **UID casuale**
a ogni lettura — è una misura anti-tracciamento. Lo si vede nei nostri test: `uidCarta` cambia
ogni volta (`08291250`, `086D7153`, `082D1147`…) e inizia sempre con `08` = UID randomizzato.
Prima di PACE/BAC nessun file è leggibile, e gli unici leggibili senza chiave (EF.CardAccess)
sono identici per tutti i documenti dello stesso tipo. Quindi: niente identificativo stabile
senza decifrare.

**Pattern consigliato (enrollment).** Alla **prima** lettura completa si ottengono identificatori
stabili e pseudonimi: `idDocumento` (HMAC della firma SOD, immutabile per quel documento),
`idChip` (hash della chiave di Active Authentication) e, per gli italiani, `chiaveAnagrafica`
(HMAC del CF). Si salva uno di questi come tessera del socio. Per il tornello, dato che il
documento non è ri-riconoscibile da solo, si **scrive quell'id su un tag/fob NFC con UID stabile**
(vedi l'endpoint `/write` e la "tessera socio"): il tornello legge il fob, non il documento.

### `tokenCarta` — identificatore uniforme per i cancelli

**Ogni** lettura (CIE, TS-CNS, passaporto, carta estera, carta di pagamento, fob NFC) espone un
campo **`tokenCarta`**: un HMAC stabile, pseudonimo e non reversibile (con `CRYPTO_KEY`), pensato
per agganciare qualunque carta come "tessera" di apertura. `tokenCartaFonte` dice da cosa è
derivato, in ordine di preferenza:

| Fonte | Documento | Stabile su |
|-------|-----------|------------|
| `cf` | CIE, TS-CNS | la **persona** (CIE e TS dello stesso titolare → stesso token) |
| `pan` | carta di pagamento | la carta |
| `sod` | passaporto | il documento |
| `numeroDocumento` | carta d'identità estera | il documento |
| `uid` | fob/tag NFC | il tag |

Enrollment: leggi una volta, salva `tokenCarta`; agli accessi successivi confronti il `tokenCarta`
riletto. Richiede una `CRYPTO_KEY` **stabile** in produzione (altrimenti i token cambiano a ogni
riavvio). Per le carte di pagamento basta il `tokenCarta` senza conservare il PAN.

### Documenti d'identità per paese (parser in `docs/`)

Tutti i documenti **ICAO eMRTD** sono leggibili: passaporti e carte d'identità di **USA, Russia,
Ucraina, Albania, tutta l'UE** e altri (DG1 MRZ + DG11 sono standard ICAO). Il package
**`com.dadaops.cardreader.docs`** identifica il documento e applica un parser per paese:

- **`Countries`** — ISO 3166-1 alpha-3 → nome Stato in italiano (`paesi.csv`) + appartenenza UE.
- **`DocumentRegistry`** — dalla MRZ ricava `tipo`, **`paese`**, **`unioneEuropea`**, `categoriaDocumento`.
- Parser per paese in sotto-package: `docs.it` (Italia/CF), `docs.ro` (**Romania**: decodifica il
  **CNP** → sesso + data di nascita), `docs.ua` (Ucraina, RNTRC), `docs.ru` (Russia), `docs.us` (USA),
  `docs.al` (Albania, NID), e **`docs.world.GenericParser`** per tutti gli altri.

Esempio: un passaporto russo esce come `tipo: "PASSAPORTO"`, `paese: "FEDERAZIONE RUSSA"`,
`unioneEuropea: false`, `nomiTraslitterati: true`; una carta rumena come `paese: "ROMANIA"`,
`unioneEuropea: true`, `cnp`, `sesso`, `dataNascita`.

### Carte di pagamento contactless (EMV)

Le carte di credito/debito contactless vengono riconosciute (`tipo: "Carta di credito"`) e lette
in sola lettura dei dati EMV **pubblici** (PPSE → SELECT AID → GPO → READ RECORD): `numeroCarta`
(PAN), `scadenza` (MM/AAAA), `circuito` (Visa/Mastercard/…), eventuale `titolare` (quasi sempre
**assente**, rimosso dal contactless), e un `tokenCarta` = HMAC del PAN per il matching senza
ri-esporre il numero. **Non** sono presenti né leggibili: **CVV2**, **PIN**, e i crittogrammi sono
dinamici → con questi dati **non si effettuano pagamenti**.

> ⚠️ **Attenzione legale.** Leggere il PAN in chiaro porta il sistema **sotto PCI-DSS** e, se
> usato per identificare persone, sotto il **GDPR** (dato finanziario, serve base giuridica e
> consenso). Per il solo riconoscimento "tessera" è preferibile usare il `tokenCarta` (HMAC) e non
> conservare mai il PAN. Le carte di pagamento **non** sono pensate come badge: meglio un fob NFC
> dedicato.

Se una carta **non espone il PAN** (es. Amex tokenizzata, wallet), il `tokenCarta` ripiega
sull'**UID** (`tokenCartaFonte: uid`). Ma molte carte di pagamento usano un **UID casuale**: in
quel caso viene impostato `uidCasuale: true` (e un `avviso` in `/identify`) perché il token **non
è stabile** tra un tap e l'altro — non utilizzabile per il riconoscimento. Verifica `uidCasuale`
prima di salvarlo come tessera.

**Luogo di nascita dal CF (CIE/TS-CNS).** Il codice catastale viene estratto dal codice fiscale
gestendo l'**omocodia** (le cifre sostituite da lettere vengono riconvertite). Se è un comune
italiano si valorizzano `comuneNascita`/`provinciaNascita`/`regione`; se è un **codice estero**
(`Z…`, nati all'estero) si valorizzano `natoEstero` e `statoNascita`, risolto via
`stati.csv` (lista dei codici Stato dell'Agenzia delle Entrate inclusa nelle risorse).

### Codice fiscale dal passaporto

Il codice fiscale **non è memorizzato sul passaporto italiano** (a differenza della CIE, dove è
il "numero personale" del DG11). In ogni caso il valore esce sempre nel campo `codiceFiscale`,
accompagnato dal booleano **`codiceFiscaleCertified`**:

- **`true`** — letto direttamente dal documento (CIE / TS-CNS).
- **`false`** — **calcolato** dai dati anagrafici (passaporto), quando il luogo di nascita (DG11)
  è risolvibile a un comune italiano. È una **stima** (`codiceFiscaleNota`): non risolve
  omocodia/omonimie e non vale per i nati all'estero.

### Fotografia (DG2)

Con `foto:true` il servizio legge il **DG2** (volto). Il dato sulla carta è in **JPEG 2000**
(`image/jp2`), che Chrome/Firefox non mostrano: il servizio prova a **convertirlo in PNG**
lato server (plugin `jai-imageio-jpeg2000`) e restituisce un data URL pronto per un `<img>`:

- `fotoBase64` — `data:image/png;base64,...` (o il blob originale se la conversione fallisce);
- `fotoFormato` — `png` oppure `originale`;
- `fotoMimeOriginale`, `fotoLarghezza`, `fotoAltezza` — metadati per debug.

## API / OpenAPI

Il contratto completo per il frontend è in [`openapi.yaml`](src/main/resources/openapi.yaml):

- **`GET /docs`** → **Swagger UI** già pronta nel browser (carica lo spec via CDN).
- **`GET /openapi.yaml`** → lo spec grezzo (per Postman o un generatore di client).

Entrambi senza auth. Esempio: `http://localhost:8765/docs`.

## Configurazione

Precedenza (dalla più alta): **system property `-D`** > **variabile d'ambiente** > **file
`identity.properties`** > default. Il file è incluso nel jar come default; per la produzione metti
una copia accanto al jar (o indica il path con `-Dconfig=/percorso/identity.properties` /
`IDENTITY_CONFIG`). Vedi [`identity.properties.example`](identity.properties.example).

| Property `-D` / chiave file | Env | Default | Note |
|---|---|---|---|
| `port` | `IDENTITY_PORT` | 8765 | porta HTTP (solo 127.0.0.1) |
| `apikey` | `IDENTITY_APIKEY` | (auto) | header `X-API-Key`; se assente ne genera una e la stampa |
| `signkey` | `IDENTITY_SIGNKEY` | (auto) | chiave del checksum HMAC delle risposte |
| `cryptokey` | `IDENTITY_CRYPTOKEY` | (auto) | chiave dei token pseudonimi (`tokenCarta`, `chiaveAnagrafica`) — **stabile e condivisa** tra i tornelli |
| `origin` | `IDENTITY_ORIGIN` | * | origine CORS consentita |
| `console.enabled` | `IDENTITY_CONSOLE` | true | abilita la pagina di test `/` e i dump `debug`. **In produzione: false** |
| `config` | `IDENTITY_CONFIG` | — | path di un file di configurazione esterno |

**Console di debug e protezione.** Con `console.enabled=false` la pagina `/` risponde **403** e il
parametro `debug` (dump APDU, PAN in chiaro) viene **ignorato**. Lo stato si commuta anche a runtime
dall'icona nel **system tray** (voce "Console di debug abilitata"). `GET /openapi.yaml` resta sempre
disponibile.

## Struttura del codice

Tutto nel package **`com.dadaops.cardreader`**, a classi piccole e riusabili:

- **`IdentityServer`** — entry point (`main`): bootstrap, avvio server + tray.
- **`AppContext`** — configurazione, chiavi, flag console, registri (comuni/stati), nonce.
- util: **`Hex`**, **`Json`**, **`Ber`** (BER-TLV), **`Text`** (date/stringhe).
- **`Signer`** — HMAC, `chiaveAnagrafica`/`tokenCarta`, nonce.
- identità: **`FiscalCode`** (CF + catastale), **`Territory`** (comune/Stato), **`DocumentBuilder`** (risposta uniforme + checksum).
- trasporto (SPI neutra): **`CardSource`**, **`CardLink`**, **`ReaderInfo`**, **`CardLinkException`**, **`ImageDecoder`**, **`Platform`** (iniezione).
- smartcard: **`Apdu`** (APDU su `CardLink`), **`Detector`** (tipo carta).
- reader: **`Mrz`**, **`TsCnsReader`**, **`EmrtdReader`** (CIE/passaporto/estere), **`EmvReader`**, **`NfcReader`**.
- **`CardDispatcher`** — smista la lettura; **`CardReaderApi`** (facade programmatica) e **`ApiRouter`** (routing REST neutro).
- desktop: **`PcscCardSource`**/**`PcscCardLink`** (PC/SC), **`DesktopImageDecoder`** (ImageIO), **`ApiServer`** (httpserver), **`TrayUi`** (tray), **`IdentityServer`** (`main`).

La pagina di test e la specifica sono risorse (`console.html`, `openapi.yaml`), servite a runtime.

### Trasporto astratto (stesso codice: server desktop *e* libreria Android)

La logica di lettura non dipende dalla piattaforma: usa solo le interfacce **`CardSource`** /
**`CardLink`** (trasporto carte) e **`ImageDecoder`** (conversione foto), iniettate via
**`Platform.configure(source, decoder)`**. Due implementazioni del trasporto:

- **Desktop** → `PcscCardSource` (PC/SC, `javax.smartcardio`) + `DesktopImageDecoder` (ImageIO).
- **Android** → un `CardSource` su **NFC `IsoDep`** (fornito dall'app) + `ImageDecoder.PASSTHROUGH`.

La superficie è doppia: **`CardReaderApi`** (chiamate programmatiche che ritornano JSON) e
**`ApiRouter`** (routing REST neutro), usati sia dal server desktop sia da un server HTTP nell'app
Android. Le classi desktop (`Pcsc*`, `DesktopImageDecoder`, `ApiServer`, `TrayUi`, `IdentityServer`)
sono **escluse** dal jar `cie-cns-wedge-core.jar` (vedi build), così l'artefatto per Android non
trascina `javax.smartcardio`/`AWT`/`httpserver`/`ImageIO` (assenti su Android).

## Uso come libreria in un progetto Android

Scenario: tablet con lettore NFC, web-app nel **browser** del tablet che recupera i dati dal lettore.
L'app Android espone localmente gli **stessi endpoint REST**, così il browser chiama
`http://localhost:8765/...` esattamente come col server desktop.

**1. Dipendenze** (Gradle): `cie-cns-wedge-core.jar` + jMRTD + scuba-smartcards (NON la variante
`*-j2se`) + BouncyCastle per Android (`bcprov-jdk15on` o Spongy Castle) + un server HTTP embedded
(es. NanoHTTPD).

**2. Adapter NFC** — incapsula `IsoDep` in `CardLink`/`CardSource` (e in un `CardService` scuba per
jMRTD). Schema:

```java
class IsoDepLink implements CardLink {
  private final IsoDep iso;
  IsoDepLink(Tag tag) throws IOException { iso = IsoDep.get(tag); iso.setTimeout(5000); iso.connect(); }
  public byte[] transmit(byte[] apdu) throws CardLinkException {
    try { return iso.transceive(apdu); } catch (IOException e) { throw new CardLinkException("nfc", e); }
  }
  public byte[] atr() { return iso.getHistoricalBytes() != null ? iso.getHistoricalBytes() : iso.getHiLayerResponse(); }
  public void disconnect(boolean reset) { try { iso.close(); } catch (IOException ignored) {} }
}
// CardSource: readers()=un solo "NFC"; connect()=new IsoDepLink(currentTag);
// cardService(): un net.sf.scuba.smartcards.CardService che delega a IsoDep (vedi sample jMRTD-Android).
```

**3. Avvio** nell'app (una volta, p.es. quando arriva un Tag NFC o all'onCreate):

```java
Platform.configure(myIsoDepCardSource, ImageDecoder.PASSTHROUGH);   // JP2->PNG opzionale su Android
// poi: server HTTP locale (NanoHTTPD) che inoltra ad ApiRouter:
ApiRouter.Response r = ApiRouter.route(method, path, query, apiKeyHeader, body);
// ...oppure chiamate dirette: CardReaderApi.check(can, null, true, null,null,null, false);
```

Note Android: il **CAN/MRZ** della CIE/passaporto si gestisce come sul desktop; la **foto** resta in
JPEG 2000 se non si fornisce un decoder (usa una libreria JP2 o decodifica lato client). Lettori
**USB CCID** (non NFC) richiedono un driver USB a parte: il modello a `CardSource` lo prevede, basta
un'altra implementazione del trasporto.

## Build

```bash
mvn -U clean package        # -> target/cie-cns-wedge.jar (fat-jar)
```

## Avvio — tre modalità

Lo stesso jar fa tre cose, scelte da un parametro:

```bash
java -jar cie-cns-wedge.jar            # 1) SERVER REST (default) su 127.0.0.1:8765
java -jar cie-cns-wedge.jar gui        # 2) GUI STANDALONE: finestra che legge e mostra tutti i
                                       #    campi in tabella, con copia-incolla (TSV/JSON/token)
./run.sh   / run.bat                   # script (Linux/macOS / Windows), accettano lo stesso parametro
```

3) **Libreria** per Android (jar `cie-cns-wedge-core.jar`) — vedi sezione Android sopra.

La GUI riusa la stessa logica del server (`CardReaderApi`): legge la carta, mostra i campi in una
tabella selezionabile (Ctrl+C o "Copia tabella TSV"), il JSON grezzo e la foto, più "Copia tokenCarta".

Su JDK 9–17 può servire `--add-modules java.smartcardio` (già incluso in `run.sh`/`run.bat`). Su
Linux servono `pcscd` + `libccid` attivi; su Windows il servizio Smart Card è già presente.

## Release automatica su GitHub (jar con chiavi incluse)

Il workflow [`.github/workflows/release.yml`](.github/workflows/release.yml) costruisce un
**fat-jar autopartente con la configurazione già dentro** (chiavi prese dai **Secret** del repo) e
lo pubblica come asset di una GitHub Release. Si attiva al push di un tag `v*` (es.
`git tag v1.0.0 && git push --tags`) o manualmente da *Actions → Run workflow*.

Imposta in *Settings → Secrets and variables → Actions*:

| Secret | Uso |
|--------|-----|
| `IDENTITY_APIKEY` | header `X-API-Key` |
| `IDENTITY_SIGNKEY` | checksum HMAC |
| `IDENTITY_CRYPTOKEY` | token pseudonimi (stabile e condivisa) |
| `IDENTITY_ORIGIN` / `IDENTITY_PORT` / `IDENTITY_CONSOLE` | opzionali (default `*` / `8765` / `false`) |

Scaricato il jar dalla Release, si avvia con `java -jar cie-cns-wedge-vX.Y.Z.jar` — niente file di
config né argomenti.

> ⚠️ **Un jar è uno zip**: chi lo possiede può estrarre le chiavi (`unzip -p ...jar
> identity.properties`). Usa questo workflow **solo con repo/release PRIVATI**. In alternativa
> *non* impostare i secret (il jar genera chiavi a runtime) e distribuisci un `identity.properties`
> esterno separatamente.

## Eseguibile Windows con icona nel tray (jpackage)

`jpackage` e' incluso nel JDK. Crea un'app nativa con JRE incorporato (nessuna finestra
console, solo l'icona nel tray):

```bat
jpackage --type app-image ^
  --name Identity ^
  --input target ^
  --main-jar cie-cns-wedge.jar ^
  --main-class com.dadaops.cardreader.IdentityServer ^
  --icon icon.ico ^
  --java-options "--add-modules java.smartcardio" ^
  --java-options "-Dapikey=LA_TUA_API_KEY"
```

Risultato: cartella `Identity/` con `Identity.exe`. Per un installer `.msi`/`.exe`
usa `--type msi` (richiede WiX Toolset installato). L'icona `icon.ico` la fornisci tu
(formato .ico); se la ometti viene usata quella di default.

Per l'avvio automatico al login: metti un collegamento a `Identity.exe` nella cartella
`shell:startup`.

## Servizio Linux / Raspberry

Vedi `deploy/identity.service`. In sintesi:

```bash
sudo apt install pcscd libccid    # supporto lettore
sudo systemctl enable --now pcscd
sudo mkdir -p /opt/identity && sudo cp target/cie-cns-wedge.jar /opt/identity/
sudo cp deploy/identity.service /etc/systemd/system/
# modifica API key e percorsi nel file, poi:
sudo systemctl daemon-reload
sudo systemctl enable --now identity
```

## Note di sicurezza (importante)

- Il servizio ascolta solo su `127.0.0.1`, quindi non e' raggiungibile dalla rete.
- L'API key in una pagina browser **non e' un vero segreto** (e' visibile nel JS lato
  client): serve a impedire chiamate banali da altri processi/pagine, non a proteggere
  da chi ispeziona il codice. Imposta comunque una chiave stabile e propria.
- Restringi `IDENTITY_ORIGIN` all'origine reale del gestionale invece di `*`, cosi' solo
  quella pagina puo' chiamare /check dal browser.
- Trattamento di dati personali (GDPR): assicurati di avere base giuridica e informativa;
  non registrare piu' dati del necessario.

## Carte supportate

- TS-CNS / CNS (inclusa ACE 2021): lettura libera del file Dati personali, senza CAN.
- CIE: lettura via PACE con il CAN (6 cifre sulla carta), dati da DG1 (MRZ) e DG11 (CF).
- Passaporto elettronico / ID estero (eMRTD): accesso via MRZ (PACE-MRZ con fallback BAC),
  dati da DG1 (MRZ TD3/TD1). Con `foto:true` anche il volto da DG2.
- Carte d'identità biometriche estere (es. CNIe francese): stesso motore eMRTD (CAN o MRZ).
- Carte di pagamento contactless (EMV): PAN + scadenza + circuito (sola lettura; no CVV2/PIN). Vedi
  l'avvertenza PCI-DSS sopra.