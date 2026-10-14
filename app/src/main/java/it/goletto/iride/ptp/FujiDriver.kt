package it.goletto.iride.ptp

import it.goletto.iride.camera.Brand
import it.goletto.iride.camera.CamFile
import it.goletto.iride.camera.CameraDriver
import it.goletto.iride.camera.Capabilities
import it.goletto.iride.camera.ExpoKey
import it.goletto.iride.camera.ExpoOption
import it.goletto.iride.camera.ExposureState
import it.goletto.iride.camera.Shot
import it.goletto.iride.camera.extractJpeg
import it.goletto.iride.camera.formatAperture
import it.goletto.iride.camera.formatEv
import it.goletto.iride.camera.standardWbLabel
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.concurrent.thread

/**
 * Fujifilm via Wi-Fi ("controllo remoto" dell'app Fujifilm). Il protocollo si discosta dal PTP/IP
 * standard: porte 55740 (comandi), 55741 (eventi), 55742 (live view) e pacchetti senza tipo.
 * Sequenza e formati come nel progetto open source malc0mn/ptp-ip (provato su X-T1).
 * Per ogni scatto arriva sul telefono l'anteprima JPEG; la scheda non è consultabile.
 */
class FujiDriver(
    private val socketFactory: SocketFactory,
    private val host: String,
    private val log: (String) -> Unit,
) : CameraDriver {

    override val brand = Brand.FUJIFILM
    override var model: String = "Fujifilm"
        private set
    override val caps = Capabilities(
        bulb = false,
        cardBrowsing = false,
        autofocusToggle = false,
        galleryNote = "Con Fujifilm arriva sul telefono l'anteprima JPEG di ogni scatto. Gli originali restano sulla scheda.",
    )

    private var cmd: Socket? = null
    private var cmdIn: InputStream? = null
    private var cmdOut: OutputStream? = null
    private var evt: Socket? = null
    private var stream: Socket? = null
    private var tid = 0
    private val lock = Any()
    private val events = LinkedBlockingQueue<IntArray>() // [codice, p1, p2]
    private val previews = ConcurrentHashMap<String, ByteArray>()
    private val newFiles = LinkedBlockingQueue<CamFile>()
    private val nameFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)
    private val dateFmt = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.ROOT)

    @Volatile
    private var open = false

    @Volatile
    private var expo = ExposureState()

    @Volatile
    override var exposureVersion = 0
        private set

    override fun exposure() = expo

    fun connect(guid: ByteArray, friendlyName: String, preconnected: Socket? = null) {
        val s = preconnected ?: socketFactory.createSocket().also {
            it.tcpNoDelay = true
            it.connect(InetSocketAddress(host, CMD_PORT), 8000)
        }
        s.soTimeout = 60_000
        cmd = s
        val inp = BufferedInputStream(s.getInputStream(), 256 * 1024)
        val out = BufferedOutputStream(s.getOutputStream())
        cmdIn = inp
        cmdOut = out

        // InitCommandRequest standard, ma con la versione di protocollo Fuji in testa
        val init = Buf().u32(PROTOCOL_VERSION).raw(guid).utf16z(friendlyName).bytes()
        out.write(Buf().u32(init.size + 8).u32(PtpIp.INIT_COMMAND_REQUEST).raw(init).bytes())
        out.flush()
        val ack = readRaw(inp)
        val type = le32(ack, 4)
        if (type == PtpIp.INIT_FAIL) throw PtpException("La Fujifilm ha rifiutato la connessione")
        if (type != PtpIp.INIT_COMMAND_ACK) throw PtpException("Risposta inattesa dalla Fujifilm (tipo $type)")
        if (ack.size > 28) model = "Fujifilm " + readUtf16z(ack, 28)
        log("Fotocamera: $model")

        op(PtpOp.OPEN_SESSION, 1)
        log("Se la fotocamera chiede di accettare «$friendlyName», premi OK")
        setProp(INIT_SEQUENCE, 5)
        val appVersion = getProp(APP_VERSION)
        setProp(APP_VERSION, appVersion)
        op(INITIATE_OPEN_CAPTURE, 0)
        open = true

        // il canale eventi si apre dopo InitiateOpenCapture e non richiede inizializzazione
        val e = socketFactory.createSocket()
        e.tcpNoDelay = true
        e.connect(InetSocketAddress(host, EVENT_PORT), 5000)
        evt = e
        thread(isDaemon = true, name = "fuji-events") { readEvents(e) }
        readDescs()
        refreshState()
    }

    // ------------------------------------------------------------ trasporto

    private fun readFully(inp: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(b, off, n - off)
            if (r < 0) throw IOException("Connessione chiusa dalla fotocamera")
            off += r
        }
        return b
    }

    /** Legge un pacchetto completo, lunghezza inclusa. */
    private fun readRaw(inp: InputStream): ByteArray {
        val h = readFully(inp, 4)
        val len = le32(h, 0)
        if (len < 4 || len > 64 * 1024 * 1024) throw PtpException("Pacchetto Fuji non valido ($len)")
        return h + readFully(inp, len - 4)
    }

    private fun request(dataPhase: Int, code: Int, t: Int, p1: Int): ByteArray =
        // lunghezza, fase dati (u16), codice (u16), transazione, 5 parametri
        Buf().u32(32).u16(dataPhase).u16(code).u32(t).u32(p1).u32(0).u32(0).u32(0).u32(0).bytes()

    /**
     * Esegue un'operazione e restituisce (codice risposta, dati). I pacchetti dati hanno fase 2;
     * l'ultimo pacchetto porta il codice di risposta.
     */
    private fun op(code: Int, p1: Int, dataValue: Int? = null, multi: Boolean = false): Pair<Int, ByteArray> = synchronized(lock) {
        try {
            opLocked(code, p1, dataValue, multi)
        } catch (e: IOException) {
            // errore di rete a metà operazione: il flusso non è più allineato, si chiude
            if (e !is PtpException && open) {
                open = false
                log("Collegamento interrotto: ${e.message}")
                runCatching { cmd?.close() }
                runCatching { evt?.close() }
            }
            throw e
        }
    }

    private fun opLocked(code: Int, p1: Int, dataValue: Int?, multi: Boolean): Pair<Int, ByteArray> {
        val out = cmdOut ?: throw PtpException("Non connesso")
        val inp = cmdIn ?: throw PtpException("Non connesso")
        val t = ++tid
        out.write(request(1, code, t, p1))
        if (dataValue != null) out.write(request(2, code, t, dataValue))
        out.flush()
        val data = ByteArrayOutputStream()
        while (true) {
            val pkt = readRaw(inp)
            if (pkt.size < 12) continue
            val phase = le16(pkt, 4)
            val c = le16(pkt, 6)
            val payloadStart = 12
            if (phase == 2 || (multi && c == code)) {
                if (pkt.size > payloadStart) data.write(pkt, payloadStart, pkt.size - payloadStart)
                continue
            }
            if (c != PtpResp.OK && c != code && c != PtpResp.SESSION_ALREADY_OPEN) {
                throw PtpException("Operazione ${hex(code)} rifiutata (${hex(c)})")
            }
            if (pkt.size > payloadStart) data.write(pkt, payloadStart, pkt.size - payloadStart)
            return Pair(c, data.toByteArray())
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException()
    }

    private fun setProp(code: Int, value: Int) {
        op(PtpOp.SET_DEVICE_PROP_VALUE, code, dataValue = value)
    }

    private fun getProp(code: Int): Int {
        val (_, d) = op(PtpOp.GET_DEVICE_PROP_VALUE, code)
        return when {
            d.size >= 4 -> le32(d, 0)
            d.size >= 2 -> le16(d, 0)
            else -> 0
        }
    }

    private fun readEvents(s: Socket) {
        try {
            val inp = BufferedInputStream(s.getInputStream())
            while (open) {
                val p = readRaw(inp)
                // lunghezza, fase (u16), codice (u16), numero parametri, transazione, p1, p2, p3
                if (p.size < 8) continue
                val code = le16(p, 6)
                val p1 = if (p.size >= 20) le32(p, 16) else 0
                val p2 = if (p.size >= 24) le32(p, 20) else 0
                events.offer(intArrayOf(code, p1, p2))
            }
        } catch (e: Exception) {
            if (open) log("Canale eventi Fuji chiuso: ${e.message}")
        }
    }

    private fun waitEvent(code: Int, timeoutMs: Long): IntArray? {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = end - System.currentTimeMillis()
            if (left <= 0) return null
            val e = events.poll(left, TimeUnit.MILLISECONDS) ?: return null
            if (e[0] == code) return e
        }
    }

    // ------------------------------------------------------------ stato

    /** Proprietà correnti in un colpo solo: u16 numero, poi (u16 codice, u32 valore). */
    private fun refreshState() {
        val d = runCatching { op(PtpOp.GET_DEVICE_PROP_VALUE, CURRENT_STATE).second }.getOrNull() ?: return
        if (d.size < 2) return
        val n = le16(d, 0)
        val props = HashMap<Int, Long>()
        for (i in 0 until n) {
            val o = 2 + i * 6
            if (o + 6 > d.size) break
            props[le16(d, o)] = le32(d, o + 2).toLong() and 0xFFFFFFFFL
        }
        val values = HashMap<ExpoKey, ExpoOption>()
        val options = HashMap<ExpoKey, List<ExpoOption>>()
        props[PtpProp.FNUMBER]?.let { if (it > 0) values[ExpoKey.AV] = ExpoOption(it, label(ExpoKey.AV, it)) }
        props[EXPOSURE_INDEX]?.let { values[ExpoKey.ISO] = ExpoOption(it, label(ExpoKey.ISO, it)) }
        props[PtpProp.EXPOSURE_BIAS]?.let { values[ExpoKey.EV] = ExpoOption(it, label(ExpoKey.EV, it)) }
        props[PtpProp.WHITE_BALANCE]?.let { values[ExpoKey.WB] = ExpoOption(it, label(ExpoKey.WB, it)) }
        props[CAPTURES_REMAINING]?.let { values[ExpoKey.SHOTS] = ExpoOption(it, it.toString()) }
        // valori ammessi dai descrittori standard (GetDevicePropDesc), letti una volta e aggiornati ogni tanto
        for ((key, code) in keyProp) {
            val d = descs[code] ?: continue
            values.getOrPut(key) { ExpoOption(d.current, label(key, d.current)) }
            if (d.writable && d.values.size > 1) options[key] = d.values.map { ExpoOption(it, label(key, it)) }
        }
        val state = ExposureState(values, options)
        if (state != expo) {
            expo = state
            exposureVersion++
        }
    }

    private val keyProp = mapOf(
        ExpoKey.AV to PtpProp.FNUMBER,
        ExpoKey.ISO to EXPOSURE_INDEX,
        ExpoKey.EV to PtpProp.EXPOSURE_BIAS,
        ExpoKey.WB to PtpProp.WHITE_BALANCE,
    )
    private val descs = ConcurrentHashMap<Int, PropDesc>()

    private fun label(key: ExpoKey, v: Long): String = when (key) {
        ExpoKey.AV -> formatAperture(v / 100.0)
        ExpoKey.ISO -> isoLabel(v)
        ExpoKey.EV -> formatEv(v.toShort() / 1000.0)
        ExpoKey.WB -> standardWbLabel(v, Brand.FUJIFILM)
        else -> v.toString()
    }

    private fun readDescs() {
        for (code in keyProp.values) {
            val d = runCatching { op(PtpOp.GET_DEVICE_PROP_DESC, code).second }.getOrNull() ?: continue
            if (d.size < 6) continue
            runCatching { PtpCamera.parseDpd(d, 0, sony = false, sonyV3 = false).first }.getOrNull()?.let { descs[code] = it }
        }
    }

    private fun isoLabel(v: Long): String {
        if (v == 0xFFFFFFFFL) return "Auto"
        val value = v and 0xFFFF
        return when ((v ushr 16).toInt()) {
            0x4000 -> (if (value < 200) "L" else "H") + value
            0x8000 -> "S$value"
            else -> value.toString()
        }
    }

    private var pollCount = 0

    override fun poll(): List<CamFile> {
        pollCount++
        if (pollCount % 20 == 0) readDescs()
        if (pollCount % 5 == 0) refreshState()
        val out = ArrayList<CamFile>()
        while (true) out.add(newFiles.poll() ?: break)
        return out
    }

    override fun capture(autofocus: Boolean): Shot {
        events.clear()
        return try {
            op(PtpOp.INITIATE_CAPTURE, 0)
            waitEvent(EVT_OBJECT_ADDED, 30_000)
            val preview = waitEvent(EVT_PREVIEW_AVAILABLE, 30_000)
            // l'anteprima va letta, altrimenti la fotocamera non accetta lo scatto successivo
            val (_, img) = op(GET_CAPTURE_PREVIEW, 0, multi = true)
            waitEvent(PtpEvt.CAPTURE_COMPLETE, 10_000)
            if (preview != null && img.isNotEmpty()) {
                val now = Date()
                val id = "fuji-${now.time}"
                previews[id] = img
                // tiene in memoria solo le ultime anteprime (sono già salvate sul telefono)
                if (previews.size > 30) previews.keys.minByOrNull { it.removePrefix("fuji-").toLongOrNull() ?: 0L }?.let { previews.remove(it) }
                newFiles.offer(CamFile(id, "FUJI_${nameFmt.format(now)}.JPG", img.size.toLong(), dateFmt.format(now), transient = true))
            }
            Shot.OK
        } catch (e: PtpException) {
            log("Scatto: ${e.message}")
            if (e.message?.contains(hex(PtpResp.DEVICE_BUSY)) == true) Shot.BUSY else Shot.FAILED
        }
    }

    override fun bulbStart(): Shot = Shot.FAILED
    override fun bulbEnd() = Unit

    // ------------------------------------------------------------ live view

    @Volatile
    private var latest: ByteArray? = null

    override fun startLiveView() {
        val s = socketFactory.createSocket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, STREAM_PORT), 5000)
        s.soTimeout = 10_000
        stream = s
        thread(isDaemon = true, name = "fuji-liveview") {
            try {
                val inp = BufferedInputStream(s.getInputStream(), 256 * 1024)
                while (stream === s) {
                    val p = readRaw(inp)
                    // intestazione di 18 byte, poi il JPEG
                    if (p.size > 18) latest = extractJpeg(p, 18)
                }
            } catch (e: Exception) {
                if (stream === s) log("Live view Fuji interrotto: ${e.message}")
            }
        }
    }

    override fun stopLiveView() {
        val s = stream
        stream = null
        latest = null
        runCatching { s?.close() }
    }

    override fun liveViewFrame(): ByteArray? {
        val f = latest
        latest = null
        if (f == null) Thread.sleep(40)
        return f
    }

    // ------------------------------------------------------------ file

    override fun listFiles(progress: (Int, Int) -> Unit): List<CamFile> = emptyList()

    override fun thumbnail(file: CamFile): ByteArray? = previews[file.id]

    override fun download(file: CamFile, out: OutputStream, progress: (Long, Long) -> Unit) {
        val img = previews[file.id] ?: throw IOException("anteprima non più disponibile")
        out.write(img)
        progress(img.size.toLong(), img.size.toLong())
    }

    override fun setExposure(key: ExpoKey, code: Long): Boolean {
        val prop = keyProp[key] ?: return false
        return try {
            setProp(prop, code.toInt())
            descs[prop]?.let { descs[prop] = PropDesc(it.code, it.type, it.writable, code, it.values) }
            refreshState()
            true
        } catch (e: Exception) {
            log("${key.title}: ${e.message}")
            false
        }
    }

    override fun close() {
        if (open) runCatching { op(PtpOp.CLOSE_SESSION, 0) }
        open = false
        stopLiveView()
        runCatching { evt?.close() }
        runCatching { cmd?.close() }
    }

    companion object {
        const val CMD_PORT = 55740
        const val EVENT_PORT = 55741
        const val STREAM_PORT = 55742
        private const val PROTOCOL_VERSION = -0x70AC1B0E // 0x8F53E4F2
        private const val INIT_SEQUENCE = 0xDF01
        private const val APP_VERSION = 0xDF24
        private const val CURRENT_STATE = 0xD212
        private const val EXPOSURE_INDEX = 0xD02A
        private const val CAPTURES_REMAINING = 0xD229
        private const val INITIATE_OPEN_CAPTURE = 0x101C
        private const val GET_CAPTURE_PREVIEW = 0x9022
        private const val EVT_PREVIEW_AVAILABLE = 0xC001
        private const val EVT_OBJECT_ADDED = 0xC004
    }
}
