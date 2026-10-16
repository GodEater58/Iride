package it.goletto.iride.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Fotocamera dimostrativa: tutto funziona senza una fotocamera vera. Serve per provare l'app,
 * per mostrarla e per generare le schermate della guida. Il live view disegna un cielo stellato
 * con la Via Lattea; la messa a fuoco a passi sfoca e rimette a fuoco le stelle davvero, così
 * l'assistente stelle misura valori realistici.
 */
class DemoDriver : CameraDriver {

    override val brand = Brand.CANON
    override val model = "Canon EOS 6D"
    override val caps = Capabilities(
        autofocusButton = true,
        liveViewQuality = true,
        zoomLevels = listOf("1×", "5×", "10×"),
        zoomPosition = true,
        focusDrive = true,
    )

    // ---------------------------------------------------------------- esposizione

    private val tvLabels = listOf("30\"", "25\"", "20\"", "15\"", "13\"", "10\"", "8\"", "6\"", "5\"", "4\"", "3\"", "2\"", "1\"", "1/2", "1/4", "1/8", "1/15", "1/30", "1/60", "1/125", "1/250", "1/500", "1/1000", "1/2000", "1/4000")
    private val avLabels = listOf("f/2.8", "f/3.2", "f/3.5", "f/4.0", "f/4.5", "f/5.0", "f/5.6", "f/6.3", "f/7.1", "f/8.0", "f/9.0", "f/10", "f/11", "f/13", "f/16", "f/22")
    private val isoLabels = listOf("100", "200", "400", "800", "1600", "3200", "6400", "12800", "25600")
    private val evLabels = listOf("-3", "-2.7", "-2.3", "-2", "-1.7", "-1.3", "-1", "-0.7", "-0.3", "0", "+0.3", "+0.7", "+1", "+1.3", "+1.7", "+2", "+2.3", "+2.7", "+3")
    private val wbLabels = listOf("Auto", "Luce diurna", "Ombra", "Nuvoloso", "Tungsteno", "Fluorescente", "Temp. colore")
    private val modeLabels = listOf("M", "Av", "Tv", "P", "B")

    private fun opts(l: List<String>) = l.mapIndexed { i, s -> ExpoOption(i.toLong(), s) }

    private val current = mutableMapOf(
        ExpoKey.MODE to 0, ExpoKey.TV to 2, ExpoKey.AV to 0, ExpoKey.ISO to 5, ExpoKey.EV to 9, ExpoKey.WB to 6,
    )

    @Volatile
    override var exposureVersion = 1
        private set

    override fun exposure(): ExposureState {
        val lists = mapOf(
            ExpoKey.MODE to modeLabels, ExpoKey.TV to tvLabels, ExpoKey.AV to avLabels,
            ExpoKey.ISO to isoLabels, ExpoKey.EV to evLabels, ExpoKey.WB to wbLabels,
        )
        val values = HashMap<ExpoKey, ExpoOption>()
        val options = HashMap<ExpoKey, List<ExpoOption>>()
        for ((k, l) in lists) {
            val i = current[k] ?: 0
            values[k] = ExpoOption(i.toLong(), l[i])
            options[k] = opts(l)
        }
        values[ExpoKey.SHOTS] = ExpoOption(625, (625 - shots).toString())
        return ExposureState(values, options)
    }

    override fun setExposure(key: ExpoKey, code: Long): Boolean {
        if (key !in current) return false
        current[key] = code.toInt()
        exposureVersion++
        return true
    }

    // ---------------------------------------------------------------- scatto e file

    private var shots = 0
    private val pending = ArrayList<CamFile>()

    override fun poll(): List<CamFile> = synchronized(pending) {
        val out = ArrayList(pending)
        pending.clear()
        out
    }

    override fun capture(autofocus: Boolean): Shot {
        if (autofocus) Thread.sleep(400)
        Thread.sleep(250)
        shots++
        exposureVersion++
        val n = 2048 + shots
        synchronized(pending) { pending.add(CamFile("demo-new-$n", "IMG_$n.CR2", 26_400_000, "20261016T213000")) }
        return Shot.OK
    }

    override fun bulbStart(): Shot = Shot.OK
    override fun bulbEnd() {
        capture(false)
    }

    override fun autofocus(): Shot {
        Thread.sleep(900)
        return Shot.OK
    }

    override fun listFiles(progress: (Int, Int) -> Unit): List<CamFile> {
        val out = ArrayList<CamFile>()
        val nights = listOf("20261016" to 22, "20261012" to 18, "20261009" to 14)
        var n = 2048
        for ((day, count) in nights) {
            for (i in 0 until count) {
                val mm = 59 - i * 2
                val time = "${day}T23${String.format("%02d", max(mm, 0))}00"
                val raw = i % 4 != 3
                out.add(CamFile("demo-$n", if (raw) "IMG_$n.CR2" else "IMG_$n.JPG", if (raw) 26_400_000 else 7_800_000, time))
                n--
            }
            progress(out.size, 54)
        }
        Thread.sleep(500)
        return out
    }

    override fun thumbnail(file: CamFile): ByteArray? {
        val rnd = Random(file.id.hashCode())
        val w = 160
        val h = 107
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val tops = listOf(Color.rgb(8, 12, 26), Color.rgb(12, 16, 30), Color.rgb(16, 12, 22), Color.rgb(6, 14, 24))
        val bottoms = listOf(Color.rgb(44, 36, 70), Color.rgb(60, 58, 86), Color.rgb(84, 60, 44), Color.rgb(30, 50, 74))
        val k = rnd.nextInt(tops.size)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), tops[k], bottoms[k], Shader.TileMode.CLAMP) })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        repeat(40) {
            p.alpha = 80 + rnd.nextInt(175)
            c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h * 0.8f, 0.4f + rnd.nextFloat() * 0.9f, p)
        }
        val ground = Path().apply {
            moveTo(0f, h * 0.82f)
            quadTo(w * 0.3f, h * (0.66f + rnd.nextFloat() * 0.1f), w * 0.55f, h * 0.78f)
            quadTo(w * 0.8f, h * 0.7f, w.toFloat(), h * 0.8f)
            lineTo(w.toFloat(), h.toFloat())
            lineTo(0f, h.toFloat())
            close()
        }
        c.drawPath(ground, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(5, 6, 8) })
        return jpeg(bmp, 85)
    }

    override fun download(file: CamFile, out: OutputStream, progress: (Long, Long) -> Unit) {
        // la modalità demo non scrive file finti sul telefono: il download viene solo simulato
        val total = file.size
        var done = 0L
        while (done < total) {
            Thread.sleep(40)
            done = min(total, done + total / 25)
            progress(done, total)
        }
    }

    // ---------------------------------------------------------------- live view

    private var quality = LvQuality.BALANCED
    private var zoom = 1f
    private var zcx = 0.5f
    private var zcy = 0.5f
    /** Posizione della messa a fuoco: 0 = a fuoco, lontano da 0 = stelle sfocate. */
    @Volatile
    private var focusPos = -14

    private class Star(val x: Float, val y: Float, val b: Float, val tint: Int)

    private val stars: List<Star> by lazy {
        val rnd = Random(42)
        val list = ArrayList<Star>()
        repeat(520) {
            // più stelle lungo la banda della Via Lattea, che attraversa il cielo in diagonale
            val onBand = rnd.nextFloat() < 0.5f
            val x = rnd.nextFloat()
            val y = if (onBand) (0.75f - x * 0.6f + (rnd.nextFloat() - 0.5f) * 0.22f) else rnd.nextFloat() * 0.85f
            val b = rnd.nextFloat().let { it * it * it } * 0.85f + 0.12f
            val tint = listOf(Color.WHITE, Color.WHITE, Color.WHITE, Color.rgb(255, 236, 205), Color.rgb(210, 224, 255))[rnd.nextInt(5)]
            list.add(Star(x, y, b, tint))
        }
        // una stella brillante vicino al centro, comoda per la messa a fuoco
        list.add(Star(0.52f, 0.45f, 1f, Color.WHITE))
        list
    }

    private var frame: Bitmap? = null

    override fun setLiveViewQuality(q: LvQuality) {
        quality = q
    }

    override fun startLiveView() {
        zoom = 1f
        Thread.sleep(300)
    }

    override fun stopLiveView() {
        zoom = 1f
    }

    override fun liveViewFrame(): ByteArray {
        val w = when {
            zoom > 1f -> 1024
            quality == LvQuality.FLUID -> 640
            quality == LvQuality.BALANCED -> 800
            else -> 1024
        }
        val h = w * 2 / 3
        val bmp = frame?.takeIf { it.width == w } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { frame = it }
        draw(Canvas(bmp), w, h)
        Thread.sleep(45)
        return jpeg(bmp, 82)
    }

    /** Da coordinate del cielo (0..1) a pixel del fotogramma, tenendo conto dell'ingrandimento. */
    private fun sx(x: Float, w: Int) = ((x - zcx) * zoom + 0.5f) * w
    private fun sy(y: Float, h: Int) = ((y - zcy) * zoom + 0.5f) * h

    private fun draw(c: Canvas, w: Int, h: Int) {
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, sy(0f, h), 0f, sy(1f, h), Color.rgb(4, 6, 14), Color.rgb(22, 24, 40), Shader.TileMode.CLAMP) })
        // Via Lattea: chiazze di luce lungo la banda
        val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0..10) {
            val x = i / 10f
            val y = 0.75f - x * 0.6f
            val r = (0.16f + 0.05f * ((i * 7) % 3)) * w * zoom
            glow.shader = RadialGradient(sx(x, w), sy(y, h), r, Color.argb(48, 120, 110, 150), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawCircle(sx(x, w), sy(y, h), r, glow)
        }
        // stelle: sfocate in proporzione alla distanza dal fuoco, con la stessa luce totale
        val defocus = abs(focusPos).toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (s in stars) {
            val px = sx(s.x, w)
            val py = sy(s.y, h)
            val rn = (0.0011f + defocus * 0.00075f) * (0.6f + s.b) * w * zoom
            if (px < -rn || py < -rn || px > w + rn || py > h + rn) continue
            p.color = s.tint
            p.alpha = (255 * s.b / (1f + defocus * 0.32f)).toInt().coerceIn(18, 255)
            c.drawCircle(px, py, max(0.8f, rn), p)
        }
        // profilo delle montagne
        val ground = Path().apply {
            moveTo(sx(0f, w), sy(0.86f, h))
            cubicTo(sx(0.2f, w), sy(0.74f, h), sx(0.32f, w), sy(0.8f, h), sx(0.48f, w), sy(0.83f, h))
            cubicTo(sx(0.62f, w), sy(0.86f, h), sx(0.78f, w), sy(0.72f, h), sx(1f, w), sy(0.84f, h))
            lineTo(sx(1f, w), sy(1.2f, h))
            lineTo(sx(0f, w), sy(1.2f, h))
            close()
        }
        c.drawPath(ground, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(3, 4, 6) })
    }

    override fun liveZoom(level: Int, cx: Float?, cy: Float?): Boolean {
        val f = listOf(1f, 5f, 10f).getOrElse(level) { 1f }
        if (cx != null && cy != null) {
            if (zoom > 1f) {
                zcx += (cx - 0.5f) / zoom
                zcy += (cy - 0.5f) / zoom
            } else {
                zcx = cx
                zcy = cy
            }
        }
        zoom = f
        if (f == 1f) {
            zcx = 0.5f
            zcy = 0.5f
        }
        return true
    }

    override fun panZoom(dx: Float, dy: Float): Boolean {
        if (zoom <= 1f) return false
        zcx = (zcx + dx / zoom).coerceIn(0f, 1f)
        zcy = (zcy + dy / zoom).coerceIn(0f, 1f)
        return true
    }

    override fun focusDrive(step: Int): Boolean {
        val size = when (abs(step)) { 1 -> 1; 2 -> 4; else -> 12 }
        focusPos += if (step < 0) -size else size
        Thread.sleep(150)
        return true
    }

    override fun lens(): LensInfo = LensInfo.fromName("EF16-35mm f/2.8L II USM")

    override fun sensor(): SensorInfo = SensorInfo(35.8f, 5472)

    override fun close() = Unit

    private fun jpeg(bmp: Bitmap, q: Int): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
        return out.toByteArray()
    }
}
