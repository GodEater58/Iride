# Iride 0.4.2 — primo test reale

*13/10/2026*

Correzione del collegamento trovata con il primo test reale (Canon 6D via hotspot).

- La ricerca apriva una connessione di prova sulla porta 15740 e poi una seconda per collegarsi: le Canon accettano **un solo collegamento PTP/IP** e restavano bloccate. Ora la connessione trovata viene riusata direttamente (anche per Fujifilm, porta 55740)
- La ricerca si ferma appena trova una fotocamera PTP/IP, così la connessione non resta inattiva
- Con l'indirizzo IP inserito a mano ci si collega subito, senza connessioni di prova
- Messaggio e istruzioni: dopo un tentativo fallito spegnere e riaccendere il Wi-Fi della fotocamera
