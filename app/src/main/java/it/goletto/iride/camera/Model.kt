package it.goletto.iride.camera

import androidx.compose.runtime.Immutable
import java.io.Closeable
import java.io.OutputStream
import java.util.Locale

/** Marche supportate. AUTO riconosce la fotocamera dopo il collegamento. */
enum class Brand(val label: String) {
    AUTO("Automatico"),
    CANON("Canon"),
    NIKON("Nikon"),
    SONY("Sony"),
    FUJIFILM("Fujifilm"),
    OLYMPUS("Olympus / OM"),
    PANASONIC("Panasonic"),
    OTHER_PTP("Altra marca"),
}

/** Le grandezze di esposizione mostrate nel pannello, uguali per tutte le marche. */
enum class ExpoKey(val title: String, val short: String) {
    MODE("Modo di scatto", "Modo"),
    TV("Tempo di scatto", "Tempo"),
    AV("Apertura", "Diaframma"),
    ISO("Sensibilità ISO", "ISO"),
    EV("Compensazione esposizione", "Comp. EV"),
    WB("Bilanciamento del bianco", "Bil. bianco"),
    SHOTS("Scatti rimasti", "Scatti liberi"),
    ;

    /** Valori testuali (non numerici): si scelgono da un elenco invece che dalla ghiera. */
    val isList: Boolean get() = this == MODE || this == WB
}

/** Un valore così come lo intende la fotocamera ([code]) e come lo leggiamo noi ([label]). */
@Immutable
data class ExpoOption(val code: Long, val label: String)

@Immutable
data class ExposureState(
    val values: Map<ExpoKey, ExpoOption> = emptyMap(),
    val options: Map<ExpoKey, List<ExpoOption>> = emptyMap(),
) {
    fun text(k: ExpoKey): String = values[k]?.label ?: "--"
    fun canEdit(k: ExpoKey): Boolean = (options[k]?.size ?: 0) > 1
}

/** Cosa sa fare il driver: l'interfaccia nasconde o disattiva il resto. */
@Immutable
data class Capabilities(
    val liveView: Boolean = true,
    val bulb: Boolean = true,
    val exposureControl: Boolean = true,
    val cardBrowsing: Boolean = true,
    val autofocusToggle: Boolean = true,
    /** Nota mostrata in galleria quando la scheda non è consultabile. */
    val galleryNote: String = "",
    /** Tasto «Metti a fuoco» (autofocus senza scattare). */
    val autofocusButton: Boolean = false,
    /** La fotocamera può inviare il live view in dimensioni diverse. */
    val liveViewQuality: Boolean = false,
    /** Ingrandimenti del live view fatti dalla fotocamera (es. 1×, 5×, 10×); vuoto = solo zoom dell'app. */
    val zoomLevels: List<String> = emptyList(),
    /** Il riquadro di ingrandimento si può spostare su un punto toccato e trascinare. */
    val zoomPosition: Boolean = false,
    /** Messa a fuoco a passi dal telefono (motore dell'obiettivo). */
    val focusDrive: Boolean = false,
)

enum class Shot { OK, BUSY, FAILED }

/** Dimensione del live view: più piccolo = più fluido via Wi-Fi. */
enum class LvQuality(val label: String) {
    FLUID("Fluido"),
    BALANCED("Bilanciato"),
    DETAIL("Dettaglio"),
}

private val RAW_EXT = setOf("CR2", "CR3", "CRW", "NEF", "NRW", "ARW", "SRF", "SR2", "RAF", "ORF", "ORI", "RW2", "RWL", "DNG", "PEF", "IIQ", "3FR", "X3F")

@Immutable
data class CamFile(
    /** Identificativo per il driver: handle PTP o percorso HTTP. */
    val id: String,
    val name: String,
    val size: Long,
    /** Data nel formato PTP "yyyyMMddTHHmmss" (vuota se sconosciuta). */
    val captureDate: String,
    /** Il file esiste solo nella memoria della fotocamera e va scaricato subito (Sony in PC remoto). */
    val transient: Boolean = false,
) {
    private val ext: String get() = name.substringAfterLast('.', "").uppercase(Locale.ROOT)
    val isRaw: Boolean get() = ext in RAW_EXT
    val isJpeg: Boolean get() = ext == "JPG" || ext == "JPEG"
    val kind: String get() = when {
        isRaw -> "RAW"
        isJpeg -> "JPG"
        else -> ext.ifEmpty { "File" }
    }
}

/**
 * Un driver per marca. Tutte le chiamate sono bloccanti e vanno fatte da un thread di I/O.
 */
interface CameraDriver : Closeable {
    val brand: Brand
    val model: String
    val caps: Capabilities

    /** Cresce a ogni cambio di esposizione: l'interfaccia aggiorna solo quando serve. */
    val exposureVersion: Int
    fun exposure(): ExposureState

    /** Lettura periodica (~1 s): aggiorna lo stato e restituisce i file nuovi. */
    fun poll(): List<CamFile>

    fun capture(autofocus: Boolean): Shot
    fun bulbStart(): Shot
    fun bulbEnd()

    fun startLiveView()
    fun stopLiveView()
    /** Un fotogramma JPEG del live view, o null se non è ancora pronto. */
    fun liveViewFrame(): ByteArray?

    fun listFiles(progress: (Int, Int) -> Unit): List<CamFile>
    fun thumbnail(file: CamFile): ByteArray?
    /** Scarica l'originale completo. Lancia un'eccezione se non riesce. */
    fun download(file: CamFile, out: OutputStream, progress: (Long, Long) -> Unit)

    fun setExposure(key: ExpoKey, code: Long): Boolean

    /** Mette a fuoco senza scattare. */
    fun autofocus(): Shot = Shot.FAILED

    /** Dimensione del live view, applicata al prossimo avvio. */
    fun setLiveViewQuality(q: LvQuality) = Unit

    /**
     * Ingrandimento del live view fatto dalla fotocamera: [level] è l'indice in [Capabilities.zoomLevels].
     * Se [cx], [cy] (0..1 sull'inquadratura intera) sono valorizzati, il riquadro si centra lì.
     */
    fun liveZoom(level: Int, cx: Float? = null, cy: Float? = null): Boolean = false

    /** Sposta il riquadro ingrandito di una frazione della vista attuale (positivo = destra/basso). */
    fun panZoom(dx: Float, dy: Float): Boolean = false

    /** Un passo di messa a fuoco: -3..-1 verso il vicino, 1..3 verso l'infinito (3 = passo grande). */
    fun focusDrive(step: Int): Boolean = false
}

/** Utilità comuni per estrarre un JPEG da un blocco di dati (live view di molte marche). */
fun extractJpeg(d: ByteArray, from: Int = 0): ByteArray? {
    var start = -1
    var i = from
    while (i < d.size - 1) {
        if (d[i] == 0xFF.toByte() && d[i + 1] == 0xD8.toByte()) {
            start = i
            break
        }
        i++
    }
    if (start < 0) return null
    var end = d.size
    var j = d.size - 2
    while (j > start) {
        if (d[j] == 0xFF.toByte() && d[j + 1] == 0xD9.toByte()) {
            end = j + 2
            break
        }
        j--
    }
    return d.copyOfRange(start, end)
}

/** Formatta un tempo di scatto in secondi come sulle fotocamere: 30", 2"5, 1/125. */
fun formatSeconds(t: Double): String {
    if (t <= 0) return "--"
    return if (t >= 0.3) {
        val whole = t.toInt()
        val frac = Math.round((t - whole) * 10).toInt()
        if (frac == 0 || frac == 10) "${if (frac == 10) whole + 1 else whole}\"" else "$whole\"$frac"
    } else {
        "1/${Math.round(1.0 / t)}"
    }
}

fun formatAperture(f: Double): String =
    if (f <= 0) "--" else if (f >= 10) "f/${Math.round(f)}" else "f/" + String.format(Locale.ROOT, "%.1f", f)

/** Compensazione in EV: 0, +0.3, +0.7, -1, +1.5… */
fun formatEv(ev: Double): String {
    if (kotlin.math.abs(ev) < 0.05) return "0"
    val t = String.format(Locale.ROOT, "%+.1f", ev)
    return if (t.endsWith(".0")) t.dropLast(2) else t
}

/** Bilanciamento del bianco con i codici PTP standard (0x5005) e le estensioni delle varie marche. */
fun standardWbLabel(v: Long, brand: Brand): String = when (v) {
    1L -> "Manuale"
    2L -> "Auto"
    3L -> "Auto un tocco"
    4L -> "Luce diurna"
    5L -> "Fluorescente"
    6L -> "Tungsteno"
    7L -> "Flash"
    else -> when (brand) {
        Brand.NIKON, Brand.SONY, Brand.OTHER_PTP -> when (v) {
            0x8010L -> "Nuvoloso"
            0x8011L -> "Ombra"
            0x8012L -> "Temp. colore"
            0x8013L -> "Preimpostato"
            0x8016L -> "Auto luce naturale"
            in 0x8001L..0x8006L -> "Fluorescente ${v - 0x8000L}"
            in 0x8020L..0x8023L -> "Personalizzato ${v - 0x801FL}"
            else -> "WB " + java.lang.Long.toHexString(v)
        }
        Brand.FUJIFILM -> when (v) {
            in 0x8001L..0x8005L -> "Fluorescente ${v - 0x8000L}"
            0x8006L -> "Ombra"
            0x8007L -> "Temp. colore"
            in 0x8008L..0x800CL -> "Personalizzato ${v - 0x8007L}"
            else -> "WB " + java.lang.Long.toHexString(v)
        }
        else -> "WB $v"
    }
}
