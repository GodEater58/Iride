package it.goletto.iride.http

import it.goletto.iride.camera.Brand
import it.goletto.iride.camera.CamFile
import it.goletto.iride.camera.CameraDriver
import it.goletto.iride.camera.Capabilities
import it.goletto.iride.camera.ExpoKey
import it.goletto.iride.camera.ExpoOption
import it.goletto.iride.camera.ExposureState
import it.goletto.iride.camera.Shot
import it.goletto.iride.camera.extractJpeg
import it.goletto.iride.camera.formatEv
import it.goletto.iride.net.NetContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * Olympus / OM System via Wi-Fi (protocollo dell'app OI.Share): comandi HTTP *.cgi verso
 * 192.168.0.10 con risposte XML, live view RTP su UDP. Comandi come nella libreria olympus-wifi.
 */
class OlympusDriver(
    private val net: NetContext,
    private val host: String,
    private val log: (String) -> Unit,
) : CameraDriver {

    override val brand = Brand.OLYMPUS
    override var model: String = "Olympus"
        private set
    override var caps = Capabilities(bulb = false, autofocusToggle = false)
        private set

    private val headers = mapOf("User-Agent" to "OI.Share v2", "Host" to host)
    private var commands: String = ""
    private var mode = ""
    private val lock = Any()

    @Volatile
    private var expo = ExposureState()

    @Volatile
    override var exposureVersion = 0
        private set

    override fun exposure() = expo

    private val propOf = mapOf(
        ExpoKey.MODE to "takemode",
        ExpoKey.TV to "shutspeedvalue",
        ExpoKey.AV to "focalvalue",
        ExpoKey.ISO to "isospeedvalue",
        ExpoKey.EV to "expcomp",
        ExpoKey.WB to "wbvalue",
    )
    private val enums = HashMap<ExpoKey, List<String>>()

    private fun url(cmd: String, params: Map<String, String>): String {
        val q = params.entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8").replace("%2F", "/") }
        return "http://$host/$cmd.cgi" + if (q.isNotEmpty()) "?$q" else ""
    }

    private fun get(cmd: String, params: Map<String, String> = emptyMap(), timeoutMs: Int = 8000): String =
        String(net.request(url(cmd, params), headers = headers, timeoutMs = timeoutMs))

    private fun switchMode(m: String, extra: Map<String, String> = emptyMap()) {
        if (mode == m && extra.isEmpty()) return
        get("switch_cammode", mapOf("mode" to m) + extra)
        mode = m
        if (m != "rec") liveOn = false
    }

    fun initialize() {
        commands = get("get_commandlist")
        val info = get("get_caminfo")
        tag(info, "model")?.let { model = "Olympus $it" }
        log("Fotocamera: $model")
        caps = caps.copy(liveView = "startliveview" in commands)
        synchronized(lock) {
            switchMode("rec")
            refreshExposure()
        }
    }

    private fun tag(xml: String, name: String): String? =
        Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()

    private fun shutterLabel(v: String): String = when {
        v.contains('"') -> v
        v.all { it.isDigit() } -> "1/$v"
        else -> v
    }

    private fun label(k: ExpoKey, v: String): String = when (k) {
        ExpoKey.TV -> shutterLabel(v)
        ExpoKey.AV -> if (v.firstOrNull()?.isDigit() == true) "f/$v" else v
        ExpoKey.ISO -> if (v.equals("auto", true)) "Auto" else v
        ExpoKey.EV -> v.replace(',', '.').toDoubleOrNull()?.let { formatEv(it) } ?: v
        ExpoKey.WB -> if (v == "0") "Auto" else "WB $v"
        else -> v
    }

    /** Legge tutte le proprietà con get_camprop com=desc propname=desclist (solo in modo rec). */
    private fun refreshExposure() {
        if (mode != "rec") return
        val xml = runCatching { get("get_camprop", mapOf("com" to "desc", "propname" to "desclist")) }.getOrNull() ?: return
        val values = HashMap<ExpoKey, ExpoOption>()
        val options = HashMap<ExpoKey, List<ExpoOption>>()
        Regex("<desc>(.*?)</desc>", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { m ->
            val d = m.groupValues[1]
            val name = tag(d, "propname") ?: return@forEach
            val key = propOf.entries.firstOrNull { it.value == name }?.key ?: return@forEach
            val cur = tag(d, "value") ?: return@forEach
            val list = tag(d, "enum")?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()
            val all = if (cur in list) list else listOf(cur) + list
            enums[key] = all
            values[key] = ExpoOption(all.indexOf(cur).toLong(), label(key, cur))
            if (tag(d, "attribute") == "getset" && list.size > 1) {
                options[key] = all.mapIndexed { i, v -> ExpoOption(i.toLong(), label(key, v)) }
            }
        }
        val state = ExposureState(values, options)
        if (state != expo) {
            expo = state
            exposureVersion++
        }
    }

    private var pollCount = 0
    private var knownNames: Set<String>? = null
    private var checkNewUntil = 0L

    override fun poll(): List<CamFile> {
        pollCount++
        synchronized(lock) {
            if (pollCount % 5 == 0 && mode == "rec") refreshExposure()
        }
        // dopo uno scatto cerca il file nuovo (richiede il modo play, quindi solo senza live view)
        if (System.currentTimeMillis() < checkNewUntil && !liveOn && pollCount % 2 == 0) {
            val known = knownNames ?: return emptyList()
            val now = runCatching { listFiles { _, _ -> } }.getOrNull() ?: return emptyList()
            val fresh = now.filter { it.name !in known }
            if (fresh.isNotEmpty()) {
                knownNames = known + fresh.map { it.name }
                checkNewUntil = 0
                return fresh
            }
        }
        return emptyList()
    }

    override fun capture(autofocus: Boolean): Shot = synchronized(lock) {
        try {
            if (knownNames == null) knownNames = runCatching { listFiles { _, _ -> }.map { it.name }.toSet() }.getOrNull()
            if ("shutter" in commands && !liveOn) {
                switchMode("shutter")
                Thread.sleep(300)
                get("exec_shutter", mapOf("com" to "1st2ndpush"))
                Thread.sleep(500)
                get("exec_shutter", mapOf("com" to "2nd1strelease"))
            } else {
                switchMode("rec")
                get("exec_takemotion", mapOf("com" to "starttake"), 30000)
            }
            checkNewUntil = System.currentTimeMillis() + 20000
            Shot.OK
        } catch (e: Exception) {
            log("Scatto: ${e.message}")
            if (e.message?.contains("HTTP 5") == true) Shot.BUSY else Shot.FAILED
        }
    }

    override fun bulbStart(): Shot = Shot.FAILED
    override fun bulbEnd() = Unit

    // ------------------------------------------------------------ live view (RTP su UDP)

    @Volatile
    private var liveOn = false

    @Volatile
    private var latest: ByteArray? = null
    private var udp: DatagramSocket? = null

    override fun startLiveView() {
        synchronized(lock) { startLiveLocked() }
    }

    private fun startLiveLocked() {
        val port = LIVE_PORT
        val sock = net.udp(port)
        sock.soTimeout = 2000
        udp = sock
        switchMode("rec", mapOf("lvqty" to "0640x0480"))
        get("exec_takemisc", mapOf("com" to "startliveview", "port" to port.toString()))
        liveOn = true
        thread(isDaemon = true, name = "olympus-liveview") { receive(sock) }
        Unit
    }

    private fun receive(sock: DatagramSocket) {
        val buf = ByteArray(65536)
        val frame = ByteArrayOutputStream()
        while (udp === sock) {
            val pkt = DatagramPacket(buf, buf.size)
            try {
                sock.receive(pkt)
            } catch (e: Exception) {
                continue
            }
            val d = buf
            val len = pkt.length
            if (len < 12 || (d[0].toInt() and 0xC0) != 0x80) continue
            val padding = d[0].toInt() and 0x20 != 0
            val extension = d[0].toInt() and 0x10 != 0
            val csrc = d[0].toInt() and 0x0F
            val marker = d[1].toInt() and 0x80 != 0
            var end = len
            if (padding) end -= d[len - 1].toInt() and 0xFF
            var start = 12 + 4 * csrc
            if (extension && start + 4 <= end) {
                // il primo pacchetto di ogni fotogramma porta un'estensione con le informazioni di scatto
                val extLen = ((d[start + 2].toInt() and 0xFF) shl 8) or (d[start + 3].toInt() and 0xFF)
                start += 4 + extLen * 4
                frame.reset()
            }
            if (start < end) frame.write(d, start, end - start)
            if (marker) {
                latest = extractJpeg(frame.toByteArray())
                frame.reset()
            }
        }
        runCatching { sock.close() }
    }

    override fun stopLiveView() {
        synchronized(lock) { stopLiveLocked() }
    }

    private fun stopLiveLocked() {
        udp = null
        liveOn = false
        latest = null
        runCatching { get("exec_takemisc", mapOf("com" to "stopliveview")) }
    }

    override fun liveViewFrame(): ByteArray? {
        val f = latest
        latest = null
        if (f == null) Thread.sleep(40)
        return f
    }

    // ------------------------------------------------------------ file

    override fun listFiles(progress: (Int, Int) -> Unit): List<CamFile> = synchronized(lock) {
        if (liveOn) return emptyList()
        switchMode("play")
        val out = ArrayList<CamFile>()
        list("/DCIM", out)
        progress(out.size, out.size)
        out.sortedWith(compareByDescending<CamFile> { it.captureDate }.thenByDescending { it.name })
    }

    private fun list(dir: String, out: MutableList<CamFile>) {
        val text = try {
            get("get_imglist", mapOf("DIR" to dir))
        } catch (e: IOException) {
            if (e.message?.contains("HTTP 404") == true) return else throw e
        }
        for (line in text.split("\r\n", "\n")) {
            val c = line.split(',')
            if (c.size != 6) continue
            val path = c[0] + "/" + c[1]
            val size = c[2].toLongOrNull() ?: 0
            val attrib = c[3].toIntOrNull() ?: 0
            val date = c[4].toIntOrNull() ?: 0
            val time = c[5].toIntOrNull() ?: 0
            if (attrib and (2 or 4 or 8) != 0) continue
            if (attrib and 16 != 0) {
                list(path, out)
            } else {
                // data e ora in formato FAT
                val ts = String.format(
                    "%04d%02d%02dT%02d%02d%02d",
                    1980 + (date shr 9), (date shr 5) and 15, date and 31,
                    time shr 11, (time shr 5) and 63, 2 * (time and 31),
                )
                out.add(CamFile(path, c[1], size, ts))
            }
        }
    }

    override fun thumbnail(file: CamFile): ByteArray? = synchronized(lock) {
        if (liveOn) return null
        runCatching {
            switchMode("play")
            net.request(url("get_thumbnail", mapOf("DIR" to file.id)), headers = headers)
        }.getOrNull()
    }

    override fun download(file: CamFile, out: OutputStream, progress: (Long, Long) -> Unit) {
        synchronized(lock) { downloadLocked(file, out, progress) }
    }

    private fun downloadLocked(file: CamFile, out: OutputStream, progress: (Long, Long) -> Unit) {
        if (!liveOn) runCatching { switchMode("play") }
        val c = net.open("http://$host${file.id}", 30000)
        try {
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: file.size
            var done = 0L
            val buf = ByteArray(64 * 1024)
            c.inputStream.use { inp ->
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    progress(done, total)
                }
            }
        } finally {
            c.disconnect()
        }
    }

    override fun setExposure(key: ExpoKey, code: Long): Boolean = synchronized(lock) {
        val prop = propOf[key] ?: return false
        val value = enums[key]?.getOrNull(code.toInt()) ?: return false
        return try {
            switchMode("rec")
            val body = "<?xml version=\"1.0\"?>\r\n<set>\r\n<value>$value</value>\r\n</set>\r\n".toByteArray()
            net.request(
                url("set_camprop", mapOf("com" to "set", "propname" to prop)),
                "POST", body, headers + ("Content-Type" to "text/plain;charset=utf-8"),
            )
            refreshExposure()
            true
        } catch (e: Exception) {
            log("${key.title}: ${e.message}")
            false
        }
    }

    override fun close() {
        if (liveOn) runCatching { stopLiveView() }
    }

    companion object {
        const val DEFAULT_HOST = "192.168.0.10"
        private const val LIVE_PORT = 28488

        fun probe(net: NetContext, host: String): Boolean = runCatching {
            String(net.request("http://$host/get_caminfo.cgi", headers = mapOf("User-Agent" to "OI.Share v2", "Host" to host), timeoutMs = 1500)).contains("<model>")
        }.getOrDefault(false)
    }
}
