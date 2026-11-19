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
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import java.io.ByteArrayInputStream

/**
 * Prime POS for Android: the normal Prime POS screens (any store can sign in) plus
 * direct printing to network printers (Wi-Fi / LAN, ESC/POS port 9100) and paired Bluetooth printers, and the cash drawer.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val HOST = "pos.primehub.co.jp"
        const val HOME = "https://$HOST/login.php"
    }

    private lateinit var web: WebView
    @Volatile private var pageHost: String = ""
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var camRequest: PermissionRequest? = null   // the page asked for the camera (barcode / QR scanner) while Android asks the user
    private var printView: WebView? = null   // kept until Android's print screen has the page (barcode labels)

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
                if (u.scheme == "primepos" && u.host == "wifi") { openNetworkSettings(); return true }   // offline page: "Wi-Fi settings"
                if (u.host == HOST || u.scheme == "about" || u.scheme == "data") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}   // other sites: normal browser
                return true
            }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                pageHost = Uri.parse(url).host ?: ""
            }
            override fun onPageFinished(view: WebView, url: String) {
                val u = Uri.parse(url)
                // the app reopens on the last staff screen only: a guest page (QR menu, delivery, booking) opened to test a QR code
                // must never become the start page, or the tablet would be stuck on the guest menu
                if (u.host == HOST && isStaffPage(u)) getPreferences(MODE_PRIVATE).edit().putString("last", url).apply()
                // the POS screen works offline (kept by its service worker): remember it, so the offline page can offer it
                if (u.host == HOST && (u.path ?: "").matches(Regex("^/s/[^/]+/pos/(index\\.php)?$"))) getPreferences(MODE_PRIVATE).edit().putString("lastPos", "${u.scheme}://${u.host}${u.path}").apply()
                CookieManager.getInstance().flush()
                // some WebView versions (and pages kept by a service worker) show their own "Webpage not available · net::ERR_FAILED"
                // without calling onReceivedError: if that page is on screen, replace it with ours
                if (url.startsWith("http")) view.evaluateJavascript("(function(){var t=document.body?document.body.innerText:'';return /net::ERR_|Webpage not available|ウェブページを表示できません/.test(t)&&!document.querySelector('script[src]');})()") {
                    if (it == "true") showOffline(url)
                }
            }
            // window.PrimePOSApp is given to every frame of the page, and Android cannot tell which frame calls it.
            // So inside the app no other website's page may load in a frame (scripts, images and styles from other sites still load).
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val u = req.url
                if (req.isForMainFrame || u.host == HOST || (u.scheme != "http" && u.scheme != "https")) return null
                val accept = req.requestHeaders.entries.firstOrNull { it.key.equals("Accept", true) }?.value ?: ""
                val dest = req.requestHeaders.entries.firstOrNull { it.key.equals("Sec-Fetch-Dest", true) }?.value ?: ""
                if (dest.equals("iframe", true) || dest.equals("frame", true) || accept.startsWith("text/html", true))
                    return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                return null
            }
            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                // posted, not called here: some WebView versions draw their own "Webpage not available" page over a load started inside this callback
                if (req.isForMainFrame) { view.stopLoading(); val failed = req.url.toString(); view.post { showOffline(failed) } }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            // camera for the barcode / QR scanner on the POS — only for Prime POS pages
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val ours = request.origin?.host == HOST
                    val cam = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                    if (!ours || !cam) { request.deny(); return@runOnUiThread }
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                    else { camRequest = request; requestPermissions(arrayOf(Manifest.permission.CAMERA), 2) }
                }
            }
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
        else web.loadUrl(getPreferences(MODE_PRIVATE).getString("last", null)?.takeIf { isStaffPage(Uri.parse(it)) } ?: HOME)   // an older app may have saved a guest page
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) {
            val r = camRequest; camRequest = null
            if (r != null) { if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) r.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) else r.deny() }
        }
        if (requestCode == 3) {   // Bluetooth (Nearby devices) for Bluetooth printers: tell the printer settings page
            val g = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            if (pageHost == HOST) web.evaluateJavascript("window.ppBtReady && window.ppBtReady($g)", null)
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); web.saveState(outState) }
    override fun onPause() { super.onPause(); CookieManager.getInstance().flush(); shown?.cancel() }

    /** POS, kitchen, Admin, My stores and sign-in: the screens a shop tablet should come back to */
    private fun isStaffPage(u: Uri): Boolean {
        val p = u.path ?: return false
        if (u.host != HOST || p.contains("setup.php")) return false
        return p.matches(Regex("^/s/[^/]+/(pos|kds|staff)(/.*)?$")) || p.startsWith("/app/") || p == "/login.php"
    }

    private fun openNetworkSettings() {
        val panel = if (Build.VERSION.SDK_INT >= 29) Intent(android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY) else Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
        try { startActivity(panel) } catch (_: Exception) { try { startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)) } catch (_: Exception) {} }
    }

    /** No internet: "Check internet connection" with Reconnect (tries again now) and Wi-Fi settings; it also retries by itself when the line is back. */
    private fun showOffline(failed: String) {
        val pos = getPreferences(MODE_PRIVATE).getString("lastPos", null)
        val q = { u: String -> u.replace("'", "%27").replace("\"", "%22").replace("<", "%3C") }
        val ic = { d: String, size: Int -> """<svg viewBox="0 0 24 24" width="$size" height="$size" aria-hidden="true"><path fill="currentColor" d="$d"/></svg>""" }
        val btn = "display:flex;align-items:center;justify-content:center;gap:10px;width:100%;margin-top:12px;padding:15px 20px;border:0;border-radius:14px;font-size:18px;font-weight:800"
        // the POS keeps working offline (tables, orders, payments are saved on the tablet) — offer it unless it is what just failed
        val posBtn = if (pos != null && !failed.startsWith(pos)) """<button onclick="location.href='${q(pos)}'" style="$btn;background:#fff;color:#1d2621">${ic("M19.5 3.5 18 2l-1.5 1.5L15 2l-1.5 1.5L12 2l-1.5 1.5L9 2 7.5 3.5 6 2 4.5 3.5 3 2v20l1.5-1.5L6 22l1.5-1.5L9 22l1.5-1.5L12 22l1.5-1.5L15 22l1.5-1.5L18 22l1.5-1.5L21 22V2zM19 19H5V5h14zM6 15h12v2H6m0-6h12v2H6m0-6h12v2H6z", 22)}<span>POSをオフラインで開く<br><small style="font-weight:600">Open the POS offline</small></span></button>""" else ""
        val html = """
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body style="font-family:sans-serif;background:#2a3d33;color:#fff;display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0;text-align:center;padding:0 20px;box-sizing:border-box">
            <div style="max-width:420px;width:100%"><div style="color:#b89a5e">${ic("M2.28 3 1 4.27l1.47 1.47c-.43.26-.86.55-1.27.86L3 9c.53-.4 1.08-.75 1.66-1.07l2.23 2.23c-.74.34-1.45.75-2.09 1.24l1.8 2.4c.78-.58 1.66-1.03 2.6-1.33L11.75 15c-1.25.07-2.41.5-3.35 1.2L12 21l2.46-3.27L17.74 21 19 19.72M12 3c-2.15 0-4.2.38-6.1 1.07l2.39 2.4C9.5 6.16 10.72 6 12 6c3.38 0 6.5 1.11 9 3l1.8-2.4C19.79 4.34 16.06 3 12 3m0 6c-.38 0-.75 0-1.12.05l3.19 3.2c1.22.28 2.36.82 3.33 1.55l1.8-2.4C17.2 9.89 14.7 9 12 9", 64)}</div>
            <h2 style="margin:10px 0 4px">インターネット接続を確認してください</h2><p style="opacity:.85;margin:0 0 12px;font-size:17px">Check your internet connection</p>
            <p style="opacity:.8;font-size:14.5px;line-height:1.6">Wi-Fiまたはモバイルデータを確認してください。接続が戻ると自動で開き直します。<br>Check Wi-Fi or mobile data. The page reopens by itself when the connection is back.</p>
            <button id="re" onclick="go()" style="$btn;background:#b89a5e;color:#1d2621">${ic("M17.65 6.35A7.96 7.96 0 0 0 12 4a8 8 0 0 0-8 8 8 8 0 0 0 8 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0 1 12 18a6 6 0 0 1-6-6 6 6 0 0 1 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4z", 22)}再接続 Reconnect</button>
            <button onclick="location.href='primepos://wifi'" style="$btn;background:transparent;color:#fff;border:1.5px solid rgba(255,255,255,.4)">${ic("M12 12c-2.03 0-3.9.67-5.4 1.8l-1.8-2.4C6.81 9.89 9.3 9 12 9s5.19.89 7.2 2.4l-.46.6c-1.08.05-2.11.33-3.01.81A8.9 8.9 0 0 0 12 12m9-3 1.8-2.4C19.79 4.34 16.05 3 12 3S4.21 4.34 1.2 6.6L3 9c2.5-1.88 5.62-3 9-3s6.5 1.12 9 3m-9 6c-1.35 0-2.6.45-3.6 1.2L12 21l.22-.29a6.9 6.9 0 0 1 .95-5.58c-.38-.08-.77-.13-1.17-.13m11.8 5.4c.1 0 .1.1 0 .2l-1 1.7c-.1.1-.2.1-.3.1l-1.2-.4c-.3.2-.5.3-.8.5l-.2 1.3c0 .1-.1.2-.2.2h-2c-.1 0-.2-.1-.3-.2l-.2-1.3c-.3-.1-.6-.3-.8-.5l-1.2.5c-.1 0-.2 0-.3-.1l-1-1.7c-.1-.1 0-.2.1-.3l1.1-.8v-1l-1.1-.8c-.1-.1-.1-.2-.1-.3l1-1.7c.1-.1.2-.1.3-.1l1.2.5c.3-.2.5-.3.8-.5l.2-1.3c0-.1.1-.2.3-.2h2c.1 0 .2.1.2.2l.2 1.3c.3.1.6.3.9.5l1.2-.5c.1 0 .3 0 .3.1l1 1.7c.1.1 0 .2-.1.3l-1.1.8v1zM20.5 19c0-.8-.7-1.5-1.5-1.5s-1.5.7-1.5 1.5.7 1.5 1.5 1.5 1.5-.7 1.5-1.5", 22)}Wi-Fi設定 Wi-Fi settings</button>
            $posBtn
            <p id="st" style="opacity:.7;font-size:13px;margin-top:12px"></p></div>
            <script>function go(){document.getElementById('st').textContent='接続しています… Connecting…';location.href='${q(failed)}';}
            addEventListener('online',function(){setTimeout(go,1500);});setInterval(function(){if(navigator.onLine)go();},15000);</script></body></html>
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
        BtPrinter.reason(e)?.let { return it }
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
        /** a page may only reach a printer: a paired Bluetooth printer, or a private-network address (10.x, 172.16–31.x, 192.168.x) on a raw
         *  printing port (9100–9199). Never the internet, the router's admin page or other services on the shop Wi-Fi. */
        private fun printerOk(ip: String, port: Int): Boolean {
            if (BtPrinter.isBt(ip)) return true
            val p = if (port > 0) port else 9100
            if (p !in 9100..9199) return false
            val m = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").find(ip.trim()) ?: return false
            val b = m.groupValues.drop(1).map { it.toInt() }
            if (b.any { it > 255 }) return false
            return b[0] == 10 || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168)
        }

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
                    if (!printerOk(p.optString("ip", ""), p.optInt("port", 9100))) { toast("⚠ $name: プリンターのアドレスではありません · Not a printer address on the shop network"); continue }
                    try {
                        EscPos.send(this@MainActivity, p.getString("ip"), p.optInt("port", 9100), EscPos.render(EscPos.testTicket(name, p.optInt("width", 48)), Prefs.kanji(this@MainActivity)))
                        toast("✓ $name")
                    } catch (e: Exception) { toast("⚠ $name: ${printerError(e)}") }
                }
            }.start()
        }

        /** offline mode (no internet): print a kitchen ticket now, straight to the printer on the shop Wi-Fi.
         *  Runs on the WebView bridge thread, so waiting for the printer is fine. Returns "" when printed, otherwise the error. */
        @JavascriptInterface fun printText(ip: String, port: Int, text: String, copies: Int): String {
            if (!ok()) return "not allowed"
            if (!printerOk(ip, port)) return "プリンターのアドレスではありません · Not a printer address on the shop network"
            return try {
                EscPos.send(this@MainActivity, ip, port, EscPos.render(text, Prefs.kanji(this@MainActivity)), copies, 4000); ""
            } catch (e: Exception) { printerError(e) }
        }

        /** Bluetooth printers paired with this tablet (Android Settings → Bluetooth), for the printer settings.
         *  Returns {"devices":[{name, mac, printer}]} or {"error":"permission"|"off"|"none"}; "permission" also asks Android for it,
         *  and the page is told with window.ppBtReady(granted) when the user answers. */
        @JavascriptInterface fun btList(): String {
            if (!ok()) return "{\"error\":\"none\"}"
            val r = BtPrinter.paired(this@MainActivity)
            if (r.contains("\"permission\"") && Build.VERSION.SDK_INT >= 31) runOnUiThread { requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 3) }
            return r
        }
        /** print a page (barcode label sheets) with Android's print screen: PDF, Wi-Fi printers, label printers with a print plugin */
        @JavascriptInterface fun printHtml(html: String, title: String) {
            if (!ok() || html.length > 5_000_000) return
            runOnUiThread {
                val w = WebView(this@MainActivity)
                w.settings.javaScriptEnabled = false
                w.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        val name = title.take(60).ifBlank { "Prime POS" }
                        try { (getSystemService(PRINT_SERVICE) as android.print.PrintManager).print(name, view.createPrintDocumentAdapter(name), android.print.PrintAttributes.Builder().build()) }
                        catch (e: Exception) { toast("⚠ " + (e.message ?: "print")) }
                    }
                }
                printView = w
                w.loadDataWithBaseURL("https://$HOST/", html, "text/html", "utf-8", null)
            }
        }
        /** open Android's Bluetooth settings to pair a printer */
        @JavascriptInterface fun btSettings() {
            if (!ok()) return
            runOnUiThread { try { startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (_: Exception) {} }
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
            if (!ok() || !printerOk(ip, port)) return
            Thread { try { EscPos.send(this@MainActivity, ip, port, EscPos.drawerKick(), 1, 3000) } catch (e: Exception) { toast("⚠ ドロア Drawer: " + printerError(e), quiet = true) } }.start()
        }
    }
}
