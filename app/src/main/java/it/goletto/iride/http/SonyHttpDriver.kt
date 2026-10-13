package it.goletto.iride.http

import it.goletto.iride.camera.Brand
import it.goletto.iride.camera.CamFile
import it.goletto.iride.camera.CameraDriver
import it.goletto.iride.camera.Capabilities
import it.goletto.iride.camera.ExpoKey
import it.goletto.iride.camera.ExpoOption
import it.goletto.iride.camera.ExposureState
import it.goletto.iride.camera.Shot
import it.goletto.iride.camera.formatEv
import it.goletto.iride.net.NetContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * Sony Camera Remote API (JSON su HTTP), usata dalle Sony fino a circa il 2019 (a7 II, a6000…).
 * Formato dei comandi e del flusso live view come nella documentazione dell'API e in pysony.
 * La scheda non è consultabile: per ogni scatto arriva l'anteprima "postview".
 */
class SonyHttpDriver(
    private val net: NetContext,
    /** Esempio: http://192.168.122.1:8080/sony */
    private val base: String,
    private val log: (String) -> Unit,
) : CameraDriver {

    override val brand = Brand.SONY
    override var model: String = "Sony Alpha"
        private set
    override var caps = Capabilities(
        cardBrowsing = false,
        autofocusToggle = false,
        galleryNote = "Con le Sony meno recenti arriva sul telefono l'anteprima di ogni scatto (circa 2 megapixel). Gli originali restano sulla scheda.",
    )
        private set

    private var apis: Set<String> = emptySet()
    private var id = 1
    private val pending = ConcurrentLinkedQueue<CamFile>()
    private val candidates = HashMap<ExpoKey, List<String>>()
    private val nameFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)
    private val dateFmt = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.ROOT)

    @Volatile
    private var expo = ExposureState()

    @Volatile
    override var exposureVersion = 0
        private set

    override fun exposure() = expo

    /** Chiamata JSON-RPC. Restituisce l'array "result" o lancia SonyApiException. */
    private fun call(method: String, params: JSONArray = JSONArray(), service: String = "camera", version: String = "1.0", timeoutMs: Int = 10000): JSONArray {
        val req = JSONObject()
            .put("method", method)
            .put("params", params)
            .put("id", id++)
            .put("version", version)
        val body = net.request("$base/$service", "POST", req.toString().toByteArray(), mapOf("Content-Type" to "application/json"), timeoutMs)
        val res = JSONObject(String(body))
        if (res.has("error")) {
            val e = res.getJSONArray("error")
            throw SonyApiException(e.optInt(0), e.optString(1))
        }
        return res.optJSONArray("result") ?: JSONArray()
    }

    fun initialize() {
        apis = readApis()
        if ("startRecMode" in apis) {
            runCatching { call("startRecMode") }
            Thread.sleep(1500)
            apis = readApis()
        }
        runCatching {
            val info = call("getApplicationInfo")
            log("API remota Sony ${info.optString(1)}")
        }
        caps = caps.copy(
            liveView = "startLiveview" in apis,
            bulb = "startBulbShooting" in apis,
            exposureControl = apis.any { it.startsWith("set") && (it.contains("ShutterSpeed") || it.contains("FNumber") || it.contains("IsoSpeedRate") || it.contains("ExposureCompensation") || it.contains("WhiteBalance")) },
        )
        readWbModes()
        refresh()
    }

    private fun readApis(): Set<String> = runCatching {
        val r = call("getAvailableApiList").getJSONArray(0)
        (0 until r.length()).map { r.getString(it) }.toSet()
    }.getOrDefault(emptySet())

    /** Legge lo stato con getEvent (non bloccante) e aggiorna il pannello esposizione. */
    private fun refresh() {
        if ("getEvent" !in apis) return
        val r = runCatching { call("getEvent", JSONArray().put(false), timeoutMs = 5000) }.getOrNull() ?: return
        val objects = ArrayList<JSONObject>()
        for (i in 0 until r.length()) {
            when (val item = r.opt(i)) {
                is JSONObject -> objects.add(item)
                is JSONArray -> for (j in 0 until item.length()) item.optJSONObject(j)?.let { objects.add(it) }
            }
        }
        val values = HashMap<ExpoKey, ExpoOption>()
        val options = HashMap<ExpoKey, List<ExpoOption>>()
        fun read(key: ExpoKey, type: String, current: String, list: String, label: (String) -> String) {
            val o = objects.firstOrNull { it.optString("type") == type } ?: return
            val cur = o.optString(current).takeIf { it.isNotEmpty() } ?: return
            val cand = o.optJSONArray(list)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            val all = if (cur in cand) cand else listOf(cur) + cand
            candidates[key] = all
            values[key] = ExpoOption(all.indexOf(cur).toLong(), label(cur))
            if (cand.size > 1) options[key] = all.mapIndexed { i, v -> ExpoOption(i.toLong(), label(v)) }
        }
        read(ExpoKey.MODE, "exposureMode", "currentExposureMode", "exposureModeCandidates") { modeLabel(it) }
        read(ExpoKey.TV, "shutterSpeed", "currentShutterSpeed", "shutterSpeedCandidates") { if (it.equals("BULB", true)) "Bulb" else it }
        read(ExpoKey.AV, "fNumber", "currentFNumber", "fNumberCandidates") { if (it.startsWith("-")) "--" else "f/$it" }
        read(ExpoKey.ISO, "isoSpeedRate", "currentIsoSpeedRate", "isoSpeedRateCandidates") { if (it.equals("AUTO", true)) "Auto" else it }
        // compensazione: indici interi, passo 1 = 1/3 EV, 2 = 1/2 EV
        objects.firstOrNull { it.optString("type") == "exposureCompensation" }?.let { o ->
            val cur = o.optInt("currentExposureCompensation", 0)
            val min = o.optInt("minExposureCompensation", 0)
            val max = o.optInt("maxExposureCompensation", 0)
            val unit = if (o.optInt("stepIndexOfExposureCompensation", 1) == 2) 0.5 else 1.0 / 3
            values[ExpoKey.EV] = ExpoOption(cur.toLong(), formatEv(cur * unit))
            if (max > min) options[ExpoKey.EV] = (min..max).map { ExpoOption(it.toLong(), formatEv(it * unit)) }
        }
        objects.firstOrNull { it.optString("type") == "whiteBalance" }?.let { o ->
            val cur = o.optString("currentWhiteBalanceMode").takeIf { it.isNotEmpty() } ?: return@let
            val list = wbModes.ifEmpty { listOf(cur) }
            val all = if (cur in list) list else listOf(cur) + list
            candidates[ExpoKey.WB] = all
            values[ExpoKey.WB] = ExpoOption(all.indexOf(cur).toLong(), wbLabel(cur))
            if (all.size > 1) options[ExpoKey.WB] = all.mapIndexed { i, v -> ExpoOption(i.toLong(), wbLabel(v)) }
        }
        objects.firstOrNull { it.has("numberOfRecordableImages") }?.let {
            val n = it.optInt("numberOfRecordableImages", -1)
            if (n >= 0) values[ExpoKey.SHOTS] = ExpoOption(n.toLong(), n.toString())
        }
        val state = ExposureState(values, options)
        if (state != expo) {
            expo = state
            exposureVersion++
        }
    }

    private var wbModes: List<String> = emptyList()

    /** Elenco dei bilanciamenti disponibili (richiesta separata, letta ogni tanto). */
    private fun readWbModes() {
        if ("getAvailableWhiteBalance" !in apis) return
        runCatching {
            val list = call("getAvailableWhiteBalance").getJSONArray(1)
            wbModes = (0 until list.length()).mapNotNull { list.optJSONObject(it)?.optString("whiteBalanceMode") }.filter { it.isNotEmpty() }
        }
    }

    private fun wbLabel(v: String): String = when {
        v == "Auto WB" -> "Auto"
        v == "Daylight" -> "Luce diurna"
        v == "Shade" -> "Ombra"
        v == "Cloudy" -> "Nuvoloso"
        v == "Incandescent" -> "Tungsteno"
        v == "Flash" -> "Flash"
        v == "Color Temperature" -> "Temp. colore"
        v.startsWith("Fluorescent") -> "Fluorescente" + v.substringAfter(":", "").let { if (it.isNotBlank()) " " + it.trim() else "" }
        v.startsWith("Custom") -> "Personalizzato" + v.removePrefix("Custom")
        else -> v
    }

    private fun modeLabel(v: String): String = when (v) {
        "Manual Exposure" -> "M"
        "Program Auto" -> "P"
        "Aperture" -> "A"
        "Shutter" -> "S"
        else -> "Auto"
    }

    private var pollCount = 0

    override fun poll(): List<CamFile> {
        pollCount++
        if (pollCount % 30 == 1) readWbModes()
        if (pollCount % 2 == 0) refresh()
        val out = ArrayList<CamFile>()
        while (true) out.add(pending.poll() ?: break)
        return out
    }

    private fun addPostview(result: JSONArray) {
        val urls = result.optJSONArray(0) ?: return
        val now = Date()
        for (i in 0 until urls.length()) {
            val url = urls.optString(i).takeIf { it.startsWith("http") } ?: continue
            pending.add(CamFile(url, "SONY_${nameFmt.format(now)}${if (i > 0) "_$i" else ""}.JPG", 0, dateFmt.format(now), transient = true))
        }
    }

    private fun shotFrom(e: Exception): Shot {
        log("Scatto: ${e.message}")
        // 1 = "Any", 40400-40403 = fotocamera impegnata in uno scatto lungo
        return if (e is SonyApiException && (e.code == 1 || e.code in 40400..40403)) Shot.BUSY else Shot.FAILED
    }

    override fun capture(autofocus: Boolean): Shot = try {
        addPostview(call("actTakePicture", timeoutMs = 60000))
        Shot.OK
    } catch (e: SonyApiException) {
        if (e.code == 40403 && "awaitTakePicture" in apis) {
            // scatto ancora in corso (posa lunga): si aspetta il risultato
            runCatching { addPostview(call("awaitTakePicture", timeoutMs = 120000)) }
            Shot.OK
        } else {
            shotFrom(e)
        }
    } catch (e: Exception) {
        shotFrom(e)
    }

    override fun bulbStart(): Shot = try {
        call("startBulbShooting")
        Shot.OK
    } catch (e: Exception) {
        shotFrom(e)
    }

    override fun bulbEnd() {
        runCatching { call("stopBulbShooting") }
        if ("awaitTakePicture" in apis) runCatching { addPostview(call("awaitTakePicture", timeoutMs = 120000)) }
    }

    // ------------------------------------------------------------ live view

    @Volatile
    private var latestFrame: ByteArray? = null

    @Volatile
    private var liveConn: HttpURLConnection? = null

    override fun startLiveView() {
        val url = call("startLiveview").getString(0)
        val c = net.open(url, 10000)
        liveConn = c
        thread(isDaemon = true, name = "sony-liveview") {
            try {
                val inp = DataInputStream(c.inputStream.buffered(64 * 1024))
                val common = ByteArray(8)
                val payload = ByteArray(128)
                while (liveConn === c) {
                    inp.readFully(common)
                    if (common[0] != 0xFF.toByte()) throw IOException("flusso live view non valido")
                    val type = common[1].toInt() and 0xFF
                    inp.readFully(payload)
                    val size = ((payload[4].toInt() and 0xFF) shl 16) or ((payload[5].toInt() and 0xFF) shl 8) or (payload[6].toInt() and 0xFF)
                    val padding = payload[7].toInt() and 0xFF
                    val data = ByteArray(size)
                    inp.readFully(data)
                    if (padding > 0) inp.skipBytes(padding)
                    if (type == 1) latestFrame = data
                }
            } catch (e: Exception) {
                if (liveConn === c) log("Live view Sony interrotto: ${e.message}")
            }
        }
    }

    override fun stopLiveView() {
        val c = liveConn
        liveConn = null
        latestFrame = null
        runCatching { c?.disconnect() }
        runCatching { call("stopLiveview") }
    }

    override fun liveViewFrame(): ByteArray? {
        val f = latestFrame
        latestFrame = null
        if (f == null) Thread.sleep(40)
        return f
    }

    // ------------------------------------------------------------ file

    override fun listFiles(progress: (Int, Int) -> Unit): List<CamFile> = emptyList()
    override fun thumbnail(file: CamFile): ByteArray? = null

    override fun download(file: CamFile, out: OutputStream, progress: (Long, Long) -> Unit) {
        val c = net.open(file.id, 30000)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
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

    override fun setExposure(key: ExpoKey, code: Long): Boolean {
        val params = JSONArray()
        val method = when (key) {
            ExpoKey.EV -> {
                params.put(code.toInt())
                "setExposureCompensation"
            }
            else -> {
                val value = candidates[key]?.getOrNull(code.toInt()) ?: return false
                params.put(value)
                when (key) {
                    ExpoKey.TV -> "setShutterSpeed"
                    ExpoKey.AV -> "setFNumber"
                    ExpoKey.ISO -> "setIsoSpeedRate"
                    ExpoKey.MODE -> "setExposureMode"
                    ExpoKey.WB -> {
                        // modo, temperatura abilitata, temperatura
                        params.put(false).put(-1)
                        "setWhiteBalance"
                    }
                    else -> return false
                }
            }
        }
        return try {
            call(method, params)
            refresh()
            true
        } catch (e: Exception) {
            log("${key.title}: ${e.message}")
            false
        }
    }

    override fun close() {
        if (liveConn != null) stopLiveView()
        if ("stopRecMode" in apis) runCatching { call("stopRecMode", timeoutMs = 3000) }
    }

    companion object {
        /** Prova gli indirizzi tipici dell'API Sony su un host; restituisce la base o null. */
        fun probe(net: NetContext, host: String): String? {
            for (port in listOf(8080, 10000)) {
                val base = "http://$host:$port/sony"
                val ok = runCatching {
                    val req = JSONObject().put("method", "getVersions").put("params", JSONArray()).put("id", 1).put("version", "1.0")
                    val body = net.request("$base/camera", "POST", req.toString().toByteArray(), mapOf("Content-Type" to "application/json"), 1500)
                    JSONObject(String(body)).has("result")
                }.getOrDefault(false)
                if (ok) return base
            }
            return null
        }
    }
}

class SonyApiException(val code: Int, message: String) : IOException("errore Sony $code: $message")
