package it.goletto.iride.net

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Collegamento col cavo USB (OTG): trova la fotocamera tra i dispositivi collegati
 * e chiede all'utente il permesso di usarla.
 */
class UsbLink(private val context: Context) {

    class Found(val device: UsbDevice, val intf: UsbInterface, val name: String)

    private val manager: UsbManager? = context.getSystemService(UsbManager::class.java)

    /** Il telefono può fare da host USB (quasi tutti, con un adattatore OTG). */
    val supported: Boolean
        get() = manager != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    /** Prima fotocamera collegata (interfaccia «Still Image», cioè PTP), oppure null. */
    fun find(): Found? {
        val m = manager ?: return null
        for (d in m.deviceList.values) {
            for (k in 0 until d.interfaceCount) {
                val i = d.getInterface(k)
                if (i.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE) {
                    val name = runCatching {
                        listOfNotNull(d.manufacturerName, d.productName).joinToString(" ").trim()
                    }.getOrDefault("").ifEmpty { "Fotocamera USB" }
                    return Found(d, i, name)
                }
            }
        }
        return null
    }

    /** Chiede il permesso di usare la fotocamera (finestra di sistema); true se concesso. */
    suspend fun requestPermission(device: UsbDevice): Boolean {
        val m = manager ?: return false
        if (m.hasPermission(device)) return true
        val action = context.packageName + ".USB_PERMISSION"
        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    runCatching { context.unregisterReceiver(this) }
                    if (cont.isActive) cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }
            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            // il sistema aggiunge l'esito all'intent: serve un PendingIntent modificabile ed esplicito
            val pi = PendingIntent.getBroadcast(
                context, 0, Intent(action).setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            m.requestPermission(device, pi)
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
        }
    }

    fun open(found: Found): UsbDeviceConnection? = manager?.openDevice(found.device)
}
