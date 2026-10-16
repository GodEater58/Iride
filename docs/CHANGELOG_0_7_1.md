# Iride 0.7.1 — revisione del codice

*14/10/2026*

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
