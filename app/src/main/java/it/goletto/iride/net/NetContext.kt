package it.goletto.iride.net

import android.net.Network
import java.io.IOException
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import javax.net.SocketFactory

/**
 * Accesso alla rete della fotocamera per i driver. Se il telefono è collegato al Wi-Fi della
 * fotocamera come rete "locale", tutto passa da lì; altrimenti si usa la rete normale (hotspot).
 */
class NetContext(val socketFactory: SocketFactory, private val network: Network?) {

    fun open(url: String, timeoutMs: Int = 8000): HttpURLConnection {
        val u = URL(url)
        val c = (network?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.useCaches = false
        return c
    }

    /** GET o POST semplice: restituisce il corpo della risposta. Lancia IOException se il codice non è 2xx. */
    fun request(
        url: String,
        method: String = "GET",
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 8000,
    ): ByteArray {
        val c = open(url, timeoutMs)
        try {
            c.requestMethod = method
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val data = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (code !in 200..299) throw IOException("HTTP $code da $url")
            return data
        } finally {
            c.disconnect()
        }
    }

    /** Socket UDP in ascolto sulla porta indicata, legata alla rete della fotocamera. */
    fun udp(port: Int): DatagramSocket {
        val s = DatagramSocket(null)
        s.reuseAddress = true
        network?.bindSocket(s)
        s.bind(InetSocketAddress(port))
        return s
    }
}
