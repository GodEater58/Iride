package it.goletto.iride.ptp

import java.io.Closeable
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Canale PTP verso la fotocamera: Wi-Fi (PTP/IP) o cavo USB. I driver delle marche
 * usano solo questa interfaccia, quindi funzionano uguali con entrambi i collegamenti.
 */
interface PtpTransport : Closeable {
    val isOpen: Boolean
    val cameraName: String

    /** true per il cavo USB: banda ampia, il live view può usare la dimensione grande. */
    val wired: Boolean

    /** Coda degli eventi standard: i driver la svuotano durante il polling. */
    val events: ConcurrentLinkedQueue<PtpEvent>

    /**
     * Esegue una transazione PTP. Se [sink] è valorizzato i dati ricevuti vengono scritti lì
     * (download di file grandi senza tenerli in memoria), altrimenti finiscono in [PtpResponse.data].
     */
    fun transaction(
        op: Int,
        params: IntArray = IntArray(0),
        dataOut: ByteArray? = null,
        sink: OutputStream? = null,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): PtpResponse
}
