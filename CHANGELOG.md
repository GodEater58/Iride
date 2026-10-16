# Registro modifiche

Ogni versione corrisponde a una build nella pagina Releases (l'APK si installa sopra la precedente).

## 0.8.1 (16/10/2026)

Verifica per la Nikon D7200 (e le Nikon con Wi-Fi integrato).

- **Nikon: proprietà proprie della marca lette correttamente.** Le Nikon non elencano le loro proprietà (dimensione e ingrandimento del live view, focali e diaframma dell'obiettivo…) nelle informazioni del dispositivo: vanno chieste con GetVendorPropCodes (0x90CA), come fa libgphoto2. Prima Iride non le vedeva, quindi su D7200 mancavano ingrandimento, dimensione del live view e dati dell'obiettivo
- Nikon: modo «controllo da computer» (ChangeCameraMode 0x90C2) all'avvio, se la fotocamera lo prevede
- Nikon: se il live view è vietato, il Registro dice perché (ghiera non su P/A/S/M, tempo su posa B, scheda, batteria, temperatura…), dalla proprietà LiveViewProhibitCondition (0xD1A4); se è già acceso non viene riavviato
- Istruzioni di collegamento Nikon: le D7200/D750/D5300 creano la propria rete, quindi va usata la modalità «Wi-Fi fotocamera»

## 0.8.0 (14/10/2026)

Prova sul campo col Wi-Fi della 6D e consigli per la Via Lattea.

- **Live view Fluido/Bilanciato col Wi-Fi della fotocamera**: sulla 6D l'uscita per smartphone (0x4) non dava fotogrammi e il live view si fermava dopo 15 s. Ora, se un'uscita non manda fotogrammi entro 3 s, Iride passa alla successiva (fino a quella per PC) e la ricorda per la sessione; nel Registro compaiono le uscite ammesse e quella scelta
- **Preset dell'intervallometro**: quello scelto è evidenziato (ambra con spunta) con una riga che lo descrive. «Stelle 30 s» diventa **«Via Lattea»**, «Deep sky» precisa che serve un inseguitore, «Dark frame» mantiene la posa delle foto
- **Consigli per la Via Lattea** in base all'obiettivo montato: Iride legge il nome dell'obiettivo (Canon, proprietà 0xD1D8) o focali e diaframma (Nikon, 0xD0E3–0xD0E5 e 0x5008). Tempo massimo con la regola NPF (35×N + 30×p)/f usando il passo dei pixel del sensore, confronto con la regola del 500, ISO 3200–6400 e bianco 3400–4400 K come nella guida Canon. Per gli zoom si sceglie la focale. Tasto «Applica alla fotocamera» (diaframma tutto aperto, tempo, ISO; serve il modo M)
- Corretto il nome doppio «Canon Canon EOS 6D»

## 0.7.1 (14/10/2026)

Revisione del codice: bug corretti e ottimizzazioni.

**Bug**
- Dopo un errore di rete a metà di un comando (es. live view interrotto) il collegamento restava aperto ma "disallineato": i comandi successivi leggevano risposte sbagliate. Ora si chiude subito e l'app segnala la connessione persa (Wi-Fi, USB e Fujifilm)
- Olympus e Panasonic: se l'avvio del live view falliva, la porta UDP restava occupata e i tentativi successivi non funzionavano
- Olympus e Panasonic: con la rete caduta il thread del live view poteva girare a vuoto consumando CPU

**Ottimizzazioni**
- Galleria: con migliaia di foto le miniature potevano esaurire la memoria. Ora al massimo 400 in memoria, in formato più leggero (metà memoria), ricaricate quando servono
- Galleria: le miniature dei riquadri già usciti dallo schermo non vengono più richieste (prima intasavano il canale e rallentavano il live view); una richiesta alla volta
- Galleria: raggruppamento per giorno e conteggi ricalcolati solo quando cambiano i file, non a ogni selezione
- Animazioni (pallino di stato, diaframma della schermata iniziale, anelli dei tasti) attive solo quando servono: prima ridisegnavano lo schermo di continuo anche da ferme, consumando batteria e CPU tolta al live view

## 0.7.0 (14/10/2026)

Assistente di messa a fuoco sulle stelle.

- Tasto **«Stelle»** sul mirino (live view acceso): sotto il mirino compare il pannello dell'assistente
- **Misura della stella (HFR)** su ogni fotogramma, con valore migliore, grafico e cerchio sulla stella inseguita (verde quando sei al minimo). Funziona con tutte le marche
- **Canon**: tocchi una stella e la fotocamera ingrandisce 5× su quel punto (comandi Zoom 0x9158 e ZoomPosition 0x9159); poi l'app ricentra la stella se serve. Trascinando si sposta il riquadro. Ingranditi si usa l'uscita grande del live view
- **Passi di messa a fuoco** dal telefono, tre ampiezze per verso: Canon (DriveLens 0x9155, obiettivo su AF), Nikon (MfDrive 0x9204), Sony PC remoto (ManualFocusAdjust 0xD2D1, fotocamera in MF)
- **Nikon**: ingrandimento del live view (proprietà 0xD1A3: intero, medio, massimo)
- Codici verificati su libgphoto2. Le coordinate del riquadro Canon si basano sulla risoluzione del sensore: da verificare sulla 6D

## 0.6.0 (14/10/2026)

Live view con meno ritardo e collegamento col cavo USB come alternativa al Wi-Fi.

- **Cavo USB (OTG)**, facoltativo: riquadro «Oppure col cavo USB» nella schermata di collegamento, compare quando una fotocamera è collegata. Stessi driver PTP (Canon, Nikon, Sony; le altre come PTP generico), live view quasi istantaneo. Su Canon col cavo si usa l'uscita grande anche in «Bilanciato»
- **Indicatore sul mirino**: fotogrammi al secondo e ritardo medio (dalla richiesta al fotogramma pronto)
- Il fotogramma si ridisegna senza ricomporre il mirino e senza passare dal thread principale
- Durante il live view via Wi-Fi lo stato della fotocamera si legge ogni 6 s (prima 3,6 s); subito dopo uno scatto si legge comunque
- Il Wi-Fi a bassa latenza resta acceso solo quando serve (non col cavo)

## 0.5.1 (13/10/2026)

Verifica delle altre marche alla luce dei problemi trovati sulla Canon 6D.

- Automatico: la porta Fujifilm (55740) viene provata prima della 15740 standard, così una Fujifilm non resta occupata da un collegamento PTP/IP sbagliato
- Nikon: dimensione del live view (Fluido/Bilanciato/Dettaglio) sui modelli che la supportano (proprietà 0xD1AC, es. D850)
- Olympus: scattando con il live view acceso, tutta la scheda risultava "nuova" in galleria
- Verificati contro libgphoto2 tutti i codici Canon usati (comandi, eventi, proprietà)

## 0.5.0 (13/10/2026)

Live view ancora più fluido e tasto per mettere a fuoco.

- **Dimensione del live view**: Fluido / Bilanciato / Dettaglio. Su Canon si usano le uscite per smartphone (media e piccola) invece di quella grande per PC: fotogrammi molto più leggeri via Wi-Fi. Anche Sony (API remota) e Olympus
- Corretto: il live view Canon impostava la nitidezza (0xD1B3) invece del modo EVF (0xD1B1)
- **Tasto «Metti a fuoco»** accanto allo scatto e pulsante «AF» sul mirino: Canon (DoAf), Nikon (AfDrive), Sony (mezza pressione); anello animato e verde/rosso in base all'esito
- Interruttore «Autofocus allo scatto» separato dal tasto di messa a fuoco
- Wi-Fi a bassa latenza di Android attivo durante il live view
- Durante il live view lo stato della fotocamera si legge ogni 3,6 s invece di 1,2 s
- Buffer di ricezione TCP più ampio

## 0.4.3 (13/10/2026)

Live view più fluido (segnalato lento sulla Canon 6D).

- La scheda non viene più letta subito dopo il collegamento ma solo quando si apre la galleria: con migliaia di foto teneva occupato il canale per minuti
- Canale verso la fotocamera "equo": le richieste del live view non restano in coda dietro elenco file e miniature
- Live view in due fasi parallele (scaricamento e decodifica), si mostra sempre il fotogramma più recente
- Bitmap riusate a turno: meno lavoro per il telefono a ogni fotogramma
- Attesa tra un tentativo e l'altro ridotta da 100 a 15 ms quando la fotocamera non ha ancora un fotogramma pronto

## 0.4.2 (13/10/2026)

Correzione del collegamento trovata con il primo test reale (Canon 6D via hotspot).

- La ricerca apriva una connessione di prova sulla porta 15740 e poi una seconda per collegarsi: le Canon accettano **un solo collegamento PTP/IP** e restavano bloccate. Ora la connessione trovata viene riusata direttamente (anche per Fujifilm, porta 55740)
- La ricerca si ferma appena trova una fotocamera PTP/IP, così la connessione non resta inattiva
- Con l'indirizzo IP inserito a mano ci si collega subito, senza connessioni di prova
- Messaggio e istruzioni: dopo un tentativo fallito spegnere e riaccendere il Wi-Fi della fotocamera

## 0.4.1 (13/10/2026)

Correzioni dopo una revisione del codice.

- Annullando il collegamento, la sessione aperta nel frattempo ora viene chiusa (prima restava appesa e la fotocamera rifiutava il collegamento successivo)
- Scollegandosi durante un download la chiusura non resta più bloccata fino a 60 secondi
- Il collegamento al Wi-Fi della fotocamera si arrende dopo 60 secondi invece di attendere all'infinito
- La ghiera dei parametri non cambia più il valore da sola all'apertura
- Nikon: dopo la posa B viene ripristinato il tempo di scatto precedente
- Intervallometro: la posa B salvata viene ignorata sulle fotocamere che non la supportano; un errore vero ferma la sequenza dopo 20 secondi invece di 2 minuti
- Galleria: i file ricevuti al volo (Sony, Fujifilm) non spariscono con «Aggiorna» e non si riscaricano due volte
- Sony meno recenti: ogni scatto compare anche se la fotocamera riusa lo stesso indirizzo di anteprima
- Fujifilm: in memoria restano solo le ultime 30 anteprime
- Download e lettura della scheda vengono interrotti correttamente allo scollegamento

## 0.4.0 (13/10/2026)

Tutte le marche principali e controllo completo dei parametri di scatto.

- **Parametri di scatto dal telefono**: tempo, diaframma, ISO, compensazione dell'esposizione, bilanciamento del bianco e modo di scatto (quando la fotocamera lo consente da remoto)
- Pannello esposizione su due righe: Tempo, Diaframma, ISO, EV grandi; Modo, Bilanciamento del bianco, Scatti liberi sotto
- Ghiera per i valori numerici, elenco a riquadri per bilanciamento del bianco e modo
- Driver **Sony meno recenti** (API remota HTTP, a7 II, a6000…): scatto, posa B, live view, parametri; arriva l'anteprima di ogni scatto
- Driver **Fujifilm** (protocollo remoto Fuji, porte 55740-55742): scatto con anteprima, live view, diaframma, ISO, EV e bilanciamento del bianco
- Driver **Olympus / OM System** (protocollo OI.Share): scatto, live view RTP, parametri, galleria e download
- Driver **Panasonic Lumix** (cam.cgi): scatto e live view
- Riconoscimento automatico esteso a tutte le marche (porte 15740, 55740, 8080, 10000, 80)

Da verificare sulle fotocamere: tutti i driver diversi da Canon.

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
