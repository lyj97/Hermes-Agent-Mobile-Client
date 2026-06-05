package dev.hermes.mobile

import android.annotation.SuppressLint
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import androidx.core.view.WindowCompat
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val LOG_TAG = "HermesWebView"
private const val HERMES_STATUS_BAR_COLOR = "#031615"
private const val PREFS_NAME = "hermes_mobile_client"
private const val PREF_LAST_DASHBOARD_BASE = "last_dashboard_base"
private const val PREF_TEXT_ZOOM = "text_zoom"
private const val STATE_WEBVIEW = "state_webview"
private const val DEFAULT_TEXT_ZOOM = 90

class MainActivity : ComponentActivity() {
    private lateinit var webView: HermesWebView
    private lateinit var statusBarView: View
    private lateinit var quickKeysBar: HorizontalScrollView
    private lateinit var root: LinearLayout
    private val mainHandler = Handler(Looper.getMainLooper())
    private val startupExecutor = Executors.newSingleThreadExecutor()
    private val attemptedBases = CopyOnWriteArrayList<String>()
    private var showingConnectionHub = false
    private var statusBarColor = Color.parseColor(HERMES_STATUS_BAR_COLOR)
    private var statusBarColorAnimator: ValueAnimator? = null
    private var colorSamplingEnabled = false
    private var colorSamplingInFlight = false
    private val sampleTopColorRunnable = object : Runnable {
        override fun run() {
            sampleWebViewTopColor()
            if (colorSamplingEnabled) mainHandler.postDelayed(this, 1200L)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        WebView.setWebContentsDebuggingEnabled(false)
        CookieManager.getInstance().setAcceptCookie(true)

        webView = HermesWebView(this).apply {
            mobileInputSink = object : HermesWebView.MobileInputSink {
                override fun sendText(text: String) {
                    sendHermesMobileText(text)
                }

                override fun sendKey(key: String) {
                    sendHermesMobileKey(key)
                }
            }

            setBackgroundColor(Color.rgb(4, 28, 28))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.textZoom = getSavedTextZoom()
            settings.userAgentString = "${settings.userAgentString} HermesAgentMobile/0.1"

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    return handleInternalUrl(url)
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url ?: return false
                    return handleInternalUrl(uri.toString())
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    Log.d(LOG_TAG, "Loaded $url")
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        injectMobileChrome(view)
                        injectMobileInputBridge(view)
                        injectTerminalTouchWheelBridge(view)
                        triggerTerminalRelayout(view)
                        mainHandler.postDelayed({ sampleWebViewTopColor() }, 500L)
                        startColorSampling()
                    } else {
                        stopColorSampling()
                        animateStatusBarColor(Color.parseColor(HERMES_STATUS_BAR_COLOR))
                    }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    Log.e(LOG_TAG, "WebView error ${error.errorCode}: ${error.description} for ${request.url}")
                    if (request.isForMainFrame) {
                        renderStatusPage(
                            "Dashboard load failed: ${error.description} (${error.errorCode})",
                            listOf(request.url.toString()),
                        )
                    }
                    super.onReceivedError(view, request, error)
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse,
                ) {
                    Log.e(LOG_TAG, "WebView HTTP ${errorResponse.statusCode}: ${errorResponse.reasonPhrase} for ${request.url}")
                    if (request.isForMainFrame) {
                        renderStatusPage(
                            "Dashboard returned HTTP ${errorResponse.statusCode}: ${errorResponse.reasonPhrase}",
                            listOf(request.url.toString()),
                        )
                    }
                    super.onReceivedHttpError(view, request, errorResponse)
                }
            }

        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        statusBarView = View(this).apply {
            setBackgroundColor(statusBarColor)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
            )
        }
        webView.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        quickKeysBar = createQuickKeysBar()
        root.addView(statusBarView)
        root.addView(webView)
        root.addView(quickKeysBar)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val h = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            statusBarView.layoutParams = statusBarView.layoutParams.apply { height = h }
            updateImeLayout(imeHeight)
            insets
        }
        ViewCompat.setWindowInsetsAnimationCallback(
            root,
            object : WindowInsetsAnimationCompat.Callback(
                WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_STOP,
            ) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: List<WindowInsetsAnimationCompat>,
                ): WindowInsetsCompat {
                    val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    updateImeLayout(imeHeight)
                    return insets
                }
            },
        )
        setContentView(root)
        ViewCompat.requestApplyInsets(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        val restored = savedInstanceState?.getBundle(STATE_WEBVIEW)?.let { state ->
            webView.restoreState(state)
            true
        } ?: false
        if (restored) {
            Log.d(LOG_TAG, "Restored WebView state after configuration change")
            return
        }

        val lastBase = getSavedDashboardBase()
        if (!lastBase.isNullOrBlank()) {
            loadDashboardBase(lastBase, persist = false)
        } else {
            renderConnectionHome()
        }
    }

    private fun updateImeLayout(imeHeight: Int) {
        if (imeHeight > 0) {
            quickKeysBar.visibility = View.VISIBLE
            root.setPadding(0, 0, 0, imeHeight)
            webView.setPadding(0, 0, 0, 0)
        } else {
            quickKeysBar.visibility = View.GONE
            root.setPadding(0, 0, 0, 0)
            webView.setPadding(0, 0, 0, 0)
        }
    }

    private fun createQuickKeysBar(): HorizontalScrollView {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val buttons = listOf(
            QuickKey("Tab") { webView.mobileInputSink?.sendText("\t") },
            QuickKey("Ctrl+C") { dispatchCtrlCToTerminal() },
            QuickKey("Esc") { dispatchEscToTerminal() },
            QuickKey("↑") { webView.mobileInputSink?.sendKey("up") },
            QuickKey("↓") { webView.mobileInputSink?.sendKey("down") },
            QuickKey("←") { webView.mobileInputSink?.sendKey("left") },
            QuickKey("→") { webView.mobileInputSink?.sendKey("right") },
            QuickKey("/") { webView.mobileInputSink?.sendText("/") },
            QuickKey("-") { webView.mobileInputSink?.sendText("-") },
            QuickKey("~") { webView.mobileInputSink?.sendText("~") },
            QuickKey("|") { webView.mobileInputSink?.sendText("|") },
            QuickKey(":") { webView.mobileInputSink?.sendText(":") },
        )
        buttons.forEach { key -> container.addView(createQuickKeyButton(key)) }

        return HorizontalScrollView(this).apply {
            visibility = View.GONE
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.parseColor("#061816"))
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44),
            )
            addView(container)
        }
    }

    private fun createQuickKeyButton(key: QuickKey): TextView {
        return TextView(this).apply {
            text = key.label
            setTextColor(Color.parseColor("#ffe6cb"))
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = android.view.Gravity.CENTER
            isClickable = true
            isFocusable = true
            minHeight = dp(44)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = quickKeyBackground()
            setOnClickListener {
                webView.requestFocus()
                key.action()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply {
                marginEnd = dp(4)
            }
        }
    }

    private fun quickKeyBackground(): StateListDrawable {
        fun shape(color: String) = GradientDrawable().apply {
            setColor(Color.parseColor(color))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape("#173a34"))
            addState(intArrayOf(android.R.attr.state_focused), shape("#173a34"))
            addState(intArrayOf(), shape("#0d2420"))
        }
    }

    private fun dispatchCtrlCToTerminal() {
        val js = """(function(){ var t=document.querySelector('.xterm-helper-textarea')||document.querySelector('.xterm');if(t){t.dispatchEvent(new KeyboardEvent('keydown',{key:'c',code:'KeyC',keyCode:67,ctrlKey:true,bubbles:true,cancelable:true}));t.dispatchEvent(new KeyboardEvent('keyup',{key:'c',code:'KeyC',keyCode:67,ctrlKey:true,bubbles:true,cancelable:true}));}})();"""
        webView.evaluateJavascript(js, null)
    }

    private fun dispatchEscToTerminal() {
        val js = """(function(){ var t=document.querySelector('.xterm-helper-textarea')||document.querySelector('.xterm');if(t){t.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',code:'Escape',keyCode:27,bubbles:true,cancelable:true}));t.dispatchEvent(new KeyboardEvent('keyup',{key:'Escape',code:'Escape',keyCode:27,bubbles:true,cancelable:true}));}})();"""
        webView.evaluateJavascript(js, null)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private data class QuickKey(val label: String, val action: () -> Unit)

    private fun startColorSampling() {
        colorSamplingEnabled = true
        mainHandler.removeCallbacks(sampleTopColorRunnable)
        mainHandler.postDelayed(sampleTopColorRunnable, 300L)
    }

    private fun stopColorSampling() {
        colorSamplingEnabled = false
        mainHandler.removeCallbacks(sampleTopColorRunnable)
    }

    private fun sampleWebViewTopColor() {
        if (colorSamplingInFlight) return
        if (webView.width <= 0 || webView.height <= 0 || !webView.isShown) return
        val sampleRows = 8.coerceAtMost(webView.height)
        val location = IntArray(2)
        webView.getLocationInWindow(location)
        val srcRect = Rect(location[0], location[1], location[0] + webView.width, location[1] + sampleRows)
        val bitmap = Bitmap.createBitmap(webView.width, sampleRows, Bitmap.Config.ARGB_8888)
        colorSamplingInFlight = true
        PixelCopy.request(window, srcRect, bitmap, { result ->
            colorSamplingInFlight = false
            if (result == PixelCopy.SUCCESS) {
                val color = averageTopColor(bitmap, sampleRows)
                animateStatusBarColor(color)
            }
            bitmap.recycle()
        }, mainHandler)
    }

    private fun averageTopColor(bitmap: Bitmap, rows: Int): Int {
        val width = bitmap.width
        val height = rows.coerceAtMost(bitmap.height)
        var r = 0L; var g = 0L; var b = 0L; var count = 0L
        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = bitmap.getPixel(x, y)
                if (Color.alpha(color) == 0) continue
                r += Color.red(color); g += Color.green(color); b += Color.blue(color); count++
            }
        }
        if (count == 0L) return statusBarColor
        return Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    private fun animateStatusBarColor(nextColor: Int) {
        if (nextColor == statusBarColor) return
        statusBarColorAnimator?.cancel()
        statusBarColorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), statusBarColor, nextColor).apply {
            duration = 200L
            addUpdateListener { animator ->
                val color = animator.animatedValue as Int
                statusBarView.setBackgroundColor(color)
                statusBarColor = color
            }
            start()
        }
    }

    override fun onDestroy() {
        statusBarColorAnimator?.cancel()
        stopColorSampling()
        startupExecutor.shutdownNow()
        webView.destroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val webState = Bundle()
        webView.saveState(webState)
        outState.putBundle(STATE_WEBVIEW, webState)
    }

    private fun renderConnectionHome() {
        showingConnectionHub = true
        val saved = getSavedDashboardBase()
        val savedBlock = if (!saved.isNullOrBlank()) {
            """
            <a class="primary" href="hermes://saved">
              <span class="icon">↻</span>
              <span><b>Resume saved connection</b><small>$saved</small></span>
            </a>
            """.trimIndent()
        } else {
            ""
        }
        val html = """
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
              <style>
                :root{--bg:#031615;--panel:#081f1c;--line:#264139;--ink:#fff0d2;--muted:#91a196;--gold:#f5c84c;--green:#7cff9b;}
                *{box-sizing:border-box}
                body{margin:0;min-height:100svh;background:
                  radial-gradient(circle at 50% -10%,rgba(245,200,76,.18),transparent 32%),
                  radial-gradient(circle at 20% 90%,rgba(124,255,155,.10),transparent 34%),
                  linear-gradient(180deg,#061b19 0%,#020b0b 100%);
                  color:var(--ink);font-family:monospace;letter-spacing:.02em}
                body:before{content:"";position:fixed;inset:0;pointer-events:none;background-image:
                  linear-gradient(rgba(255,255,255,.035) 1px,transparent 1px),
                  linear-gradient(90deg,rgba(255,255,255,.025) 1px,transparent 1px);
                  background-size:26px 26px;mask-image:linear-gradient(#000,transparent 82%)}
                .wrap{padding:max(32px,calc(env(safe-area-inset-top,0px) + 16px)) 22px 28px;max-width:720px;margin:0 auto}
                .top{display:flex;align-items:center;justify-content:space-between;margin-bottom:34px}
                .brand{font-size:13px;color:var(--gold);letter-spacing:.42em;text-transform:uppercase}
                .mark{width:42px;height:42px;border:1px solid rgba(245,200,76,.55);border-radius:999px;display:grid;place-items:center;color:var(--gold);background:rgba(8,31,28,.72);box-shadow:0 0 42px rgba(245,200,76,.12)}
                h1{font-size:31px;line-height:1.05;margin:0 0 12px;text-transform:uppercase;letter-spacing:.08em}
                .lead{color:var(--muted);font-size:14px;line-height:1.55;margin:0 0 28px;max-width:35em}
                .rail{border-left:1px solid rgba(245,200,76,.42);padding-left:14px;margin-bottom:20px;color:#d7c59a;font-size:12px;line-height:1.55}
                .stack{display:grid;gap:11px}
                a{color:inherit;text-decoration:none}
                .primary,.choice{display:flex;gap:14px;align-items:center;padding:16px 15px;border:1px solid var(--line);background:linear-gradient(135deg,rgba(8,31,28,.92),rgba(4,18,17,.92));border-radius:18px;min-height:72px}
                .primary{border-color:rgba(245,200,76,.75);box-shadow:0 0 0 1px rgba(245,200,76,.12) inset,0 16px 40px rgba(0,0,0,.22)}
                .choice:active,.primary:active{transform:translateY(1px);border-color:var(--gold)}
                .icon{width:42px;height:42px;border-radius:14px;border:1px solid rgba(245,200,76,.45);display:grid;place-items:center;color:var(--gold);font-size:21px;flex:0 0 auto;background:rgba(245,200,76,.06)}
                b{display:block;font-size:14px;margin-bottom:5px}
                small{display:block;color:var(--muted);font-size:11px;line-height:1.35}
                .hint{margin-top:18px;padding:14px 15px;border:1px dashed rgba(145,161,150,.35);border-radius:16px;color:var(--muted);font-size:11px;line-height:1.5}
                .footer{display:flex;justify-content:space-between;align-items:center;margin-top:28px;color:#5f746b;font-size:11px}
                .menu{color:var(--ink);border:1px solid rgba(255,240,210,.25);border-radius:999px;padding:9px 12px;background:rgba(8,31,28,.55)}
              </style>
            </head>
            <body>
              <div class="wrap">
                <div class="top">
                  <div class="brand">Hermes Agent</div>
                  <a class="menu" href="hermes://menu">Power</a>
                </div>
                <div class="mark">☤</div>
                <h1>Connect your Hermes</h1>
                <p class="lead">Run the mobile connector where Hermes Agent already lives. It starts the dashboard, prefers Tailscale when available, and prints one URL for this app.</p>
                <div class="rail">Recommended path: Tailscale on phone + host, then one connector command.</div>
                <div class="stack">
                  $savedBlock
                  <a class="primary" href="hermes://script">
                    <span class="icon">⌁</span>
                    <span><b>Install Hermes Mobile Connector</b><small>Copy the npx command for PC, Mac, Linux, or VPS.</small></span>
                  </a>
                  <a class="choice" href="hermes://manual">
                    <span class="icon">↗</span>
                    <span><b>Paste connector URL</b><small>Use the URL printed by the connector, usually a Tailscale address.</small></span>
                  </a>
                  <a class="choice" href="hermes://discover">
                    <span class="icon">⌕</span>
                    <span><b>Scan same Wi-Fi</b><small>Fallback for local dashboards on port 9119.</small></span>
                  </a>
                </div>
                <div class="hint">No mocks. No separate mobile backend. This app opens the real Hermes dashboard.</div>
                <div class="footer"><span>Android client</span><span>Hermes owns the agent runtime</span></div>
              </div>
            </body>
            </html>
        """.trimIndent()
        mainHandler.post { webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) }
    }

    private fun connectSavedOrManual() {
        val lastBase = getSavedDashboardBase()
        if (!lastBase.isNullOrBlank()) {
            loadDashboardBase(lastBase, persist = false)
        } else {
            promptForManualEndpoint()
        }
    }

    private fun bootstrapDashboardConnection() {
        renderStatusPage("Scanning local network for Hermes dashboard...", attemptedBases.toList())
        startupExecutor.execute {
            Log.d(LOG_TAG, "bootstrap: start")
            val lastBase = getSavedDashboardBase()
            if (!lastBase.isNullOrBlank() && isHermesDashboardBase(lastBase)) {
                Log.d(LOG_TAG, "bootstrap: using last base $lastBase")
                loadDashboardBase(lastBase, persist = false)
                return@execute
            }

            val discovered = discoverHermesDashboardBases()
            val selected = discovered.firstOrNull()

            if (selected != null) {
                Log.d(LOG_TAG, "bootstrap: using discovered base $selected")
                loadDashboardBase(selected, persist = true)
                return@execute
            }

            Log.w(LOG_TAG, "bootstrap: discovery failed, showing status page")
            renderStatusPage("Could not find Hermes dashboard automatically.", attemptedBases.toList())
            renderConnectionHome()
        }
    }

    private fun loadDashboardBase(base: String, persist: Boolean) {
        val normalizedBase = normalizeDashboardBase(base)
        if (normalizedBase.isBlank()) {
            renderStatusPage("Connector URL is empty.", attemptedBases.toList())
            renderConnectionHome()
            return
        }
        if (persist) {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_LAST_DASHBOARD_BASE, normalizedBase)
                .apply()
        }
        showingConnectionHub = false
        val chatUrl = "$normalizedBase/chat"
        renderStatusPage("Opening Hermes dashboard...", listOf(normalizedBase))
        startupExecutor.execute {
            warmupDashboard(normalizedBase)
            mainHandler.post { webView.loadUrl(chatUrl) }
        }
    }

    private fun warmupDashboard(base: String) {
        runCatching {
            val conn = (URL("$base/api/status").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 800
                readTimeout = 800
                instanceFollowRedirects = true
            }
            conn.inputStream.use { it.readNBytes(32) }
            conn.disconnect()
        }
    }

    private fun getSavedDashboardBase(): String? {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(PREF_LAST_DASHBOARD_BASE, null) ?: return null
        val normalized = normalizeDashboardBase(raw)
        if (normalized.isNotBlank() && normalized != raw) {
            prefs.edit().putString(PREF_LAST_DASHBOARD_BASE, normalized).apply()
        }
        return normalized.ifBlank { null }
    }

    private fun getSavedTextZoom(): Int {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(PREF_TEXT_ZOOM, DEFAULT_TEXT_ZOOM)
            .coerceIn(60, 160)
    }

    private fun discoverHermesDashboardBases(): List<String> {
        val candidates = linkedSetOf<String>()

        discoverViaMdns().forEach { host ->
            candidates += "http://$host:9119"
        }
        discoverViaLanProbe().forEach { host ->
            candidates += "http://$host:9119"
        }

        val verified = mutableListOf<String>()
        for (base in candidates) {
            attemptedBases += base
            if (isHermesDashboardBase(base)) {
                verified += base
            }
        }
        Log.d(LOG_TAG, "Discovery verified ${verified.size} Hermes dashboard endpoints")
        return verified
    }

    private fun discoverViaMdns(): Set<String> {
        val nsd = getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return emptySet()
        val hosts = Collections.synchronizedSet(mutableSetOf<String>())
        val done = CountDownLatch(1)
        val resolvePending = Collections.synchronizedList(mutableListOf<CountDownLatch>())

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {
                done.countDown()
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                done.countDown()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                done.countDown()
            }
            override fun onServiceFound(service: NsdServiceInfo) {
                val name = service.serviceName.lowercase()
                if (!name.contains("hermes")) return
                val wait = CountDownLatch(1)
                resolvePending += wait
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        wait.countDown()
                    }
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val host = resolved.host?.hostAddress
                        if (!host.isNullOrBlank()) hosts += host
                        wait.countDown()
                    }
                })
            }
        }

        return try {
            nsd.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            done.await(3500, TimeUnit.MILLISECONDS)
            runCatching { nsd.stopServiceDiscovery(listener) }
            resolvePending.forEach { it.await(1200, TimeUnit.MILLISECONDS) }
            hosts
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun discoverViaLanProbe(): Set<String> {
        val localIp = localIpv4Address() ?: return emptySet()
        val parts = localIp.split(".")
        if (parts.size != 4) return emptySet()
        val prefix = "${parts[0]}.${parts[1]}.${parts[2]}."
        val found = Collections.synchronizedSet(mutableSetOf<String>())
        val pool = Executors.newFixedThreadPool(32)
        val stop = AtomicBoolean(false)
        try {
            for (i in 1..254) {
                pool.execute {
                    if (stop.get()) return@execute
                    val host = "$prefix$i"
                    if (isHermesDashboardBase("http://$host:9119")) {
                        found += host
                        stop.set(true)
                    }
                }
            }
            pool.shutdown()
            val finished = pool.awaitTermination(8, TimeUnit.SECONDS)
            if (!finished) pool.shutdownNow()
        } catch (_: Exception) {
            pool.shutdownNow()
        }
        return found
    }

    private fun isHermesDashboardBase(baseUrl: String): Boolean {
        val clean = normalizeDashboardBase(baseUrl)
        if (clean.isBlank()) return false
        val statusUrl = "$clean/api/status"
        return try {
            Log.d(LOG_TAG, "probe $statusUrl")
            val conn = (URL(statusUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1200
                readTimeout = 1200
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            if (code != 200) return false
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            body.contains("\"version\"") && body.contains("\"gateway_running\"")
        } catch (_: Exception) {
            false
        }
    }

    private fun renderStatusPage(message: String, attempted: List<String>) {
        val attemptedHtml = if (attempted.isEmpty()) {
            "<li>No endpoints attempted yet.</li>"
        } else {
            attempted.joinToString("") { "<li>${normalizeDashboardBase(it)}/api/status</li>" }
        }
        val html = """
            <html><body style="background:#041c1c;color:#ffe6cb;font-family:monospace;padding:24px;line-height:1.45">
            <h3 style="margin:0 0 10px 0">Hermes Mobile Client</h3>
            <p style="margin:0 0 10px 0">$message</p>
            <p style="margin:0 0 6px 0">Attempted endpoints:</p>
            <ul style="margin:0 0 12px 0;padding-left:20px">$attemptedHtml</ul>
            <p style="margin:0">Ensure Hermes dashboard is reachable on port 9119 from this phone network, then relaunch app.</p>
            </body></html>
        """.trimIndent()
        mainHandler.post { webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) }
    }

    private fun promptForManualEndpoint() {
        mainHandler.post {
            val input = EditText(this).apply {
                hint = "http://device.tailnet.ts.net:9119"
                setText("http://")
                setTextColor(Color.parseColor("#ffe6cb"))
                setHintTextColor(Color.parseColor("#89917e"))
                setBackgroundColor(Color.parseColor("#0d1d18"))
                setPadding(28, 22, 28, 22)
            }
            val wrapper = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#041c1c"))
                setPadding(36, 28, 36, 20)
            }
            val title = TextView(this).apply {
                text = "PASTE CONNECTOR URL"
                setTextColor(Color.parseColor("#ffe6cb"))
                textSize = 16f
                setPadding(0, 0, 0, 10)
            }
            val help = TextView(this).apply {
                text = "Paste the URL printed by hermes-mobile. Tailscale addresses are preferred because they avoid public firewall setup."
                setTextColor(Color.parseColor("#89917e"))
                textSize = 12f
                setPadding(0, 0, 0, 14)
            }
            wrapper.addView(title)
            wrapper.addView(help)
            wrapper.addView(input)

            AlertDialog.Builder(this)
                .setView(wrapper)
                .setCancelable(false)
                .setPositiveButton("Connect") { _, _ ->
                    hideKeyboard(input)
                    val raw = input.text?.toString()?.trim().orEmpty()
                    val base = normalizeDashboardBase(raw)
                    if (base.isNotBlank()) {
                        attemptedBases += base
                        loadDashboardBase(base, persist = true)
                    } else {
                        renderStatusPage("Manual endpoint is empty.", attemptedBases.toList())
                        renderConnectionHome()
                    }
                }
                .setNegativeButton("Back") { _, _ -> renderConnectionHome() }
                .show()
        }
    }

    private fun hideKeyboard(input: EditText) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
    }

    private fun normalizeDashboardBase(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed
        } else {
            "http://$trimmed"
        }
        return try {
            val url = URL(withScheme)
            val host = url.host
            if (host.isNullOrBlank()) return ""
            val protocol = if (url.protocol.equals("https", ignoreCase = true)) "https" else "http"
            val port = if (url.port > 0) ":${url.port}" else ""
            "$protocol://$host$port"
        } catch (_: Exception) {
            withScheme
                .substringBefore("?")
                .substringBefore("#")
                .removeSuffix("/")
                .removeSuffix("/chat")
                .removeSuffix("/")
        }
    }

    private fun showVpsScriptDialog() {
        val script = """
            # Run this where Hermes Agent is installed.
            # Recommended: install Tailscale on this machine and on your phone first.

            npx github:areu01or00/Hermes-Agent-Mobile-Client install

            # Later:
            npx github:areu01or00/Hermes-Agent-Mobile-Client status
            npx github:areu01or00/Hermes-Agent-Mobile-Client url
            npx github:areu01or00/Hermes-Agent-Mobile-Client restart
            npx github:areu01or00/Hermes-Agent-Mobile-Client logs
            npx github:areu01or00/Hermes-Agent-Mobile-Client uninstall
        """.trimIndent()

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#041c1c"))
            setPadding(28, 20, 28, 12)
        }
        val text = TextView(this).apply {
            setTextColor(Color.parseColor("#ffe6cb"))
            textSize = 11f
            this.text = script
        }
        scroll.addView(text)

        AlertDialog.Builder(this)
            .setTitle("Hermes Mobile Connector")
            .setView(scroll)
            .setPositiveButton("Copy") { _, _ ->
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("hermes_mobile_connector", script))
                renderStatusPage("Connector command copied. Run it where Hermes Agent is installed, then paste the printed URL here.", emptyList())
                renderConnectionHome()
            }
            .setNegativeButton("Back") { _, _ -> renderConnectionHome() }
            .show()
    }

    private fun handleInternalUrl(url: String): Boolean {
        if (!url.startsWith("hermes://")) return false
        val host = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
        when (host) {
            "discover" -> bootstrapDashboardConnection()
            "manual" -> promptForManualEndpoint()
            "saved" -> connectSavedOrManual()
            "script" -> showVpsScriptDialog()
            "menu" -> showHamburgerMenu()
            "textsize" -> showTextSizeDialog()
            "connector" -> showConnectorStateDialog()
            "reloadtui" -> reloadFreshTui()
        }
        return true
    }

    private fun reloadFreshTui() {
        val base = getSavedDashboardBase()
        if (base.isNullOrBlank()) {
            renderConnectionHome()
            return
        }
        showingConnectionHub = false
        renderStatusPage("Opening fresh Hermes TUI...", listOf(base))
        webView.postDelayed({
            webView.loadUrl("$base/chat")
        }, 80)
    }

    private fun showConnectorStateDialog() {
        val base = getSavedDashboardBase()
        if (base.isNullOrBlank()) {
            AlertDialog.Builder(this)
                .setTitle("Connector State")
                .setMessage("No saved Hermes connector URL yet.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Connector State")
            .setMessage("Checking $base ...")
            .setPositiveButton("Close", null)
            .show()

        startupExecutor.execute {
            val message = runCatching {
                val conn = (URL("$base/api/status").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 2500
                    readTimeout = 2500
                    instanceFollowRedirects = true
                }
                val code = conn.responseCode
                val raw = if (code in 200..299) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                }
                conn.disconnect()

                val json = runCatching { org.json.JSONObject(raw) }.getOrNull()
                val version = json?.optString("version")?.takeIf { it.isNotBlank() }
                    ?: json?.optString("hermes_version")?.takeIf { it.isNotBlank() }
                    ?: "unknown"
                val gateway = json?.optString("gateway_status")?.takeIf { it.isNotBlank() }
                    ?: json?.optString("gateway")?.takeIf { it.isNotBlank() }
                    ?: json?.optString("status")?.takeIf { it.isNotBlank() }
                    ?: "unknown"
                val sessions = json?.opt("active_sessions")?.toString()
                    ?: json?.opt("sessions")?.toString()
                    ?: "unknown"

                """
                URL: $base
                HTTP: $code
                Hermes: $version
                Gateway: $gateway
                Active sessions: $sessions
                """.trimIndent()
            }.getOrElse { err ->
                """
                URL: $base
                Connector unreachable.

                ${err.message.orEmpty()}
                """.trimIndent()
            }
            mainHandler.post { dialog.setMessage(message) }
        }
    }

    private fun showHamburgerMenu() {
        AlertDialog.Builder(this)
            .setTitle("Menu")
            .setItems(arrayOf("Logout")) { _, which ->
                if (which == 0) logoutAndReset()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun logoutAndReset() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        webView.clearHistory()
        webView.clearCache(true)
        renderConnectionHome()
    }

    private fun showTextSizeDialog() {
        val current = getSavedTextZoom()
        val title = TextView(this).apply {
            text = "Text Size: $current%"
            setTextColor(Color.parseColor("#ffe6cb"))
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }
        val slider = SeekBar(this).apply {
            max = 100
            progress = current - 60
        }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#041c1c"))
            setPadding(36, 28, 36, 20)
            addView(title)
            addView(slider)
        }

        val applyZoom = { zoom: Int ->
            webView.settings.textZoom = zoom
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(PREF_TEXT_ZOOM, zoom)
                .apply()
            triggerTerminalRelayout(webView)
        }

        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            var changedDuringDrag = false
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val zoom = (progress + 60).coerceIn(60, 160)
                title.text = "Text Size: ${zoom}%"
                applyZoom(zoom)
                changedDuringDrag = true
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                changedDuringDrag = false
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (!changedDuringDrag) return
                val url = webView.url.orEmpty()
                if (!showingConnectionHub && (url.startsWith("http://") || url.startsWith("https://"))) {
                    // Fallback: xterm occasionally ignores synthetic resize after text zoom.
                    // Reload guarantees refit without requiring manual portrait<->landscape rotate.
                    webView.postDelayed({ webView.reload() }, 120)
                }
            }
        })

        AlertDialog.Builder(this)
            .setTitle("Terminal Text Size")
            .setView(wrapper)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun injectMobileChrome(view: WebView) {
        if (showingConnectionHub) return
        view.evaluateJavascript(
            """
            (function(){
              if(!document.getElementById('hermes-mobile-client-style')){
                var style=document.createElement('style');
                style.id='hermes-mobile-client-style';
                style.textContent=[
                  'html,body,#root{touch-action:pan-x pan-y;-webkit-overflow-scrolling:touch;}',
                  'body,*{overscroll-behavior:auto;}',
                  'input,textarea,select{font-size:16px!important;}',
                  '#hermes-mobile-power,#hermes-mobile-textsize,#hermes-mobile-connector{width:34px;height:34px;border-radius:999px;border:1px solid rgba(255,230,203,.45);background:rgba(4,28,28,.25);color:#ffe6cb;display:inline-flex;align-items:center;justify-content:center;text-decoration:none;backdrop-filter:blur(6px);-webkit-backdrop-filter:blur(6px);line-height:1;box-sizing:border-box;}',
                  '#hermes-mobile-power{font-size:17px;}',
                  '#hermes-mobile-textsize{font-size:15px;}',
                  '#hermes-mobile-connector{font-size:18px;color:#65ff9a;border-color:rgba(101,255,154,.45);}',
                  '#hermes-mobile-power:hover,#hermes-mobile-power:active,#hermes-mobile-textsize:hover,#hermes-mobile-textsize:active,#hermes-mobile-connector:hover,#hermes-mobile-connector:active{background:rgba(255,215,94,.12);border-color:rgba(255,215,94,.75);color:#ffd75e;}',
                  '#hermes-mobile-dead-session{position:fixed;left:16px;right:16px;bottom:74px;z-index:99998;display:none;align-items:center;justify-content:space-between;gap:14px;padding:14px 16px;border:1px solid rgba(255,215,94,.6);border-radius:18px;background:linear-gradient(135deg,rgba(4,28,28,.96),rgba(9,46,40,.94));color:#ffe6cb;box-shadow:0 18px 60px rgba(0,0,0,.45);backdrop-filter:blur(10px);-webkit-backdrop-filter:blur(10px);font-family:monospace;}',
                  '#hermes-mobile-dead-session.is-visible{display:flex;}',
                  '#hermes-mobile-dead-session strong{display:block;color:#ffd75e;font-size:12px;letter-spacing:.12em;text-transform:uppercase;margin-bottom:3px;}',
                  '#hermes-mobile-dead-session span{font-size:12px;opacity:.8;}',
                  '#hermes-mobile-dead-session a{white-space:nowrap;color:#031818;background:#ffd75e;border:1px solid rgba(255,230,203,.55);border-radius:999px;padding:10px 13px;text-decoration:none;font-size:12px;font-weight:700;}'
                ].join('\n');
                document.head.appendChild(style);
              }

              if(!document.getElementById('hermes-mobile-power')){
                var b=document.createElement('a');
                b.id='hermes-mobile-power';
                b.href='hermes://menu';
                b.setAttribute('aria-label','Power');
                b.setAttribute('title','Power');
                b.innerHTML='<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 2v10"/><path d="M18.4 6.6a9 9 0 1 1-12.8 0"/></svg>';
                var z=document.createElement('a');
                z.id='hermes-mobile-textsize';
                z.href='hermes://textsize';
                z.setAttribute('aria-label','Text Size');
                z.setAttribute('title','Text Size');
                z.textContent='A';
                var d=document.createElement('a');
                d.id='hermes-mobile-connector';
                d.href='hermes://connector';
                d.setAttribute('aria-label','Connector State');
                d.setAttribute('title','Connector State');
                d.textContent='●';

                var walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);
                var brandText=null;
                while(walker.nextNode()){
                  var t=(walker.currentNode.nodeValue||'').replace(/\s+/g,' ').trim().toUpperCase();
                  if(t.indexOf('HERMES')!==-1 && t.indexOf('AGENT')!==-1){
                    brandText=walker.currentNode.parentElement;
                    break;
                  }
                }
                var host=brandText;
                while(host && host!==document.body){
                  var r=host.getBoundingClientRect();
                  if(r.width>60 && r.height>20) break;
                  host=host.parentElement;
                }
                if(host && host!==document.body){
                  host.style.display='flex';
                  host.style.alignItems='center';
                  host.style.justifyContent='space-between';
                  host.style.gap='10px';
                  var controls=document.createElement('div');
                  controls.style.display='inline-flex';
                  controls.style.gap='8px';
                  controls.appendChild(d);
                  controls.appendChild(z);
                  controls.appendChild(b);
                  host.appendChild(controls);
                }else{
                  d.style.position='fixed';
                  d.style.top='max(12px, calc(env(safe-area-inset-top, 0px) + 8px))';
                  d.style.left='12px';
                  d.style.zIndex='99999';
                  z.style.position='fixed';
                  z.style.top='max(12px, calc(env(safe-area-inset-top, 0px) + 8px))';
                  z.style.left='54px';
                  z.style.zIndex='99999';
                  b.style.position='fixed';
                  b.style.top='max(12px, calc(env(safe-area-inset-top, 0px) + 8px))';
                  b.style.left='96px';
                  b.style.zIndex='99999';
                  document.body.appendChild(d);
                  document.body.appendChild(z);
                  document.body.appendChild(b);
                }
              }

              if(!document.getElementById('hermes-mobile-dead-session')){
                var dead=document.createElement('div');
                dead.id='hermes-mobile-dead-session';
                dead.innerHTML='<div><strong>TUI session ended</strong><span>Open a fresh Hermes terminal and resume there.</span></div><a href="hermes://reloadtui">Open fresh TUI</a>';
                document.body.appendChild(dead);
              }
              if(!window.__HermesMobileDeadSessionWatch){
                window.__HermesMobileDeadSessionWatch=true;
                var checkDead=function(){
                  var dead=document.getElementById('hermes-mobile-dead-session');
                  if(!dead) return;
                  var text=(document.body&&document.body.innerText||'').toLowerCase();
                  var ended=text.indexOf('[session ended]')!==-1
                    || text.indexOf('gateway exited')!==-1
                    || text.indexOf('chat unavailable')!==-1;
                  dead.classList.toggle('is-visible', ended);
                };
                setInterval(checkDead,1500);
                new MutationObserver(checkDead).observe(document.body,{childList:true,subtree:true,characterData:true});
                setTimeout(checkDead,300);
              }

            })();
            """.trimIndent(),
            null,
        )
    }

    private fun sendHermesMobileText(text: String) {
        if (text.isEmpty()) return
        webView.post {
            val json = org.json.JSONObject.quote(text)
            webView.evaluateJavascript(
                "window.HermesMobileNativeInput&&window.HermesMobileNativeInput.text($json)",
                null,
            )
        }
    }

    private fun sendHermesMobileKey(key: String) {
        webView.post {
            val json = org.json.JSONObject.quote(key)
            webView.evaluateJavascript(
                "window.HermesMobileNativeInput&&window.HermesMobileNativeInput.key($json)",
                null,
            )
        }
    }

    private fun triggerTerminalRelayout(view: WebView) {
        view.postDelayed({
            view.evaluateJavascript(
                """
                (function(){
                  var root = document.documentElement;
                  var prevWidth = root.style.width;
                  // Force a measurable layout delta so xterm ResizeObserver refits columns.
                  root.style.width = 'calc(100% - 1px)';
                  setTimeout(function(){ root.style.width = prevWidth || ''; }, 90);
                  window.dispatchEvent(new Event('orientationchange'));
                  window.dispatchEvent(new Event('resize'));
                  if (window.visualViewport) {
                    window.visualViewport.dispatchEvent(new Event('resize'));
                  }
                })();
                """.trimIndent(),
                null,
            )
        }, 90)
        view.postDelayed({
            view.evaluateJavascript(
                """
                (function(){
                  window.dispatchEvent(new Event('orientationchange'));
                  window.dispatchEvent(new Event('resize'));
                  if (window.visualViewport) {
                    window.visualViewport.dispatchEvent(new Event('resize'));
                  }
                })();
                """.trimIndent(),
                null,
            )
        }, 260)
    }

    private fun injectTerminalTouchWheelBridge(view: WebView) {
        if (showingConnectionHub) return
        view.evaluateJavascript(
            """
            (function(){
              if(!document.getElementById('hermes-terminal-touch-wheel-style')){
                var style=document.createElement('style');
                style.id='hermes-terminal-touch-wheel-style';
                style.textContent=[
                  '.xterm,.xterm *,.xterm-viewport,.xterm-scrollable-element{touch-action:none!important;-webkit-overflow-scrolling:auto!important;overscroll-behavior:contain!important;}',
                  '.xterm:has(.xterm-viewport),.xterm:has(.xterm-scrollable-element){touch-action:none!important;overscroll-behavior:contain!important;}'
                ].join('\n');
                document.head.appendChild(style);
              }

              if(window.__HermesTerminalTouchWheelBridge) {
                window.__HermesTerminalTouchWheelBridge.install();
                return;
              }

              function xtermElements(){
                return Array.prototype.slice.call(document.querySelectorAll('.xterm, .xterm-viewport, .xterm-scrollable-element'));
              }

              function installOn(host){
                if(!host || host.__hermesTouchWheelBridgeInstalled) return;
                host.__hermesTouchWheelBridgeInstalled=true;
                host.setAttribute('data-hermes-touch-wheel-bridge','true');

                var lastX=null;
                var lastY=null;

                function wheelTarget(){
                  var terminal = host.closest && host.closest('.xterm');
                  var scope = terminal || host;
                  return scope.querySelector && scope.querySelector('.xterm-scrollable-element')
                    || document.querySelector('.xterm-scrollable-element')
                    || host;
                }

                host.addEventListener('touchstart',function(event){
                  if(!event.touches || event.touches.length < 1) return;
                  if(event.target && event.target.closest && event.target.closest('.xterm')){
                    event.stopPropagation();
                  }
                  lastX=event.touches[0].clientX;
                  lastY=event.touches[0].clientY;
                },{passive:true});

                host.addEventListener('touchmove',function(event){
                  if(!event.touches || event.touches.length < 1 || lastX === null || lastY === null) return;
                  var touch=event.touches[0];
                  var deltaX=lastX - touch.clientX;
                  var deltaY=lastY - touch.clientY;
                  lastX=touch.clientX;
                  lastY=touch.clientY;
                  event.preventDefault();
                  event.stopPropagation();
                  var target=wheelTarget();
                  var wheel=new WheelEvent('wheel',{
                    deltaX:deltaX,
                    deltaY:deltaY,
                    deltaMode:WheelEvent.DOM_DELTA_PIXEL,
                    bubbles:true,
                    cancelable:true
                  });
                  target.dispatchEvent(wheel);
                },{passive:false});

                function reset(){
                  lastX=null;
                  lastY=null;
                }

                host.addEventListener('touchend',reset,{passive:true});
                host.addEventListener('touchcancel',reset,{passive:true});
              }

              function install(){
                xtermElements().forEach(installOn);
              }

              window.__HermesTerminalTouchWheelBridge={install:install};
              install();

              if(document.body){
                new MutationObserver(install).observe(document.body,{childList:true,subtree:true});
              }
            })();
            """.trimIndent(),
            null,
        )
    }

    private fun injectMobileInputBridge(view: WebView) {
        if (showingConnectionHub) return
        view.evaluateJavascript(
            """
            (function(){
              if(window.HermesMobileNativeInput) return;

              function terminalTarget(){
                return document.querySelector('.xterm-helper-textarea')
                  || document.querySelector('.xterm textarea')
                  || document.querySelector('.xterm');
              }

              function focusTerminal(){
                var target=terminalTarget();
                if(target && target.focus) target.focus();
                return target;
              }

              function fire(target,type,init){
                var ev;
                try {
                  ev = new InputEvent(type, Object.assign({bubbles:true,cancelable:true,composed:true}, init || {}));
                } catch (_) {
                  ev = document.createEvent('Event');
                  ev.initEvent(type,true,true);
                  Object.assign(ev, init || {});
                }
                target.dispatchEvent(ev);
              }

              function keyEvent(target,type,key,code,keyCode,extra){
                var ev;
                var opts = Object.assign({
                  key:key,
                  code:code,
                  bubbles:true,
                  cancelable:true,
                  composed:true,
                  keyCode:keyCode,
                  which:keyCode
                }, extra || {});
                try {
                  ev = new KeyboardEvent(type, opts);
                } catch (_) {
                  ev = document.createEvent('KeyboardEvent');
                  ev.initKeyboardEvent(type,true,true,window,key,0,false,false,false,false);
                }
                target.dispatchEvent(ev);
              }

              function sendText(text){
                var target=focusTerminal();
                if(!target) return false;
                if(target.tagName === 'TEXTAREA' || target.tagName === 'INPUT'){
                  target.value = text;
                  fire(target,'beforeinput',{inputType:'insertText',data:text});
                  fire(target,'input',{inputType:'insertText',data:text});
                  target.value = '';
                  return true;
                }
                keyEvent(target,'keydown',text,'',text.charCodeAt(0));
                keyEvent(target,'keypress',text,'',text.charCodeAt(0));
                keyEvent(target,'keyup',text,'',text.charCodeAt(0));
                return true;
              }

              function sendKey(key){
                var target=focusTerminal();
                if(!target) return false;
                var spec={
                  backspace:['Backspace','Backspace',8,'deleteContentBackward'],
                  delete:['Delete','Delete',46,'deleteContentForward'],
                  enter:['Enter','Enter',13,'insertLineBreak'],
                  up:['ArrowUp','ArrowUp',38,null],
                  down:['ArrowDown','ArrowDown',40,null],
                  left:['ArrowLeft','ArrowLeft',37,null],
                  right:['ArrowRight','ArrowRight',39,null]
                }[key];
                if(!spec) return false;
                keyEvent(target,'keydown',spec[0],spec[1],spec[2]);
                if(spec[3]) fire(target,'beforeinput',{inputType:spec[3],data:null});
                if(spec[3]) fire(target,'input',{inputType:spec[3],data:null});
                keyEvent(target,'keyup',spec[0],spec[1],spec[2]);
                if(target.value !== undefined) target.value = '';
                return true;
              }

              window.HermesMobileNativeInput = {
                text: sendText,
                key: sendKey
              };
            })();
            """.trimIndent(),
            null,
        )
    }

    private fun localIpv4Address(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                if (!name.startsWith("wlan") && !name.startsWith("eth") && !name.startsWith("en")) continue
                val addresses = iface.inetAddresses
                for (addr in addresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}

class HermesWebView(context: Context) : WebView(context) {
    interface MobileInputSink {
        fun sendText(text: String)
        fun sendKey(key: String)
    }

    var mobileInputSink: MobileInputSink? = null

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN

        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val value = text?.toString().orEmpty()
                if (value.isNotEmpty()) mobileInputSink?.sendText(value)
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                val count = beforeLength.coerceAtLeast(1)
                repeat(count) { mobileInputSink?.sendKey("backspace") }
                return true
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                return deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action != KeyEvent.ACTION_DOWN) return true
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DEL -> mobileInputSink?.sendKey("backspace")
                    KeyEvent.KEYCODE_FORWARD_DEL -> mobileInputSink?.sendKey("delete")
                    KeyEvent.KEYCODE_ENTER -> mobileInputSink?.sendKey("enter")
                    KeyEvent.KEYCODE_DPAD_UP -> mobileInputSink?.sendKey("up")
                    KeyEvent.KEYCODE_DPAD_DOWN -> mobileInputSink?.sendKey("down")
                    KeyEvent.KEYCODE_DPAD_LEFT -> mobileInputSink?.sendKey("left")
                    KeyEvent.KEYCODE_DPAD_RIGHT -> mobileInputSink?.sendKey("right")
                    else -> event.unicodeChar.takeIf { it > 0 }?.let { mobileInputSink?.sendText(it.toChar().toString()) }
                }
                return true
            }

            override fun performEditorAction(editorAction: Int): Boolean {
                mobileInputSink?.sendKey("enter")
                return true
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = super.dispatchTouchEvent(event)
}
