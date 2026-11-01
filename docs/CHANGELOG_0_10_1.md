# Iride 0.10.1 — Collegamento Nikon

*01/11/2026*

Correzioni dopo la prova di un collega con una Nikon D7200 in modalità «Wi-Fi fotocamera»: il telefono si collegava e poi si scollegava. Non c'era il Registro, quindi le modifiche seguono quello che fanno due programmi che funzionano col Wi-Fi delle Nikon: [airmtp](https://github.com/shezi/airmtp) (dichiara D7200 e D750) e [NikonLink](https://github.com/AchooLuv/NikonLink) (Android, modelli Z e D).

Valgono solo con la marca **Nikon** scelta: Canon e le altre marche si collegano come prima.

- **Pausa dopo l'apertura della connessione.** NikonLink ha misurato che la fotocamera chiude il collegamento se il primo pacchetto arriva subito dopo l'apertura: ora Iride aspetta 350 ms, sia sul canale comandi sia su quello eventi
- **Prova sul canale eventi.** Secondo airmtp, senza una prova (Probe Request) sul canale eventi prima di aprire la sessione, la sessione si blocca; in Iride il blocco sarebbe finito con uno scollegamento dopo circa 60 secondi. Se la fotocamera non risponde alla prova, Iride aspetta un secondo, come fa airmtp
- **Identificativo dell'app Nikon.** Con la marca Nikon Iride si presenta con l'identificativo fisso usato da NikonLink (`00112233445566778899AABBCCDDEEFF`), che secondo NikonLink è quello annunciato dall'app Nikon; libgphoto2 e airmtp ne usano uno con lo stesso inizio
- **Caduta del Wi-Fi nel Registro.** Se è Android a chiudere il Wi-Fi della fotocamera, il Registro scrive «Android ha chiuso il Wi-Fi della fotocamera» e il collegamento si chiude subito, così parte la riconnessione invece di aspettare un minuto
- **Istruzioni Nikon** nella schermata di collegamento: di fabbrica la rete Nikon non ha password (la chiave va lasciata vuota), l'app Nikon va chiusa prima di collegarsi, e se il telefono si stacca dalla rete va disattivato il passaggio automatico ai dati mobili. In modalità Automatico un avviso consiglia di scegliere Nikon

Non provato su una Nikon vera: va verificato con la D7200.

## Fonti

- [airmtp – sequenza di collegamento e prova sul canale eventi](https://github.com/shezi/airmtp)
- [NikonLink – pausa dopo l'apertura e identificativo Nikon](https://github.com/AchooLuv/NikonLink)
- [libgphoto2 – ptpip.c](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptpip.c)
- [Manuale D7200 – rete senza password all'inizio](https://www.manualslib.com/manual/1071175/Nikon-D7200.html?page=276)
