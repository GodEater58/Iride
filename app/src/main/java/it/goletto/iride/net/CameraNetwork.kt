package it.goletto.iride.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import it.goletto.iride.ptp.PtpIp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import javax.net.SocketFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Gestisce il collegamento di rete con la fotocamera.
 *
 * Modalità Wi-Fi fotocamera: la rete viene richiesta con WifiNetworkSpecifier, che la tiene
 * "locale" all'app: non diventa la rete predefinita, quindi internet resta sui dati mobili.
 * Le socket PTP/IP vengono create con network.socketFactory per usare solo quella rete.
 *
 * Modalità hotspot / stessa rete: si usano le socket normali.
 */
class CameraNetwork(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)!!
    private val wm = context.applicationContext.getSystemService(WifiManager::class.java)!!
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    var network: Network? = null
        private set

    val supportsConcurrentWifi: Boolean
        get() = Build.VERSION.SDK_INT >= 31 && wm.isStaConcurrencyForLocalOnlyConnectionsSupported

    suspend fun connectToCameraWifi(ssid: String, passphrase: String): Network =
        suspendCancellableCoroutine { cont ->
            release()
            val spec = WifiNetworkSpecifier.Builder().setSsid(ssid)
            if (passphrase.isNotEmpty()) spec.setWpa2Passphrase(passphrase)
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec.build())
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) {
                    network = n
                    if (cont.isActive) cont.resume(n)
                }

                override fun onUnavailable() {
                    if (cont.isActive) cont.resumeWithException(IOException("Wi-Fi della fotocamera non disponibile o connessione annullata"))
                }

                override fun onLost(n: Network) {
                    if (n == network) network = null
                }
            }
            callback = cb
            // dopo 60 s senza risposta Android chiama onUnavailable: niente attese infinite
            cm.requestNetwork(request, cb, 60_000)
            cont.invokeOnCancellation { release() }
        }

    fun release() {
        val cb = callback
        callback = null
        network = null
        if (cb != null) {
            try {
                cm.unregisterNetworkCallback(cb)
            } catch (ignored: Exception) {
            }
        }
    }

    private var wifiLock: WifiManager.WifiLock? = null

    /**
     * Durante il live view chiede ad Android il Wi-Fi a bassa latenza (niente risparmio
     * energetico tra un pacchetto e l'altro). Rilasciato appena il live view si ferma.
     */
    fun lowLatency(on: Boolean) {
        if (on) {
            if (wifiLock?.isHeld == true) return
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "iride-liveview").apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }
        } else {
            runCatching { wifiLock?.release() }
            wifiLock = null
        }
    }

    fun socketFactory(): SocketFactory = network?.socketFactory ?: SocketFactory.getDefault()

    private fun localAddresses(): List<Inet4Address> {
        val n = network
        if (n != null) {
            return cm.getLinkProperties(n)?.linkAddresses?.mapNotNull { it.address as? Inet4Address } ?: emptyList()
        }
        val skip = listOf("rmnet", "ccmni", "dummy", "tun", "lo", "v4-", "clat")
        return NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && skip.none { p -> it.name.startsWith(p) } }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
    }

    /** Contesto di rete per i driver: HTTP e UDP instradati sulla rete giusta. */
    fun context(): NetContext = NetContext(socketFactory(), network)

    /**
     * Cerca nelle sottoreti /24 locali i dispositivi con una delle porte indicate aperta.
     * Restituisce coppie (indirizzo, porta), nell'ordine delle porte richieste.
     */
    suspend fun scan(ports: List<Int>, keepOpen: Set<Int>, onProgress: (String) -> Unit): List<Found> = coroutineScope {
        val factory = socketFactory()
        val sem = Semaphore(64)
        val hosts = localAddresses().flatMap { a ->
            val b = a.address
            val self = b[3].toInt() and 0xFF
            val prefix = "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}."
            (1..254).filter { it != self }.map { prefix + it }
        }.distinct()
        onProgress("Ricerca su ${hosts.size} indirizzi…")
        // appena si trova una fotocamera PTP/IP la ricerca si ferma: la connessione aperta
        // non deve restare inattiva a lungo, o la fotocamera la chiude
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val found = hosts.flatMap { h -> ports.map { port -> h to port } }.map { (h, port) ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    if (stop.get()) {
                        null
                    } else if (port in keepOpen) {
                        // le fotocamere PTP/IP accettano una sola connessione: quella trovata va riusata
                        open(factory, h, port, 300)?.let {
                            stop.set(true)
                            Found(h, port, it)
                        }
                    } else if (probe(factory, h, port, 300)) {
                        Found(h, port, null)
                    } else {
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull()
        found.sortedBy { ports.indexOf(it.port) }
    }

    /** Apre una connessione TCP e la lascia aperta; null se non risponde. */
    fun open(factory: SocketFactory, host: String, port: Int, timeoutMs: Int): Socket? {
        val s = factory.createSocket()
        return try {
            s.tcpNoDelay = true
            // buffer di ricezione ampio, impostato prima di collegarsi: fotogrammi del live view più rapidi
            s.receiveBufferSize = 1 shl 20
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s
        } catch (e: Exception) {
            runCatching { s.close() }
            null
        }
    }

    /** Una fotocamera trovata; [socket] è la connessione già aperta da riusare (porte PTP/IP). */
    class Found(val host: String, val port: Int, val socket: Socket?)

    fun probe(factory: SocketFactory, host: String, port: Int = PtpIp.PORT, timeoutMs: Int = 400): Boolean = try {
        factory.createSocket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    } catch (e: Exception) {
        false
    }
}
