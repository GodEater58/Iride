# EosLink

App Android per il controllo remoto delle Canon EOS via Wi-Fi (PTP/IP), pensata come alternativa più completa a Camera Connect. Obiettivo principale: Canon 6D, da provare anche sulla 1300D.

## Cosa fa (versione 0.1)

| Funzione | Note |
|---|---|
| Connessione con **dati mobili sempre attivi** | Hotspot del telefono, stessa rete, oppure Wi-Fi della fotocamera tenuto "locale" all'app |
| Ricerca automatica della fotocamera | Scansione della porta 15740 sulla rete locale, oppure IP manuale |
| Scatto remoto | Con o senza autofocus |
| Live view | Anteprima JPEG dal mirino elettronico |
| Intervallometro | Numero scatti, pausa, posa Bulb in secondi (oltre i 30 s) |
| Galleria con miniature | Selezione rapida di tutti i RAW o tutti i JPG |
| Download dei **RAW originali (CR2)** | Camera Connect li converte in JPEG |
| Sottocartelle Light / Dark / Flat / Bias | File salvati in `Download/EosLink/<cartella>` |

## Installazione

Ogni push su `main` compila l'APK con GitHub Actions e lo pubblica nella pagina **Releases** del repository. Scaricalo dal telefono e installalo (va abilitata l'installazione da origini sconosciute). Gli aggiornamenti si installano sopra la versione precedente.

## Collegamento consigliato

1. Attiva l'hotspot del telefono.
2. Sulla fotocamera: Wi-Fi → Attiva → funzione Smartphone, e collegala alla rete dell'hotspot.
3. In EosLink scegli "Hotspot del telefono o stessa rete" e premi **Connetti**.
4. Se la fotocamera chiede di confermare il dispositivo «EosLink…», premi OK.

## Stato

Prototipo da collaudare sulla fotocamera. Il protocollo segue le specifiche PTP/IP e i comandi Canon EOS documentati da libgphoto2. Ogni passaggio viene registrato nella scheda **Registro**: in caso di problemi, il registro indica dove si blocca.

## Struttura

- `ptp/` — client PTP/IP e comandi Canon EOS
- `net/` — gestione rete (Wi-Fi locale all'app, scansione)
- `ui/` — interfaccia Jetpack Compose
