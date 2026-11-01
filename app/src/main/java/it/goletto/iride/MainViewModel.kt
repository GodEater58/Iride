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
import it.goletto.iride.camera.DemoDriver
import it.goletto.iride.camera.ExpoKey
import it.goletto.iride.camera.ExposureState
import it.goletto.iride.camera.LvQuality
import it.goletto.iride.camera.Shot
import it.goletto.iride.camera.LensInfo
import it.goletto.iride.camera.LiveAnalyzer
import it.goletto.iride.camera.ExpoOption
import it.goletto.iride.camera.SensorInfo
import it.goletto.iride.camera.SkyAdvisor
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

data class LogLine(val time: String, val text: String, val error: Boolean)

private const val MAX_THUMBS = 400
const val MILKY_WAY = "Via Lattea"

enum class SeqPhase { IDLE, EXPOSING, WAITING, BUSY }

/**
 * Preset dell'intervallometro; [bulb] null = lascia la posa attuale (i dark devono uguagliare le foto).
 * [folder] è la sottocartella di Download/Iride dove finiscono i file scaricati.
 */
data class Preset(val name: String, val count: Int, val pause: Double, val bulb: Double?, val folder: String, val note: String)

/** Ritardi possibili prima di una sequenza, in secondi. */
val START_DELAYS = listOf(0, 10, 60, 600, 1800)

/** La fotocamera ha rifiutato lo scatto ma la connessione è viva: non serve riconnettersi. */
class ShotRefused(message: String) : IOException(message)

private const val RECONNECT_TRIES = 3

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

    init {
        net.lostListener = { main.post { onWifiLost() } }
    }

    /**
     * Android ha chiuso il Wi-Fi della fotocamera: lo si scrive nel Registro e si chiude subito
     * il collegamento, così parte la riconnessione invece di aspettare un minuto di timeout.
     */
    private fun onWifiLost() {
        log("Android ha chiuso il Wi-Fi della fotocamera")
        val c = camera ?: return
        if (!connected || viaUsb) return
        thread(isDaemon = true) { runCatching { c.close() } }
    }

    // Aspetto
    var night by mutableStateOf(prefs.getBoolean("night", false))
        private set
    var gridOn by mutableStateOf(prefs.getBoolean("grid", true))
        private set

    fun toggleNight() {
        night = !night
        prefs.edit().putBoolean("night", night).apply()
    }

    private fun applyNight(on: Boolean) {
        night = on
        prefs.edit().putBoolean("night", night).apply()
    }

    fun toggleGrid() {
        gridOn = !gridOn
        prefs.edit().putBoolean("grid", gridOn).apply()
    }

    // Aiuti sul mirino: istogramma e focus peaking
    var histOn by mutableStateOf(prefs.getBoolean("hist", false))
        private set
    var peakingOn by mutableStateOf(prefs.getBoolean("peaking", false))
        private set
    /** Ultimo istogramma del live view (null = spento o non ancora calcolato). */
    var histogram by mutableStateOf<LiveAnalyzer.Histogram?>(null)
        private set
    /** Contorni a fuoco da sovrapporre al fotogramma. */
    var peakFrame by mutableStateOf<ImageBitmap?>(null)
        private set

    fun toggleHistogram() {
        histOn = !histOn
        prefs.edit().putBoolean("hist", histOn).apply()
        if (!histOn) histogram = null
    }

    fun togglePeaking() {
        peakingOn = !peakingOn
        prefs.edit().putBoolean("peaking", peakingOn).apply()
        if (!peakingOn) peakFrame = null
    }

    /** L'app è in primo piano: gli avvisi diventano notifiche solo quando non la si guarda. */
    private var appVisible = true

    private fun alert(title: String, text: String) {
        if (!appVisible) SessionService.alert(getApplication(), title, text)
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
        Preset(MILKY_WAY, 20, 2.0, 0.0, "Light", "Camera fissa su treppiede: tempo dalla regola NPF per avere stelle puntiformi, diaframma tutto aperto, ISO 3200–6400."),
        Preset("Deep sky (inseguitore)", 15, 3.0, 120.0, "Light", "Pose da 2 minuti in posa B: serve una montatura che segue il moto delle stelle."),
        Preset("Timelapse", 300, 5.0, 0.0, "", "300 scatti, uno ogni 5 secondi, col tempo impostato sulla fotocamera."),
        Preset("Dark frame", 20, 2.0, null, "Dark", "Stessi tempo e ISO delle foto, col tappo sull'obiettivo: servono a togliere il rumore del sensore."),
        Preset("Flat", 20, 1.0, 0.0, "Flat", "Davanti all'obiettivo un panno bianco o uno schermo acceso uniforme. Stessi fuoco e diaframma delle foto, modo Av: il tempo lo sceglie la fotocamera."),
        Preset("Bias", 30, 1.0, 0.0, "Bias", "Tappo sull'obiettivo, stesso ISO delle foto e il tempo più breve della fotocamera (es. 1/4000)."),
    )

    /** Attesa prima del primo scatto, in secondi (es. per far finire il crepuscolo). */
    var intDelay by mutableIntStateOf(prefs.getInt("intDelay", 0))
        private set

    fun changeDelay(seconds: Int) {
        intDelay = seconds
        prefs.edit().putInt("intDelay", seconds).apply()
    }

    /** Si incrementa a ogni sequenza: una sequenza vecchia che finisce in ritardo non tocca lo stato. */
    private var seqToken = 0

    /** Fine di una sequenza (completata o fermata): l'interfaccia vibra. */
    var seqEndTick by mutableIntStateOf(0)
        private set

    // Galleria
    val files = mutableStateListOf<CamFile>()
    val thumbs = mutableStateMapOf<String, ImageBitmap>()
    var selected by mutableStateOf(setOf<String>())
        private set
    var subfolder by mutableStateOf(presets.firstOrNull { it.name == prefs.getString("preset", null) }?.folder ?: "")
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
                val cam = openWifi(brand, host)
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
                val cam = openUsb(brand)
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

    /** Wi-Fi della fotocamera (se scelto) e ricerca: restituisce il driver collegato. */
    private suspend fun openWifi(b: Brand, h: String): CameraDriver {
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
        return withContext(Dispatchers.IO + NonCancellable) {
            DriverFactory.connect(b, h, net, guid(), "Iride ${Build.MODEL}", ::log) { msg ->
                main.post { if (busy) setStatus(msg) }
            }
        }
    }

    private suspend fun openUsb(b: Brand): CameraDriver {
        val found = usb.find() ?: throw IOException("Nessuna fotocamera sul cavo USB. Collegala con un adattatore OTG e accendila.")
        setStatus("Consenti a Iride di usare ${found.name}")
        if (!usb.requestPermission(found.device)) throw IOException("Permesso per la fotocamera USB negato")
        net.release()
        setStatus("Collegamento a ${found.name}…")
        return withContext(Dispatchers.IO + NonCancellable) {
            DriverFactory.connectUsb(b, usb, found, ::log)
        }
    }

    // ---------------------------------------------------------------- riconnessione automatica

    private enum class Link { WIFI, USB }

    /** Ultimo collegamento riuscito (null in demo): serve per riconnettersi da soli. */
    private var lastLink: Link? = null
    private var lastBrand = Brand.AUTO
    private var lastTarget = ""
    /** Sequenza interrotta dalla caduta della connessione: riprende da questo scatto (0 = nessuna). */
    private var resumeAt = 0
    private var resumeTotal = 0
    private var reconnectsThisSequence = 0

    /** Sta cercando di ricollegarsi dopo una caduta della connessione. */
    var reconnecting by mutableStateOf(false)
        private set

    /**
     * Prova a ricollegarsi alla stessa fotocamera, con attese crescenti (la fotocamera può
     * impiegare qualche secondo a chiudere la vecchia sessione). Se una sequenza era in corso
     * riprende dallo scatto interrotto.
     */
    private fun reconnect(link: Link, reason: String?) {
        busy = true
        reconnecting = true
        val waits = longArrayOf(2_000, 6_000, 15_000)
        connectJob = viewModelScope.launch {
            try {
                for (attempt in 1..RECONNECT_TRIES) {
                    val pending = if (resumeAt > 0) " La sequenza riprenderà dallo scatto $resumeAt di $resumeTotal." else ""
                    setStatus("Connessione persa. Nuovo tentativo $attempt di $RECONNECT_TRIES…$pending")
                    refreshSession()
                    delay(waits[attempt - 1])
                    try {
                        // gli ultimi tentativi cercano di nuovo la fotocamera: l'indirizzo può essere cambiato
                        val target = if (attempt < RECONNECT_TRIES) lastTarget else host
                        val cam = if (link == Link.USB) openUsb(lastBrand) else openWifi(lastBrand, target)
                        if (!isActive) {
                            thread(isDaemon = true) { runCatching { cam.close() } }
                            throw CancellationException("annullato")
                        }
                        reconnecting = false
                        onConnected(cam, usbLink = link == Link.USB)
                        log("Riconnesso al tentativo $attempt")
                        if (resumeAt > 0) {
                            val first = resumeAt
                            val total = resumeTotal
                            resumeAt = 0
                            delay(1500)
                            if (connected) {
                                log("Riprendo la sequenza dallo scatto $first di $total")
                                beginSequence(first, total, 0)
                            }
                        }
                        return@launch
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("Tentativo $attempt non riuscito: ${e.message}")
                        net.release()
                    }
                }
                val lostAt = if (resumeAt > 0) " La sequenza si è fermata allo scatto $resumeAt di $resumeTotal." else ""
                reconnecting = false
                resumeAt = 0
                setStatus("Connessione persa ($reason) e riconnessione non riuscita: ricollegati a mano.$lostAt", error = true)
                alert("Fotocamera scollegata", "Riconnessione non riuscita.$lostAt")
                refreshSession()
            } catch (e: CancellationException) {
                reconnecting = false
                resumeAt = 0
                cleanup()
                setStatus("Riconnessione annullata")
            } finally {
                busy = false
            }
        }
    }

    /** Aggiorna la fotocamera vista sul cavo USB (la schermata di collegamento lo chiama di continuo). */
    fun refreshUsb() {
        usbCamera = if (usb.supported) usb.find()?.name else null
    }

    // ---------------------------------------------------------------- modalità demo

    /** Collegata alla fotocamera dimostrativa (nessuna fotocamera vera). */
    var demo by mutableStateOf(false)
        private set
    /** Richieste dall'esterno (scene della guida): scheda da aprire, ghiera da mostrare, scorrimento. */
    var requestedTab by mutableStateOf<Int?>(null)
        private set
    var requestedDial by mutableStateOf<ExpoKey?>(null)
        private set
    var scrollToInterval by mutableIntStateOf(0)
        private set

    fun consumeTab() { requestedTab = null }
    fun consumeDial() { requestedDial = null }

    /** Prova l'app senza fotocamera: tutto funziona con una fotocamera simulata. */
    fun connectDemo() {
        if (busy || connected) return
        demo = true
        log("Modalità demo: fotocamera simulata")
        onConnected(DemoDriver(), usbLink = false)
        cameraHost = "demo"
    }

    /**
     * Prepara una schermata della guida (avvio con l'extra "scene"): usata dall'emulatore
     * su GitHub per fotografare l'app sempre aggiornata.
     */
    fun runScene(name: String) {
        viewModelScope.launch {
            applyNight(name == "night")
            // aiuti del mirino e bracketing solo nella scena dedicata: le altre restano come nella guida
            val tools = name == "tools"
            if (histOn != tools) toggleHistogram()
            if (peakingOn != tools) togglePeaking()
            changeBracket(if (tools) 5 else 1)
            if (name == "connect") {
                brand = Brand.CANON
                mode = Mode.HOTSPOT
                return@launch
            }
            if (lvQuality != LvQuality.BALANCED) {
                lvQuality = LvQuality.BALANCED
                prefs.edit().putString("lvq", lvQuality.name).apply()
            }
            connectDemo()
            delay(800)
            when (name) {
                "shoot", "night", "tools" -> toggleLive()
                "dial" -> requestedDial = ExpoKey.TV
                "stars" -> {
                    toggleLive()
                    delay(2500)
                    toggleStarAssist()
                    delay(1000)
                    tapViewfinder(0.52f, 0.45f)
                    delay(2500)
                    // avvicinamento al fuoco con un piccolo superamento, come si fa a mano
                    repeat(4) { focusStep(2); delay(900) }
                    repeat(2) { focusStep(-1); delay(900) }
                }
                "interval" -> {
                    applyPreset(presets.first())
                    delay(1200)
                    scrollToInterval++
                }
                "gallery" -> {
                    requestedTab = 1
                    delay(3500)
                    selected = files.filter { it.isRaw }.take(3).map { it.id }.toSet()
                    subfolder = "Light"
                }
                "log" -> requestedTab = 2
            }
        }
    }

    private fun onConnected(cam: CameraDriver, usbLink: Boolean) {
        val target = if (usbLink) "USB" else DriverFactory.lastHost
        lastLink = if (cam is DemoDriver) null else if (usbLink) Link.USB else Link.WIFI
        lastBrand = cam.brand
        lastTarget = if (usbLink) "" else target
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
        refreshLens()
        // la scheda si legge solo quando si apre la galleria: con migliaia di foto
        // terrebbe occupato il canale per minuti e rallenterebbe il live view
        filesLoaded = false
    }

    fun cancelConnect() {
        val wasReconnecting = reconnecting
        reconnecting = false
        resumeAt = 0
        connectJob?.cancel()
        cleanup()
        busy = false
        setStatus(if (wasReconnecting) "Riconnessione annullata" else "Collegamento annullato")
    }

    fun disconnect() {
        reconnecting = false
        resumeAt = 0
        cleanup()
        setStatus("Scollegato")
        log("Scollegato")
    }

    /**
     * Servizio in primo piano mentre una sequenza, un download o una riconnessione sono in corso:
     * a schermo spento Android non mette in pausa l'app né spegne la rete. Si ferma quando non serve più.
     */
    private fun refreshSession() {
        val app = getApplication<Application>()
        when {
            intervalRunning -> SessionService.update(
                app,
                if (seqDone == 0 && seqPhase == SeqPhase.WAITING) "Intervallometro: inizio tra poco"
                else "Intervallometro: scatto ${minOf(seqDone + 1, seqTotal)} di $seqTotal",
            )
            reconnecting -> SessionService.update(app, "Connessione persa: riprovo a collegarmi…")
            downloading -> SessionService.update(app, "Download: file $dlIndex di $dlTotal")
            else -> SessionService.stop(app)
        }
    }

    // live view sospeso quando l'app va in secondo piano (batteria del telefono e della fotocamera)
    private var resumeLive = false

    fun onAppVisible(visible: Boolean) {
        appVisible = visible
        if (!visible) {
            if (liveOn && !intervalRunning) {
                resumeLive = true
                toggleLive()
            }
        } else if (resumeLive) {
            resumeLive = false
            if (connected && !liveOn) toggleLive()
        }
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
        demo = false
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
        seqToken++
        bracketJob?.cancel()
        bracketing = false
        histogram = null
        peakFrame = null
        downloading = false
        resumeLive = false
        // durante la riconnessione il servizio resta acceso: a schermo spento serve la rete
        refreshSession()
        files.clear()
        thumbs.clear()
        thumbLoading.clear()
        thumbOrder.clear()
        thumbFailed.clear()
        selected = emptySet()
        saved = emptySet()
        exposure = ExposureState()
        lens = null
        sensor = null
        skyFocal = 0f
        lastPropsVersion = -1
        caps = Capabilities()
    }

    private fun onConnectionLost(reason: String?) {
        if (!connected) return
        log("Connessione persa: $reason")
        val link = lastLink
        // sequenza a metà: si ricorda da dove riprendere (lo scatto in corso è perso)
        if (intervalRunning && seqDone < seqTotal && reconnectsThisSequence < 10) {
            resumeAt = seqDone + 1
            resumeTotal = seqTotal
            reconnectsThisSequence++
        } else {
            resumeAt = 0
        }
        if (link == null) {
            cleanup()
            setStatus("Connessione persa: $reason", error = true)
            return
        }
        reconnecting = true
        cleanup()
        reconnect(link, reason)
    }

    override fun onCleared() {
        reconnecting = false
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
        if (bracketShots > 1 && caps.exposureControl) {
            shootBracket(cam)
            return
        }
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

    // ---------------------------------------------------------------- bracketing

    /** Scatti del bracketing: 1 = spento, altrimenti 3, 5 o 7. */
    var bracketShots by mutableIntStateOf(prefs.getInt("brk", 1))
        private set
    /** Distanza tra uno scatto e l'altro, in EV. */
    var bracketStep by mutableIntStateOf(prefs.getInt("brkStep", 1))
        private set
    var bracketing by mutableStateOf(false)
        private set
    var bracketStatus by mutableStateOf("")
        private set
    private var bracketJob: Job? = null

    fun changeBracket(shots: Int) {
        bracketShots = shots
        bracketStatus = ""
        prefs.edit().putInt("brk", shots).apply()
    }

    fun changeBracketStep(ev: Int) {
        bracketStep = ev
        prefs.edit().putInt("brkStep", ev).apply()
    }

    private class BracketPlan(val key: ExpoKey, val base: ExpoOption, val shots: List<ExpoOption>)

    /** "+0.7" → 0.7, "-1" → -1, "0" → 0 */
    private fun evValue(label: String): Double? = label.replace(',', '.').replace("EV", "").trim().removePrefix("+").toDoubleOrNull()

    /**
     * Valori da scattare, nell'ordine 0, −, +, −−, ++. In manuale si cambia il tempo (diaframma
     * e ISO restano fissi); negli altri modi la compensazione, così la fotocamera non annulla
     * la differenza cambiando il diaframma. Ogni valore è il più vicino tra quelli offerti.
     */
    private fun bracketPlan(ex: ExposureState, shots: Int, step: Int): BracketPlan? {
        val offsets = buildList {
            add(0.0)
            for (k in 1..shots / 2) {
                add(-k.toDouble() * step)
                add(k.toDouble() * step)
            }
        }
        fun log2(v: Double) = ln(v) / ln(2.0)
        val tv = ex.values[ExpoKey.TV]
        val tvSec = tv?.let { SkyAdvisor.seconds(it.label) }
        val tvOpts = ex.options[ExpoKey.TV].orEmpty().mapNotNull { o -> SkyAdvisor.seconds(o.label)?.takeIf { it > 0 }?.let { o to log2(it) } }
        val ev = ex.values[ExpoKey.EV]
        val evNow = ev?.let { evValue(it.label) }
        val evOpts = ex.options[ExpoKey.EV].orEmpty().mapNotNull { o -> evValue(o.label)?.let { o to it } }
        val tvUsable = tv != null && tvSec != null && tvSec > 0 && tvOpts.size > 1
        val evUsable = ev != null && evNow != null && evOpts.size > 1
        val useTv = tvUsable && (ex.canEdit(ExpoKey.AV) || !evUsable)
        return when {
            useTv -> {
                val base = log2(tvSec!!)
                BracketPlan(ExpoKey.TV, tv!!, offsets.map { off -> tvOpts.minBy { abs(it.second - (base + off)) }.first }.distinctBy { it.code })
            }
            evUsable -> BracketPlan(
                ExpoKey.EV, ev!!,
                offsets.map { off -> evOpts.minBy { abs(it.second - (evNow!! + off)) }.first }.distinctBy { it.code },
            )
            else -> null
        }
    }

    private fun shootBracket(cam: CameraDriver) {
        if (bracketing || intervalRunning) return
        bracketing = true
        bracketStatus = "Preparazione del bracketing…"
        val shots = bracketShots
        val step = bracketStep
        bracketJob = viewModelScope.launch {
            var plan: BracketPlan? = null
            try {
                val pl = withContext(Dispatchers.IO) { bracketPlan(cam.exposure(), shots, step) }
                    ?: throw ShotRefused("in questo modo di scatto non si possono cambiare né il tempo né la compensazione")
                plan = pl
                val what = if (pl.key == ExpoKey.TV) "tempo" else "compensazione"
                log("Bracketing di ${pl.shots.size} scatti sul $what: " + pl.shots.joinToString(" · ") { it.label })
                for ((i, o) in pl.shots.withIndex()) {
                    val label = "Bracketing ${i + 1} di ${pl.shots.size} · ${if (pl.key == ExpoKey.EV) "EV " else ""}${o.label}"
                    bracketStatus = label
                    val set = withContext(Dispatchers.IO) {
                        cam.setExposure(pl.key, o.code).also { syncExposure(cam) }
                    }
                    if (!set) throw ShotRefused("la fotocamera non accetta ${o.label}")
                    delay(250)
                    shotTick++
                    lastShotAt = SystemClock.elapsedRealtime()
                    // autofocus solo sul primo scatto: gli altri devono avere lo stesso fuoco
                    val af = autofocus && i == 0
                    retryShot({ cam.capture(af) }) { bracketStatus = "$label · la fotocamera sta salvando…" }
                }
                bracketStatus = "Bracketing completato: ${pl.shots.size} scatti"
                log(bracketStatus)
            } catch (e: CancellationException) {
                bracketStatus = ""
            } catch (e: Exception) {
                bracketStatus = "Bracketing interrotto: ${e.message}"
                log(bracketStatus)
            } finally {
                // si rimette il valore di partenza
                val p = plan
                if (p != null) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { cam.setExposure(p.key, p.base.code) }
                        runCatching { syncExposure(cam) }
                    }
                }
                bracketing = false
            }
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
            histogram = null
            peakFrame = null
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
                        // 20 s: lascia a Canon il tempo di provare tutte le uscite del live view
                        if (SystemClock.elapsedRealtime() - lastFrame > 20_000) throw IOException("nessun fotogramma ricevuto")
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
                    histogram = null
                    peakFrame = null
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
        val analyzer = LiveAnalyzer()
        var analyzed = 0
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
            } else if (histOn || peakingOn) {
                // un fotogramma su due: basta all'occhio e lascia la CPU al live view
                if (++analyzed % 2 == 0) {
                    val color = if (night) 0xFFFFB4A8.toInt() else 0xFF35E0FF.toInt()
                    val r = analyzer.analyze(bmp, histOn, peakingOn, color)
                    histogram = if (histOn) r.histogram else null
                    peakFrame = if (peakingOn) r.peaking?.asImageBitmap() else null
                }
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

    /** Preset scelto, evidenziato nell'interfaccia. */
    var selectedPreset by mutableStateOf<String?>(prefs.getString("preset", null))
        private set

    fun applyPreset(p: Preset) {
        intCount = p.count.toString()
        intPause = fmtNum(p.pause)
        p.bulb?.let { intBulb = fmtNum(it) }
        selectedPreset = p.name
        // i file scaricati vanno nella cartella giusta per lo stacking (Light, Dark, Flat, Bias)
        subfolder = p.folder
        prefs.edit().putString("preset", p.name).apply()
        if (p.name == MILKY_WAY) refreshLens()
    }

    // ---------------------------------------------------------------- consigli per la Via Lattea

    var lens by mutableStateOf<LensInfo?>(null)
        private set
    var sensor by mutableStateOf<SensorInfo?>(null)
        private set
    /** Focale usata per il calcolo (per gli zoom la sceglie l'utente). */
    var skyFocal by mutableFloatStateOf(0f)
        private set

    fun refreshLens() {
        val cam = camera ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val l = runCatching { cam.lens() }.getOrNull()
            val s = runCatching { cam.sensor() }.getOrNull()
            withContext(Dispatchers.Main) {
                val changed = l?.name != lens?.name
                lens = l
                sensor = s
                if (changed || skyFocal <= 0f) skyFocal = l?.currentFocal ?: l?.minFocal ?: 24f
            }
        }
    }

    fun stepSkyFocal(delta: Int) {
        val l = lens
        val min = l?.minFocal ?: 8f
        val max = l?.maxFocal ?: 800f
        val step = if (max - min > 40f) 5f else 1f
        skyFocal = (skyFocal + delta * step).coerceIn(min, max)
    }

    /** Diaframma per il calcolo: il massimo dell'obiettivo, o il più aperto tra quelli offerti dalla fotocamera. */
    private fun widestAperture(): Float? =
        lens?.maxAperture ?: exposure.options[ExpoKey.AV]?.mapNotNull { SkyAdvisor.aperture(it.label) }?.minOrNull()?.toFloat()

    fun skyAdvice(): SkyAdvisor.Advice? {
        val f = skyFocal.takeIf { it > 0f } ?: return null
        val n = widestAperture() ?: SkyAdvisor.aperture(exposure.values[ExpoKey.AV]?.label ?: "")?.toFloat() ?: 2.8f
        return SkyAdvisor.milkyWay(f, n, sensor)
    }

    /** Imposta sulla fotocamera diaframma, tempo e ISO consigliati (serve il modo M). */
    fun applySkyAdvice() {
        val cam = camera ?: return
        val adv = skyAdvice() ?: return
        val needBulb = adv.npfSeconds > 30f && caps.bulb
        intBulb = if (needBulb) SkyAdvisor.bulbSeconds(adv.npfSeconds).toString() else "0"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val done = ArrayList<String>()
                val ex = cam.exposure()
                ex.options[ExpoKey.AV]
                    ?.mapNotNull { o -> SkyAdvisor.aperture(o.label)?.let { o to it } }
                    ?.minByOrNull { it.second }
                    ?.let { (o, _) -> if (cam.setExposure(ExpoKey.AV, o.code)) done.add(o.label) }
                val tvOpts = ex.options[ExpoKey.TV].orEmpty()
                val tv = if (needBulb) {
                    tvOpts.firstOrNull { it.label.equals("Bulb", true) || it.label == "B" }
                } else {
                    tvOpts.mapNotNull { o -> SkyAdvisor.seconds(o.label)?.let { o to it } }
                        .filter { it.second <= adv.npfSeconds + 0.01 }
                        .maxByOrNull { it.second }?.first
                }
                if (tv != null && cam.setExposure(ExpoKey.TV, tv.code)) done.add(tv.label)
                ex.options[ExpoKey.ISO]
                    ?.mapNotNull { o -> SkyAdvisor.iso(o.label)?.let { o to it } }
                    ?.minByOrNull { kotlin.math.abs(it.second - adv.iso) }
                    ?.let { (o, _) -> if (cam.setExposure(ExpoKey.ISO, o.code)) done.add("ISO ${o.label}") }
                if (done.isEmpty()) {
                    log("Via Lattea: valori non applicati. Metti la fotocamera in modo M e riprova")
                } else {
                    log("Via Lattea: impostati " + done.joinToString(" · ") + if (needBulb) " · posa B ${intBulb} s" else "")
                }
                syncExposure(cam)
            } catch (e: Exception) {
                log("Via Lattea: impostazione fallita (${e.message})")
            }
        }
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

    /** Durata stimata della sequenza in secondi, ritardo iniziale compreso (0 se non calcolabile). */
    fun estimatedSeconds(): Int {
        val count = intCount.trim().toIntOrNull() ?: return 0
        val bulb = if (caps.bulb) parseSeconds(intBulb) else 0.0
        // senza posa B vale il tempo della fotocamera, se è un numero (non Bulb o Auto)
        val shot = if (bulb > 0) bulb else SkyAdvisor.seconds(exposure.text(ExpoKey.TV)) ?: 0.0
        val pause = parseSeconds(intPause)
        val total = (shot + pause) * count - pause
        if (total <= 0) return 0
        return (total + intDelay).roundToInt()
    }

    fun startInterval() {
        if (camera == null) return
        val count = intCount.trim().toIntOrNull()
        if (count == null || count < 1) {
            intervalStatus = "Numero di scatti non valido"
            return
        }
        prefs.edit().putString("intCount", intCount).putString("intPause", intPause).putString("intBulb", intBulb).apply()
        reconnectsThisSequence = 0
        beginSequence(1, count, intDelay)
    }

    /** Scatti da [first] a [total] (dopo una riconnessione si riparte da metà). */
    private fun beginSequence(first: Int, total: Int, delaySeconds: Int) {
        val cam = camera ?: return
        val count = total
        val pauseMs = (parseSeconds(intPause) * 1000).toLong()
        // con fotocamere senza posa B da remoto il valore salvato non va usato
        val bulbMs = if (caps.bulb) (parseSeconds(intBulb) * 1000).toLong() else 0L
        val token = ++seqToken
        intervalRunning = true
        seqDone = first - 1
        seqTotal = count
        seqPhase = if (delaySeconds > 0) SeqPhase.WAITING else SeqPhase.IDLE
        refreshSession()
        intervalJob = viewModelScope.launch {
            var completed = false
            try {
                if (delaySeconds > 0) {
                    countdown(delaySeconds * 1000L, SeqPhase.WAITING) { left -> "La sequenza inizia tra ${formatLeft(left)}" }
                    refreshSession()
                }
                for (i in first..count) {
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
                    refreshSession()
                    log("$label completato")
                    if (i < count && pauseMs > 0) {
                        countdown(pauseMs, SeqPhase.WAITING) { left -> "Prossimo scatto tra ${formatLeft(left)}" }
                    }
                }
                completed = true
                intervalStatus = "Sequenza completata: $count scatti"
                log(intervalStatus)
                alert("Sequenza completata", "$count scatti eseguiti.")
            } catch (e: CancellationException) {
                if (token == seqToken) intervalStatus = "Sequenza interrotta dopo $seqDone scatti"
            } catch (e: Exception) {
                if (token == seqToken) {
                    if (e is ShotRefused || !connected) {
                        intervalStatus = "Sequenza fermata: ${e.message}"
                        log(intervalStatus)
                        alert("Sequenza fermata", "Dopo $seqDone scatti: ${e.message}")
                    } else {
                        // probabile caduta della connessione: riconnessione e ripresa
                        intervalStatus = "Connessione persa allo scatto ${seqDone + 1}"
                        onConnectionLost(e.message)
                    }
                }
            } finally {
                // una sequenza sostituita (es. ripresa dopo la riconnessione) non tocca lo stato della nuova
                if (token == seqToken) {
                    intervalRunning = false
                    seqPhase = SeqPhase.IDLE
                    seqPhaseFraction = 0f
                    seqSecondsLeft = 0
                    seqEndTick++
                    refreshSession()
                }
                if (completed) reconnectsThisSequence = 0
            }
        }
    }

    /** 75 → "1 min 15 s", 42 → "42 s" */
    private fun formatLeft(seconds: Long): String {
        if (seconds < 60) return "$seconds s"
        val s = seconds % 60
        return if (s > 0) "${seconds / 60} min $s s" else "${seconds / 60} min"
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
    private suspend fun retryWhileBusy(label: String, action: () -> Shot) = retryShot(action) {
        seqPhase = SeqPhase.BUSY
        intervalStatus = "$label · la fotocamera sta salvando, riprovo…"
    }

    private suspend fun retryShot(action: () -> Shot, onBusy: () -> Unit) {
        val start = SystemClock.elapsedRealtime()
        while (true) {
            val shot = withContext(Dispatchers.IO) { action() }
            if (shot == Shot.OK) return
            val elapsed = SystemClock.elapsedRealtime() - start
            // "occupata" può durare a lungo (scrittura su scheda, riduzione rumore); un errore vero no
            if (shot == Shot.FAILED && elapsed > 20_000) {
                throw ShotRefused("la fotocamera rifiuta lo scatto (controlla messa a fuoco, modo di scatto e scheda)")
            }
            if (elapsed > 120_000) {
                throw ShotRefused("la fotocamera non accetta lo scatto da oltre 2 minuti")
            }
            onBusy()
            delay(500)
        }
    }

    fun stopInterval() {
        intervalJob?.cancel()
        // fermata a mano: niente ripresa automatica se nel frattempo cade la connessione
        resumeAt = 0
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

    // miniature: una richiesta alla volta (il live view non resta in coda) e al massimo
    // MAX_THUMBS in memoria; quelle di riquadri usciti dallo schermo non vengono più chieste
    private val thumbGate = Semaphore(1)
    private val thumbOrder = ArrayDeque<String>()
    private val thumbFailed = HashSet<String>()

    /** Carica la miniatura; va chiamata dal riquadro (si annulla se il riquadro esce dallo schermo). */
    suspend fun loadThumb(file: CamFile) {
        val handle = file.id
        if (thumbs.containsKey(handle) || handle in thumbFailed || !thumbLoading.add(handle)) return
        val cam = camera
        if (cam == null) {
            thumbLoading.remove(handle)
            return
        }
        try {
            val ib = thumbGate.withPermit {
                withContext(Dispatchers.IO) {
                    val data = cam.thumbnail(file) ?: return@withContext null
                    // RGB_565: metà memoria, per una miniatura non si vede la differenza
                    val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                    BitmapFactory.decodeByteArray(data, 0, data.size, opts)?.asImageBitmap()
                }
            }
            if (ib == null) {
                thumbFailed.add(handle)
            } else if (camera === cam) {
                thumbs[handle] = ib
                thumbOrder.addLast(handle)
                while (thumbOrder.size > MAX_THUMBS) {
                    val old = thumbOrder.removeFirst()
                    thumbs.remove(old)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            thumbFailed.add(handle)
        } finally {
            thumbLoading.remove(handle)
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
                refreshSession()
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
            galleryStatus = if (demo) "Demo: download di $ok file simulato" else "Scaricati $ok di ${list.size} file in $dest"
            log(galleryStatus)
            selected = emptySet()
            downloading = false
            dlFraction = 1f
            refreshSession()
        }
    }

    private fun saveFile(
        resolver: ContentResolver,
        cam: CameraDriver,
        f: CamFile,
        sub: String,
        progress: (Long, Long) -> Unit,
    ) {
        if (demo) {
            // fotocamera simulata: nessun file finto sul telefono
            cam.download(f, object : java.io.OutputStream() { override fun write(b: Int) = Unit }, progress)
            return
        }
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
