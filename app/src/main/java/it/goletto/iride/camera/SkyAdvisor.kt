package it.goletto.iride.camera

import kotlin.math.floor

/**
 * Impostazioni consigliate per la Via Lattea con camera fissa su treppiede.
 *
 * - Tempo: regola NPF semplificata, t = (35 × N + 30 × p) / f, con N diaframma, p passo dei
 *   pixel in µm e f focale in mm: stelle puntiformi anche ingrandendo (lightstalking.com/npf-rule).
 *   La regola del 500 (500 / focale equivalente) si mostra per confronto: è più permissiva.
 * - Diaframma tutto aperto; ISO 3200 intorno a f/1.4–f/2, 6400 da f/2.8 in su, non oltre;
 *   bilanciamento 3400–4400 K; RAW, stabilizzatore spento (guida Canon «Into the Night»).
 */
object SkyAdvisor {

    class Advice(
        val focal: Float,
        val aperture: Float,
        val npfSeconds: Float,
        val rule500Seconds: Float,
        val iso: Int,
        val kelvin: Int,
        val sensorEstimated: Boolean,
    )

    fun milkyWay(focal: Float, aperture: Float, sensor: SensorInfo?): Advice {
        val pitch = sensor?.pitchUm ?: 5f
        val npf = (35f * aperture + 30f * pitch) / focal
        val crop = sensor?.crop ?: 1f
        return Advice(
            focal = focal,
            aperture = aperture,
            npfSeconds = npf,
            rule500Seconds = 500f / (focal * crop),
            iso = if (aperture <= 2.0f) 3200 else 6400,
            kelvin = 3900,
            sensorEstimated = sensor == null,
        )
    }

    /** "1/125" → 0.008, 13" → 13, 0"8 → 0.8; null per Bulb, Auto o valori non numerici. */
    fun seconds(label: String): Double? {
        val l = label.trim()
        if (l.startsWith("1/")) return l.substring(2).toDoubleOrNull()?.let { 1.0 / it }
        val q = l.indexOf('"')
        if (q > 0) {
            val whole = l.substring(0, q).toDoubleOrNull() ?: return null
            val frac = l.substring(q + 1).toDoubleOrNull() ?: 0.0
            return whole + frac / 10.0
        }
        return l.removeSuffix("s").toDoubleOrNull()
    }

    /** "f/2.8" → 2.8 */
    fun aperture(label: String): Double? = label.trim().removePrefix("f/").removePrefix("F").toDoubleOrNull()

    /** "6400" → 6400; null per Auto */
    fun iso(label: String): Int? = label.trim().toIntOrNull()

    /** Secondi interi per la posa B (per esposizioni oltre i 30 s). */
    fun bulbSeconds(s: Float): Int = floor(s.toDouble()).toInt()
}
