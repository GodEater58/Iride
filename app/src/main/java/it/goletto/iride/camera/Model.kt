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
    SHOTS("Scatti rimasti", "Scatti liberi"),
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
)

enum class Shot { OK, BUSY, FAILED }

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
