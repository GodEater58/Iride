package it.goletto.eoslink.ptp

import java.io.OutputStream

class CamFile(
    val handle: Int,
    val name: String,
    val size: Long,
    val format: Int,
    val captureDate: String,
) {
    val isRaw: Boolean
        get() = name.endsWith(".CR2", true) || name.endsWith(".CR3", true) ||
            format == 0xB103 || format == 0xB108

    val isJpeg: Boolean
        get() = name.endsWith(".JPG", true) || name.endsWith(".JPEG", true) || format == 0x3801
}

/** Operazioni di alto livello per le Canon EOS (6D, 1300D e simili) sopra PTP/IP. */
class CanonCamera(private val ptp: PtpIpClient, private val log: (String) -> Unit) {

    var model: String = ""
        private set
    private var ops: Set<Int> = emptySet()

    fun initialize() {
        try {
            val di = ptp.transaction(PtpOp.GET_DEVICE_INFO)
            if (di.ok) {
                val r = Reader(di.data)
                r.u16()          // StandardVersion
                r.u32()          // VendorExtensionID
                r.u16()          // VendorExtensionVersion
                r.str()          // VendorExtensionDesc
                r.u16()          // FunctionalMode
                ops = r.u16Array().toSet()
                r.u16Array()     // Events
                r.u16Array()     // DeviceProperties
                r.u16Array()     // CaptureFormats
                r.u16Array()     // ImageFormats
                val manufacturer = r.str()
                model = r.str()
                log("Modello: $manufacturer $model, ${ops.size} operazioni supportate")
            }
        } catch (e: Exception) {
            log("DeviceInfo non leggibile: ${e.message}")
        }
        if (PtpOp.CANON_SET_REMOTE_MODE in ops) check(ptp.transaction(PtpOp.CANON_SET_REMOTE_MODE, intArrayOf(1)), "SetRemoteMode")
        if (PtpOp.CANON_SET_EVENT_MODE in ops) check(ptp.transaction(PtpOp.CANON_SET_EVENT_MODE, intArrayOf(1)), "SetEventMode")
        // La prima GetEvent restituisce lo stato completo: la scartiamo
        repeat(2) { runCatching { pollEvents() } }
    }

    private fun check(r: PtpResponse, what: String): PtpResponse {
        if (!r.ok) log("$what: risposta ${hex(r.code)}")
        return r
    }

    /** Legge la coda eventi Canon e restituisce gli handle dei nuovi file creati. */
    fun pollEvents(): List<Int> {
        if (PtpOp.CANON_GET_EVENT !in ops) return emptyList()
        val r = ptp.transaction(PtpOp.CANON_GET_EVENT)
        if (!r.ok) return emptyList()
        val d = r.data
        val added = ArrayList<Int>()
        var off = 0
        while (off + 8 <= d.size) {
            val size = le32(d, off)
            val type = le32(d, off + 4)
            if (size < 8 || type == 0) break
            if (type == CanonEvent.OBJECT_ADDED_EX && off + 12 <= d.size) added.add(le32(d, off + 8))
            off += size
        }
        return added
    }

    /** Scatto singolo. Restituisce il codice di risposta (OK, occupata, ecc.). */
    fun capture(autofocus: Boolean): Int {
        return if (PtpOp.CANON_RELEASE_ON in ops) {
            val r = ptp.transaction(PtpOp.CANON_RELEASE_ON, intArrayOf(3, if (autofocus) 0 else 1))
            ptp.transaction(PtpOp.CANON_RELEASE_OFF, intArrayOf(3))
            check(r, "Scatto").code
        } else {
            check(ptp.transaction(PtpOp.CANON_REMOTE_RELEASE), "Scatto").code
        }
    }

    /** Inizio posa Bulb (ghiera su B). */
    fun bulbStart(): Int {
        return if (PtpOp.CANON_RELEASE_ON in ops) {
            check(ptp.transaction(PtpOp.CANON_RELEASE_ON, intArrayOf(3, 1)), "Bulb start").code
        } else {
            check(ptp.transaction(PtpOp.CANON_BULB_START), "Bulb start").code
        }
    }

    fun bulbEnd() {
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

    fun startLiveView() {
        setPropEx(CanonProp.EVF_MODE, 1)
        setPropEx(CanonProp.EVF_OUTPUT_DEVICE, 2)
    }

    fun stopLiveView() {
        setPropEx(CanonProp.EVF_OUTPUT_DEVICE, 0)
    }

    /** Un frame JPEG del live view, oppure null se la fotocamera non è ancora pronta. */
    fun liveViewFrame(): ByteArray? {
        val r = ptp.transaction(PtpOp.CANON_GET_VIEWFINDER, intArrayOf(0x00100000, 0, 0))
        if (!r.ok) return null
        return extractJpeg(r.data)
    }

    private fun extractJpeg(d: ByteArray): ByteArray? {
        var off = 0
        while (off + 8 <= d.size) {
            val size = le32(d, off)
            val type = le32(d, off + 4)
            if (size < 8 || off + size > d.size) break
            if ((type == 1 || type == 11) && size > 8) return d.copyOfRange(off + 8, off + size)
            off += size
        }
        for (i in 0 until d.size - 1) {
            if (d[i] == 0xFF.toByte() && d[i + 1] == 0xD8.toByte()) return d.copyOfRange(i, d.size)
        }
        return null
    }

    fun listFiles(onProgress: (Int, Int) -> Unit): List<CamFile> {
        val st = ptp.transaction(PtpOp.GET_STORAGE_IDS)
        val storages = if (st.ok) Reader(st.data).u32Array() else intArrayOf(-1)
        val result = ArrayList<CamFile>()
        for (s in storages) {
            if (s != -1 && (s and 0xFFFF) == 0) continue // scheda non inserita
            val h = ptp.transaction(PtpOp.GET_OBJECT_HANDLES, intArrayOf(s, 0, 0))
            if (!h.ok) {
                log("Elenco file storage ${hex(s)}: ${hex(h.code)}")
                continue
            }
            val handles = Reader(h.data).u32Array()
            handles.forEachIndexed { i, handle ->
                val f = info(handle)
                if (f != null && f.format != 0x3001) result.add(f)
                onProgress(i + 1, handles.size)
            }
        }
        return result.sortedWith(compareByDescending<CamFile> { it.captureDate }.thenByDescending { it.name })
    }

    fun info(handle: Int): CamFile? {
        val r = ptp.transaction(PtpOp.GET_OBJECT_INFO, intArrayOf(handle))
        if (!r.ok || r.data.size < 53) return null
        val x = Reader(r.data)
        x.u32()                                   // StorageID
        val format = x.u16()
        x.u16()                                   // Protection
        val size = x.u32().toLong() and 0xFFFFFFFFL
        x.pos = 52
        val name = x.str()
        val date = x.str()
        return CamFile(handle, name, size, format, date)
    }

    fun thumbnail(handle: Int): ByteArray? {
        val r = ptp.transaction(PtpOp.GET_THUMB, intArrayOf(handle))
        return if (r.ok && r.data.isNotEmpty()) r.data else null
    }

    fun download(file: CamFile, out: OutputStream, progress: (Long, Long) -> Unit): Boolean {
        val r = ptp.transaction(PtpOp.GET_OBJECT, intArrayOf(file.handle), sink = out, onProgress = progress)
        return r.ok
    }
}
