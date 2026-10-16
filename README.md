# Iride

App Android per il controllo remoto delle fotocamere via Wi-Fi, alternativa più completa alle app dei produttori (Camera Connect, SnapBridge, Imaging Edge…). Versione attuale: **0.8.1**.

## Marche supportate

| Marca | Protocollo | Scatto | Parametri | Live view | Dimensione LV | Messa a fuoco | Posa B | Galleria |
|---|---|---|---|---|---|---|---|---|
| Canon EOS | PTP/IP | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ RAW originali |
| Nikon | PTP/IP | ✅ | ✅ | ✅ | modelli recenti | ✅ | ✅ (modo M) | ✅ |
| Sony a7 III / a7 IV e successive | PTP/IP (PC remoto) | ✅ | ✅ | ✅ | – | ✅ | ✅ | foto scaricate subito |
| Sony meno recenti | API remota HTTP | ✅ | ✅ | ✅ | ✅ | ✅ | se supportata | anteprima |
| Fujifilm | PTP/IP Fuji | ✅ | ✅ (no tempo) | ✅ | – | – | – | anteprima |
| Olympus / OM System | HTTP (OI.Share) | ✅ | ✅ | ✅ | ✅ | – | – | ✅ |
| Panasonic Lumix | HTTP (cam.cgi) | ✅ | – | ✅ | – | – | – | – |
| Altre PTP/IP | PTP standard | ✅ | se esposti | – | – | – | – | ✅ |

**Collaudo:** solo la Canon 6D è stata provata su una fotocamera reale (collegamento via hotspot, live view). Gli altri driver seguono libgphoto2 e altri progetti open source e vanno collaudati.

## Cosa fa

| Funzione | Note |
|---|---|
| Collegamento con **dati mobili sempre attivi** | Hotspot del telefono, stessa rete, oppure Wi-Fi della fotocamera tenuto "locale" all'app |
| Cavo USB (facoltativo) | Con un adattatore OTG: live view quasi istantaneo, stessi comandi del Wi-Fi (Canon, Nikon, Sony; altre come PTP generico) |
| Ricerca automatica | Cerca le porte tipiche della marca (15740, 55740, 8080, 10000, 80) e riusa la connessione trovata; oppure IP manuale |
| Parametri di scatto | Tempo, diaframma, ISO, compensazione, bilanciamento del bianco e modo, modificabili dal telefono quando la fotocamera lo consente |
| Scatto remoto | Pulsante a diaframma animato, autofocus allo scatto attivabile |
| Messa a fuoco | Tasto «Metti a fuoco» e pulsante «AF» sul mirino, con esito verde/rosso |
| Messa a fuoco sulle stelle | Tasto «Stelle»: misura HFR con grafico, zoom 5×/10× della fotocamera sul punto toccato (Canon), passi di messa a fuoco dal telefono (Canon, Nikon, Sony) |
| Live view | Fluido / Bilanciato / Dettaglio, indicatore fps e ritardo, griglia dei terzi, pizzica per zoom, doppio tocco 4× |
| Intervallometro | Preset (Via Lattea, Deep sky, Timelapse, Dark frame), numero scatti, pausa, posa B oltre i 30 s, durata stimata |
| Consigli Via Lattea | Dall'obiettivo montato: tempo massimo con la regola NPF, diaframma, ISO e bianco; si applicano alla fotocamera con un tasto |
| Galleria | Letta all'apertura, raggruppata per giorno, filtro RAW/JPG, anteprima, selezione multipla |
| Download dei **RAW originali** | Camera Connect li converte in JPEG |
| Sottocartelle Light / Dark / Flat / Bias | File salvati in `Download/Iride/<cartella>` |
| Modalità notte | Rosso su nero per non perdere l'adattamento al buio |
| Registro | Ogni passaggio del collegamento, errori in rosso, tasto Copia |

Lo storico delle versioni è in [CHANGELOG.md](CHANGELOG.md).

## Installazione

Ogni push su `main` compila l'APK con GitHub Actions e lo pubblica nella pagina **Releases**. Scaricalo dal telefono e installalo (va abilitata l'installazione da origini sconosciute). Gli aggiornamenti si installano sopra la versione precedente.

Chi aveva la vecchia **EosLink** deve disinstallarla: Iride ha un identificativo diverso.

## Collegamento consigliato (Canon)

1. Attiva l'hotspot del telefono.
2. Sulla fotocamera: Wi-Fi → Attiva → funzione Smartphone, e collegala alla rete dell'hotspot.
3. In Iride scegli la marca **Canon**, modalità "Hotspot", e premi **Collega**.
4. Se la fotocamera chiede di confermare «Iride», premi OK.

Le istruzioni per le altre marche sono nella schermata di collegamento («Come preparare la fotocamera»).

## Cose da sapere

- **Un solo collegamento alla volta.** Le fotocamere PTP/IP (Canon, Nikon, Sony, Fujifilm) accettano una sola connessione. Se un tentativo fallisce, spegni e riaccendi il Wi-Fi della fotocamera prima di riprovare.
- **Wi-Fi della fotocamera lento.** Sulla 6D un RAW può richiedere fino a 20 secondi. Per il live view usa «Fluido» per inquadrare e «Dettaglio» per la messa a fuoco manuale.
- **Cavo USB.** Serve un adattatore OTG (USB-C → USB-A o il cavo della fotocamera). Il collegamento USB non è ancora stato provato su una fotocamera reale.
- **Sony a7 IV e successive:** disattiva l'autenticazione di accesso nel menu Rete (non supportata).
- **Nikon con Wi-Fi integrato (D7200, D750, D5300…):** la fotocamera crea la sua rete, usa «Wi-Fi fotocamera». Per il live view ghiera su P/A/S/M e tempo diverso da posa B.
- **Nikon Z e modelli recenti con SnapBridge:** l'abbinamento via Bluetooth potrebbe impedire il collegamento (non verificato).
- **Fujifilm:** la dimensione del live view non è regolabile, perché le fonti non concordano sul codice da usare.

## Struttura del codice

- `camera/` — modello comune (`CameraDriver`, esposizione, file) e `DriverFactory` (ricerca e riconoscimento della marca)
- `ptp/` — canale PTP (`PtpTransport`: Wi-Fi `PtpIpClient`, cavo `PtpUsbClient`) e driver Canon, Nikon, Sony (PC remoto), Fujifilm, PTP generico
- `http/` — driver Sony (API remota), Olympus, Panasonic
- `net/` — rete: Wi-Fi della fotocamera "locale" all'app, ricerca, HTTP/UDP instradati, Wi-Fi a bassa latenza; `UsbLink` per il cavo USB
- `ui/` — interfaccia Jetpack Compose (`theme/` colori e font Barlow, `Iris.kt` diaframma, una schermata per file)
- `licenses/` — licenza OFL dei font

## Fonti

- [libgphoto2](https://github.com/gphoto/libgphoto2) — comandi PTP Canon, Nikon, Sony
- [malc0mn/ptp-ip](https://github.com/malc0mn/ptp-ip) — protocollo Fujifilm
- [olympus-wifi](https://github.com/joergmlpts/olympus-wifi) — protocollo Olympus
- [pysony](https://github.com/Bloodevil/sony_camera_api) — API remota Sony
- [node-lumix](https://github.com/toddtreece/node-lumix) — protocollo Panasonic
