package it.goletto.iride.ptp

import it.goletto.iride.camera.LvQuality
import it.goletto.iride.camera.LensInfo
import it.goletto.iride.camera.SensorInfo
import it.goletto.iride.camera.Brand
import it.goletto.iride.camera.CamFile
import it.goletto.iride.camera.Capabilities
import it.goletto.iride.camera.ExpoKey
import it.goletto.iride.camera.ExpoOption
import it.goletto.iride.camera.ExposureState
import it.goletto.iride.camera.Shot
import java.util.concurrent.ConcurrentHashMap

/** Canon EOS (6D, 1300D e simili) via PTP/IP o USB, con le estensioni EOS documentate da libgphoto2. */
class CanonDriver(ptp: PtpTransport, log: (String) -> Unit) : PtpCamera(ptp, log) {

    override val brand = Brand.CANON
    override var caps = Capabilities()
        private set

    private var quality = LvQuality.BALANCED

    override fun setLiveViewQuality(q: LvQuality) {
        quality = q
    }

    private val props = ConcurrentHashMap<Int, Int>()
    private val allowed = ConcurrentHashMap<Int, IntArray>()

    private val keyProp = mapOf(
        ExpoKey.MODE to Expo.MODE,
        ExpoKey.TV to Expo.TV,
        ExpoKey.AV to Expo.AV,
        ExpoKey.ISO to Expo.ISO,
        ExpoKey.EV to Expo.EV,
        ExpoKey.WB to Expo.WB,
        ExpoKey.SHOTS to Expo.SHOTS,
    )

    override fun initialize() {
        super.initialize()
        if (PtpOp.CANON_SET_REMOTE_MODE in ops) check(ptp.transaction(PtpOp.CANON_SET_REMOTE_MODE, intArrayOf(1)), "SetRemoteMode")
        if (PtpOp.CANON_SET_EVENT_MODE in ops) check(ptp.transaction(PtpOp.CANON_SET_EVENT_MODE, intArrayOf(1)), "SetEventMode")
        // La prima GetEvent restituisce lo stato completo (proprietà e valori ammessi)
        repeat(2) { runCatching { poll() } }
        val (w, h) = sensorSize(info.model)
        coordW = w
        coordH = h
        sensorMm = sensorWidthMm(info.model)
        if (lensName.isNotEmpty()) log("Obiettivo: $lensName")
        caps = Capabilities(
            autofocusButton = PtpOp.CANON_DO_AF in ops || PtpOp.CANON_RELEASE_ON in ops,
            liveViewQuality = true,
            zoomLevels = if (PtpOp.CANON_ZOOM in ops) listOf("1×", "5×", "10×") else emptyList(),
            zoomPosition = PtpOp.CANON_ZOOM_POSITION in ops,
            focusDrive = PtpOp.CANON_DRIVE_LENS in ops,
        )
    }

    // ---------------------------------------------------------------- assistente di messa a fuoco

    /**
     * Coordinate del riquadro di ingrandimento: si assume che siano in pixel del sensore
     * (come il sistema di coordinate del live view nell'SDK Canon). Se non fosse esatto,
     * l'app ricentra la stella dopo lo zoom e il riquadro si può trascinare.
     */
    private var coordW = 6000
    private var coordH = 4000
    private var sensorMm = 22.3f

    @Volatile
    private var lensName = ""

    override fun lens(): LensInfo? = lensName.takeIf { it.isNotEmpty() }?.let { LensInfo.fromName(it) }

    override fun sensor(): SensorInfo = SensorInfo(sensorMm, coordW)
    private var zoom = 1
    private var zcx = 0.5f
    private var zcy = 0.5f

    private fun sensorSize(model: String): Pair<Int, Int> {
        val m = model.uppercase()
        return when {
            "6D MARK II" in m -> 6240 to 4160
            "6D" in m -> 5472 to 3648
            "5D MARK IV" in m -> 6720 to 4480
            "5D MARK III" in m -> 5760 to 3840
            "5D MARK II" in m -> 5616 to 3744
            "5DS" in m -> 8688 to 5792
            "7D MARK II" in m -> 5472 to 3648
            "7D" in m -> 5184 to 3456
            "90D" in m || "M6 MARK II" in m -> 6960 to 4640
            "R5" in m -> 8192 to 5464
            "R6" in m -> 5472 to 3648
            "RP" in m -> 6240 to 4160
            "EOS R" in m -> 6720 to 4480
            "80D" in m || "77D" in m || "800D" in m || "200D" in m || "1300D" in m.replace(" ", "") || "750D" in m || "760D" in m -> 6000 to 4000
            "70D" in m || "60D" in m || "700D" in m || "100D" in m || "1200D" in m -> 5184 to 3456
            else -> 6000 to 4000
        }
    }

    /** Larghezza del sensore: pieno formato (6D 35,8 mm) o APS-C Canon (22,3 mm). */
    private fun sensorWidthMm(model: String): Float {
        val m = model.uppercase()
        return when {
            "6D MARK II" in m -> 35.9f
            "6D" in m -> 35.8f
            "5D" in m || "1D" in m || Regex("""EOS R\d?\b|EOS RP""").containsMatchIn(m) -> 36.0f
            else -> 22.3f
        }
    }

    private fun sendZoomPosition() {
        val view = maxOf(zoom, 5)   // a 1× il riquadro mostrato è quello del 5×
        val rw = coordW / view
        val rh = coordH / view
        zcx = zcx.coerceIn(rw / 2f / coordW, 1f - rw / 2f / coordW)
        zcy = zcy.coerceIn(rh / 2f / coordH, 1f - rh / 2f / coordH)
        val x = (zcx * coordW - rw / 2f).toInt().coerceIn(0, coordW - rw)
        val y = (zcy * coordH - rh / 2f).toInt().coerceIn(0, coordH - rh)
        check(ptp.transaction(PtpOp.CANON_ZOOM_POSITION, intArrayOf(x, y)), "Posizione zoom")
        log("Riquadro di ingrandimento: $x,$y (${coordW}×$coordH)")
    }

    override fun liveZoom(level: Int, cx: Float?, cy: Float?): Boolean {
        if (PtpOp.CANON_ZOOM !in ops) return false
        val factor = listOf(1, 5, 10).getOrElse(level) { 1 }
        if (cx != null && cy != null && PtpOp.CANON_ZOOM_POSITION in ops) {
            // il punto toccato è relativo alla vista attuale: se si è già ingranditi va riportato all'intera
            val view = if (zoom > 1) zoom.toFloat() else 1f
            if (zoom > 1) {
                zcx += (cx - 0.5f) / view
                zcy += (cy - 0.5f) / view
            } else {
                zcx = cx
                zcy = cy
            }
            sendZoomPosition()
        }
        // ingranditi serve l'uscita grande: con quella per smartphone si perderebbe il dettaglio
        if (factor > 1 && zoom == 1) setPropEx(CanonProp.EVF_OUTPUT_DEVICE, 2)
        val ok = check(ptp.transaction(PtpOp.CANON_ZOOM, intArrayOf(factor)), "Zoom ${factor}×").ok
        if (ok) {
            if (factor == 1 && zoom > 1) setPropEx(CanonProp.EVF_OUTPUT_DEVICE, currentOutput)
            zoom = factor
        }
        return ok
    }

    override fun panZoom(dx: Float, dy: Float): Boolean {
        if (PtpOp.CANON_ZOOM_POSITION !in ops || zoom == 1) return false
        zcx += dx / zoom
        zcy += dy / zoom
        sendZoomPosition()
        return true
    }

    override fun focusDrive(step: Int): Boolean {
        if (PtpOp.CANON_DRIVE_LENS !in ops || step == 0) return false
        val size = minOf(3, kotlin.math.abs(step))
        // 1..3 verso il vicino, 0x8001..0x8003 verso l'infinito (libgphoto2 "manualfocusdrive")
        val v = if (step < 0) size else 0x8000 or size
        return check(ptp.transaction(PtpOp.CANON_DRIVE_LENS, intArrayOf(v)), "Messa a fuoco manuale").ok
    }

    private fun label(prop: Int, v: Int): String = when (prop) {
        Expo.MODE -> Expo.mode(v)
        Expo.SHOTS -> v.toString()
        Expo.EV -> Expo.ev(v)
        Expo.WB -> Expo.wb(v)
        else -> Expo.format(prop, v)
    }

    private fun rebuildExposure() {
        val values = HashMap<ExpoKey, ExpoOption>()
        val options = HashMap<ExpoKey, List<ExpoOption>>()
        for ((key, prop) in keyProp) {
            val v = props[prop] ?: continue
            values[key] = ExpoOption(v.toLong() and 0xFFFFFFFFL, label(prop, v))
            val list = allowed[prop]
            if (list != null && list.size > 1 && key != ExpoKey.SHOTS) {
                options[key] = list.map { ExpoOption(it.toLong() and 0xFFFFFFFFL, label(prop, it)) }
            }
        }
        publishExposure(ExposureState(values, options))
    }

    /** Legge la coda eventi Canon: nuovi file, proprietà cambiate, valori ammessi. */
    override fun poll(): List<CamFile> {
        drainStandardEvents()
        if (PtpOp.CANON_GET_EVENT !in ops) return emptyList()
        val r = ptp.transaction(PtpOp.CANON_GET_EVENT)
        if (!r.ok) return emptyList()
        val d = r.data
        val added = ArrayList<Int>()
        var changed = false
        var off = 0
        while (off + 8 <= d.size) {
            val size = le32(d, off)
            val type = le32(d, off + 4)
            if (size < 8 || type == 0 || off + size > d.size) break
            when (type) {
                CanonEvent.OBJECT_ADDED_EX -> if (size >= 12) added.add(le32(d, off + 8))
                CanonEvent.PROP_VALUE_CHANGED -> if (size >= 16 && le32(d, off + 8) == LENS_NAME) {
                    // nome dell'obiettivo: testo ASCII terminato da zero (libgphoto2)
                    var e = off + 12
                    while (e < off + size && d[e] != 0.toByte()) e++
                    lensName = String(d, off + 12, e - off - 12, Charsets.US_ASCII).trim()
                } else if (size >= 16) {
                    val prop = le32(d, off + 8)
                    val value = le32(d, off + 12)
                    if (props[prop] != value) {
                        props[prop] = value
                        changed = true
                    }
                }
                CanonEvent.AVAIL_LIST_CHANGED -> if (size >= 20) {
                    val prop = le32(d, off + 8)
                    val count = le32(d, off + 16)
                    if (count in 0..512 && 20 + count * 4 <= size) {
                        allowed[prop] = IntArray(count) { le32(d, off + 20 + it * 4) }
                        changed = true
                    }
                }
            }
            off += size
        }
        if (changed) rebuildExposure()
        return added.mapNotNull { objectInfo(it) }
    }

    override fun setExposure(key: ExpoKey, code: Long): Boolean {
        val prop = keyProp[key] ?: return false
        val ok = setPropEx(prop, code.toInt()).ok
        if (ok) {
            props[prop] = code.toInt()
            rebuildExposure()
        }
        return ok
    }

    override fun capture(autofocus: Boolean): Shot {
        val code = if (PtpOp.CANON_RELEASE_ON in ops) {
            val r = ptp.transaction(PtpOp.CANON_RELEASE_ON, intArrayOf(3, if (autofocus) 0 else 1))
            ptp.transaction(PtpOp.CANON_RELEASE_OFF, intArrayOf(3))
            check(r, "Scatto").code
        } else {
            check(ptp.transaction(PtpOp.CANON_REMOTE_RELEASE), "Scatto").code
        }
        return shotFrom(code)
    }

    /** Inizio posa Bulb (ghiera su B). */
    override fun bulbStart(): Shot {
        val code = if (PtpOp.CANON_RELEASE_ON in ops) {
            check(ptp.transaction(PtpOp.CANON_RELEASE_ON, intArrayOf(3, 1)), "Bulb start").code
        } else {
            check(ptp.transaction(PtpOp.CANON_BULB_START), "Bulb start").code
        }
        return shotFrom(code)
    }

    override fun bulbEnd() {
        if (PtpOp.CANON_RELEASE_ON in ops) {
            check(ptp.transaction(PtpOp.CANON_RELEASE_OFF, intArrayOf(3)), "Bulb end")
        } else {
            check(ptp.transaction(PtpOp.CANON_BULB_END), "Bulb end")
        }
    }

    private fun setPropEx(prop: Int, value: Int): PtpResponse {
        val data = Buf().u32(12).u32(prop).u32(value).bytes()
        return check(ptp.transaction(PtpOp.CANON_SET_PROP_EX, dataOut = data), "Proprietà ${hex(prop)}")
    }

    /**
     * Uscita del live view: è una maschera di bit (1 = schermo, 2 = PC grande,
     * 4 = smartphone medio, 8 = smartphone piccolo), come in libgphoto2. Le dimensioni
     * piccole pesano molto meno via Wi-Fi, ma non tutte le fotocamere le inviano davvero
     * (sulla 6D col Wi-Fi della fotocamera l'uscita 0x4 non dava fotogrammi): se un'uscita
     * non produce fotogrammi entro pochi secondi si passa alla successiva e la si ricorda.
     */
    private fun candidatesFor(q: LvQuality): List<Int> {
        val wanted = when {
            // col cavo USB la banda basta per l'uscita grande, tranne in «Fluido»
            ptp.wired && q != LvQuality.FLUID -> listOf(2, 4, 8)
            q == LvQuality.FLUID -> listOf(8, 4, 2)
            q == LvQuality.BALANCED -> listOf(4, 8, 2)
            else -> listOf(2, 4, 8)
        }
        val list = allowed[CanonProp.EVF_OUTPUT_DEVICE]
        val out = ArrayList<Int>()
        for (bit in wanted) {
            if (list == null) out.add(bit) else list.filter { (it and 0xE) == bit }.forEach { out.add(it) }
        }
        out.add(2)
        return out.distinct().filter { it !in badOutputs }.ifEmpty { listOf(2) }
    }

    private val badOutputs = HashSet<Int>()
    private var lvCandidates: List<Int> = listOf(2)
    private var lvTry = 0
    private var lvStartAt = 0L
    private var lvGotFrame = false
    private var lvLastCode = 0

    /** Uscita in uso (serve per tornarci dopo lo zoom). */
    private val currentOutput: Int get() = lvCandidates.getOrElse(lvTry) { 2 }

    private fun applyOutput() {
        while (lvTry < lvCandidates.size) {
            val out = lvCandidates[lvTry]
            if (setPropEx(CanonProp.EVF_OUTPUT_DEVICE, out).ok) {
                log("Live view: uscita ${hex(out)} (${quality.label})")
                break
            }
            badOutputs.add(out)
            lvTry++
        }
        if (lvTry >= lvCandidates.size) {
            lvCandidates = listOf(2)
            lvTry = 0
            setPropEx(CanonProp.EVF_OUTPUT_DEVICE, 2)
        }
        lvStartAt = System.currentTimeMillis()
        lvGotFrame = false
    }

    override fun startLiveView() {
        zoom = 1
        setPropEx(CanonProp.EVF_MODE, 1)
        allowed[CanonProp.EVF_OUTPUT_DEVICE]?.let { a -> log("Uscite live view ammesse: " + a.joinToString { hex(it) }) }
        lvCandidates = candidatesFor(quality)
        lvTry = 0
        applyOutput()
    }

    override fun autofocus(): Shot {
        return if (PtpOp.CANON_DO_AF in ops) {
            val r = check(ptp.transaction(PtpOp.CANON_DO_AF), "Messa a fuoco")
            // il tempo per mettere a fuoco (in live view la 6D usa il contrasto, più lento)
            Thread.sleep(1800)
            if (PtpOp.CANON_AF_CANCEL in ops) ptp.transaction(PtpOp.CANON_AF_CANCEL)
            shotFrom(r.code)
        } else {
            // mezza pressione del pulsante di scatto
            val r = check(ptp.transaction(PtpOp.CANON_RELEASE_ON, intArrayOf(1, 0)), "Messa a fuoco")
            Thread.sleep(1500)
            ptp.transaction(PtpOp.CANON_RELEASE_OFF, intArrayOf(1))
            shotFrom(r.code)
        }
    }

    override fun stopLiveView() {
        if (zoom > 1) runCatching { ptp.transaction(PtpOp.CANON_ZOOM, intArrayOf(1)) }
        zoom = 1
        setPropEx(CanonProp.EVF_OUTPUT_DEVICE, 0)
    }

    override fun liveViewFrame(): ByteArray? {
        val r = ptp.transaction(PtpOp.CANON_GET_VIEWFINDER, intArrayOf(0x00100000, 0, 0))
        lvLastCode = r.code
        val frame = if (r.ok) parseViewfinder(r.data) else null
        if (frame != null) {
            lvGotFrame = true
            return frame
        }
        // nessun fotogramma da questa uscita: dopo 3 s si prova la successiva
        if (!lvGotFrame && zoom == 1 && lvTry < lvCandidates.size - 1 && System.currentTimeMillis() - lvStartAt > 3000) {
            val bad = currentOutput
            badOutputs.add(bad)
            lvTry++
            log("Uscita ${hex(bad)} senza fotogrammi (risposta ${hex(lvLastCode)}): provo ${hex(currentOutput)}")
            applyOutput()
        }
        return null
    }

    private fun parseViewfinder(d: ByteArray): ByteArray? {
        var off = 0
        while (off + 8 <= d.size) {
            val size = le32(d, off)
            val type = le32(d, off + 4)
            if (size < 8 || off + size > d.size) break
            if ((type == 1 || type == 11) && size > 8) return d.copyOfRange(off + 8, off + size)
            off += size
        }
        return it.goletto.iride.camera.extractJpeg(d)
    }

    private companion object {
        const val LENS_NAME = 0xD1D8   // PTP_DPC_CANON_EOS_LensName
    }
}
