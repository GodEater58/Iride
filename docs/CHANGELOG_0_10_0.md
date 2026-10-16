# Iride 0.10.0 — strumenti di scatto e riconnessione automatica

*16/10/2026*

Nuovi aiuti sul mirino, bracketing, più strumenti per le sessioni di astrofotografia e una connessione che si riprende da sola.

**Scatto**
- **Barra degli aiuti sotto il mirino**: Griglia, Istogramma e Peaking si accendono e spengono con un tocco (prima la griglia era un tasto sul mirino). Le scelte restano salvate
- **Istogramma live** in basso a destra sul mirino, con scala a radice per vedere anche le zone con pochi pixel. Una barra rossa sul bordo destro avvisa che parte dell'immagine è bruciata, sul sinistro che è nera piena
- **Focus peaking**: i contorni a fuoco si colorano (azzurro, rosa chiaro in modalità notte). Si evidenziano solo i bordi più netti e sopra una soglia minima: con tutto sfocato non si accende quasi nulla. Calcolato su una copia ridotta del fotogramma, uno su due, per non rallentare il live view
- **Bracketing** 3, 5 o 7 scatti a 1 o 2 EV di distanza, nell'ordine normale, più scuro, più chiaro. In modo M cambia il tempo (diaframma e ISO fissi), negli altri modi la compensazione. Autofocus solo sul primo scatto; alla fine torna il valore di partenza

**Astrofotografia**
- **Preset Flat e Bias** accanto a Via Lattea, Deep sky, Timelapse e Dark frame
- **Cartella di download dal preset**: Via Lattea e Deep sky → `Light`, Dark frame → `Dark`, Flat → `Flat`, Bias → `Bias`. La cartella scelta è indicata sotto il preset, pronta per lo stacking
- **Ritardo prima della sequenza**: subito, 10 s, 1, 10 o 30 minuti (es. per aspettare la fine del crepuscolo)
- **Durata stimata** più precisa: senza posa B usa il tempo impostato sulla fotocamera e include il ritardo
- **Avviso a fine sequenza** con suono e vibrazione quando il telefono è in tasca o a schermo spento (canale di notifica «Fine sequenza e problemi», silenziabile a parte); con l'app aperta, doppia vibrazione
- **Live view e miniature rossi in modalità notte**: prima il fotogramma restava a colori e faceva perdere l'adattamento al buio

**Stabilità**
- **Riconnessione automatica**: se la connessione cade, Iride riprova da sola 3 volte (dopo 2, 6 e 15 s; l'ultimo tentativo usa l'indirizzo della schermata di collegamento o, se è vuoto, la ricerca automatica). Il servizio in primo piano resta acceso, così funziona anche a schermo spento. «Annulla» ferma i tentativi
- **Ripresa della sequenza**: se la connessione cade durante l'intervallometro, dopo la riconnessione la sequenza riparte dallo scatto interrotto (quello in corso al momento della caduta va perso). Se la fotocamera rifiuta lo scatto (scheda piena, messa a fuoco…) la sequenza si ferma come prima, senza riconnettersi
- Una sequenza vecchia che termina in ritardo non modifica più lo stato di quella nuova

**Usabilità**
- **Doppio tocco per interrompere la sequenza**: il primo tocco arma il tasto (diventa rosso), il secondo entro 3 s ferma. Un tocco sbagliato al buio non fa più perdere la serata

> La riconnessione non è ancora stata provata su una fotocamera reale: va verificata sul campo con la 6D. Se la fotocamera accetta un solo collegamento e non chiude la vecchia sessione, può servire spegnere e riaccendere il suo Wi-Fi.
