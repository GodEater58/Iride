package it.goletto.iride.ptp

import it.goletto.iride.camera.standardWbLabel
import it.goletto.iride.camera.formatEv
import it.goletto.iride.camera.Brand
import it.goletto.iride.camera.CamFile
import it.goletto.iride.camera.Capabilities
import it.goletto.iride.camera.ExpoKey
import it.goletto.iride.camera.ExpoOption
import it.goletto.iride.camera.ExposureState
import it.goletto.iride.camera.Shot
import it.goletto.iride.camera.extractJpeg
import it.goletto.iride.camera.formatAperture
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Sony Alpha in modalità "PC remoto" via PTP/IP (a7 III, a7 IV e successive).
 * Sequenza di collegamento, pulsante di scatto e proprietà da libgphoto2 (camlibs/ptp2).
 *
 * In PC remoto la scheda non è consultabile: ogni foto scattata resta nella memoria
 * della fotocamera (oggetto 0xFFFFC001) e va scaricata subito dal telefono.
 */
class SonyPtpDriver(ptp: PtpTransport, log: (String) -> Unit) : PtpCamera(ptp, log) {

    override val brand = Brand.SONY
    override val caps = Capabilities(
        cardBrowsing = false,
        autofocusButton = true,
        focusDrive = true,
        galleryNote = "Con le Sony in modalità PC remoto le foto arrivano sul telefono appena scattate. Per tenere una copia anche sulla scheda, imposta sulla fotocamera la destinazione di salvataggio «PC + fotocamera».",
    )

    private var protocolV3 = false
    private val descs = ConcurrentHashMap<Int, PropDesc>()
    private var pendingObject = false

    override fun initialize() {
        super.initialize()
        sdioConnect(1)
        sdioConnect(2)
        var tries = 20
        while (tries-- > 0) {
            // chiede la versione 3.00 (0x12C); le fotocamere più vecchie rispondono con la loro
            val r = ptp.transaction(SonyOp.GET_EXT_DEVICE_INFO, intArrayOf(0x12C, 1))
            if (r.ok && r.data.size >= 2) {
                protocolV3 = le16(r.data, 0) == 0x12C
                break
            }
            Thread.sleep(100)
        }
        sdioConnect(3)
        log("Protocollo Sony ${if (protocolV3) "3.00" else "2.00"}")
        // priorità all'applicazione invece che alle ghiere della fotocamera
        ptp.transaction(SonyOp.SET_EXT_DEVICE_PROP_VALUE, intArrayOf(SONY_PRIORITY_MODE), dataOut = byteArrayOf(1))
        // le Sony recenti vogliono qualche secondo prima di accettare lo scatto
        val end = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < end) {
            refreshProps()
            Thread.sleep(300)
        }
    }

    private fun sdioConnect(phase: Int) {
        check(ptp.transaction(SonyOp.SDIO_CONNECT, intArrayOf(phase, 0, 0)), "SDIO connect $phase")
    }

    private fun refreshProps() {
        val r = ptp.transaction(SonyOp.GET_ALL_EXT_DEVICE_PROP_INFO)
        if (!r.ok || r.data.size <= 8) return
        val d = r.data
        var o = 8 // numero di voci (u64)
        while (o + 6 < d.size) {
            val parsed = runCatching { parseDpd(d, o, sony = true, sonyV3 = protocolV3) }.getOrNull() ?: break
            val (desc, next) = parsed
            if (next <= o) break
            descs[desc.code] = desc
            o = next
        }
        rebuildExposure()
    }

    private fun shutterLabel(v: Long): String {
        if (v == 0L) return "Bulb"
        val x = (v ushr 16) and 0xFFFF
        val y = v and 0xFFFF
        if (y == 0L) return "--"
        return when {
            y == 1L -> "$x\""
            y == 10L && x % 10 == 0L -> "${x / 10}\""
            y == 10L -> "${x / 10}\"${x % 10}"
            else -> "$x/$y"
        }
    }

    private fun isoLabel(v: Long): String {
        val base = v and 0xFFFFFF
        return if (base == 0xFFFFFFL) "Auto" else base.toString()
    }

    private fun modeLabel(v: Long): String = when (v) {
        1L -> "M"
        2L -> "P"
        3L -> "A"
        4L -> "S"
        else -> "Auto"
    }

    private val keyProp = mapOf(
        ExpoKey.MODE to PtpProp.EXPOSURE_PROGRAM,
        ExpoKey.TV to SONY_SHUTTER,
        ExpoKey.AV to PtpProp.FNUMBER,
        ExpoKey.ISO to SONY_ISO,
        ExpoKey.EV to SONY_EXPOSURE_COMP,
        ExpoKey.WB to PtpProp.WHITE_BALANCE,
    )

    private fun format(key: ExpoKey, v: Long): String = when (key) {
        ExpoKey.MODE -> modeLabel(v)
        ExpoKey.TV -> shutterLabel(v)
        ExpoKey.AV -> formatAperture(v / 100.0)
        ExpoKey.ISO -> isoLabel(v)
        ExpoKey.EV -> formatEv(v.toShort() / 1000.0)
        ExpoKey.WB -> standardWbLabel(v, Brand.SONY)
        ExpoKey.SHOTS -> v.toString()
    }

    private fun rebuildExposure() {
        val values = HashMap<ExpoKey, ExpoOption>()
        val options = HashMap<ExpoKey, List<ExpoOption>>()
        for ((key, prop) in keyProp) {
            val d = descs[prop] ?: continue
            values[key] = ExpoOption(d.current, format(key, d.current))
            if (d.writable && d.values.size > 1) {
                options[key] = d.values.map { ExpoOption(it, format(key, it)) }
            }
        }
        publishExposure(ExposureState(values, options))
    }

    override fun poll(): List<CamFile> {
        drainStandardEvents()
        refreshProps()
        val inMemory = descs[SONY_OBJECT_IN_MEMORY]?.current ?: 0L
        if (inMemory >= 0x8000 && !pendingObject) {
            val f = objectInfo(SONY_CAPTURED_OBJECT, transient = true)
            if (f != null) {
                pendingObject = true
                // nome univoco: la Sony riusa lo stesso handle per ogni scatto
                return listOf(f.copy(id = "${f.id}:${System.currentTimeMillis()}"))
            }
        }
        return emptyList()
    }

    override fun download(file: CamFile, out: OutputStream, progress: (Long, Long) -> Unit) {
        try {
            val r = ptp.transaction(PtpOp.GET_OBJECT, intArrayOf(SONY_CAPTURED_OBJECT), sink = out, onProgress = progress)
            if (!r.ok) throw IOException("la fotocamera ha rifiutato il download (${hex(r.code)})")
        } finally {
            pendingObject = false
        }
    }

    override fun thumbnail(file: CamFile): ByteArray? = null

    override fun listFiles(progress: (Int, Int) -> Unit): List<CamFile> = emptyList()

    private fun control(prop: Int, value: Int): Int =
        ptp.transaction(SonyOp.CONTROL_DEVICE, intArrayOf(prop), dataOut = Buf().u16(value).bytes()).code

    override fun capture(autofocus: Boolean): Shot {
        // mezza pressione (2 = premuto), pressione completa, poi rilascio (1)
        val a = control(SONY_SHUTTER_HALF, 2)
        if (autofocus) Thread.sleep(400)
        val b = control(SONY_SHUTTER_FULL, 2)
        Thread.sleep(200)
        control(SONY_SHUTTER_FULL, 1)
        control(SONY_SHUTTER_HALF, 1)
        val code = if (a != PtpResp.OK) a else b
        if (code != PtpResp.OK) log("Scatto: risposta ${hex(code)}")
        return shotFrom(code)
    }

    override fun autofocus(): Shot {
        // mezza pressione: la fotocamera mette a fuoco, poi si rilascia
        val code = control(SONY_SHUTTER_HALF, 2)
        Thread.sleep(1200)
        control(SONY_SHUTTER_HALF, 1)
        return shotFrom(code)
    }

    override fun bulbStart(): Shot {
        // in modalità BULB il pulsante tenuto premuto mantiene aperto l'otturatore
        control(SONY_SHUTTER_HALF, 2)
        val code = control(SONY_SHUTTER_FULL, 2)
        return shotFrom(code)
    }

    override fun bulbEnd() {
        control(SONY_SHUTTER_FULL, 1)
        control(SONY_SHUTTER_HALF, 1)
    }

    /** Messa a fuoco manuale (proprietà ManualFocusAdjust, valori ±1..±7): funziona con la fotocamera in MF. */
    override fun focusDrive(step: Int): Boolean {
        if (step == 0) return false
        val size = when (kotlin.math.abs(step)) { 1 -> 1; 2 -> 3; else -> 7 }
        return control(MANUAL_FOCUS_ADJUST, if (step < 0) -size else size) == PtpResp.OK
    }

    override fun startLiveView() = Unit
    override fun stopLiveView() = Unit

    override fun liveViewFrame(): ByteArray? {
        val r = ptp.transaction(PtpOp.GET_OBJECT, intArrayOf(SONY_LIVE_VIEW_OBJECT))
        if (!r.ok) return null
        return extractJpeg(r.data)
    }

    override fun setExposure(key: ExpoKey, code: Long): Boolean {
        val prop = keyProp[key] ?: return false
        val d = descs[prop] ?: return false
        val data = packValue(d.type, code) ?: return false
        val ok = check(ptp.transaction(SonyOp.SET_EXT_DEVICE_PROP_VALUE, intArrayOf(prop), dataOut = data), key.title).ok
        if (ok) refreshProps()
        return ok
    }

    companion object {
        const val SONY_SHUTTER = 0xD20D
        const val SONY_ISO = 0xD21E
        const val SONY_EXPOSURE_COMP = 0xD224
        const val SONY_OBJECT_IN_MEMORY = 0xD215
        const val SONY_PRIORITY_MODE = 0xD25A
        const val SONY_SHUTTER_HALF = 0xD2C1
        const val SONY_SHUTTER_FULL = 0xD2C2
        const val MANUAL_FOCUS_ADJUST = 0xD2D1
        const val SONY_CAPTURED_OBJECT = -0x3FFF // 0xFFFFC001
        const val SONY_LIVE_VIEW_OBJECT = -0x3FFE // 0xFFFFC002
    }
}
