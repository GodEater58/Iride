package it.goletto.iride

import android.app.Application
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
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
import it.goletto.iride.net.CameraNetwork
import it.goletto.iride.net.UsbLink
import it.goletto.iride.camera.Brand
import it.goletto.iride.camera.CamFile
import it.goletto.iride.camera.CameraDriver
import it.goletto.iride.camera.Capabilities
import it.goletto.iride.camera.DriverFactory
import it.goletto.iride.camera.ExpoKey
import it.goletto.iride.camera.ExposureState
import it.goletto.iride.camera.LvQuality
import it.goletto.iride.camera.Shot
import it.goletto.iride.camera.StarMeasure
import it.goletto.iride.camera.StarMeter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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

data class LogLine(val time: String, val text: String, val error: Boolean)

enum class SeqPhase { IDLE, EXPOSING, WAITING, BUSY }

data class Preset(val name: String, val count: Int, val pause: Double, val bulb: Double)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    enum class Mode { HOTSPOT, CAMERA_WIFI }

    private val prefs = app.getSharedPreferences("iride", Context.MODE_PRIVATE)
    private val net = CameraNetwork(app)
    private val usb = UsbLink(app)
    private val main = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.ITALY)

    private var camera: CameraDriver? = null
    private var connectJob: Job? = null
    private var pollJob: Job? = null
    private var liveJob: Job? = null
    private var intervalJob: Job? = null
    private var downloadJob: Job? = null
    private var filesJob: Job? = null
    private val thumbLoading = HashSet<String>()
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
    var brand by mutableStateOf(
        runCatching { Brand.valueOf(prefs.getString("brand", null) ?: "AUTO") }.getOrDefault(Brand.AUTO)
    )
    var connectedBrand by mutableStateOf(Brand.AUTO)
        private set
    var caps by mutableStateOf(Capabilities())
        private set
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
    /** Collegata col cavo USB invece che via Wi-Fi. */
    var viaUsb by mutableStateOf(false)
        private set
    /** Nome della fotocamera collegata al cavo USB (null = nessuna). */
    var usbCamera by mutableStateOf<String?>(null)
        private set
    val usbSupported: Boolean get() = usb.supported
    val logs = mutableStateListOf<LogLine>()

    // Scatto
    var liveFrame by mutableStateOf<ImageBitmap?>(null)
        private set
    var liveOn by mutableStateOf(false)
        private set
    /** C'è almeno un fotogramma: cambia una volta sola, così il mirino non si ricompone a ogni fotogramma. */
    var hasFrame by mutableStateOf(false)
        private set
    /** Fotogrammi al secondo e ritardo (richiesta → fotogramma pronto), es. "24 fps · 60 ms". */
    var lvStats by mutableStateOf("")
        private set

    // Assistente di messa a fuoco sulle stelle
    var starAssist by mutableStateOf(false)
        private set
    /** Indice in caps.zoomLevels dell'ingrandimento della fotocamera (0 = intero). */
    var zoomIndex by mutableIntStateOf(0)
        private set
    var starMeasure by mutableStateOf<StarMeasure?>(null)
        private set
    /** HFR attuale (mediana degli ultimi 3 fotogrammi) e migliore della sessione; 0 = nessuno. */
    var hfrNow by mutableFloatStateOf(0f)
        private set
    var hfrBest by mutableFloatStateOf(0f)
        private set
    var hfrHistory by mutableStateOf<List<Float>>(emptyList())
        private set
    var focusMoving by mutableStateOf(false)
        private set
    @Volatile private var tapHint: FloatArray? = null
    @Volatile private var resetStar = false
    private var recenterJob: Job? = null
    var autofocus by mutableStateOf(true)
    var focusing by mutableStateOf(false)
        private set
    /** Esito dell'ultima messa a fuoco, per un breve segnale sul tasto (null = nessuno). */
    var focusResult by mutableStateOf<Boolean?>(null)
        private set
    var lvQuality by mutableStateOf(
        runCatching { LvQuality.valueOf(prefs.getString("lvq", null) ?: "BALANCED") }.getOrDefault(LvQuality.BALANCED)
    )
        private set
    var shotTick by mutableIntStateOf(0)
        private set
    var exposure by mutableStateOf(ExposureState())
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
    val thumbs = mutableStateMapOf<String, ImageBitmap>()
    var selected by mutableStateOf(setOf<String>())
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
        Log.d("Iride", msg)
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
            .putString("brand", brand.name)
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

                // il collegamento non si può interrompere a metà: se nel frattempo è stato annullato,
                // la sessione appena aperta va chiusa invece di restare appesa sulla fotocamera
                val cam = withContext(Dispatchers.IO + NonCancellable) {
                    DriverFactory.connect(brand, host, net, guid(), "Iride ${Build.MODEL}", ::log) { msg ->
                        main.post { if (busy) setStatus(msg) }
                    }
                }
                if (!isActive) {
                    thread(isDaemon = true) { runCatching { cam.close() } }
                    throw CancellationException("annullato")
                }
                onConnected(cam, usbLink = false)
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

    /** Collegamento col cavo USB (OTG), alternativo al Wi-Fi. */
    fun connectUsb() {
        if (busy || connected) return
        savePrefs()
        busy = true
        setStatus("Collegamento via USB…")
        connectJob = viewModelScope.launch {
            try {
                val found = usb.find() ?: throw IOException("Nessuna fotocamera sul cavo USB. Collegala con un adattatore OTG e accendila.")
                setStatus("Consenti a Iride di usare ${found.name}")
                if (!usb.requestPermission(found.device)) throw IOException("Permesso per la fotocamera USB negato")
                net.release()
                setStatus("Collegamento a ${found.name}…")
                val cam = withContext(Dispatchers.IO + NonCancellable) {
                    DriverFactory.connectUsb(brand, usb, found, ::log)
                }
                if (!isActive) {
                    thread(isDaemon = true) { runCatching { cam.close() } }
                    throw CancellationException("annullato")
                }
                onConnected(cam, usbLink = true)
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

    /** Aggiorna la fotocamera vista sul cavo USB (la schermata di collegamento lo chiama di continuo). */
    fun refreshUsb() {
        usbCamera = if (usb.supported) usb.find()?.name else null
    }

    private fun onConnected(cam: CameraDriver, usbLink: Boolean) {
        val target = if (usbLink) "USB" else DriverFactory.lastHost
        camera = cam
        viaUsb = usbLink
        connectedBrand = cam.brand
        caps = cam.caps
        cam.setLiveViewQuality(lvQuality)
        if (!cam.caps.autofocusToggle) autofocus = true
        model = cam.model.ifEmpty { "Fotocamera" }
        cameraHost = target
        connected = true
        setStatus("Collegato a $model")
        log("Collegato a $model ($target)")
        syncExposure(cam, force = true)
        startPolling()
        // la scheda si legge solo quando si apre la galleria: con migliaia di foto
        // terrebbe occupato il canale per minuti e rallenterebbe il live view
        filesLoaded = false
    }

    fun cancelConnect() {
        connectJob?.cancel()
        cleanup()
        busy = false
        setStatus("Collegamento annullato")
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
        downloadJob?.cancel()
        filesJob?.cancel()
        val c = camera
        camera = null
        if (c != null) thread(isDaemon = true) { runCatching { c.close() } }
        net.lowLatency(false)
        net.release()
        connected = false
        viaUsb = false
        liveOn = false
        focusing = false
        liveFrame = null
        hasFrame = false
        lvStats = ""
        starAssist = false
        zoomIndex = 0
        starMeasure = null
        intervalRunning = false
        seqPhase = SeqPhase.IDLE
        downloading = false
        files.clear()
        thumbs.clear()
        thumbLoading.clear()
        selected = emptySet()
        saved = emptySet()
        exposure = ExposureState()
        lastPropsVersion = -1
        caps = Capabilities()
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

    private fun syncExposure(cam: CameraDriver, force: Boolean = false) {
        val v = cam.exposureVersion
        if (!force && v == lastPropsVersion) return
        lastPropsVersion = v
        val snapshot = cam.exposure()
        main.post { exposure = snapshot }
    }

    /** Lettura periodica: nuovi file, esposizione, eventi. Alcune marche la richiedono di continuo. */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            var tick = 0
            while (isActive) {
                delay(1200)
                val cam = camera ?: break
                // durante il live view via Wi-Fi lo stato si legge solo ogni 6 s: il canale resta ai
                // fotogrammi. Subito dopo uno scatto si legge comunque, per vedere il nuovo file.
                tick++
                val recentShot = SystemClock.elapsedRealtime() - lastShotAt < 8000
                if (liveOn && !viaUsb && !intervalRunning && !recentShot && tick % 5 != 0) continue
                try {
                    val added = cam.poll()
                    syncExposure(cam)
                    for (f in added) {
                        log("Nuovo file: ${f.name}")
                        withContext(Dispatchers.Main) {
                            if (files.none { it.id == f.id }) files.add(0, f)
                            // Sony in PC remoto: il file vive solo nella memoria della fotocamera
                            if (f.transient) autoTransfer(f)
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

    fun setExposure(key: ExpoKey, code: Long) {
        val cam = camera ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val label = cam.exposure().options[key]?.firstOrNull { it.code == code }?.label ?: code.toString()
                if (cam.setExposure(key, code)) {
                    log("${key.title}: $label")
                } else {
                    log("${key.title} non modificabile in questa modalità di scatto")
                }
                syncExposure(cam)
            } catch (e: Exception) {
                log("Impostazione fallita: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------------- scatto

    @Volatile
    private var lastShotAt = 0L

    fun shoot() {
        val cam = camera ?: return
        shotTick++
        lastShotAt = SystemClock.elapsedRealtime()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val shot = cam.capture(autofocus)
                log(
                    when (shot) {
                        Shot.OK -> "Scatto eseguito"
                        Shot.BUSY -> "Fotocamera occupata, riprova tra un attimo"
                        Shot.FAILED -> "Scatto non riuscito${if (autofocus) ": prova senza autofocus" else ""}"
                    }
                )
            } catch (e: Exception) {
                log("Scatto fallito: ${e.message}")
            }
        }
    }

    fun focus() {
        val cam = camera ?: return
        if (focusing) return
        focusing = true
        focusResult = null
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                cam.autofocus() == Shot.OK
            } catch (e: Exception) {
                log("Messa a fuoco fallita: ${e.message}")
                false
            }
            log(if (ok) "Messa a fuoco eseguita" else "Messa a fuoco non riuscita (soggetto troppo scuro o senza contrasto?)")
            withContext(Dispatchers.Main) {
                focusing = false
                focusResult = ok
            }
            delay(1500)
            withContext(Dispatchers.Main) { focusResult = null }
        }
    }

    /** Cambia la dimensione del live view; se è acceso lo riavvia con la nuova dimensione. */
    fun changeLvQuality(q: LvQuality) {
        if (q == lvQuality) return
        lvQuality = q
        prefs.edit().putString("lvq", q.name).apply()
        val cam = camera ?: return
        cam.setLiveViewQuality(q)
        if (liveOn) {
            toggleLive()
            viewModelScope.launch {
                delay(600)
                if (!liveOn && camera === cam) toggleLive()
            }
        }
    }

    fun toggleLive() {
        val cam = camera ?: return
        if (liveOn) {
            liveOn = false
            liveJob?.cancel()
            liveFrame = null
            hasFrame = false
            lvStats = ""
            starAssist = false
            zoomIndex = 0
            starMeasure = null
            recenterJob?.cancel()
            net.lowLatency(false)
            viewModelScope.launch(Dispatchers.IO) { runCatching { cam.stopLiveView() } }
            return
        }
        liveOn = true
        if (!viaUsb) net.lowLatency(true)
        liveJob = viewModelScope.launch(Dispatchers.IO) {
            // due fasi in parallelo: una scarica i fotogrammi, l'altra li decodifica.
            // Il canale tiene solo l'ultimo: se la decodifica è indietro i vecchi si scartano.
            val frames = Channel<Frame>(Channel.CONFLATED)
            val decoder = launch(Dispatchers.Default) { decodeFrames(frames) }
            try {
                cam.startLiveView()
                var lastFrame = SystemClock.elapsedRealtime()
                while (isActive) {
                    val asked = SystemClock.elapsedRealtime()
                    val jpg = cam.liveViewFrame()
                    if (jpg != null && jpg.size > 100) {
                        lastFrame = SystemClock.elapsedRealtime()
                        frames.trySend(Frame(jpg, asked))
                    } else {
                        if (SystemClock.elapsedRealtime() - lastFrame > 15_000) throw IOException("nessun fotogramma ricevuto")
                        // fotogramma non ancora pronto: si riprova quasi subito
                        delay(15)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Live view interrotto: ${e.message}")
                withContext(Dispatchers.Main) {
                    liveOn = false
                    liveFrame = null
                    hasFrame = false
                    lvStats = ""
                    net.lowLatency(false)
                }
            } finally {
                frames.close()
                decoder.cancel()
            }
        }
    }

    private class Frame(val jpg: ByteArray, val askedAt: Long)

    /**
     * Decodifica riusando tre bitmap a turno (una mostrata, una in arrivo, una libera): niente
     * spazzatura a ogni fotogramma. Il fotogramma si pubblica direttamente da qui, senza
     * aspettare il thread principale: il mirino lo ridisegna al primo aggiornamento dello schermo.
     */
    private suspend fun decodeFrames(frames: Channel<Frame>) {
        val pool = arrayOfNulls<Bitmap>(3)
        var turn = 0
        var count = 0
        var delaySum = 0L
        var windowStart = SystemClock.elapsedRealtime()
        // inseguimento della stella tra un fotogramma e l'altro
        var hx = 0f
        var hy = 0f
        var hasHint = false
        var lost = 0
        val hist = ArrayDeque<Float>()
        for (f in frames) {
            val jpg = f.jpg
            val opts = BitmapFactory.Options().apply {
                inMutable = true
                inBitmap = pool[turn]
            }
            val bmp = try {
                BitmapFactory.decodeByteArray(jpg, 0, jpg.size, opts)
            } catch (e: IllegalArgumentException) {
                // dimensioni cambiate (es. zoom): nuova bitmap
                opts.inBitmap = null
                BitmapFactory.decodeByteArray(jpg, 0, jpg.size, opts)
            } ?: continue
            pool[turn] = bmp
            turn = (turn + 1) % pool.size
            if (!liveOn) continue
            if (starAssist) {
                if (resetStar) {
                    resetStar = false
                    hasHint = false
                    hist.clear()
                    hfrBest = 0f
                }
                tapHint?.let {
                    tapHint = null
                    hx = it[0] * bmp.width
                    hy = it[1] * bmp.height
                    hasHint = true
                    hist.clear()
                    hfrBest = 0f
                }
                val m = StarMeter.measure(bmp, hx, hy, hasHint)
                if (m != null) {
                    hx = m.x
                    hy = m.y
                    hasHint = true
                    lost = 0
                    hist.addLast(m.hfr)
                    while (hist.size > 90) hist.removeFirst()
                    val last = hist.toList().takeLast(3).sorted()
                    hfrNow = last[last.size / 2]
                    if (hist.size >= 3 && (hfrBest == 0f || hfrNow < hfrBest)) hfrBest = hfrNow
                    hfrHistory = hist.toList()
                } else if (++lost > 8) {
                    // stella persa: si torna a cercare la più luminosa al centro
                    hasHint = false
                    hfrNow = 0f
                }
                starMeasure = m
            }
            liveFrame = bmp.asImageBitmap()
            if (!hasFrame) hasFrame = true

            // statistiche ogni secondo
            val now = SystemClock.elapsedRealtime()
            count++
            delaySum += now - f.askedAt
            if (now - windowStart >= 1000) {
                val fps = count * 1000f / (now - windowStart)
                lvStats = String.format(Locale.ITALY, "%.0f fps · %d ms", fps, delaySum / count)
                count = 0
                delaySum = 0
                windowStart = now
            }
        }
    }

    // ---------------------------------------------------------------- messa a fuoco sulle stelle

    fun toggleStarAssist() {
        if (starAssist) {
            starAssist = false
            starMeasure = null
            recenterJob?.cancel()
            if (zoomIndex != 0) setZoom(0)
        } else {
            resetStar = true
            hfrNow = 0f
            hfrBest = 0f
            hfrHistory = emptyList()
            starAssist = true
        }
    }

    /** Ingrandimento della fotocamera; con [cx], [cy] il riquadro si sposta sul punto toccato. */
    fun setZoom(index: Int, cx: Float? = null, cy: Float? = null) {
        val cam = camera ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try { cam.liveZoom(index, cx, cy) } catch (e: Exception) { log("Zoom fallito: ${e.message}"); false }
            if (!ok) return@launch
            withContext(Dispatchers.Main) { zoomIndex = index }
            resetStar = true
            if (index > 0 && cx != null && cam.caps.zoomPosition) recenter(cam)
        }
    }

    /**
     * Dopo lo zoom sul punto toccato: se la stella non è al centro del riquadro lo si sposta,
     * così il risultato è giusto anche se le coordinate della fotocamera non tornano del tutto.
     */
    private fun recenter(cam: CameraDriver) {
        recenterJob?.cancel()
        recenterJob = viewModelScope.launch(Dispatchers.IO) {
            repeat(2) {
                delay(1400)
                val m = starMeasure ?: return@launch
                val dx = m.x / m.frameW - 0.5f
                val dy = m.y / m.frameH - 0.5f
                if (kotlin.math.abs(dx) < 0.07f && kotlin.math.abs(dy) < 0.07f) return@launch
                if (!runCatching { cam.panZoom(dx, dy) }.getOrDefault(false)) return@launch
                resetStar = true
            }
        }
    }

    /** Tocco sul mirino in modalità stelle: [fx], [fy] da 0 a 1 sull'immagine. */
    fun tapViewfinder(fx: Float, fy: Float) {
        val c = caps
        if (zoomIndex == 0 && c.zoomPosition && c.zoomLevels.size > 1) {
            setZoom(1, fx, fy)
        } else {
            tapHint = floatArrayOf(fx, fy)
        }
    }

    /** Trascinamento sul mirino ingrandito: sposta il riquadro della fotocamera. */
    fun dragZoom(dx: Float, dy: Float) {
        val cam = camera ?: return
        recenterJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            if (runCatching { cam.panZoom(dx, dy) }.getOrDefault(false)) resetStar = true
        }
    }

    fun focusStep(step: Int) {
        val cam = camera ?: return
        if (focusMoving) return
        focusMoving = true
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching { cam.focusDrive(step) }.getOrDefault(false)
            if (!ok) log("Messa a fuoco a passi non riuscita: obiettivo su AF e live view acceso?")
            withContext(Dispatchers.Main) { focusMoving = false }
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
        val bulb = if (caps.bulb) parseSeconds(intBulb) else 0.0
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
        // con fotocamere senza posa B da remoto il valore salvato non va usato
        val bulbMs = if (caps.bulb) (parseSeconds(intBulb) * 1000).toLong() else 0L
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
    private suspend fun retryWhileBusy(label: String, action: () -> Shot) {
        val start = SystemClock.elapsedRealtime()
        while (true) {
            val shot = withContext(Dispatchers.IO) { action() }
            if (shot == Shot.OK) return
            val elapsed = SystemClock.elapsedRealtime() - start
            // "occupata" può durare a lungo (scrittura su scheda, riduzione rumore); un errore vero no
            if (shot == Shot.FAILED && elapsed > 20_000) {
                throw IOException("la fotocamera rifiuta lo scatto (controlla messa a fuoco, modo di scatto e scheda)")
            }
            if (elapsed > 120_000) {
                throw IOException("la fotocamera non accetta lo scatto da oltre 2 minuti")
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

    private var filesLoaded = false

    /** Legge la scheda la prima volta che si apre la galleria. */
    fun ensureFilesLoaded() {
        if (!filesLoaded && camera != null) refreshFiles()
    }

    fun refreshFiles() {
        val cam = camera ?: return
        filesLoaded = true
        galleryStatus = "Lettura della scheda…"
        filesJob?.cancel()
        filesJob = viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    cam.listFiles { n, tot ->
                        if (n % 10 == 0 || n == tot) main.post { galleryStatus = "Lettura della scheda… $n di $tot" }
                    }
                }
                // i file ricevuti al volo (Sony, Fujifilm…) non sono sulla scheda: vanno conservati
                val keep = files.filter { it.transient }
                files.clear()
                files.addAll(keep + list)
                galleryStatus = ""
            } catch (e: Exception) {
                galleryStatus = "Errore nella lettura dei file: ${e.message}"
                log(galleryStatus)
            }
        }
    }

    fun loadThumb(file: CamFile) {
        val handle = file.id
        if (thumbs.containsKey(handle) || !thumbLoading.add(handle)) return
        val cam = camera ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val data = cam.thumbnail(file) ?: return@launch
                val bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return@launch
                val ib = bmp.asImageBitmap()
                withContext(Dispatchers.Main) { thumbs[handle] = ib }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { thumbLoading.remove(handle) }
            }
        }
    }

    /** File ricevuti al volo e già salvati sul telefono: non si possono riscaricare. */
    var saved by mutableStateOf(setOf<String>())
        private set

    val selecting: Boolean get() = selected.isNotEmpty()

    fun toggleSelect(handle: String) {
        selected = if (handle in selected) selected - handle else selected + handle
    }

    fun selectAll(handles: Collection<String>) {
        selected = handles.toSet()
    }

    fun clearSelection() {
        selected = emptySet()
    }

    fun downloadOne(handle: String) {
        selected = setOf(handle)
        downloadSelected()
    }

    /** Scarica subito un file che la fotocamera tiene solo in memoria. */
    private fun autoTransfer(f: CamFile) {
        val cam = camera ?: return
        val resolver = getApplication<Application>().contentResolver
        val sub = subfolder
        viewModelScope.launch(Dispatchers.IO) {
            try {
                saveFile(resolver, cam, f, sub) { _, _ -> }
                main.post { saved = saved + f.id }
                log("Salvato sul telefono: ${f.name}")
            } catch (e: Exception) {
                log("Download di ${f.name} fallito: ${e.message}")
            }
        }
    }

    fun downloadSelected() {
        val cam = camera ?: return
        if (downloading) return
        val list = files.filter { it.id in selected && it.id !in saved }
        if (list.isEmpty()) {
            selected = emptySet()
            return
        }
        val resolver = getApplication<Application>().contentResolver
        val sub = subfolder
        downloading = true
        dlTotal = list.size
        dlIndex = 0
        dlFraction = 0f
        downloadJob = viewModelScope.launch {
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
            val dest = "Download/Iride" + if (sub.isNotEmpty()) "/$sub" else ""
            galleryStatus = "Scaricati $ok di ${list.size} file in $dest"
            log(galleryStatus)
            selected = emptySet()
            downloading = false
            dlFraction = 1f
        }
    }

    private fun saveFile(
        resolver: ContentResolver,
        cam: CameraDriver,
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
                Environment.DIRECTORY_DOWNLOADS + "/Iride" + if (sub.isNotEmpty()) "/$sub" else ""
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("impossibile creare il file sul telefono")
        try {
            val stream = resolver.openOutputStream(uri) ?: throw IOException("impossibile scrivere il file")
            stream.use { raw ->
                val buffered = BufferedOutputStream(raw, 1 shl 20)
                cam.download(f, buffered, progress)
                buffered.flush()
            }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }
}
