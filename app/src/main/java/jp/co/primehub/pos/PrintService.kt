package jp.co.primehub.pos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps printing while the tablet is on: every few seconds it asks Prime POS for new tickets
 * (api/print.php?action=pending), prints each one on its network printer, then reports ack / fail.
 * Runs as a foreground service (a small "Prime POS 印刷中" notification) so Android does not stop it.
 */
class PrintService : Service() {

    companion object {
        const val CHANNEL = "printing"
        const val NOTE_ID = 7
        @Volatile var running = false
        @Volatile var lastStatus = ""

        fun start(ctx: Context) {
            if (!Prefs.configured(ctx)) return
            val i = Intent(ctx, PrintService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, PrintService::class.java)) }
    }

    @Volatile private var alive = false
    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Printing / 印刷", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = note(getString(R.string.print_waiting))
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTE_ID, n)
        if (!alive) {
            alive = true; running = true
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "primepos:print").apply { setReferenceCounted(false); acquire() }
            @Suppress("DEPRECATION")
            wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "primepos:print").apply { setReferenceCounted(false); acquire() }
            worker = Thread { loop() }.apply { name = "print-loop"; isDaemon = true; start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false; running = false
        worker?.interrupt()
        try { wake?.release() } catch (_: Exception) {}
        try { wifi?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun loop() {
        var idle = 0
        while (alive) {
            val url = Prefs.url(this); val key = Prefs.key(this)
            if (url.isEmpty() || key.isEmpty()) { stopSelf(); return }
            try {
                val res = http("$url?key=${enc(key)}&action=pending")
                val jobs = res.optJSONArray("jobs")
                if (jobs != null && jobs.length() > 0) {
                    idle = 0
                    for (i in 0 until jobs.length()) printJob(url, key, jobs.getJSONObject(i))
                } else {
                    idle++
                    if (lastStatus.isEmpty() || lastStatus.startsWith("⚠ " + getString(R.string.print_offline_prefix))) status(getString(R.string.print_waiting))
                }
            } catch (e: InterruptedException) { return
            } catch (e: Exception) {
                status("⚠ " + getString(R.string.print_offline_prefix) + " " + (e.message ?: e.javaClass.simpleName))
            }
            try { Thread.sleep(if (idle > 100) 5000 else 2500) } catch (_: InterruptedException) { return }
        }
    }

    private fun printJob(url: String, key: String, job: JSONObject) {
        val id = job.getInt("id")
        val p = job.getJSONObject("printer")
        val name = p.optString("name", "Printer"); val ip = p.getString("ip"); val port = p.optInt("port", 9100)
        try {
            EscPos.send(ip, port, EscPos.render(job.optString("text", ""), Prefs.kanji(this)), p.optInt("copies", 1))
            http("$url?key=${enc(key)}&action=ack&id=$id")
            val t = SimpleDateFormat("HH:mm", Locale.JAPAN).format(Date())
            val table = job.optString("table", "")
            status("✓ $t  $name" + (if (table.isNotEmpty() && table != "null") "  ·  T$table" else ""))
        } catch (e: Exception) {
            try { http("$url?key=${enc(key)}&action=fail&id=$id") } catch (_: Exception) {}
            status("⚠ $name ($ip) " + getString(R.string.print_failed) + ": " + (e.message ?: ""))
        }
    }

    private fun http(u: String): JSONObject {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "PrimePOSApp/" + BuildConfigHelper.version(this))
        try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 403) throw IllegalStateException(getString(R.string.print_bad_key))
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return JSONObject(body)
        } finally { c.disconnect() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun status(s: String) {
        lastStatus = s
        getSystemService(NotificationManager::class.java).notify(NOTE_ID, note(s))
    }

    private fun note(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_print)
            .setContentTitle(getString(R.string.print_title) + " · " + Prefs.store(this))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }
}

object BuildConfigHelper {
    fun version(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1"
    } catch (_: Exception) { "1" }
}
