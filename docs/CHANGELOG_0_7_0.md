# Iride 0.7.0 — messa a fuoco sulle stelle

*14/10/2026*

Assistente di messa a fuoco sulle stelle.

- Tasto **«Stelle»** sul mirino (live view acceso): sotto il mirino compare il pannello dell'assistente
- **Misura della stella (HFR)** su ogni fotogramma, con valore migliore, grafico e cerchio sulla stella inseguita (verde quando sei al minimo). Funziona con tutte le marche
- **Canon**: tocchi una stella e la fotocamera ingrandisce 5× su quel punto (comandi Zoom 0x9158 e ZoomPosition 0x9159); poi l'app ricentra la stella se serve. Trascinando si sposta il riquadro. Ingranditi si usa l'uscita grande del live view
- **Passi di messa a fuoco** dal telefono, tre ampiezze per verso: Canon (DriveLens 0x9155, obiettivo su AF), Nikon (MfDrive 0x9204), Sony PC remoto (ManualFocusAdjust 0xD2D1, fotocamera in MF)
- **Nikon**: ingrandimento del live view (proprietà 0xD1A3: intero, medio, massimo)
- Codici verificati su libgphoto2. Le coordinate del riquadro Canon si basano sulla risoluzione del sensore: da verificare sulla 6D
