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
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
        setContentView(web)

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
    override fun onPause() { super.onPause(); CookieManager.getInstance().flush() }

    private fun showOffline(failed: String) {
        val html = """
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body style="font-family:sans-serif;background:#2a3d33;color:#fff;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;text-align:center">
            <div><div style="font-size:54px">📶</div><h2>インターネットに接続できません</h2><p style="opacity:.8">No internet connection</p>
            <button onclick="location.href='${failed.replace("'", "%27")}'" style="margin-top:18px;padding:14px 28px;border:0;border-radius:12px;background:#b89a5e;color:#1d2621;font-size:18px;font-weight:800">再読み込み Retry</button></div></body></html>
        """.trimIndent()
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", failed)
    }

    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }

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
                    } catch (e: Exception) { toast("⚠ $name (${p.optString("ip")}): ${e.message}") }
                }
            }.start()
        }

        /** offline mode (no internet): print a kitchen ticket now, straight to the printer on the shop Wi-Fi.
         *  Runs on the WebView bridge thread, so waiting for the printer is fine. Returns "" when printed, otherwise the error. */
        @JavascriptInterface fun printText(ip: String, port: Int, text: String, copies: Int): String {
            if (!ok()) return "not allowed"
            return try {
                EscPos.send(ip, if (port > 0) port else 9100, EscPos.render(text, Prefs.kanji(this@MainActivity)), copies, 4000); ""
            } catch (e: Exception) { e.message ?: "print error" }
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
            Thread { try { EscPos.send(ip, if (port > 0) port else 9100, EscPos.drawerKick()) } catch (e: Exception) { toast("⚠ $ip: ${e.message}") } }.start()
        }
    }
}
