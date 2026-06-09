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
| POST   | /check             | X-API-Key   | Legge la carta. Body `{"can":"NNNNNN"}` (CAN solo per CIE) |
| GET    | /                  | no          | Pagina di test |

Esempio chiamata dal gestionale (browser):

```js
const r = await fetch('http://localhost:8765/check', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', 'X-API-Key': 'LA_TUA_API_KEY' },
  body: JSON.stringify({ can: '123456' })   // can vuoto/assente per la TS-CNS
});
const dati = await r.json();   // { tipo, cognome, nome, codiceFiscale, dataNascitaISO, ... }
```

## Configurazione

Via system property (`-Dnome=valore`) o variabile d'ambiente:

| Property | Env              | Default | Note |
|----------|------------------|---------|------|
| port     | IDENTITY_PORT    | 8765    | porta HTTP (solo 127.0.0.1) |
| apikey   | IDENTITY_APIKEY  | (auto)  | se assente ne genera una e la stampa all'avvio |
| origin   | IDENTITY_ORIGIN  | *       | origine CORS consentita |

## Build

```bash
mvn -U clean package
# -> target/cie-cns-wedge.jar (fat-jar)
```

Esecuzione semplice:

```bash
java --add-modules java.smartcardio -jar target/cie-cns-wedge.jar
```

## Eseguibile Windows con icona nel tray (jpackage)

`jpackage` e' incluso nel JDK. Crea un'app nativa con JRE incorporato (nessuna finestra
console, solo l'icona nel tray):

```bat
jpackage --type app-image ^
  --name Identity ^
  --input target ^
  --main-jar cie-cns-wedge.jar ^
  --main-class IdentityServer ^
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