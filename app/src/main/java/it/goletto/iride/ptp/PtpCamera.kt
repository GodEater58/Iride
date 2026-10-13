package it.goletto.iride.ptp

import it.goletto.iride.camera.CamFile
import it.goletto.iride.camera.CameraDriver
import it.goletto.iride.camera.ExpoKey
import it.goletto.iride.camera.ExpoOption
import it.goletto.iride.camera.ExposureState
import java.io.IOException
import java.io.OutputStream

/** Codici di tipo dei valori PTP. */
object PtpType {
    const val INT8 = 0x0001
    const val UINT8 = 0x0002
    const val INT16 = 0x0003
    const val UINT16 = 0x0004
    const val INT32 = 0x0005
    const val UINT32 = 0x0006
    const val INT64 = 0x0007
    const val UINT64 = 0x0008
    const val STR = 0xFFFF
    const val ARRAY = 0x4000

    fun size(t: Int): Int = when (t and 0x0FFF) {
        INT8, UINT8 -> 1
        INT16, UINT16 -> 2
        INT32, UINT32 -> 4
        INT64, UINT64 -> 8
        0x0009, 0x000A -> 16
        else -> 0
    }
}

/** Descrittore di una proprietà PTP (standard o Sony). */
class PropDesc(
    val code: Int,
    val type: Int,
    val writable: Boolean,
    val current: Long,
    val values: List<Long>,
    val rangeMin: Long? = null,
    val rangeMax: Long? = null,
    val rangeStep: Long? = null,
)

class DeviceInfo(
    val vendorExtension: Int,
    val operations: Set<Int>,
    val properties: Set<Int>,
    val manufacturer: String,
    val model: String,
)

/**
 * Base comune per le fotocamere PTP/IP: informazioni sul dispositivo, elenco file,
 * miniature, download e proprietà standard. Le marche aggiungono i propri comandi.
 */
abstract class PtpCamera(protected val ptp: PtpIpClient, protected val log: (String) -> Unit) : CameraDriver {

    protected var info: DeviceInfo = DeviceInfo(0, emptySet(), emptySet(), "", "")
    protected val ops: Set<Int> get() = info.operations

    override val model: String get() = listOf(info.manufacturer.substringBefore(' ').trim(), info.model).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { ptp.cameraName }

    @Volatile
    private var expo = ExposureState()

    @Volatile
    override var exposureVersion: Int = 0
        protected set

    override fun exposure(): ExposureState = expo

    protected fun publishExposure(state: ExposureState) {
        if (state != expo) {
            expo = state
            exposureVersion++
        }
    }

    open fun initialize() {
        info = readDeviceInfo(ptp)
        log("Modello: ${info.manufacturer} ${info.model}, ${info.operations.size} operazioni")
    }

    override fun close() = ptp.close()

    protected fun check(r: PtpResponse, what: String): PtpResponse {
        if (!r.ok) log("$what: risposta ${hex(r.code)}")
        return r
    }

    protected fun shotFrom(code: Int): it.goletto.iride.camera.Shot = when (code) {
        PtpResp.OK -> it.goletto.iride.camera.Shot.OK
        PtpResp.DEVICE_BUSY, PtpResp.CANON_NOT_READY -> it.goletto.iride.camera.Shot.BUSY
        else -> it.goletto.iride.camera.Shot.FAILED
    }

    // ------------------------------------------------------------ file

    override fun listFiles(progress: (Int, Int) -> Unit): List<CamFile> {
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
                val f = objectInfo(handle)
                if (f != null) result.add(f)
                progress(i + 1, handles.size)
            }
        }
        return result.sortedWith(compareByDescending<CamFile> { it.captureDate }.thenByDescending { it.name })
    }

    /** Informazioni su un oggetto; null per le cartelle o in caso di errore. */
    protected fun objectInfo(handle: Int, transient: Boolean = false): CamFile? {
        val r = ptp.transaction(PtpOp.GET_OBJECT_INFO, intArrayOf(handle))
        if (!r.ok || r.data.size < 53) return null
        val x = Reader(r.data)
        x.u32()                                   // StorageID
        val format = x.u16()
        if (format == 0x3001) return null         // cartella
        x.u16()                                   // Protection
        val size = x.u32().toLong() and 0xFFFFFFFFL
        x.pos = 52
        val name = x.str()
        val date = x.str()
        return CamFile(handle.toLong().and(0xFFFFFFFFL).toString(), name, size, date, transient)
    }

    protected fun handleOf(f: CamFile): Int = f.id.toLong().toInt()

    override fun thumbnail(file: CamFile): ByteArray? {
        val r = ptp.transaction(PtpOp.GET_THUMB, intArrayOf(handleOf(file)))
        return if (r.ok && r.data.isNotEmpty()) r.data else null
    }

    override fun download(file: CamFile, out: OutputStream, progress: (Long, Long) -> Unit) {
        val r = ptp.transaction(PtpOp.GET_OBJECT, intArrayOf(handleOf(file)), sink = out, onProgress = progress)
        if (!r.ok) throw IOException("la fotocamera ha rifiutato il download (${hex(r.code)})")
    }

    /** Svuota gli eventi standard e restituisce gli oggetti aggiunti (ObjectAdded). */
    protected fun drainStandardEvents(onOther: (PtpEvent) -> Unit = {}): List<Int> {
        val added = ArrayList<Int>()
        while (true) {
            val e = ptp.events.poll() ?: break
            if (e.code == PtpEvt.OBJECT_ADDED && e.params.isNotEmpty()) added.add(e.params[0]) else onOther(e)
        }
        return added
    }

    // ------------------------------------------------------------ proprietà standard

    fun getPropDesc(code: Int): PropDesc? {
        if (code !in info.properties) return null
        val r = ptp.transaction(PtpOp.GET_DEVICE_PROP_DESC, intArrayOf(code))
        if (!r.ok) return null
        return runCatching { parseDpd(r.data, 0, sony = false, sonyV3 = false).first }.getOrNull()
    }

    fun setPropValue(code: Int, type: Int, value: Long): Boolean {
        val data = packValue(type, value) ?: return false
        return check(ptp.transaction(PtpOp.SET_DEVICE_PROP_VALUE, intArrayOf(code), dataOut = data), "Proprietà ${hex(code)}").ok
    }

    /** Pannello esposizione dalle proprietà standard PTP, con le formattazioni passate. */
    protected fun standardExposure(
        format: Map<ExpoKey, (Long) -> String>,
        props: Map<ExpoKey, Int>,
    ): ExposureState {
        val values = HashMap<ExpoKey, ExpoOption>()
        val options = HashMap<ExpoKey, List<ExpoOption>>()
        for ((key, code) in props) {
            val d = getPropDesc(code) ?: continue
            val f = format[key] ?: { v: Long -> v.toString() }
            values[key] = ExpoOption(d.current, f(d.current))
            if (d.writable && d.values.isNotEmpty()) options[key] = d.values.map { ExpoOption(it, f(it)) }
        }
        return ExposureState(values, options)
    }

    companion object {
        fun readDeviceInfo(ptp: PtpIpClient): DeviceInfo {
            val di = ptp.transaction(PtpOp.GET_DEVICE_INFO)
            if (!di.ok) return DeviceInfo(0, emptySet(), emptySet(), "", ptp.cameraName)
            val r = Reader(di.data)
            r.u16()                     // StandardVersion
            val vendor = r.u32()        // VendorExtensionID
            r.u16()                     // VendorExtensionVersion
            r.str()                     // VendorExtensionDesc
            r.u16()                     // FunctionalMode
            val ops = r.u16Array().toSet()
            r.u16Array()                // Events
            val props = r.u16Array().toSet()
            r.u16Array()                // CaptureFormats
            r.u16Array()                // ImageFormats
            val manufacturer = r.str()
            val model = r.str()
            return DeviceInfo(vendor, ops, props, manufacturer, model)
        }

        /** Legge un valore del tipo indicato. Le stringhe e gli array vengono saltati (valore 0). */
        fun readValue(d: ByteArray, off: Int, type: Int): Pair<Long, Int> {
            if (type == PtpType.STR) {
                val n = d[off].toInt() and 0xFF
                return Pair(0L, off + 1 + n * 2)
            }
            if (type and PtpType.ARRAY != 0) {
                val n = le32(d, off)
                return Pair(0L, off + 4 + n * PtpType.size(type))
            }
            return when (type) {
                PtpType.INT8 -> Pair(d[off].toLong(), off + 1)
                PtpType.UINT8 -> Pair((d[off].toInt() and 0xFF).toLong(), off + 1)
                PtpType.INT16 -> Pair(le16(d, off).toShort().toLong(), off + 2)
                PtpType.UINT16 -> Pair(le16(d, off).toLong(), off + 2)
                PtpType.INT32 -> Pair(le32(d, off).toLong(), off + 4)
                PtpType.UINT32 -> Pair(le32(d, off).toLong() and 0xFFFFFFFFL, off + 4)
                PtpType.INT64, PtpType.UINT64 -> Pair(le64(d, off), off + 8)
                else -> {
                    val s = PtpType.size(type)
                    if (s == 0) throw IOException("tipo PTP non gestito ${hex(type)}")
                    Pair(0L, off + s)
                }
            }
        }

        fun packValue(type: Int, v: Long): ByteArray? = when (type) {
            PtpType.INT8, PtpType.UINT8 -> Buf().u8(v.toInt()).bytes()
            PtpType.INT16, PtpType.UINT16 -> Buf().u16(v.toInt()).bytes()
            PtpType.INT32, PtpType.UINT32 -> Buf().u32(v.toInt()).bytes()
            PtpType.INT64, PtpType.UINT64 -> Buf().u64(v).bytes()
            else -> null
        }

        /**
         * Analizza un DevicePropDesc. Formato standard: codice, tipo, get/set, default, corrente, form.
         * Sony aggiunge un byte "abilitato" dopo get/set e, dalle versioni 3, una seconda lista di valori.
         * Restituisce il descrittore e l'offset subito dopo.
         */
        fun parseDpd(d: ByteArray, start: Int, sony: Boolean, sonyV3: Boolean): Pair<PropDesc, Int> {
            var o = start
            val code = le16(d, o); o += 2
            val type = le16(d, o); o += 2
            val getSet = d[o].toInt() and 0xFF; o += 1
            var enabled = 1
            if (sony) {
                enabled = d[o].toInt() and 0xFF; o += 1
            }
            val (_, afterDefault) = readValue(d, o, type); o = afterDefault
            val (current, afterCurrent) = readValue(d, o, type); o = afterCurrent
            var values: List<Long> = emptyList()
            var rMin: Long? = null
            var rMax: Long? = null
            var rStep: Long? = null
            if (o < d.size) {
                val form = d[o].toInt() and 0xFF; o += 1
                when (form) {
                    1 -> {
                        val (a, o1) = readValue(d, o, type)
                        val (b, o2) = readValue(d, o1, type)
                        val (c, o3) = readValue(d, o2, type)
                        rMin = a; rMax = b; rStep = c; o = o3
                    }
                    2 -> {
                        val n = le16(d, o); o += 2
                        val list = ArrayList<Long>(n)
                        repeat(n) {
                            val (v, nx) = readValue(d, o, type)
                            list.add(v); o = nx
                        }
                        values = list
                        // Sony recenti: seconda lista (valori impostabili) se il valore successivo non è un codice proprietà
                        if (sony && o + 2 <= d.size && le16(d, o) < 0x200) {
                            val n2 = le16(d, o); o += 2
                            val list2 = ArrayList<Long>(n2)
                            repeat(n2) {
                                val (v, nx) = readValue(d, o, type)
                                list2.add(v); o = nx
                            }
                            values = list2
                        }
                    }
                }
            }
            val writable = if (sony) {
                if (sonyV3) enabled == 1 && getSet == 1 else enabled == 1 || (getSet and 0x80) != 0
            } else {
                getSet == 1
            }
            return Pair(PropDesc(code, type, writable, current, values, rMin, rMax, rStep), o)
        }
    }
}
