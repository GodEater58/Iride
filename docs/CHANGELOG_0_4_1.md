# Iride 0.4.1 — correzioni

*13/10/2026*

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
