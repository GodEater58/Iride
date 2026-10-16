# Iride 0.5.1 — verifica delle altre marche

*13/10/2026*

Verifica delle altre marche alla luce dei problemi trovati sulla Canon 6D.

- Automatico: la porta Fujifilm (55740) viene provata prima della 15740 standard, così una Fujifilm non resta occupata da un collegamento PTP/IP sbagliato
- Nikon: dimensione del live view (Fluido/Bilanciato/Dettaglio) sui modelli che la supportano (proprietà 0xD1AC, es. D850)
- Olympus: scattando con il live view acceso, tutta la scheda risultava "nuova" in galleria
- Verificati contro libgphoto2 tutti i codici Canon usati (comandi, eventi, proprietà)
