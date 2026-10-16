package it.goletto.iride.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.abs

/**
 * Aiuti sul live view, calcolati su una copia ridotta del fotogramma (al massimo [MAX_W] pixel
 * di larghezza): istogramma della luminosità e focus peaking (contorni netti evidenziati).
 * Bitmap e buffer si riusano: niente allocazioni a ogni fotogramma. Da usare da un solo thread.
 */
class LiveAnalyzer {

    /** [bins] da 0 a 1 (la colonna più alta vale 1); frazioni di pixel neri e bianchi pieni. */
    class Histogram(val bins: FloatArray, val darkClip: Float, val brightClip: Float)

    class Result(val histogram: Histogram?, val peaking: Bitmap?)

    private var small: Bitmap? = null
    private var canvas: Canvas? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = Rect()
    private var px = IntArray(0)
    private var lum = IntArray(0)
    private var out = IntArray(0)
    private val counts = IntArray(BINS)
    private val gradCounts = IntArray(MAX_GRAD + 1)
    // due bitmap a turno: una mostrata, una in scrittura
    private val overlays = arrayOfNulls<Bitmap>(2)
    private var turn = 0

    fun analyze(src: Bitmap, histogram: Boolean, peaking: Boolean, peakColor: Int): Result {
        if (!histogram && !peaking) return Result(null, null)
        val w = minOf(src.width, MAX_W)
        val h = (src.height.toLong() * w / src.width).toInt().coerceAtLeast(3)
        val bmp = if (w == src.width) src else scaled(src, w, h)
        val n = w * h
        if (px.size < n) {
            px = IntArray(n)
            lum = IntArray(n)
            out = IntArray(n)
        }
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (i in 0 until n) {
            val c = px[i]
            lum[i] = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
        }
        val hist = if (histogram) histogram(n) else null
        val peak = if (peaking) peaking(w, h, peakColor) else null
        return Result(hist, peak)
    }

    private fun scaled(src: Bitmap, w: Int, h: Int): Bitmap {
        var s = small
        if (s == null || s.width != w || s.height != h) {
            s = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            small = s
            canvas = Canvas(s)
        }
        dst.set(0, 0, w, h)
        canvas!!.drawBitmap(src, null, dst, paint)
        return s
    }

    private fun histogram(n: Int): Histogram {
        counts.fill(0)
        var dark = 0
        var bright = 0
        for (i in 0 until n) {
            val l = lum[i]
            counts[l * BINS / 256]++
            if (l <= 2) dark++ else if (l >= 253) bright++
        }
        val max = counts.max().coerceAtLeast(1).toFloat()
        val bins = FloatArray(BINS) { counts[it] / max }
        return Histogram(bins, dark / n.toFloat(), bright / n.toFloat())
    }

    /**
     * Contrasto locale (differenze centrali orizzontali e verticali, 0..510). Si evidenziano i
     * contorni più netti dell'immagine, ma solo sopra una soglia minima: con tutto sfocato
     * non resta quasi nulla, ed è proprio il segnale che serve per mettere a fuoco.
     */
    private fun peaking(w: Int, h: Int, color: Int): Bitmap {
        gradCounts.fill(0)
        var counted = 0
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val g = abs(lum[i + 1] - lum[i - 1]) + abs(lum[i + w] - lum[i - w])
                out[i] = g
                gradCounts[g]++
                counted++
            }
        }
        // valore sotto il quale sta il 99,7% dei pixel: il "massimo" senza farsi ingannare dal rumore
        var target = (counted * 0.997).toInt()
        var p997 = MAX_GRAD
        for (g in 0..MAX_GRAD) {
            target -= gradCounts[g]
            if (target <= 0) {
                p997 = g
                break
            }
        }
        val threshold = maxOf(MIN_GRAD, (p997 * 0.55f).toInt())
        for (x in 0 until w) {
            out[x] = 0
            out[(h - 1) * w + x] = 0
        }
        for (y in 1 until h - 1) {
            val row = y * w
            out[row] = 0
            out[row + w - 1] = 0
            for (x in 1 until w - 1) {
                val i = row + x
                out[i] = if (out[i] >= threshold) color else 0
            }
        }
        var o = overlays[turn]
        if (o == null || o.width != w || o.height != h) {
            o = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            overlays[turn] = o
        }
        o.setPixels(out, 0, w, 0, 0, w, h)
        turn = (turn + 1) % overlays.size
        return o
    }

    companion object {
        const val BINS = 64
        private const val MAX_W = 480
        private const val MAX_GRAD = 510
        private const val MIN_GRAD = 56
    }
}
