# Esempio Android — lettura carte via NFC, stessa REST del desktop

Modulo di **partenza** (non compilato dalla build Maven) che usa `cie-cns-wedge-core.jar` come
libreria: legge i documenti via **NFC IsoDep** ed espone in locale **gli stessi endpoint REST**, così
la web-app nel browser del tablet chiama `http://localhost:8765/...` esattamente come col server desktop.

## Come funziona

- `IsoDepLink` / `IsoDepCardService` incapsulano `android.nfc.tech.IsoDep` nel trasporto neutro
  (`CardLink` e il `CardService` scuba per jMRTD).
- `IsoDepCardSource` è la `CardSource` iniettata; l'`Activity` le passa il `Tag` a ogni tap.
- `LocalHttpServer` (NanoHTTPD) inoltra le richieste a `com.dadaops.cardreader.ApiRouter`.
- `MainActivity` fa il bootstrap: `Platform.boot(...)` + `Platform.setKeys(...)` + avvio server + reader mode NFC.

Flusso: l'utente avvicina il documento → `onTagDiscovered` salva il tag → il browser chiama
`POST http://localhost:8765/check` (o `/identify`) → la libreria legge il chip e risponde in JSON.

## Setup

1. Build del core: nella root del repo `mvn -q clean package`, poi copia
   `target/cie-cns-wedge-core.jar` in `android-sample/app/libs/`.
2. Apri `android-sample/` in Android Studio (aggiungi `gradlew`/wrapper o usa quello del progetto).
3. Imposta le **chiavi** (`Platform.setKeys`) **uguali** a quelle del backend/altri lettori, così i
   `tokenCarta` combaciano ovunque.
4. Installa su un tablet con NFC. Apri la web-app nel browser; punta le chiamate a `http://localhost:8765`.

## Note importanti

- **BouncyCastle**: Android include una versione ridotta di BC. Il sample fa
  `Security.removeProvider("BC")` + aggiunge il provider completo: necessario per PACE. Se hai
  conflitti di classi duplicate, valuta `bcprov-jdk15on` o lo shading del provider.
- **Lettori PACE-only (passaporto IT)**: richiedono le APDU estese; il controller NFC dei tablet
  moderni le supporta (`IsoDep.isExtendedLengthApduSupported()`), a differenza di molti lettori USB.
- **Foto (DG2) JPEG 2000**: con `ImageDecoder.PASSTHROUGH` l'immagine resta in JP2 (Android non la
  decodifica nativamente). Per avere il PNG: usa una libreria JP2, oppure decodifica lato client, o
  implementa un `ImageDecoder` Android e passalo a `Platform.boot(source, tuoDecoder)`.
- **Cleartext localhost**: `usesCleartextTraffic="true"` per consentire `http://localhost` dal browser.
- **CAN/MRZ**: la web-app li invia nel body come col desktop (`{"can":"123456"}` o
  `{"documentNumber","dateOfBirth","dateOfExpiry"}`).
