package it.goletto.iride.http

import it.goletto.iride.camera.Brand
import it.goletto.iride.camera.CamFile
import it.goletto.iride.camera.CameraDriver
import it.goletto.iride.camera.Capabilities
import it.goletto.iride.camera.ExpoKey
import it.goletto.iride.camera.ExposureState
import it.goletto.iride.camera.Shot
import it.goletto.iride.camera.extractJpeg
import it.goletto.iride.net.NetContext
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * Panasonic Lumix via Wi-Fi: comandi HTTP cam.cgi verso 192.168.54.1 e live view come
 * fotogrammi JPEG su UDP. Versione di base: scatto e live view; esposizione e galleria
 * restano sulla fotocamera (le Lumix espongono i file solo via DLNA).
 */
class PanasonicDriver(
    private val net: NetContext,
    private val host: String,
    private val deviceId: String,
    private val deviceName: String,
    private val log: (String) -> Unit,
) : CameraDriver {

    override val brand = Brand.PANASONIC
    override var model: String = "Panasonic Lumix"
        private set
    override val caps = Capabilities(
        bulb = false,
        exposureControl = false,
        cardBrowsing = false,
        autofocusToggle = false,
        galleryNote = "Con le Lumix la galleria non è ancora disponibile in Iride: lo scatto e il live view funzionano, le foto restano sulla scheda.",
    )
    override val exposureVersion = 0
    override fun exposure() = ExposureState()

    private fun cam(params: String, timeoutMs: Int = 6000): String =
        String(net.request("http://$host/cam.cgi?$params", timeoutMs = timeoutMs))

    private fun ok(xml: String) = xml.contains("<result>ok</result>")

    fun initialize() {
        // le Lumix più recenti chiedono di registrare il dispositivo (conferma sulla fotocamera)
        runCatching {
            val r = cam("mode=accctrl&type=req_acc&value=$deviceId&value2=" + URLEncoder.encode(deviceName, "UTF-8"), 30000)
            log("Registrazione dispositivo: ${r.take(80)}")
        }
        runCatching {
            val info = cam("mode=getinfo&type=capability")
            Regex("<model>(.*?)</model>").find(info)?.groupValues?.get(1)?.let { model = "Panasonic $it" }
        }
        if (!ok(cam("mode=camcmd&value=recmode"))) log("La fotocamera non è passata in modalità scatto")
    }

    private var pollCount = 0

    override fun poll(): List<CamFile> {
        // tiene viva la sessione (e il flusso live view)
        pollCount++
        if (pollCount % 2 == 0) runCatching { cam("mode=getstate", 3000) }
        return emptyList()
    }

    override fun capture(autofocus: Boolean): Shot = try {
        val r = cam("mode=camcmd&value=capture", 15000)
        when {
            ok(r) -> Shot.OK
            r.contains("err_busy") -> Shot.BUSY
            else -> {
                log("Scatto: ${r.take(120)}")
                Shot.FAILED
            }
        }
    } catch (e: Exception) {
        log("Scatto: ${e.message}")
        Shot.FAILED
    }

    override fun bulbStart(): Shot = Shot.FAILED
    override fun bulbEnd() = Unit

    @Volatile
    private var latest: ByteArray? = null
    private var udp: DatagramSocket? = null
    private var lastKeepAlive = 0L

    override fun startLiveView() {
        val sock = net.udp(LIVE_PORT)
        sock.soTimeout = 2000
        udp = sock
        cam("mode=startstream&value=$LIVE_PORT")
        thread(isDaemon = true, name = "lumix-liveview") {
            val buf = ByteArray(65536)
            while (udp === sock) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(pkt)
                } catch (e: Exception) {
                    continue
                }
                // ogni datagramma contiene un'intestazione seguita da un JPEG completo
                latest = extractJpeg(buf.copyOf(pkt.length))
            }
            runCatching { sock.close() }
        }
    }

    override fun stopLiveView() {
        udp = null
        latest = null
        runCatching { cam("mode=stopstream") }
    }

    override fun liveViewFrame(): ByteArray? {
        val now = System.currentTimeMillis()
        if (now - lastKeepAlive > 1000) {
            lastKeepAlive = now
            runCatching { cam("mode=getstate", 2000) }
        }
        val f = latest
        latest = null
        if (f == null) Thread.sleep(40)
        return f
    }

    override fun listFiles(progress: (Int, Int) -> Unit): List<CamFile> = emptyList()
    override fun thumbnail(file: CamFile): ByteArray? = null
    override fun download(file: CamFile, out: OutputStream, progress: (Long, Long) -> Unit) {
        throw java.io.IOException("download non disponibile per Panasonic")
    }

    override fun setExposure(key: ExpoKey, code: Long): Boolean = false

    override fun close() {
        if (udp != null) stopLiveView()
    }

    companion object {
        const val DEFAULT_HOST = "192.168.54.1"
        private const val LIVE_PORT = 49199

        fun probe(net: NetContext, host: String): Boolean = runCatching {
            String(net.request("http://$host/cam.cgi?mode=getstate", timeoutMs = 1500)).contains("<camrply>")
        }.getOrDefault(false)
    }
}
