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
import androidx.compose.runtime.getValue
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
    var status by mutableStateOf("Non connesso")
        private set
    var model by mutableStateOf("")
        private set
    val logs = mutableStateListOf<String>()

    // Scatto
    var liveFrame by mutableStateOf<ImageBitmap?>(null)
        private set
    var liveOn by mutableStateOf(false)
        private set
    var autofocus by mutableStateOf(true)
    var intCount by mutableStateOf("10")
    var intPause by mutableStateOf("2")
    var intBulb by mutableStateOf("0")
    var intervalRunning by mutableStateOf(false)
        private set
    var intervalStatus by mutableStateOf("")
        private set

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

    val keepScreenOn: Boolean get() = intervalRunning || liveOn || downloading

    fun log(msg: String) {
        val line = "${timeFmt.format(Date())}  $msg"
        Log.d("EosLink", msg)
        main.post {
            logs.add(line)
            if (logs.size > 400) logs.removeAt(0)
        }
    }

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

    // ---------------------------------------------------------------- connessione

    fun connect() {
        if (busy || connected) return
        savePrefs()
        busy = true
        connectJob = viewModelScope.launch {
            try {
                if (mode == Mode.CAMERA_WIFI) {
                    if (ssid.isBlank()) throw IOException("Inserisci il nome (SSID) della rete della fotocamera")
                    status = "Collegamento al Wi-Fi della fotocamera…"
                    net.connectToCameraWifi(ssid.trim(), password)
                    log("Wi-Fi fotocamera collegato: internet resta sui dati mobili")
                    if (net.supportsConcurrentWifi) log("Il telefono supporta due reti Wi-Fi in contemporanea")
                } else {
                    net.release()
                }

                val target = host.trim().ifEmpty {
                    status = "Ricerca della fotocamera…"
                    val found = withContext(Dispatchers.IO) { net.scan { log(it) } }
                    if (found.isEmpty()) throw IOException("Nessuna fotocamera trovata (porta 15740). Controlla che il Wi-Fi della fotocamera sia attivo, oppure inserisci l'IP.")
                    log("Trovati: ${found.joinToString()}")
                    found.first()
                }

                status = "Connessione a $target… conferma sulla fotocamera se richiesto"
                val c = PtpIpClient(net.socketFactory(), ::log)
                client = c
                withContext(Dispatchers.IO) { c.connect(target, guid(), "EosLink ${Build.MODEL}") }
                val cam = CanonCamera(c, ::log)
                withContext(Dispatchers.IO) { cam.initialize() }
                camera = cam
                model = cam.model.ifEmpty { c.cameraName.ifEmpty { "Fotocamera" } }
                prefs.edit().putString("lastHost", target).apply()
                connected = true
                status = "Connesso a $model ($target)"
                log(status)
                startPolling()
                refreshFiles()
            } catch (e: CancellationException) {
                cleanup()
                status = "Connessione annullata"
            } catch (e: Exception) {
                log("Errore: ${e.message}")
                cleanup()
                status = "Errore: ${e.message}"
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
        status = "Disconnesso"
        log("Disconnesso")
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
        downloading = false
        files.clear()
        thumbs.clear()
        thumbLoading.clear()
        selected = emptySet()
    }

    private fun onConnectionLost(reason: String?) {
        if (!connected) return
        log("Connessione persa: $reason")
        cleanup()
        status = "Connessione persa: $reason"
    }

    override fun onCleared() {
        cleanup()
    }

    /** Le Canon EOS vogliono che la coda eventi venga letta di continuo. */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1500)
                val cam = camera ?: break
                try {
                    for (h in cam.pollEvents()) {
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

    // ---------------------------------------------------------------- scatto

    fun shoot() {
        val cam = camera ?: return
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
                        if (misses > 150) throw IOException("nessun frame ricevuto")
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

    fun startInterval() {
        val cam = camera ?: return
        val count = intCount.trim().toIntOrNull()
        if (count == null || count < 1) {
            intervalStatus = "Numero di scatti non valido"
            return
        }
        val pauseMs = ((intPause.replace(',', '.').trim().toDoubleOrNull() ?: 0.0) * 1000).toLong().coerceAtLeast(0)
        val bulbMs = ((intBulb.replace(',', '.').trim().toDoubleOrNull() ?: 0.0) * 1000).toLong().coerceAtLeast(0)
        intervalRunning = true
        intervalJob = viewModelScope.launch {
            try {
                for (i in 1..count) {
                    val label = "Scatto $i di $count"
                    if (bulbMs > 0) {
                        retryWhileBusy(label) { cam.bulbStart() }
                        try {
                            val end = SystemClock.elapsedRealtime() + bulbMs
                            while (true) {
                                val left = end - SystemClock.elapsedRealtime()
                                if (left <= 0) break
                                intervalStatus = "$label · posa ${(left + 999) / 1000}s"
                                delay(minOf(left, 500L))
                            }
                        } finally {
                            withContext(NonCancellable + Dispatchers.IO) { runCatching { cam.bulbEnd() } }
                        }
                    } else {
                        intervalStatus = label
                        retryWhileBusy(label) { cam.capture(false) }
                    }
                    log("$label completato")
                    if (i < count && pauseMs > 0) {
                        val end = SystemClock.elapsedRealtime() + pauseMs
                        while (true) {
                            val left = end - SystemClock.elapsedRealtime()
                            if (left <= 0) break
                            intervalStatus = "$label fatto · prossimo tra ${(left + 999) / 1000}s"
                            delay(minOf(left, 500L))
                        }
                    }
                }
                intervalStatus = "Sequenza completata: $count scatti"
            } catch (e: CancellationException) {
                intervalStatus = "Sequenza interrotta"
            } catch (e: Exception) {
                intervalStatus = "Sequenza fermata: ${e.message}"
                log(intervalStatus)
            } finally {
                intervalRunning = false
            }
        }
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
            intervalStatus = "$label · fotocamera occupata, riprovo…"
            delay(500)
        }
    }

    fun stopInterval() {
        intervalJob?.cancel()
    }

    // ---------------------------------------------------------------- galleria

    fun refreshFiles() {
        val cam = camera ?: return
        galleryStatus = "Lettura dei file…"
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    cam.listFiles { n, tot ->
                        if (n % 10 == 0 || n == tot) main.post { galleryStatus = "Lettura dei file… $n/$tot" }
                    }
                }
                files.clear()
                files.addAll(list)
                galleryStatus = "${list.size} file sulla scheda"
            } catch (e: Exception) {
                galleryStatus = "Errore lettura file: ${e.message}"
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
                val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
                val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, opts) ?: return@launch
                val ib = bmp.asImageBitmap()
                withContext(Dispatchers.Main) { thumbs[handle] = ib }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { thumbLoading.remove(handle) }
            }
        }
    }

    fun toggleSelect(handle: Int) {
        selected = if (handle in selected) selected - handle else selected + handle
    }

    fun selectRaw() {
        selected = files.filter { it.isRaw }.map { it.handle }.toSet()
    }

    fun selectJpg() {
        selected = files.filter { it.isJpeg }.map { it.handle }.toSet()
    }

    fun clearSelection() {
        selected = emptySet()
    }

    fun downloadSelected() {
        val cam = camera ?: return
        if (downloading) return
        val list = files.filter { it.handle in selected }
        if (list.isEmpty()) return
        val resolver = getApplication<Application>().contentResolver
        val sub = subfolder
        downloading = true
        viewModelScope.launch {
            var ok = 0
            for ((i, f) in list.withIndex()) {
                val head = "Scarico ${i + 1}/${list.size}: ${f.name}"
                galleryStatus = head
                try {
                    withContext(Dispatchers.IO) {
                        var lastPct = -1
                        saveFile(resolver, cam, f, sub) { r, t ->
                            val pct = if (t > 0) (r * 100 / t).toInt() else 0
                            if (pct != lastPct) {
                                lastPct = pct
                                main.post { galleryStatus = "$head · $pct%" }
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
