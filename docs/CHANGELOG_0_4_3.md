# Iride 0.4.3 — live view più fluido

*13/10/2026*

Live view più fluido (segnalato lento sulla Canon 6D).

- La scheda non viene più letta subito dopo il collegamento ma solo quando si apre la galleria: con migliaia di foto teneva occupato il canale per minuti
- Canale verso la fotocamera "equo": le richieste del live view non restano in coda dietro elenco file e miniature
- Live view in due fasi parallele (scaricamento e decodifica), si mostra sempre il fotogramma più recente
- Bitmap riusate a turno: meno lavoro per il telefono a ogni fotogramma
- Attesa tra un tentativo e l'altro ridotta da 100 a 15 ms quando la fotocamera non ha ancora un fotogramma pronto
