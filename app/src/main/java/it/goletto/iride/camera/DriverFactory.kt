package it.goletto.iride.camera

import it.goletto.iride.ptp.CanonDriver
import it.goletto.iride.ptp.DeviceInfo
import it.goletto.iride.ptp.GenericPtpDriver
import it.goletto.iride.ptp.NikonDriver
import it.goletto.iride.ptp.PtpCamera
import it.goletto.iride.ptp.PtpIpClient
import it.goletto.iride.ptp.SonyPtpDriver
import java.util.Locale
import javax.net.SocketFactory

/** Crea il driver giusto per la fotocamera collegata. */
object DriverFactory {

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
            log("Driver: ${driver.brand.label}")
            driver.initialize()
            return driver
        } catch (e: Exception) {
            runCatching { client.close() }
            throw e
        }
    }
}
