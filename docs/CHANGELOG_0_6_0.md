# Iride 0.6.0 — live view rapido e cavo USB

*14/10/2026*

Live view con meno ritardo e collegamento col cavo USB come alternativa al Wi-Fi.

- **Cavo USB (OTG)**, facoltativo: riquadro «Oppure col cavo USB» nella schermata di collegamento, compare quando una fotocamera è collegata. Stessi driver PTP (Canon, Nikon, Sony; le altre come PTP generico), live view quasi istantaneo. Su Canon col cavo si usa l'uscita grande anche in «Bilanciato»
- **Indicatore sul mirino**: fotogrammi al secondo e ritardo medio (dalla richiesta al fotogramma pronto)
- Il fotogramma si ridisegna senza ricomporre il mirino e senza passare dal thread principale
- Durante il live view via Wi-Fi lo stato della fotocamera si legge ogni 6 s (prima 3,6 s); subito dopo uno scatto si legge comunque
- Il Wi-Fi a bassa latenza resta acceso solo quando serve (non col cavo)
