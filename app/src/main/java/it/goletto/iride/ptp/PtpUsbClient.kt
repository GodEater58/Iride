package it.goletto.iride.ptp

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

/**
 * PTP sul cavo USB (classe «Still Image»). Stessi comandi del Wi-Fi, ma ogni pacchetto
 * è un contenitore USB: lunghezza (u32), tipo (u16), codice (u16), transazione (u32), dati.
 * Il trasferimento è molto più veloce del Wi-Fi della fotocamera, quindi il live view
 * arriva con un ritardo minimo.
 */
class PtpUsbClient(
    private val conn: UsbDeviceConnection,
    private val intf: UsbInterface,
    override val cameraName: String,
    private val log: (String) -> Unit,
) : PtpTransport {

    private companion object {
        const val COMMAND = 1
        const val DATA = 2
        const val RESPONSE = 3
        const val EVENT = 4
        const val HEADER = 12
        const val TIMEOUT = 5000
        const val CHUNK = 256 * 1024
    }

    private lateinit var epOut: UsbEndpoint
    private lateinit var epIn: UsbEndpoint
    private var epEvt: UsbEndpoint? = null
    private val lock = ReentrantLock(true)
    private var transactionId = 0

    // flusso in ingresso: i contenitori arrivano uno dopo l'altro, a pezzi
    private val rx = ByteArray(CHUNK)
    private var rxPos = 0
    private var rxLen = 0

    @Volatile
    override var isOpen = false
        private set

    override val wired = true

    override val events = ConcurrentLinkedQueue<PtpEvent>()

    fun connect() {
        if (!conn.claimInterface(intf, true)) throw PtpException("Impossibile usare la porta USB della fotocamera")
        var o: UsbEndpoint? = null
        var i: UsbEndpoint? = null
        for (k in 0 until intf.endpointCount) {
            val e = intf.getEndpoint(k)
            when {
                e.type == UsbConstants.USB_ENDPOINT_XFER_BULK && e.direction == UsbConstants.USB_DIR_OUT -> o = e
                e.type == UsbConstants.USB_ENDPOINT_XFER_BULK && e.direction == UsbConstants.USB_DIR_IN -> i = e
                e.type == UsbConstants.USB_ENDPOINT_XFER_INT && e.direction == UsbConstants.USB_DIR_IN -> epEvt = e
            }
        }
        epOut = o ?: throw PtpException("Interfaccia USB senza canale di uscita")
        epIn = i ?: throw PtpException("Interfaccia USB senza canale di ingresso")
        isOpen = true
        drainStale()
        startEventReader()

        transactionId = 0
        var r = transaction(PtpOp.OPEN_SESSION, intArrayOf(1))
        if (r.code == PtpResp.SESSION_ALREADY_OPEN) {
            // sessione rimasta aperta da un collegamento precedente: si chiude e si riapre
            transaction(PtpOp.CLOSE_SESSION)
            transactionId = 0
            r = transaction(PtpOp.OPEN_SESSION, intArrayOf(1))
        }
        if (r.code != PtpResp.OK && r.code != PtpResp.SESSION_ALREADY_OPEN) {
            throw PtpException("OpenSession via USB fallita (${hex(r.code)})")
        }
        log("Collegata via USB: $cameraName")
    }

    /** Scarta eventuali dati rimasti in coda da una sessione interrotta. */
    private fun drainStale() {
        val tmp = ByteArray(CHUNK)
        repeat(4) { if (conn.bulkTransfer(epIn, tmp, tmp.size, 50) <= 0) return }
    }

    private fun startEventReader() {
        val ep = epEvt ?: return
        thread(isDaemon = true, name = "iride-usb-events") {
            val buf = ByteArray(maxOf(64, ep.maxPacketSize))
            while (isOpen) {
                val n = try { conn.bulkTransfer(ep, buf, buf.size, 500) } catch (e: Exception) { -1 }
                if (n >= HEADER && le16(buf, 4) == EVENT) {
                    val count = (minOf(n, le32(buf, 0)) - HEADER).coerceAtLeast(0) / 4
                    events.add(PtpEvent(le16(buf, 6), IntArray(count) { le32(buf, HEADER + it * 4) }))
                    while (events.size > 256) events.poll()
                }
            }
        }
    }

    override fun transaction(
        op: Int,
        params: IntArray,
        dataOut: ByteArray?,
        sink: OutputStream?,
        onProgress: ((Long, Long) -> Unit)?,
    ): PtpResponse {
        lock.lock()
        try {
            if (!isOpen) throw PtpException("Non connesso")
            return doTransaction(op, params, dataOut, sink, onProgress)
        } finally {
            lock.unlock()
        }
    }

    private fun doTransaction(
        op: Int,
        params: IntArray,
        dataOut: ByteArray?,
        sink: OutputStream?,
        onProgress: ((Long, Long) -> Unit)?,
    ): PtpResponse {
        val tid = transactionId++
        val cmd = Buf().u32(HEADER + params.size * 4).u16(COMMAND).u16(op).u32(tid)
        for (p in params) cmd.u32(p)
        send(cmd.bytes())
        if (dataOut != null) {
            send(Buf().u32(HEADER + dataOut.size).u16(DATA).u16(op).u32(tid).raw(dataOut).bytes())
        }

        val collected = ByteArrayOutputStream()
        while (true) {
            val h = read(HEADER)
            val len = le32(h, 0).toLong() and 0xFFFFFFFFL
            val type = le16(h, 4)
            val code = le16(h, 6)
            if (len < HEADER) throw PtpException("Contenitore USB non valido (lunghezza $len)")
            val payload = len - HEADER
            when (type) {
                DATA -> {
                    var received = 0L
                    val out: OutputStream = sink ?: collected
                    while (received < payload) {
                        val n = take(minOf(payload - received, CHUNK.toLong()).toInt(), out)
                        received += n
                        onProgress?.invoke(received, payload)
                    }
                }
                RESPONSE -> {
                    val p = read(payload.toInt())
                    val rp = IntArray(p.size / 4) { le32(p, it * 4) }
                    return PtpResponse(code, rp, collected.toByteArray())
                }
                else -> {
                    read(payload.toInt())
                    log("Contenitore USB ignorato (tipo $type)")
                }
            }
        }
    }

    /** Invia un contenitore; se la lunghezza è multipla del pacchetto serve un pacchetto vuoto finale. */
    private fun send(b: ByteArray) {
        var off = 0
        while (off < b.size) {
            val n = conn.bulkTransfer(epOut, b, off, minOf(CHUNK, b.size - off), TIMEOUT)
            if (n <= 0) throw IOException("Cavo USB scollegato o fotocamera non risponde")
            off += n
        }
        if (b.size % epOut.maxPacketSize == 0) conn.bulkTransfer(epOut, ByteArray(0), 0, TIMEOUT)
    }

    /** Riempie il buffer di ingresso con il prossimo trasferimento (i pacchetti vuoti si saltano). */
    private fun fill() {
        var empty = 0
        while (true) {
            val n = conn.bulkTransfer(epIn, rx, rx.size, 15_000)
            if (n > 0) {
                rxPos = 0
                rxLen = n
                return
            }
            if (n < 0 || ++empty > 3) throw IOException("Cavo USB scollegato o fotocamera non risponde")
        }
    }

    private fun read(n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            if (rxPos >= rxLen) fill()
            val k = minOf(n - off, rxLen - rxPos)
            System.arraycopy(rx, rxPos, out, off, k)
            rxPos += k
            off += k
        }
        return out
    }

    /** Copia fino a [max] byte già arrivati (o il prossimo trasferimento) in [out]. */
    private fun take(max: Int, out: OutputStream): Int {
        if (rxPos >= rxLen) fill()
        val k = minOf(max, rxLen - rxPos)
        out.write(rx, rxPos, k)
        rxPos += k
        return k
    }

    override fun close() {
        if (isOpen && lock.tryLock(1, TimeUnit.SECONDS)) {
            try {
                doTransaction(PtpOp.CLOSE_SESSION, IntArray(0), null, null, null)
            } catch (ignored: Exception) {
            } finally {
                lock.unlock()
            }
        }
        isOpen = false
        runCatching { conn.releaseInterface(intf) }
        runCatching { conn.close() }
    }
}
