# Iride 0.8.1 — Nikon D7200

*16/10/2026*

Verifica per la Nikon D7200 (e le Nikon con Wi-Fi integrato).

- **Nikon: proprietà proprie della marca lette correttamente.** Le Nikon non elencano le loro proprietà (dimensione e ingrandimento del live view, focali e diaframma dell'obiettivo…) nelle informazioni del dispositivo: vanno chieste con GetVendorPropCodes (0x90CA), come fa libgphoto2. Prima Iride non le vedeva, quindi su D7200 mancavano ingrandimento, dimensione del live view e dati dell'obiettivo
- Nikon: modo «controllo da computer» (ChangeCameraMode 0x90C2) all'avvio, se la fotocamera lo prevede
- Nikon: se il live view è vietato, il Registro dice perché (ghiera non su P/A/S/M, tempo su posa B, scheda, batteria, temperatura…), dalla proprietà LiveViewProhibitCondition (0xD1A4); se è già acceso non viene riavviato
- Istruzioni di collegamento Nikon: le D7200/D750/D5300 creano la propria rete, quindi va usata la modalità «Wi-Fi fotocamera»
