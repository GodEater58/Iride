package it.goletto.eoslink

import android.app.Application
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.goletto.eoslink.net.CameraNetwork
import it.goletto.eoslink.ptp.CamFile
import it.goletto.eoslink.ptp.CanonCamera
import it.goletto.eoslink.ptp.Expo
import it.goletto.eoslink.ptp.PtpIpClient
import it.goletto.eoslink.ptp.PtpResp
import it.goletto.eoslink.ptp.hex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.roundToInt

@Immutable
data class Exposure(
    val values: Map<Int, Int> = emptyMap(),
    val allowed: Map<Int, List<Int>> = emptyMap(),
) {
    fun text(prop: Int): String {
        val v = values[prop] ?: return "--"
        return if (prop == Expo.SHOTS) v.toString() else Expo.format(prop, v)
    }

    fun canEdit(prop: Int): Boolean = (allowed[prop]?.size ?: 0) > 1
}

data class LogLine(val time: String, val text: String, val error: Boolean)

enum class SeqPhase { IDLE, EXPOSING, WAITING, BUSY }

data class Preset(val name: String, val count: Int, val pause: Double, val bulb: Double)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    enum class Mode { HOTSPOT, CAMERA_WIFI }

    private val prefs = app.getSharedPreferences("eoslink", Context.MODE_PRIVATE)
    private val net = CameraNetwork(app)
    private val main = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.ITALY)

    private var client: PtpIpClient? = null
    private var camera: CanonCamera? = null
    private var connectJob: Job? = null
    private var pollJob: Job? = null
    private var liveJob: Job? = null
    private var intervalJob: Job? = null
    private val thumbLoading = HashSet<Int>()
    private var lastPropsVersion = -1

    // Aspetto
    var night by mutableStateOf(prefs.getBoolean("night", false))
        private set
    var gridOn by mutableStateOf(prefs.getBoolean("grid", true))
        private set

    fun toggleNight() {
        night = !night
        prefs.edit().putBoolean("night", night).apply()
    }

    fun toggleGrid() {
        gridOn = !gridOn
        prefs.edit().putBoolean("grid", gridOn).apply()
    }

    // Connessione
    var mode by mutableStateOf(
        runCatching { Mode.valueOf(prefs.getString("mode", null) ?: "HOTSPOT") }.getOrDefault(Mode.HOTSPOT)
    )
    var ssid by mutableStateOf(prefs.getString("ssid", "") ?: "")
    var password by mutableStateOf(prefs.getString("pass", "") ?: "")
    var host by mutableStateOf(prefs.getString("host", "") ?: "")
    var connected by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("Pronto a collegarsi")
        private set
    var statusError by mutableStateOf(false)
        private set
    var model by mutableStateOf("")
        private set
    var cameraHost by mutableStateOf("")
        private set
    val logs = mutableStateListOf<LogLine>()

    // Scatto
    var liveFrame by mutableStateOf<ImageBitmap?>(null)
        private set
    var liveOn by mutableStateOf(false)
        private set
    var autofocus by mutableStateOf(true)
    var shotTick by mutableIntStateOf(0)
        private set
    var exposure by mutableStateOf(Exposure())
        private set

    // Intervallometro
    var intCount by mutableStateOf(prefs.getString("intCount", "20") ?: "20")
    var intPause by mutableStateOf(prefs.getString("intPause", "2") ?: "2")
    var intBulb by mutableStateOf(prefs.getString("intBulb", "0") ?: "0")
    var intervalRunning by mutableStateOf(false)
        private set
    var intervalStatus by mutableStateOf("")
        private set
    var seqDone by mutableIntStateOf(0)
        private set
    var seqTotal by mutableIntStateOf(0)
        private set
    var seqPhase by mutableStateOf(SeqPhase.IDLE)
        private set
    var seqPhaseFraction by mutableFloatStateOf(0f)
        private set
    var seqSecondsLeft by mutableIntStateOf(0)
        private set

    val presets = listOf(
        Preset("Stelle 30 s", 20, 2.0, 0.0),
        Preset("Deep sky 2 min", 15, 3.0, 120.0),
        Preset("Timelapse", 300, 5.0, 0.0),
        Preset("Dark frame", 20, 2.0, 0.0),
    )

    // Galleria
    val files = mutableStateListOf<CamFile>()
    val thumbs = mutableStateMapOf<Int, ImageBitmap>()
    var selected by mutableStateOf(setOf<Int>())
        private set
    var subfolder by mutableStateOf("")
    var galleryStatus by mutableStateOf("")
        private set
    var downloading by mutableStateOf(false)
        private set
    var dlIndex by mutableIntStateOf(0)
        private set
    var dlTotal by mutableIntStateOf(0)
        private set
    var dlFraction by mutableFloatStateOf(0f)
        private set

    val keepScreenOn: Boolean get() = intervalRunning || liveOn || downloading

    fun log(msg: String) {
        val lower = msg.lowercase(Locale.ROOT)
        val err = listOf("errore", "fallit", "rifiut", "persa", "interrott", "non riuscit").any { it in lower }
        val line = LogLine(timeFmt.format(Date()), msg, err)
        Log.d("EosLink", msg)
        main.post {
            logs.add(line)
            if (logs.size > 500) logs.removeAt(0)
        }
    }

    fun logText(): String = logs.joinToString("\n") { "${it.time}  ${it.text}" }

    private fun guid(): ByteArray {
        val stored = prefs.getString("guid", null)
        if (stored != null && stored.length == 32) {
            return ByteArray(16) { stored.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
        val g = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString("guid", g.joinToString("") { "%02x".format(it) }).apply()
        return g
    }

    private fun savePrefs() {
        prefs.edit()
            .putString("mode", mode.name)
            .putString("ssid", ssid)
            .putString("pass", password)
            .putString("host", host)
            .apply()
    }

    private fun setStatus(text: String, error: Boolean = false) {
        status = text
        statusError = error
    }

    // ---------------------------------------------------------------- connessione

    fun connect() {
        if (busy || connected) return
        savePrefs()
        busy = true
        setStatus("Preparazione…")
        connectJob = viewModelScope.launch {
            try {
                if (mode == Mode.CAMERA_WIFI) {
                    if (ssid.isBlank()) throw IOException("Inserisci il nome della rete della fotocamera")
                    setStatus("Collegamento al Wi-Fi della fotocamera…")
                    net.connectToCameraWifi(ssid.trim(), password)
                    log("Wi-Fi fotocamera collegato: internet resta sui dati mobili")
                    if (net.supportsConcurrentWifi) log("Il telefono supporta due reti Wi-Fi in contemporanea")
                } else {
                    net.release()
                }

                val target = host.trim().ifEmpty {
                    setStatus("Cerco la fotocamera sulla rete…")
                    val found = withContext(Dispatchers.IO) { net.scan { log(it) } }
                    if (found.isEmpty()) throw IOException("Nessuna fotocamera trovata. Controlla che il Wi-Fi della fotocamera sia attivo o inserisci l'indirizzo IP.")
                    log("Trovati: ${found.joinToString()}")
                    found.first()
                }

                setStatus("Collegamento a $target… conferma sulla fotocamera se richiesto")
                val c = PtpIpClient(net.socketFactory(), ::log)
                client = c
                withContext(Dispatchers.IO) { c.connect(target, guid(), "EosLink ${Build.MODEL}") }
                val cam = CanonCamera(c, ::log)
                withContext(Dispatchers.IO) { cam.initialize() }
                camera = cam
                model = cam.model.ifEmpty { c.cameraName.ifEmpty { "Fotocamera" } }
                cameraHost = target
                connected = true
                setStatus("Collegato a $model")
                log("Collegato a $model ($target)")
                syncExposure(cam, force = true)
                startPolling()
                refreshFiles()
            } catch (e: CancellationException) {
                cleanup()
                setStatus("Collegamento annullato")
            } catch (e: Exception) {
                log("Errore: ${e.message}")
                cleanup()
                setStatus(e.message ?: "Errore sconosciuto", error = true)
            } finally {
                busy = false
            }
        }
    }

    fun cancelConnect() {
        connectJob?.cancel()
        cleanup()
    }

    fun disconnect() {
        cleanup()
        setStatus("Scollegato")
        log("Scollegato")
    }

    private fun cleanup() {
        pollJob?.cancel()
        liveJob?.cancel()
        intervalJob?.cancel()
        val c = client
        client = null
        camera = null
        if (c != null) thread(isDaemon = true) { runCatching { c.close() } }
        net.release()
        connected = false
        liveOn = false
        liveFrame = null
        intervalRunning = false
        seqPhase = SeqPhase.IDLE
        downloading = false
        files.clear()
        thumbs.clear()
        thumbLoading.clear()
        selected = emptySet()
        exposure = Exposure()
        lastPropsVersion = -1
    }

    private fun onConnectionLost(reason: String?) {
        if (!connected) return
        log("Connessione persa: $reason")
        cleanup()
        setStatus("Connessione persa: $reason", error = true)
    }

    override fun onCleared() {
        cleanup()
    }

    private fun syncExposure(cam: CanonCamera, force: Boolean = false) {
        val v = cam.propsVersion
        if (!force && v == lastPropsVersion) return
        lastPropsVersion = v
        val snapshot = Exposure(
            values = HashMap(cam.props),
            allowed = cam.allowed.mapValues { it.value.toList() },
        )
        main.post { exposure = snapshot }
    }

    /** Le Canon EOS vogliono che la coda eventi venga letta di continuo. */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1200)
                val cam = camera ?: break
                try {
                    val added = cam.pollEvents()
                    syncExposure(cam)
                    for (h in added) {
                        val f = cam.info(h) ?: continue
                        log("Nuovo file: ${f.name}")
                        withContext(Dispatchers.Main) {
                            if (files.none { it.handle == h }) files.add(0, f)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { onConnectionLost(e.message) }
                    break
                }
            }
        }
    }

    fun setExposure(prop: Int, value: Int) {
        val cam = camera ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (cam.setProp(prop, value)) {
                    log("${Expo.title(prop)}: ${Expo.format(prop, value)}")
                } else {
                    log("${Expo.title(prop)} non modificabile in questa modalità di scatto")
                }
                syncExposure(cam)
            } catch (e: Exception) {
                log("Impostazione fallita: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------------- scatto

    fun shoot() {
        val cam = camera ?: return
        shotTick++
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val code = cam.capture(autofocus)
                log(if (code == PtpResp.OK) "Scatto eseguito" else "Scatto non riuscito (${hex(code)})${if (autofocus) ": prova senza autofocus" else ""}")
            } catch (e: Exception) {
                log("Scatto fallito: ${e.message}")
            }
        }
    }

    fun toggleLive() {
        val cam = camera ?: return
        if (liveOn) {
            liveOn = false
            liveJob?.cancel()
            liveFrame = null
            viewModelScope.launch(Dispatchers.IO) { runCatching { cam.stopLiveView() } }
            return
        }
        liveOn = true
        liveJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                cam.startLiveView()
                delay(400)
                var misses = 0
                while (isActive) {
                    val jpg = cam.liveViewFrame()
                    val bmp = jpg?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    if (bmp != null) {
                        misses = 0
                        val ib = bmp.asImageBitmap()
                        withContext(Dispatchers.Main) { liveFrame = ib }
                    } else {
                        misses++
                        if (misses > 150) throw IOException("nessun fotogramma ricevuto")
                        delay(100)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Live view interrotto: ${e.message}")
                withContext(Dispatchers.Main) {
                    liveOn = false
                    liveFrame = null
                }
            }
        }
    }

    // ---------------------------------------------------------------- intervallometro

    private fun parseSeconds(s: String): Double = s.replace(',', '.').trim().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0

    fun applyPreset(p: Preset) {
        intCount = p.count.toString()
        intPause = fmtNum(p.pause)
        intBulb = fmtNum(p.bulb)
    }

    private fun fmtNum(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString().replace('.', ',')

    fun stepCount(delta: Int) {
        val v = (intCount.trim().toIntOrNull() ?: 0) + delta
        intCount = v.coerceIn(1, 9999).toString()
    }

    fun stepPause(delta: Int) {
        intPause = fmtNum((parseSeconds(intPause) + delta).coerceIn(0.0, 3600.0))
    }

    fun stepBulb(delta: Int) {
        val cur = parseSeconds(intBulb)
        // passi più lunghi per pose lunghe
        val step = if (cur >= 60 || (cur >= 30 && delta > 0)) 15 else 5
        intBulb = fmtNum((cur + delta * step).coerceIn(0.0, 3600.0))
    }

    /** Durata stimata della sequenza in secondi (0 se il tempo dipende dalla fotocamera). */
    fun estimatedSeconds(): Int {
        val count = intCount.trim().toIntOrNull() ?: return 0
        val bulb = parseSeconds(intBulb)
        val pause = parseSeconds(intPause)
        return ((bulb + pause) * count - pause).coerceAtLeast(0.0).roundToInt()
    }

    fun startInterval() {
        val cam = camera ?: return
        val count = intCount.trim().toIntOrNull()
        if (count == null || count < 1) {
            intervalStatus = "Numero di scatti non valido"
            return
        }
        prefs.edit().putString("intCount", intCount).putString("intPause", intPause).putString("intBulb", intBulb).apply()
        val pauseMs = (parseSeconds(intPause) * 1000).toLong()
        val bulbMs = (parseSeconds(intBulb) * 1000).toLong()
        intervalRunning = true
        seqDone = 0
        seqTotal = count
        intervalJob = viewModelScope.launch {
            try {
                for (i in 1..count) {
                    val label = "Scatto $i di $count"
                    if (bulbMs > 0) {
                        retryWhileBusy(label) { cam.bulbStart() }
                        shotTick++
                        try {
                            countdown(bulbMs, SeqPhase.EXPOSING) { left -> "$label · posa ${left}s" }
                        } finally {
                            withContext(NonCancellable + Dispatchers.IO) { runCatching { cam.bulbEnd() } }
                        }
                    } else {
                        seqPhase = SeqPhase.EXPOSING
                        seqPhaseFraction = 0f
                        intervalStatus = label
                        retryWhileBusy(label) { cam.capture(false) }
                        shotTick++
                    }
                    seqDone = i
                    log("$label completato")
                    if (i < count && pauseMs > 0) {
                        countdown(pauseMs, SeqPhase.WAITING) { left -> "Prossimo scatto tra ${left}s" }
                    }
                }
                intervalStatus = "Sequenza completata: $count scatti"
            } catch (e: CancellationException) {
                intervalStatus = "Sequenza interrotta dopo $seqDone scatti"
            } catch (e: Exception) {
                intervalStatus = "Sequenza fermata: ${e.message}"
                log(intervalStatus)
            } finally {
                intervalRunning = false
                seqPhase = SeqPhase.IDLE
                seqPhaseFraction = 0f
            }
        }
    }

    private suspend fun countdown(ms: Long, phase: SeqPhase, label: (Long) -> String) {
        seqPhase = phase
        val start = SystemClock.elapsedRealtime()
        val end = start + ms
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val left = end - now
            if (left <= 0) break
            seqPhaseFraction = ((now - start).toFloat() / ms).coerceIn(0f, 1f)
            val secs = (left + 999) / 1000
            seqSecondsLeft = secs.toInt()
            intervalStatus = label(secs)
            delay(minOf(left, 100L))
        }
        seqPhaseFraction = 1f
        seqSecondsLeft = 0
    }

    /** Ripete il comando mentre la fotocamera è occupata (posa precedente, scrittura su scheda). */
    private suspend fun retryWhileBusy(label: String, action: () -> Int) {
        val start = SystemClock.elapsedRealtime()
        while (true) {
            val code = withContext(Dispatchers.IO) { action() }
            if (code == PtpResp.OK) return
            if (SystemClock.elapsedRealtime() - start > 120_000) {
                throw IOException("fotocamera occupata da oltre 2 minuti (${hex(code)})")
            }
            seqPhase = SeqPhase.BUSY
            intervalStatus = "$label · la fotocamera sta salvando, riprovo…"
            delay(500)
        }
    }

    fun stopInterval() {
        intervalJob?.cancel()
    }

    // ---------------------------------------------------------------- galleria

    fun refreshFiles() {
        val cam = camera ?: return
        galleryStatus = "Lettura della scheda…"
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    cam.listFiles { n, tot ->
                        if (n % 10 == 0 || n == tot) main.post { galleryStatus = "Lettura della scheda… $n di $tot" }
                    }
                }
                files.clear()
                files.addAll(list)
                galleryStatus = ""
            } catch (e: Exception) {
                galleryStatus = "Errore nella lettura dei file: ${e.message}"
                log(galleryStatus)
            }
        }
    }

    fun loadThumb(handle: Int) {
        if (thumbs.containsKey(handle) || !thumbLoading.add(handle)) return
        val cam = camera ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val data = cam.thumbnail(handle) ?: return@launch
                val bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return@launch
                val ib = bmp.asImageBitmap()
                withContext(Dispatchers.Main) { thumbs[handle] = ib }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { thumbLoading.remove(handle) }
            }
        }
    }

    val selecting: Boolean get() = selected.isNotEmpty()

    fun toggleSelect(handle: Int) {
        selected = if (handle in selected) selected - handle else selected + handle
    }

    fun selectAll(handles: Collection<Int>) {
        selected = handles.toSet()
    }

    fun clearSelection() {
        selected = emptySet()
    }

    fun downloadOne(handle: Int) {
        selected = setOf(handle)
        downloadSelected()
    }

    fun downloadSelected() {
        val cam = camera ?: return
        if (downloading) return
        val list = files.filter { it.handle in selected }
        if (list.isEmpty()) return
        val resolver = getApplication<Application>().contentResolver
        val sub = subfolder
        downloading = true
        dlTotal = list.size
        dlIndex = 0
        dlFraction = 0f
        viewModelScope.launch {
            var ok = 0
            for ((i, f) in list.withIndex()) {
                dlIndex = i + 1
                galleryStatus = f.name
                try {
                    withContext(Dispatchers.IO) {
                        var lastPct = -1
                        saveFile(resolver, cam, f, sub) { r, t ->
                            val pct = if (t > 0) (r * 100 / t).toInt() else 0
                            if (pct != lastPct) {
                                lastPct = pct
                                val frac = (i + pct / 100f) / list.size
                                main.post { dlFraction = frac }
                            }
                        }
                    }
                    ok++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("Download di ${f.name} fallito: ${e.message}")
                }
            }
            val dest = "Download/EosLink" + if (sub.isNotEmpty()) "/$sub" else ""
            galleryStatus = "Scaricati $ok di ${list.size} file in $dest"
            log(galleryStatus)
            selected = emptySet()
            downloading = false
            dlFraction = 1f
        }
    }

    private fun saveFile(
        resolver: ContentResolver,
        cam: CanonCamera,
        f: CamFile,
        sub: String,
        progress: (Long, Long) -> Unit,
    ) {
        val ext = f.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/EosLink" + if (sub.isNotEmpty()) "/$sub" else ""
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("impossibile creare il file sul telefono")
        try {
            val stream = resolver.openOutputStream(uri) ?: throw IOException("impossibile scrivere il file")
            val ok = stream.use { raw ->
                val buffered = BufferedOutputStream(raw, 1 shl 20)
                val result = cam.download(f, buffered, progress)
                buffered.flush()
                result
            }
            if (!ok) throw IOException("la fotocamera ha rifiutato il download")
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }
}
