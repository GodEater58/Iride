package it.goletto.iride.ptp

import it.goletto.iride.camera.Brand
import it.goletto.iride.camera.CamFile
import it.goletto.iride.camera.Capabilities
import it.goletto.iride.camera.ExpoKey
import it.goletto.iride.camera.Shot
import it.goletto.iride.camera.extractJpeg
import it.goletto.iride.camera.formatAperture
import it.goletto.iride.camera.formatSeconds

/**
 * Nikon via PTP/IP. Comandi presi da libgphoto2 (camlibs/ptp2):
 * scatto su scheda con InitiateCaptureRecInMedia, posa B con TerminateCapture,
 * live view con Start/GetLiveViewImg/End, eventi con GetEvent.
 */
class NikonDriver(ptp: PtpIpClient, log: (String) -> Unit) : PtpCamera(ptp, log) {

    override val brand = Brand.NIKON
    override var caps = Capabilities()
        private set

    private var pollCount = 0
    private var exposureDirty = true

    private val keyProp = mapOf(
        ExpoKey.MODE to PtpProp.EXPOSURE_PROGRAM,
        ExpoKey.TV to PtpProp.EXPOSURE_TIME,
        ExpoKey.AV to PtpProp.FNUMBER,
        ExpoKey.ISO to PtpProp.EXPOSURE_INDEX,
    )

    private val formats: Map<ExpoKey, (Long) -> String> = mapOf(
        ExpoKey.MODE to { v -> programLabel(v) },
        // tempo in decimillesimi di secondo; 0xFFFFFFFF = posa B, 0xFFFFFFFE = posa T
        ExpoKey.TV to { v ->
            when (v) {
                0xFFFFFFFFL -> "Bulb"
                0xFFFFFFFEL -> "Time"
                else -> formatSeconds(v / 10000.0)
            }
        },
        ExpoKey.AV to { v -> formatAperture(v / 100.0) },
        ExpoKey.ISO to { v -> v.toString() },
    )

    override fun initialize() {
        super.initialize()
        caps = Capabilities(
            liveView = NikonOp.START_LIVE_VIEW in ops,
            bulb = NikonOp.TERMINATE_CAPTURE in ops && NikonOp.CAPTURE_REC_IN_MEDIA in ops,
        )
        refreshExposure()
    }

    private fun programLabel(v: Long): String = when (v) {
        1L -> "M"
        2L -> "P"
        3L -> "A"
        4L -> "S"
        else -> "Auto"
    }

    private fun refreshExposure() {
        publishExposure(standardExposure(formats, keyProp))
        exposureDirty = false
    }

    override fun poll(): List<CamFile> {
        val added = ArrayList<Int>()
        added += drainStandardEvents { e -> if (e.code == PtpEvt.DEVICE_PROP_CHANGED) exposureDirty = true }
        if (NikonOp.GET_EVENT in ops) {
            val r = ptp.transaction(NikonOp.GET_EVENT)
            if (r.ok && r.data.size >= 2) {
                // u16 numero eventi, poi per ciascuno u16 codice + u32 parametro
                val n = le16(r.data, 0)
                for (i in 0 until n) {
                    val o = 2 + i * 6
                    if (o + 6 > r.data.size) break
                    when (le16(r.data, o)) {
                        PtpEvt.OBJECT_ADDED -> added.add(le32(r.data, o + 2))
                        PtpEvt.DEVICE_PROP_CHANGED -> exposureDirty = true
                    }
                }
            }
        }
        pollCount++
        if (exposureDirty || pollCount % 8 == 0) refreshExposure()
        return added.distinct().mapNotNull { objectInfo(it) }
    }

    /** Attende che la fotocamera non sia più occupata (DeviceReady). */
    private fun waitReady(timeoutMs: Long) {
        if (NikonOp.DEVICE_READY !in ops) return
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val r = ptp.transaction(NikonOp.DEVICE_READY)
            if (r.code != PtpResp.DEVICE_BUSY) return
            Thread.sleep(100)
        }
    }

    override fun capture(autofocus: Boolean): Shot {
        val code = when {
            NikonOp.CAPTURE_REC_IN_MEDIA in ops ->
                // primo parametro: 0xFFFFFFFE con autofocus, 0xFFFFFFFF senza; secondo: 0 = scheda
                ptp.transaction(NikonOp.CAPTURE_REC_IN_MEDIA, intArrayOf(if (autofocus) -2 else -1, 0)).code
            PtpOp.INITIATE_CAPTURE in ops -> ptp.transaction(PtpOp.INITIATE_CAPTURE, intArrayOf(0, 0)).code
            else -> ptp.transaction(NikonOp.CAPTURE_SDRAM, intArrayOf(if (autofocus) 0 else 1)).code
        }
        if (code == PtpResp.OK) waitReady(4000) else log("Scatto: risposta ${hex(code)}")
        return shotFrom(code)
    }

    override fun bulbStart(): Shot {
        // Come libgphoto2: tempo su posa B e scatto senza autofocus. Serve il modo M.
        setPropValue(PtpProp.EXPOSURE_TIME, PtpType.UINT32, 0xFFFFFFFFL)
        val code = ptp.transaction(NikonOp.CAPTURE_REC_IN_MEDIA, intArrayOf(-1, 0)).code
        if (code != PtpResp.OK) log("Posa B: risposta ${hex(code)} (la ghiera è su M?)")
        return shotFrom(code)
    }

    override fun bulbEnd() {
        check(ptp.transaction(NikonOp.TERMINATE_CAPTURE, intArrayOf(0, 0)), "Fine posa B")
        waitReady(5000)
    }

    override fun startLiveView() {
        check(ptp.transaction(NikonOp.START_LIVE_VIEW), "Avvio live view")
        waitReady(3000)
    }

    override fun stopLiveView() {
        check(ptp.transaction(NikonOp.END_LIVE_VIEW), "Fine live view")
    }

    override fun liveViewFrame(): ByteArray? {
        val r = ptp.transaction(NikonOp.GET_LIVE_VIEW_IMG)
        if (!r.ok) return null
        // davanti al JPEG c'è un'intestazione la cui lunghezza cambia con il modello
        return extractJpeg(r.data)
    }

    override fun setExposure(key: ExpoKey, code: Long): Boolean {
        val prop = keyProp[key] ?: return false
        val d = getPropDesc(prop) ?: return false
        val ok = setPropValue(prop, d.type, code)
        if (ok) refreshExposure()
        return ok
    }
}
