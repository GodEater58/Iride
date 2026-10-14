package it.goletto.iride.camera

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Stella misurata nel fotogramma: centro in pixel del fotogramma, HFR in pixel, picco 0..255. */
class StarMeasure(val x: Float, val y: Float, val hfr: Float, val peak: Int, val frameW: Int, val frameH: Int) {
    val saturated: Boolean get() = peak >= 250
}

/**
 * Misura la dimensione di una stella con l'HFR (Half Flux Radius): il raggio medio pesato
 * sulla luce della stella, al netto del fondo cielo. Più è piccolo, più la stella è a fuoco.
 * È lo stesso criterio usato dai programmi di astrofotografia per la messa a fuoco.
 */
object StarMeter {

    /**
     * Cerca la stella più luminosa vicino a ([hintX], [hintY]) se [hasHint], altrimenti nella
     * parte centrale del fotogramma, e ne misura l'HFR. Null se non c'è una stella riconoscibile.
     */
    fun measure(bmp: Bitmap, hintX: Float, hintY: Float, hasHint: Boolean): StarMeasure? {
        val w = bmp.width
        val h = bmp.height
        if (w < 32 || h < 32) return null
        val r = max(8, (w * 0.035f).toInt())          // raggio di misura
        val ring = 5                                    // anello per il fondo cielo
        val sx0: Int
        val sy0: Int
        val sx1: Int
        val sy1: Int
        if (hasHint) {
            val sr = max(16, (w * 0.06f).toInt())
            sx0 = (hintX - sr).toInt(); sx1 = (hintX + sr).toInt()
            sy0 = (hintY - sr).toInt(); sy1 = (hintY + sr).toInt()
        } else {
            sx0 = (w * 0.2f).toInt(); sx1 = (w * 0.8f).toInt()
            sy0 = (h * 0.2f).toInt(); sy1 = (h * 0.8f).toInt()
        }
        val m = r + ring + 2
        val x0 = max(0, sx0 - m)
        val y0 = max(0, sy0 - m)
        val x1 = min(w, sx1 + m)
        val y1 = min(h, sy1 + m)
        val rw = x1 - x0
        val rh = y1 - y0
        if (rw < 8 || rh < 8) return null
        val px = IntArray(rw * rh)
        bmp.getPixels(px, 0, rw, x0, y0, rw, rh)
        val lum = IntArray(px.size)
        for (i in px.indices) {
            val c = px[i]
            lum[i] = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }

        // punto più luminoso, sommando 3×3 così un pixel caldo isolato pesa meno di una stella
        var best = -1
        var bx = 0
        var by = 0
        val ax0 = max(1, sx0 - x0)
        val ay0 = max(1, sy0 - y0)
        val ax1 = min(rw - 2, sx1 - x0)
        val ay1 = min(rh - 2, sy1 - y0)
        for (y in ay0..ay1) {
            val row = y * rw
            for (x in ax0..ax1) {
                val s = lum[row - rw + x - 1] + lum[row - rw + x] + lum[row - rw + x + 1] +
                    lum[row + x - 1] + lum[row + x] + lum[row + x + 1] +
                    lum[row + rw + x - 1] + lum[row + rw + x] + lum[row + rw + x + 1]
                if (s > best) {
                    best = s
                    bx = x
                    by = y
                }
            }
        }
        if (best < 0) return null

        // fondo cielo: mediana dell'anello attorno alla stella
        val bgVals = ArrayList<Int>()
        for (y in by - r - ring..by + r + ring) {
            if (y < 0 || y >= rh) continue
            for (x in bx - r - ring..bx + r + ring) {
                if (x < 0 || x >= rw) continue
                val dx = (x - bx).toFloat()
                val dy = (y - by).toFloat()
                val d = sqrt(dx * dx + dy * dy)
                if (d > r && d <= r + ring) bgVals.add(lum[y * rw + x])
            }
        }
        if (bgVals.size < 8) return null
        bgVals.sort()
        val bg = bgVals[bgVals.size / 2].toFloat()

        // centroide pesato sulla luce, ripetuto per centrarsi bene
        var cx = bx.toFloat()
        var cy = by.toFloat()
        var peak = 0
        repeat(3) {
            var sum = 0f
            var sxw = 0f
            var syw = 0f
            peak = 0
            for (y in (cy - r).toInt()..(cy + r).toInt()) {
                if (y < 0 || y >= rh) continue
                for (x in (cx - r).toInt()..(cx + r).toInt()) {
                    if (x < 0 || x >= rw) continue
                    val dx = x - cx
                    val dy = y - cy
                    if (dx * dx + dy * dy > r * r) continue
                    val v = lum[y * rw + x]
                    if (v > peak) peak = v
                    val f = v - bg
                    if (f > 0) {
                        sum += f
                        sxw += f * x
                        syw += f * y
                    }
                }
            }
            if (sum <= 0f) return null
            cx = sxw / sum
            cy = syw / sum
        }
        if (peak - bg < 25) return null

        // HFR: distanza media dal centro, pesata sulla luce
        var sum = 0f
        var sd = 0f
        for (y in (cy - r).toInt()..(cy + r).toInt()) {
            if (y < 0 || y >= rh) continue
            for (x in (cx - r).toInt()..(cx + r).toInt()) {
                if (x < 0 || x >= rw) continue
                val dx = x - cx
                val dy = y - cy
                val d2 = dx * dx + dy * dy
                if (d2 > r * r) continue
                val f = lum[y * rw + x] - bg
                if (f > 0) {
                    sum += f
                    sd += f * sqrt(d2)
                }
            }
        }
        if (sum < 150f) return null
        return StarMeasure(x0 + cx, y0 + cy, sd / sum, peak, w, h)
    }
}
