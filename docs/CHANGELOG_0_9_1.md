# Iride 0.9.1 — intervallometro a schermo spento

*16/10/2026*

Seconda revisione del codice.

- **Intervallometro e download a schermo spento**: ora girano in un servizio in primo piano con notifica («Intervallometro: scatto 5 di 20»). Prima, con il telefono fermo e lo schermo spento (il caso tipico dell'astrofotografia), Android poteva mettere l'app in pausa e spegnere la rete a metà sequenza. Il servizio tiene sveglia CPU e Wi-Fi e si ferma da solo alla fine
- **Live view sospeso quando esci dall'app** (tasto Home, schermo spento) e ripreso al ritorno: prima continuava a scaricare fotogrammi in sottofondo, consumando la batteria del telefono e della fotocamera. Durante una sequenza resta com'è
- Canon: le uscite del live view che non mandano fotogrammi si scartano dopo 2,5 s (prima 3 s) e l'app aspetta fino a 20 s prima di dichiarare il live view interrotto, così c'è il tempo di provarle tutte
- Permesso per le notifiche chiesto all'avvio (Android 13 e successivi)
