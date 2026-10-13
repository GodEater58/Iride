package it.goletto.iride.camera

import it.goletto.iride.http.OlympusDriver
import it.goletto.iride.http.PanasonicDriver
import it.goletto.iride.http.SonyHttpDriver
import it.goletto.iride.net.CameraNetwork
import it.goletto.iride.net.NetContext
import it.goletto.iride.ptp.CanonDriver
import it.goletto.iride.ptp.DeviceInfo
import it.goletto.iride.ptp.FujiDriver
import it.goletto.iride.ptp.GenericPtpDriver
import it.goletto.iride.ptp.NikonDriver
import it.goletto.iride.ptp.PtpCamera
import it.goletto.iride.ptp.PtpIp
import it.goletto.iride.ptp.PtpIpClient
import it.goletto.iride.ptp.SonyPtpDriver
import java.io.IOException
import java.util.Locale
import javax.net.SocketFactory

/** Crea il driver giusto per la fotocamera collegata. */
object DriverFactory {

    private const val HTTP = 80

    /** Indirizzo dell'ultima fotocamera collegata, per mostrarlo nell'interfaccia. */
    @Volatile
    var lastHost: String = ""
        private set

    /** Riconosce la marca dalle informazioni PTP (produttore o estensione del venditore). */
    fun detect(di: DeviceInfo): Brand {
        val m = di.manufacturer.lowercase(Locale.ROOT)
        return when {
            "canon" in m || di.vendorExtension == 0x0B -> Brand.CANON
            "nikon" in m || di.vendorExtension == 0x0A -> Brand.NIKON
            "sony" in m || di.vendorExtension == 0x11 -> Brand.SONY
            "fuji" in m -> Brand.FUJIFILM
            "panasonic" in m -> Brand.PANASONIC
            "olympus" in m || "om digital" in m -> Brand.OLYMPUS
            else -> Brand.OTHER_PTP
        }
    }

    /** Porte da cercare per ciascuna marca, in ordine di preferenza. */
    private fun portsFor(brand: Brand): List<Int> = when (brand) {
        Brand.CANON, Brand.NIKON, Brand.OTHER_PTP -> listOf(PtpIp.PORT)
        Brand.SONY -> listOf(PtpIp.PORT, 8080, 10000)
        Brand.FUJIFILM -> listOf(FujiDriver.CMD_PORT)
        Brand.OLYMPUS, Brand.PANASONIC -> listOf(HTTP)
        Brand.AUTO -> listOf(PtpIp.PORT, FujiDriver.CMD_PORT, 8080, 10000, HTTP)
    }

    /**
     * Trova la fotocamera e si collega. Se [hostInput] è vuoto cerca nella rete locale
     * le porte tipiche della marca scelta (o di tutte, con Automatico).
     */
    suspend fun connect(
        requested: Brand,
        hostInput: String,
        network: CameraNetwork,
        guid: ByteArray,
        friendlyName: String,
        log: (String) -> Unit,
        status: (String) -> Unit,
    ): CameraDriver {
        val ctx = network.context()
        val ports = portsFor(requested)
        val candidates: List<Pair<String, Int>> = if (hostInput.isNotBlank()) {
            val h = hostInput.trim()
            ports.filter { network.probe(ctx.socketFactory, h, it, 1500) }.map { h to it }
                .ifEmpty { throw IOException("Nessuna risposta da $h sulle porte ${ports.joinToString()}") }
        } else {
            status("Cerco la fotocamera sulla rete…")
            network.scan(ports) { log(it) }
        }
        if (candidates.isEmpty()) {
            throw IOException("Nessuna fotocamera trovata. Controlla che il Wi-Fi della fotocamera sia attivo e collegato, oppure inserisci l'indirizzo IP.")
        }
        log("Trovati: " + candidates.joinToString { "${it.first}:${it.second}" })

        var lastError: Exception? = null
        for ((host, port) in candidates) {
            try {
                val driver = when (port) {
                    PtpIp.PORT -> {
                        status("Collegamento a $host… conferma sulla fotocamera se richiesto")
                        connectPtp(requested, host, ctx.socketFactory, guid, friendlyName, log)
                    }
                    FujiDriver.CMD_PORT -> {
                        status("Collegamento alla Fujifilm $host… conferma sulla fotocamera se richiesto")
                        FujiDriver(ctx.socketFactory, host, log).also {
                            try {
                                it.connect(guid, friendlyName)
                            } catch (e: Exception) {
                                runCatching { it.close() }
                                throw e
                            }
                        }
                    }
                    8080, 10000 -> {
                        val base = SonyHttpDriver.probe(ctx, host) ?: continue
                        status("Collegamento alla Sony $host…")
                        SonyHttpDriver(ctx, base, log).also { it.initialize() }
                    }
                    HTTP -> connectHttp(requested, host, ctx, guid, friendlyName, log, status) ?: continue
                    else -> continue
                }
                log("Driver: ${driver.brand.label}")
                lastHost = host
                return driver
            } catch (e: Exception) {
                log("$host:$port: ${e.message}")
                lastError = e
            }
        }
        throw lastError ?: IOException("Nessuna fotocamera compatibile trovata")
    }

    private fun connectHttp(
        requested: Brand,
        host: String,
        ctx: NetContext,
        guid: ByteArray,
        friendlyName: String,
        log: (String) -> Unit,
        status: (String) -> Unit,
    ): CameraDriver? {
        if ((requested == Brand.OLYMPUS || requested == Brand.AUTO) && OlympusDriver.probe(ctx, host)) {
            status("Collegamento alla Olympus $host…")
            return OlympusDriver(ctx, host, log).also { it.initialize() }
        }
        if ((requested == Brand.PANASONIC || requested == Brand.AUTO) && PanasonicDriver.probe(ctx, host)) {
            status("Collegamento alla Lumix $host… conferma sulla fotocamera se richiesto")
            val id = guid.joinToString("") { "%02x".format(it) }.take(16)
            return PanasonicDriver(ctx, host, id, friendlyName, log).also { it.initialize() }
        }
        return null
    }

    fun connectPtp(
        requested: Brand,
        host: String,
        socketFactory: SocketFactory,
        guid: ByteArray,
        friendlyName: String,
        log: (String) -> Unit,
    ): CameraDriver {
        val client = PtpIpClient(socketFactory, log)
        try {
            client.connect(host, guid, friendlyName)
            val di = PtpCamera.readDeviceInfo(client)
            val detected = detect(di)
            val brand = if (requested == Brand.AUTO) detected else requested
            if (requested != Brand.AUTO && detected != Brand.OTHER_PTP && detected != requested) {
                log("Attenzione: la fotocamera sembra una ${detected.label}, uso comunque il driver ${requested.label}")
            }
            val driver: PtpCamera = when (brand) {
                Brand.CANON -> CanonDriver(client, log)
                Brand.NIKON -> NikonDriver(client, log)
                Brand.SONY -> SonyPtpDriver(client, log)
                else -> GenericPtpDriver(client, log)
            }
            driver.initialize()
            return driver
        } catch (e: Exception) {
            runCatching { client.close() }
            throw e
        }
    }
}
