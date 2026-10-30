package jp.co.primehub.pos

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray

/**
 * Prime POS for Android: the normal Prime POS screens (any store can sign in) plus
 * direct printing to network printers (ESC/POS, port 9100) and the cash drawer.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val HOST = "pos.primehub.co.jp"
        const val HOME = "https://$HOST/login.php"
    }

    private lateinit var web: WebView
    @Volatile private var pageHost: String = ""
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // a POS tablet stays on
        web = WebView(this)
        // Android 15+ draws apps edge-to-edge: keep the page clear of the status bar, nav bar, notch and keyboard
        val root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.green)) }
        root.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        WindowCompat.setDecorFitsSystemWindows(window, false)   // same layout on every Android version
        WindowCompat.getInsetsController(window, root).isAppearanceLightStatusBars = false   // white icons on green
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION") databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            userAgentString = "$userAgentString PrimePOSApp/${BuildConfigHelper.version(this@MainActivity)}"
        }
        web.addJavascriptInterface(Bridge(), "PrimePOSApp")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (u.host == HOST || u.scheme == "about" || u.scheme == "data") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}   // other sites: normal browser
                return true
            }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                pageHost = Uri.parse(url).host ?: ""
            }
            override fun onPageFinished(view: WebView, url: String) {
                val u = Uri.parse(url)
                if (u.host == HOST && !url.contains("setup.php")) getPreferences(MODE_PRIVATE).edit().putString("last", url).apply()
                CookieManager.getInstance().flush()
            }
            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (req.isForMainFrame) showOffline(req.url.toString())
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                return try { pickFile.launch(params.createIntent()); true } catch (_: Exception) { fileCallback = null; false }
            }
            // page alert / confirm / prompt: our own dialog, without the "The page at https://… says:" title
            override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity).setMessage(message).setCancelable(false)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }.show()
                return true
            }
            override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity).setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setNegativeButton("キャンセル Cancel") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }.show()
                return true
            }
            override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
                val input = EditText(this@MainActivity).apply { setText(defaultValue ?: ""); setSingleLine(); selectAll() }
                val box = FrameLayout(this@MainActivity).apply { val pad = (20 * resources.displayMetrics.density).toInt(); setPadding(pad, 0, pad, 0); addView(input) }
                AlertDialog.Builder(this@MainActivity).setMessage(message).setView(box)
                    .setPositiveButton("OK") { _, _ -> result.confirm(input.text.toString()) }
                    .setNegativeButton("キャンセル Cancel") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }.show()
                return true
            }
        }
        web.setDownloadListener { url, ua, cd, mime, _ ->   // e.g. menu CSV export
            try {
                val name = URLUtil.guessFileName(url, cd, mime)
                val req = DownloadManager.Request(Uri.parse(url))
                    .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                    .addRequestHeader("User-Agent", ua)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                toast("⬇ $name")
            } catch (e: Exception) { toast(e.message ?: "download failed") }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.canGoBack()) web.goBack() else finish() }
        })

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        PrintService.start(this)
        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl(getPreferences(MODE_PRIVATE).getString("last", null) ?: HOME)
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); web.saveState(outState) }
    override fun onPause() { super.onPause(); CookieManager.getInstance().flush(); shown?.cancel() }

    private fun showOffline(failed: String) {
        val html = """
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body style="font-family:sans-serif;background:#2a3d33;color:#fff;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;text-align:center">
            <div><div style="font-size:54px">📶</div><h2>インターネットに接続できません</h2><p style="opacity:.8">No internet connection</p>
            <button onclick="location.href='${failed.replace("'", "%27")}'" style="margin-top:18px;padding:14px 28px;border:0;border-radius:12px;background:#b89a5e;color:#1d2621;font-size:18px;font-weight:800">再読み込み Retry</button></div></body></html>
        """.trimIndent()
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", failed)
    }

    // one message at a time: a new one replaces the old one, the same error is not repeated for 60 s,
    // and nothing is left on screen when the app goes to the background
    private var shown: Toast? = null
    private var lastMsg = ""
    private var lastAt = 0L
    private fun toast(s: String, quiet: Boolean = false) = runOnUiThread {
        val now = System.currentTimeMillis()
        if (quiet && s == lastMsg && now - lastAt < 60000) return@runOnUiThread
        if (quiet && !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@runOnUiThread   // app in the background: say nothing
        lastMsg = s; lastAt = now
        shown?.cancel()
        shown = Toast.makeText(this, s, if (quiet) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).also { it.show() }
    }

    /** short, readable printer error (no Java exception text) */
    private fun printerError(e: Exception): String {
        val m = (e.message ?: "") + " " + (e.cause?.message ?: "")
        return when {
            m.contains("ENETUNREACH") || m.contains("Network is unreachable") -> "Wi-Fiにつながっていません · Not connected to the shop Wi-Fi"
            m.contains("EHOSTUNREACH") || m.contains("timed out", true) || m.contains("after ") -> "プリンターが見つかりません · Printer not found (power / Wi-Fi?)"
            m.contains("ECONNREFUSED") -> "プリンターが応答しません · Printer refused the connection"
            else -> "プリンターエラー · Printer error"
        }
    }

    /** window.PrimePOSApp in the page (only answers on Prime POS pages) */
    inner class Bridge {
        private fun ok() = pageHost == HOST

        @JavascriptInterface fun isApp(): Boolean = true
        @JavascriptInterface fun version(): String = BuildConfigHelper.version(this@MainActivity)
        @JavascriptInterface fun printingStore(): String = if (ok() && Prefs.configured(this@MainActivity)) Prefs.store(this@MainActivity) else ""

        @JavascriptInterface fun connectPrinting(url: String, key: String, store: String) {
            if (!ok() || !url.startsWith("https://$HOST/") || key.isBlank()) return
            Prefs.save(this@MainActivity, url, key, store)
            runOnUiThread { PrintService.stop(this@MainActivity); PrintService.start(this@MainActivity) }
            toast("✓ このタブレットで印刷します · $store")
        }

        @JavascriptInterface fun disconnectPrinting() {
            if (!ok()) return
            Prefs.clear(this@MainActivity)
            runOnUiThread { PrintService.stop(this@MainActivity) }
            toast("印刷を停止しました · Printing stopped")
        }

        /** printers: JSON [{name, ip, port, width}] */
        @JavascriptInterface fun testPrint(printers: String) {
            if (!ok()) return
            Thread {
                val arr = try { JSONArray(printers) } catch (_: Exception) { JSONArray() }
                if (arr.length() == 0) toast("プリンターがありません · No printers")
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    val name = p.optString("name", "Printer")
                    try {
                        EscPos.send(p.getString("ip"), p.optInt("port", 9100), EscPos.render(EscPos.testTicket(name, p.optInt("width", 48)), Prefs.kanji(this@MainActivity)))
                        toast("✓ $name")
                    } catch (e: Exception) { toast("⚠ $name (${p.optString("ip")}): ${printerError(e)}") }
                }
            }.start()
        }

        /** offline mode (no internet): print a kitchen ticket now, straight to the printer on the shop Wi-Fi.
         *  Runs on the WebView bridge thread, so waiting for the printer is fine. Returns "" when printed, otherwise the error. */
        @JavascriptInterface fun printText(ip: String, port: Int, text: String, copies: Int): String {
            if (!ok()) return "not allowed"
            return try {
                EscPos.send(ip, if (port > 0) port else 9100, EscPos.render(text, Prefs.kanji(this@MainActivity)), copies, 4000); ""
            } catch (e: Exception) { printerError(e) }
        }

        /** vibrate for a new QR order or a call. pattern: "on,off,on,..." in ms */
        @Suppress("DEPRECATION")
        @JavascriptInterface fun vibrate(pattern: String) {
            if (!ok()) return
            val p = pattern.split(",").mapNotNull { it.trim().toLongOrNull()?.coerceIn(0, 2000) }.take(10)
            if (p.isEmpty()) return
            val timings = longArrayOf(0) + p.toLongArray()
            try {
                val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                                  else getSystemService(VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(timings, -1))
                else v.vibrate(timings, -1)
            } catch (_: Exception) {}
        }

        /** open the cash drawer on a printer right now */
        @JavascriptInterface fun openDrawer(ip: String, port: Int) {
            if (!ok()) return
            Thread { try { EscPos.send(ip, if (port > 0) port else 9100, EscPos.drawerKick(), 1, 3000) } catch (e: Exception) { toast("⚠ ドロア Drawer: " + printerError(e), quiet = true) } }.start()
        }
    }
}
