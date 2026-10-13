package it.goletto.eoslink.ptp

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** Proprietà di esposizione Canon EOS (codici da libgphoto2) e conversione in valori leggibili. */
object Expo {
    const val AV = 0xD101
    const val TV = 0xD102
    const val ISO = 0xD103
    const val MODE = 0xD105
    const val SHOTS = 0xD11B

    private val isoNominal = intArrayOf(
        50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600,
        2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600,
        32000, 40000, 51200, 102400, 204800,
    )
    private val avNominal = doubleArrayOf(
        1.0, 1.1, 1.2, 1.4, 1.6, 1.8, 2.0, 2.2, 2.5, 2.8, 3.2, 3.5, 4.0, 4.5, 5.0, 5.6,
        6.3, 7.1, 8.0, 9.0, 10.0, 11.0, 13.0, 14.0, 16.0, 18.0, 20.0, 22.0, 25.0, 29.0,
        32.0, 36.0, 40.0, 45.0,
    )
    private val tvSeconds = doubleArrayOf(
        30.0, 25.0, 20.0, 15.0, 13.0, 10.0, 8.0, 6.0, 5.0, 4.0, 3.2, 2.5, 2.0, 1.6, 1.3, 1.0,
        0.8, 0.6, 0.5, 0.4, 0.3,
    )
    private val tvDenominators = intArrayOf(
        4, 5, 6, 8, 10, 13, 15, 20, 25, 30, 40, 50, 60, 80, 100, 125, 160, 200, 250, 320,
        400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000,
    )

    private fun nearestLog(x: Double, list: DoubleArray): Double =
        list.minByOrNull { abs(ln(it) - ln(x)) } ?: x

    fun iso(code: Int): String {
        if (code == 0) return "Auto"
        if (code < 0 || code > 0xC0) return "--"
        val v = 100.0 * 2.0.pow((code - 0x48) / 8.0)
        return isoNominal.minByOrNull { abs(ln(it.toDouble()) - ln(v)) }.toString()
    }

    fun av(code: Int): String {
        if (code <= 0 || code > 0x70) return "--"
        val f = 2.0.pow((code - 8) / 16.0)
        val n = nearestLog(f, avNominal)
        return if (n >= 10) "f/${n.roundToInt()}" else String.format(Locale.ITALY, "f/%.1f", n).replace(',', '.')
    }

    fun tv(code: Int): String {
        if (code == 0x0C) return "Bulb"
        if (code == 0) return "Auto"
        if (code < 0x10 || code > 0xA0) return "--"
        val t = 2.0.pow((0x38 - code) / 8.0)
        return if (t >= 0.29) {
            val n = nearestLog(t, tvSeconds)
            if (n >= 1 && n == n.toInt().toDouble()) "${n.toInt()}\"" else {
                val whole = n.toInt()
                val frac = ((n - whole) * 10).roundToInt()
                "$whole\"$frac"
            }
        } else {
            val d = tvDenominators.minByOrNull { abs(ln(1.0 / it) - ln(t)) }
            "1/$d"
        }
    }

    fun mode(code: Int?): String = when (code) {
        0 -> "P"
        1 -> "Tv"
        2 -> "Av"
        3 -> "M"
        4 -> "B"
        null -> "--"
        else -> "Auto"
    }

    fun format(prop: Int, code: Int): String = when (prop) {
        ISO -> iso(code)
        AV -> av(code)
        TV -> tv(code)
        else -> code.toString()
    }

    fun title(prop: Int): String = when (prop) {
        ISO -> "Sensibilità ISO"
        AV -> "Apertura"
        TV -> "Tempo di scatto"
        else -> "Valore"
    }
}
