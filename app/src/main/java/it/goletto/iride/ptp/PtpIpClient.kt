package it.goletto.iride.ptp

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory
import kotlin.concurrent.thread

class PtpException(message: String) : IOException(message)

class PtpResponse(val code: Int, val params: IntArray, val data: ByteArray) {
    val ok: Boolean get() = code == PtpResp.OK
}

/** Evento PTP standard ricevuto sul canale eventi (es. ObjectAdded 0x4002). */
class PtpEvent(val code: Int, val params: IntArray)

/**
 * Client PTP/IP: un canale comandi/dati e un canale eventi, entrambi TCP sulla porta 15740.
 * Le socket vengono create con la SocketFactory passata, così il traffico può essere
 * instradato sulla rete Wi-Fi della fotocamera mentre il resto del telefono usa i dati mobili.
 */
class PtpIpClient(
    private val socketFactory: SocketFactory,
    private val log: (String) -> Unit,
) : PtpTransport {

    private var cmdSocket: Socket? = null
    private var evtSocket: Socket? = null
    private var cmdIn: InputStream? = null
    private var cmdOut: OutputStream? = null
    private var transactionId = 0
    // equo: le richieste vengono servite in ordine di arrivo, così il live view non resta
    // in coda dietro a elenco file e miniature
    private val lock = java.util.concurrent.locks.ReentrantLock(true)

    @Volatile
    override var isOpen = false
        private set

    override var cameraName: String = ""
        private set

    override val wired = false

    override val events = java.util.concurrent.ConcurrentLinkedQueue<PtpEvent>()

    /**
     * Si collega alla fotocamera. Se [preconnected] è valorizzato usa quella connessione
     * (già aperta dalla ricerca) invece di aprirne una seconda: le Canon ne accettano una sola.
     *
     * Con [nikon] segue la sequenza che usano i programmi funzionanti col Wi-Fi delle Nikon
     * (airmtp, NikonLink): una breve pausa dopo ogni apertura e una prova sul canale eventi.
     */
    fun connect(host: String, guid: ByteArray, friendlyName: String, preconnected: Socket? = null, nikon: Boolean = false) {
        val s = preconnected ?: socketFactory.createSocket().also {
            it.tcpNoDelay = true
            it.receiveBufferSize = 1 shl 20
            it.connect(InetSocketAddress(host, PtpIp.PORT), 8000)
        }
        // Nikon: se il primo pacchetto arriva subito dopo l'apertura la fotocamera chiude il collegamento
        if (nikon) settle()
        // La fotocamera può chiedere conferma sul display: attesa lunga
        s.soTimeout = 90_000
        cmdSocket = s
        val inp = BufferedInputStream(s.getInputStream(), 256 * 1024)
        val out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
        cmdIn = inp
        cmdOut = out

        val init = Buf().raw(guid).utf16z(friendlyName).u32(0x00010000).bytes()
        writePacket(out, PtpIp.INIT_COMMAND_REQUEST, init)
        log("Richiesta inviata a $host: se la fotocamera chiede conferma, premi OK")

        val (type, payload) = readPacket(inp)
        when (type) {
            PtpIp.INIT_COMMAND_ACK -> Unit
            PtpIp.INIT_FAIL -> {
                val reason = if (payload.size >= 4) le32(payload, 0) else 0
                throw PtpException("La fotocamera ha rifiutato la connessione (motivo ${hex(reason)})")
            }
            else -> throw PtpException("Risposta inattesa all'inizializzazione (tipo $type)")
        }
        val connNumber = le32(payload, 0)
        cameraName = if (payload.size > 20) readUtf16z(payload, 20) else ""
        log("Fotocamera: $cameraName (connessione #$connNumber)")
        s.soTimeout = 60_000

        // Canale eventi
        val e = socketFactory.createSocket()
        e.tcpNoDelay = true
        e.connect(InetSocketAddress(host, PtpIp.PORT), 5000)
        if (nikon) settle()
        e.soTimeout = 15_000
        evtSocket = e
        val eIn = BufferedInputStream(e.getInputStream())
        val eOut = BufferedOutputStream(e.getOutputStream())
        writePacket(eOut, PtpIp.INIT_EVENT_REQUEST, Buf().u32(connNumber).bytes())
        val (eType, _) = readPacket(eIn)
        if (eType != PtpIp.INIT_EVENT_ACK) throw PtpException("Canale eventi rifiutato (tipo $eType)")
        if (nikon) probe(e, eIn, eOut)
        e.soTimeout = 0
        isOpen = true
        startEventReader(eIn, eOut)

        transactionId = 0
        val r = transaction(PtpOp.OPEN_SESSION, intArrayOf(1))
        if (r.code != PtpResp.OK && r.code != PtpResp.SESSION_ALREADY_OPEN) {
            throw PtpException("OpenSession fallita (${hex(r.code)})")
        }
    }

    /** Pausa tra l'apertura della connessione e il primo pacchetto (Nikon). */
    private fun settle() = Thread.sleep(NIKON_SETTLE_MS)

    /**
     * Nikon: prova (Probe Request) sul canale eventi prima di aprire la sessione. Senza, la
     * sessione può bloccarsi; se la fotocamera non risponde basta una pausa di un secondo
     * (come fa airmtp).
     */
    private fun probe(socket: Socket, inp: InputStream, out: OutputStream) {
        socket.soTimeout = 3000
        val end = System.currentTimeMillis() + 3000
        try {
            writePacket(out, PtpIp.PING, ByteArray(0))
            while (System.currentTimeMillis() < end) {
                val (type, p) = readPacket(inp)
                when (type) {
                    PtpIp.PONG -> {
                        log("Canale eventi pronto")
                        return
                    }
                    PtpIp.PING -> writePacket(out, PtpIp.PONG, ByteArray(0))
                    PtpIp.EVENT -> queueEvent(p)
                    else -> Unit
                }
            }
            log("Nessuna risposta alla prova del canale eventi: attendo un secondo")
            Thread.sleep(1000)
        } catch (ex: java.net.SocketTimeoutException) {
            log("Nessuna risposta alla prova del canale eventi: attendo un secondo")
            Thread.sleep(1000)
        }
    }

    /** payload di un evento: codice (u16), transazione (u32), parametri (u32...) */
    private fun queueEvent(p: ByteArray) {
        if (p.size < 2) return
        val count = maxOf(0, (p.size - 6) / 4)
        val params = IntArray(count) { le32(p, 6 + it * 4) }
        events.add(PtpEvent(le16(p, 0), params))
        while (events.size > 256) events.poll()
    }

    private fun startEventReader(inp: InputStream, out: OutputStream) {
        thread(isDaemon = true, name = "iride-events") {
            try {
                while (isOpen) {
                    val (type, p) = readPacket(inp)
                    when (type) {
                        PtpIp.PING -> synchronized(out) { writePacket(out, PtpIp.PONG, ByteArray(0)) }
                        PtpIp.EVENT -> queueEvent(p)
                        else -> Unit
                    }
                }
            } catch (ex: Exception) {
                if (isOpen) log("Canale eventi chiuso: ${ex.message}")
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
            return doTransaction(op, params, dataOut, sink, onProgress)
        } catch (e: IOException) {
            // un errore a metà transazione lascia il flusso disallineato: le risposte successive
            // sarebbero sbagliate. Si chiude subito, così tutto fallisce in modo chiaro.
            if (isOpen) {
                isOpen = false
                log("Collegamento interrotto: ${e.message}")
                runCatching { cmdSocket?.close() }
                runCatching { evtSocket?.close() }
            }
            throw e
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
        val out = cmdOut ?: throw PtpException("Non connesso")
        val inp = cmdIn ?: throw PtpException("Non connesso")
        val tid = transactionId++

        val req = Buf().u32(if (dataOut != null) 2 else 1).u16(op).u32(tid)
        for (p in params) req.u32(p)
        writePacket(out, PtpIp.CMD_REQUEST, req.bytes())
        if (dataOut != null) {
            writePacket(out, PtpIp.START_DATA, Buf().u32(tid).u64(dataOut.size.toLong()).bytes())
            writePacket(out, PtpIp.END_DATA, Buf().u32(tid).raw(dataOut).bytes())
        }

        val collected = ByteArrayOutputStream()
        var total = 0L
        var received = 0L
        var response: PtpResponse? = null
        while (response == null) {
            val (type, p) = readPacket(inp)
            when (type) {
                PtpIp.START_DATA -> total = if (p.size >= 12) le64(p, 4) else 0L
                PtpIp.DATA, PtpIp.END_DATA -> {
                    val n = p.size - 4
                    if (n > 0) {
                        if (sink != null) sink.write(p, 4, n) else collected.write(p, 4, n)
                        received += n
                        onProgress?.invoke(received, total)
                    }
                }
                PtpIp.CMD_RESPONSE -> {
                    val code = le16(p, 0)
                    val count = maxOf(0, (p.size - 6) / 4)
                    val rp = IntArray(count) { le32(p, 6 + it * 4) }
                    response = PtpResponse(code, rp, collected.toByteArray())
                }
                PtpIp.PING -> writePacket(out, PtpIp.PONG, ByteArray(0))
                else -> log("Pacchetto ignorato (tipo $type)")
            }
        }
        return response!!
    }

    override fun close() {
        // chiude la sessione solo se il canale è libero: se un'operazione è bloccata
        // (es. download interrotto) si chiudono direttamente le socket, senza attendere
        if (isOpen && lock.tryLock(1, java.util.concurrent.TimeUnit.SECONDS)) {
            try {
                cmdSocket?.soTimeout = 3000
                doTransaction(PtpOp.CLOSE_SESSION, IntArray(0), null, null, null)
            } catch (ignored: Exception) {
            } finally {
                lock.unlock()
            }
        }
        isOpen = false
        try { cmdSocket?.close() } catch (ignored: Exception) {}
        try { evtSocket?.close() } catch (ignored: Exception) {}
        cmdSocket = null
        evtSocket = null
        cmdIn = null
        cmdOut = null
    }

    private fun readFully(inp: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(b, off, n - off)
            if (r < 0) throw IOException("Connessione chiusa dalla fotocamera")
            off += r
        }
        return b
    }

    private fun readPacket(inp: InputStream): Pair<Int, ByteArray> {
        val h = readFully(inp, 8)
        val len = le32(h, 0)
        val type = le32(h, 4)
        if (len < 8 || len > 64 * 1024 * 1024) throw PtpException("Pacchetto non valido (lunghezza $len)")
        return Pair(type, readFully(inp, len - 8))
    }

    private fun writePacket(out: OutputStream, type: Int, payload: ByteArray) {
        out.write(Buf().u32(payload.size + 8).u32(type).raw(payload).bytes())
        out.flush()
    }

    companion object {
        /** Pausa misurata da NikonLink: la fotocamera risponde in modo affidabile all'inizializzazione. */
        private const val NIKON_SETTLE_MS = 350L

        /**
         * Identificativo fisso che annuncia l'app Nikon: con questo la fotocamera tratta il
         * telefono come un dispositivo conosciuto (NikonLink; libgphoto2 e airmtp ne usano uno
         * con lo stesso inizio).
         */
        val NIKON_GUID: ByteArray = byteArrayOf(
            0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
            0x88.toByte(), 0x99.toByte(), 0xAA.toByte(), 0xBB.toByte(),
            0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte(),
        )
    }
}
