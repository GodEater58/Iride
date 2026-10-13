package it.goletto.eoslink.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import it.goletto.eoslink.ptp.PtpIp
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
            cm.requestNetwork(request, cb)
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

    /** Cerca dispositivi con la porta PTP/IP (15740) aperta nelle sottoreti /24 locali. */
    suspend fun scan(onProgress: (String) -> Unit): List<String> = coroutineScope {
        val factory = socketFactory()
        val sem = Semaphore(48)
        val hosts = localAddresses().flatMap { a ->
            val b = a.address
            val self = b[3].toInt() and 0xFF
            val prefix = "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}."
            (1..254).filter { it != self }.map { prefix + it }
        }.distinct()
        onProgress("Ricerca su ${hosts.size} indirizzi…")
        hosts.map { h ->
            async(Dispatchers.IO) { sem.withPermit { if (probe(factory, h)) h else null } }
        }.awaitAll().filterNotNull()
    }

    private fun probe(factory: SocketFactory, host: String): Boolean = try {
        factory.createSocket().use { it.connect(InetSocketAddress(host, PtpIp.PORT), 400) }
        true
    } catch (e: Exception) {
        false
    }
}
