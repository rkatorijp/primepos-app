package jp.co.primehub.pos

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Bluetooth receipt printers (Bluetooth Classic, Serial Port Profile), the kind most 58mm / 80mm
 * ESC/POS printers use. The printer is paired once in Android Settings → Bluetooth; in Prime POS its
 * address is saved as "bt:AA:BB:CC:DD:EE:FF" in place of an IP address, so every print path
 * (orders, receipts, test print, offline tickets, cash drawer) works the same as for a network printer.
 */
object BtPrinter {
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val MAC = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")
    private val locks = ConcurrentHashMap<String, Any>()   // one connection at a time per printer
    private val pool = Executors.newCachedThreadPool()

    fun isBt(address: String) = address.startsWith("bt:", ignoreCase = true)
    fun mac(address: String) = address.substring(3).trim().uppercase()

    fun hasPermission(c: Context) = Build.VERSION.SDK_INT < 31 ||
        c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun adapter(c: Context): BluetoothAdapter? =
        (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** paired devices for the printer settings: {"devices":[{name, mac, printer}]} or {"error": "permission" | "off" | "none"} */
    @SuppressLint("MissingPermission")
    fun paired(c: Context): String {
        val a = adapter(c) ?: return JSONObject().put("error", "none").toString()
        if (!hasPermission(c)) return JSONObject().put("error", "permission").toString()
        if (!a.isEnabled) return JSONObject().put("error", "off").toString()
        val list = JSONArray()
        a.bondedDevices.orEmpty()
            .sortedByDescending { isPrinterLike(it) }
            .forEach { d -> list.put(JSONObject().put("name", d.name ?: d.address).put("mac", d.address).put("printer", isPrinterLike(d))) }
        return JSONObject().put("devices", list).toString()
    }

    @SuppressLint("MissingPermission")
    private fun isPrinterLike(d: BluetoothDevice): Boolean {
        val cls = d.bluetoothClass?.majorDeviceClass
        val n = (d.name ?: "").lowercase()
        return cls == BluetoothClass.Device.Major.IMAGING || listOf("print", "pos", "tm-", "rp-", "mpt", "sm-", "star", "epson", "sunmi", "xprinter", "pt-").any { n.contains(it) }
    }

    /** send bytes to a paired Bluetooth printer. Throws IOException with a short reason on failure. */
    @SuppressLint("MissingPermission")
    fun send(c: Context, address: String, data: ByteArray, copies: Int = 1, timeoutMs: Int = 8000) {
        val mac = mac(address)
        if (!MAC.matches(mac)) throw IOException("BT_ADDRESS")
        if (!hasPermission(c)) throw IOException("BT_PERMISSION")
        val a = adapter(c) ?: throw IOException("BT_NONE")
        if (!a.isEnabled) throw IOException("BT_OFF")
        val dev = a.getRemoteDevice(mac)
        synchronized(locks.getOrPut(mac) { Any() }) {
            try { a.cancelDiscovery() } catch (_: Exception) {}
            val sock = connect(dev, timeoutMs)
            try {
                val o = sock.outputStream
                repeat(copies.coerceIn(1, 5)) {
                    // small chunks: cheap printers have a small buffer and drop data that arrives too fast
                    var i = 0
                    while (i < data.size) { val n = minOf(512, data.size - i); o.write(data, i, n); o.flush(); i += n; if (i < data.size) Thread.sleep(15) }
                }
                o.flush()
                Thread.sleep((300 + data.size * copies / 20).coerceAtMost(2500).toLong())   // let the printer take everything before the link closes
            } finally { try { sock.close() } catch (_: Exception) {} }
        }
    }

    /** connect with a time limit; some printers only answer the "insecure" or channel-1 socket */
    @SuppressLint("MissingPermission", "DiscouragedPrivateApi")
    private fun connect(dev: BluetoothDevice, timeoutMs: Int): BluetoothSocket {
        val tries = listOf<() -> BluetoothSocket>(
            { dev.createRfcommSocketToServiceRecord(SPP) },
            { dev.createInsecureRfcommSocketToServiceRecord(SPP) },
            { dev.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(dev, 1) as BluetoothSocket },
        )
        var last: Exception? = null
        for (make in tries) {
            val s = try { make() } catch (e: Exception) { last = e; continue }
            val f = pool.submit(Callable { s.connect() })
            try { f.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS); return s }
            catch (e: TimeoutException) { try { s.close() } catch (_: Exception) {}; f.cancel(true); throw IOException("BT_TIMEOUT") }
            catch (e: Exception) { try { s.close() } catch (_: Exception) {}; last = e }
        }
        throw IOException("BT_CONNECT", last)
    }

    /** short, readable message for a Bluetooth error code */
    fun reason(e: Exception): String? = when ((e.message ?: "")) {
        "BT_PERMISSION" -> "Bluetoothの許可がありません · Bluetooth permission is off (Settings → Apps → Prime POS → Nearby devices)"
        "BT_OFF" -> "Bluetoothがオフです · Bluetooth is off on this tablet"
        "BT_NONE" -> "この端末はBluetoothに対応していません · This device has no Bluetooth"
        "BT_ADDRESS" -> "Bluetoothプリンターの設定が正しくありません · Wrong Bluetooth printer setting"
        "BT_TIMEOUT", "BT_CONNECT" -> "Bluetoothプリンターにつながりません · Can't reach the Bluetooth printer (power on? paired with this tablet? close by?)"
        else -> null
    }
}
