package it.goletto.iride.ptp

import it.goletto.iride.camera.standardWbLabel
import it.goletto.iride.camera.formatEv
import it.goletto.iride.camera.Brand
import it.goletto.iride.camera.CamFile
import it.goletto.iride.camera.Capabilities
import it.goletto.iride.camera.ExpoKey
import it.goletto.iride.camera.Shot
import it.goletto.iride.camera.formatAperture
import it.goletto.iride.camera.formatSeconds

/**
 * Qualsiasi fotocamera PTP/IP che non ha un driver dedicato (es. Ricoh, Pentax, modelli rari):
 * solo comandi standard. Scatto con InitiateCapture, file e download, esposizione se esposta.
 */
class GenericPtpDriver(ptp: PtpIpClient, log: (String) -> Unit) : PtpCamera(ptp, log) {

    override val brand = Brand.OTHER_PTP
    override var caps = Capabilities(liveView = false, bulb = false)
        private set

    private var pollCount = 0

    private val keyProp = mapOf(
        ExpoKey.MODE to PtpProp.EXPOSURE_PROGRAM,
        ExpoKey.TV to PtpProp.EXPOSURE_TIME,
        ExpoKey.AV to PtpProp.FNUMBER,
        ExpoKey.ISO to PtpProp.EXPOSURE_INDEX,
        ExpoKey.EV to PtpProp.EXPOSURE_BIAS,
        ExpoKey.WB to PtpProp.WHITE_BALANCE,
    )

    private val formats: Map<ExpoKey, (Long) -> String> = mapOf(
        ExpoKey.MODE to { v -> when (v) { 1L -> "M"; 2L -> "P"; 3L -> "A"; 4L -> "S"; else -> "Auto" } },
        ExpoKey.TV to { v -> formatSeconds(v / 10000.0) },
        ExpoKey.AV to { v -> formatAperture(v / 100.0) },
        ExpoKey.ISO to { v -> v.toString() },
        // compensazione in millesimi di EV (INT16)
        ExpoKey.EV to { v -> formatEv(v.toShort() / 1000.0) },
        ExpoKey.WB to { v -> standardWbLabel(v, brand) },
    )

    override fun initialize() {
        super.initialize()
        caps = Capabilities(
            liveView = false,
            bulb = false,
            exposureControl = keyProp.values.any { it in info.properties },
        )
        publishExposure(standardExposure(formats, keyProp))
    }

    override fun poll(): List<CamFile> {
        var dirty = false
        val added = drainStandardEvents { e -> if (e.code == PtpEvt.DEVICE_PROP_CHANGED) dirty = true }
        pollCount++
        if (dirty || pollCount % 10 == 0) publishExposure(standardExposure(formats, keyProp))
        return added.mapNotNull { objectInfo(it) }
    }

    override fun capture(autofocus: Boolean): Shot {
        val r = check(ptp.transaction(PtpOp.INITIATE_CAPTURE, intArrayOf(0, 0)), "Scatto")
        return shotFrom(r.code)
    }

    override fun bulbStart(): Shot = Shot.FAILED
    override fun bulbEnd() = Unit
    override fun startLiveView() = Unit
    override fun stopLiveView() = Unit
    override fun liveViewFrame(): ByteArray? = null

    override fun setExposure(key: ExpoKey, code: Long): Boolean {
        val prop = keyProp[key] ?: return false
        val d = getPropDesc(prop) ?: return false
        val ok = setPropValue(prop, d.type, code)
        if (ok) publishExposure(standardExposure(formats, keyProp))
        return ok
    }
}
