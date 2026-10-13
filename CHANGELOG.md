# Registro modifiche

Ogni versione corrisponde a una build nella pagina Releases (l'APK si installa sopra la precedente).

## 0.3.0 (13/10/2026)

L'app si chiama ora **Iride** ed è aperta ad altre marche.

- Nuovo nome e nuovo identificativo: va installata come app nuova (disinstallare EosLink)
- Architettura a driver: un driver per marca dietro un'interfaccia comune
- Riconoscimento automatico della marca dopo il collegamento (Canon, Nikon, Sony)
- Driver **Nikon** (PTP/IP): scatto su scheda con o senza AF, posa B, live view, esposizione standard
- Driver **Sony** PC remoto (PTP/IP, a7 III / a7 IV e successive): scatto, posa B, live view, esposizione; le foto vengono scaricate subito sul telefono
- Driver **generico PTP** per le altre marche: scatto, file e download
- Interfaccia che si adatta alla fotocamera: disattiva live view, posa B o autofocus se non disponibili
- Scelta della marca nella schermata di collegamento, con istruzioni dedicate

Da verificare sulle fotocamere: tutti i driver nuovi (scritti seguendo libgphoto2, senza prove reali).

## 0.2.0 — build 3 (13/10/2026)

Nuova interfaccia.

- Tema grafite con valori in ambra, ispirato al display superiore delle Canon
- Modalità notte rosso su nero per l'astrofotografia (icona luna)
- Diaframma animato nella schermata di collegamento e come pulsante di scatto, con vibrazione
- Mirino con squadrette, punto AF, griglia dei terzi, pizzica per zoom e doppio tocco 4×
- Pannello esposizione: modo, tempo, diaframma, ISO e scatti rimasti letti dalla fotocamera
- Ghiera a scatti per cambiare tempo, diaframma e ISO
- Intervallometro con preset, tasti − / +, durata stimata e anello di avanzamento sul pulsante
- Galleria raggruppata per giorno, filtro Tutti/RAW/JPG, anteprima, selezione multipla con pressione lunga
- Barra di download con percentuale e cartelle Light/Dark/Flat/Bias
- Registro con errori in rosso e tasto Copia
- Font Barlow e Barlow Condensed (licenza OFL in `licenses/`)
- CI: gli errori di compilazione compaiono come annotazioni della build

Da verificare sulla fotocamera: lettura e modifica di Tv/Av/ISO.

## 0.1.0 — build 2 (13/10/2026)

Prima versione.

- Client PTP/IP con comandi Canon EOS
- Collegamento via hotspot del telefono o Wi-Fi della fotocamera, con dati mobili sempre attivi
- Ricerca automatica della fotocamera sulla porta 15740
- Scatto remoto con o senza autofocus, live view
- Intervallometro con posa Bulb
- Galleria con miniature e download dei RAW originali (CR2)
- Build automatica dell'APK con GitHub Actions
