package it.goletto.iride.ptp

import it.goletto.iride.camera.LvQuality
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
        caps = Capabilities(
            autofocusButton = PtpOp.CANON_DO_AF in ops || PtpOp.CANON_RELEASE_ON in ops,
            liveViewQuality = true,
        )
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
                CanonEvent.PROP_VALUE_CHANGED -> if (size >= 16) {
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
     * piccole pesano molto meno via Wi-Fi.
     */
    private fun outputFor(q: LvQuality): Int {
        val wanted = when {
            // col cavo USB la banda basta per l'uscita grande, tranne in «Fluido»
            ptp.wired && q != LvQuality.FLUID -> listOf(2, 4, 8)
            q == LvQuality.FLUID -> listOf(8, 4, 2)
            q == LvQuality.BALANCED -> listOf(4, 8, 2)
            else -> listOf(2, 4, 8)
        }
        val list = allowed[CanonProp.EVF_OUTPUT_DEVICE]
        for (bit in wanted) {
            if (list == null) return wanted.first().let { if (it == 8 || it == 4) 4 else 2 }
            list.firstOrNull { (it and 0xE) == bit }?.let { return it }
        }
        return 2
    }

    override fun startLiveView() {
        setPropEx(CanonProp.EVF_MODE, 1)
        val out = outputFor(quality)
        if (!setPropEx(CanonProp.EVF_OUTPUT_DEVICE, out).ok && out != 2) {
            // dimensione non accettata: si torna a quella standard per PC
            setPropEx(CanonProp.EVF_OUTPUT_DEVICE, 2)
        }
        log("Live view: uscita ${hex(out)} (${quality.label})")
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
        setPropEx(CanonProp.EVF_OUTPUT_DEVICE, 0)
    }

    override fun liveViewFrame(): ByteArray? {
        val r = ptp.transaction(PtpOp.CANON_GET_VIEWFINDER, intArrayOf(0x00100000, 0, 0))
        if (!r.ok) return null
        val d = r.data
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
}
