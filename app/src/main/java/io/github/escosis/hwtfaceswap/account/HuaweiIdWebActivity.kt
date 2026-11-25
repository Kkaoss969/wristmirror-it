package io.github.escosis.hwtfaceswap.account

import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import io.github.escosis.hwtfaceswap.R
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.LinearLayout
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.github.escosis.hwtfaceswap.ThemeMode
import io.github.escosis.hwtfaceswap.ui.HuaweiIdWebHeader
import io.github.escosis.hwtfaceswap.ui.WanJingTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Official-site, one-use system WebView. Never initialize AppRuntime, a ViewModel or Bluetooth here. */
class HuaweiIdWebActivity : ComponentActivity() {
    companion object {
        const val EXTRA_ID = "huawei_id_draft"
        const val EXTRA_REQUEST = "huawei_id_request"
        const val EXTRA_ERROR = "huawei_id_status"
        private const val EXTRA_THEME = "huawei_id_theme"
        fun launchIntent(context: Context, token: String): Intent {
            val owner = requireNotNull(HuaweiIdWebSession.owner(token))
            return Intent(context, HuaweiIdWebActivity::class.java)
                .putExtra(EXTRA_REQUEST, token)
                .putExtra(EXTRA_THEME, context.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("theme", "SYSTEM"))
                .putExtras(Bundle().apply { putBinder(HuaweiIdWebOwner.EXTRA, owner) })
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var browser: WebView? = null
    private lateinit var content: LinearLayout
    private var statusText by mutableStateOf("")
    private var request: String? = null
    private var ending = false
    private var returned = false
    private var loaded = false
    private var foreground = false
    private var owner: IBinder? = null
    private val ownerDeath = IBinder.DeathRecipient { handler.post { end(null, "OWNER_CLOSED") } }
    private var handshakeComplete = false
    private val handshakeTimeout = Runnable { end(null, "OWNER_CLOSED") }
    private val closeEndpoint = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != HuaweiIdWebOwner.CLOSE && code != HuaweiIdWebOwner.ACK) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(HuaweiIdWebOwner.DESCRIPTOR)
            val token = data.readString()
            val accepted = if (code == HuaweiIdWebOwner.ACK) data.readInt() == 1 else false
            if (getCallingUid() == Process.myUid() && token == request) handler.post {
                if (code == HuaweiIdWebOwner.CLOSE) end(null, "CANCELLED")
                else if (!ending && !handshakeComplete) {
                    handshakeComplete = true
                    handler.removeCallbacks(handshakeTimeout)
                    if (accepted) startBrowser() else end(null, "OWNER_CLOSED")
                }
            }
            return true
        }
    }
    private val poll = object : Runnable {
        override fun run() {
            if (ending || !loaded) return
            captureId()
            if (!ending) handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request = intent.getStringExtra(EXTRA_REQUEST)
        owner = intent.extras?.getBinder(HuaweiIdWebOwner.EXTRA)
        setResult(RESULT_CANCELED)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT < 28 || savedInstanceState != null ||
            !HuaweiIdWebPolicy.validRequest(request) || !HuaweiIdWebProcess.claim() || owner == null) {
            finishResult(null, "UNAVAILABLE")
            return
        }
        handler.postDelayed({ end(null, "TIMEOUT") }, 5 * 60 * 1000L)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        buildChrome()
        onBackPressedDispatcher.addCallback(this) { end(null, "CANCELLED") }
        handler.postDelayed(handshakeTimeout, 2000)
        try {
            owner!!.linkToDeath(ownerDeath, 0)
            if (!HuaweiIdWebOwner.hello(owner!!, request!!, closeEndpoint)) end(null, "OWNER_CLOSED")
        } catch (_: Exception) { end(null, "OWNER_CLOSED") }
    }

    private fun startBrowser() {
        if (ending) return
        try { createBrowser(); awaitPrivateDirectory(0) }
        catch (_: Exception) { end(null, "WEBVIEW_UNAVAILABLE") }
    }

    private fun buildChrome() {
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            isSaveEnabled = false
        }
        val mode = ThemeMode.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_THEME) } ?: ThemeMode.SYSTEM
        statusText = getString(R.string.huawei_id_web_instruction)
        content.addView(ComposeView(this).apply {
            isSaveEnabled = false
            isSaveFromParentEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WanJingTheme(mode) {
                    val background = MiuixTheme.colorScheme.surface
                    SideEffect { content.setBackgroundColor(background.toArgb()) }
                    HuaweiIdWebHeader(hint = statusText, onClose = { end(null, "CANCELLED") })
                }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
    }

    @SuppressLint("SetJavaScriptEnabled") // Required by the official login page; there is no native JS bridge.
    @Suppress("DEPRECATION")
    private fun createBrowser() {
        WebView.setWebContentsDebuggingEnabled(false)
        val web = WebView(this)
        browser = web
        web.isSaveEnabled = false
        web.isSaveFromParentEnabled = false
        web.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            saveFormData = false
            savePassword = false
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
            safeBrowsingEnabled = true
        }
        web.setDownloadListener { _, _, _, _, _ -> if (!ending) statusText = getString(R.string.huawei_id_web_no_download) }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onShowFileChooser(view: WebView?, callback: ValueCallback<Array<android.net.Uri>>?, params: FileChooserParams?): Boolean {
                callback?.onReceiveValue(null); return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            private fun blocked(url: String?): Boolean {
                if (ending) return true
                if (HuaweiIdWebPolicy.allowsNavigation(url)) return false
                statusText = getString(R.string.huawei_id_web_navigation_blocked)
                return true
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (ending) return true
                // CAPTCHA/SSO resources can be third-party HTTPS frames; they never get a native bridge or UID access.
                if (request?.isForMainFrame == false) return !HuaweiIdWebPolicy.allowsEmbeddedNavigation(request.url?.toString())
                return blocked(request?.url?.toString())
            }
            @Deprecated("Platform compatibility")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = blocked(url)
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (ending) return
                if (!HuaweiIdWebPolicy.allowsNavigation(url)) { view?.stopLoading(); return }
                statusText = getString(R.string.huawei_id_web_instruction)
            }
            override fun onPageFinished(view: WebView?, url: String?) { if (!ending) captureId() }
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError?) {
                handler.cancel(); if (!ending) statusText = getString(R.string.huawei_id_web_tls_error)
            }
            override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) { handler?.cancel() }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (!ending && request?.isForMainFrame == true) statusText = getString(R.string.huawei_id_web_load_error)
            }
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                end(null, "WEBVIEW_CLOSED"); return true
            }
        }
        content.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun awaitPrivateDirectory(attempt: Int) {
        if (ending) return
        val profile = HuaweiIdWebProcess.profileRequest
        if (profile != null && HuaweiIdSessionFiles.hasExpectedLayout(dataDir, cacheDir, profile)) {
            loaded = true
            try {
                // SSO cookies are enabled only after both private provider paths have been observed.
                val web = browser ?: return
                CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(web, true) }
                web.loadUrl(HuaweiIdWebPolicy.START_URL)
                handler.post(poll)
            } catch (_: Exception) { end(null, "WEBVIEW_UNAVAILABLE") }
        } else if (attempt < 40) handler.postDelayed({ awaitPrivateDirectory(attempt + 1) }, 50)
        else end(null, "ISOLATION_UNAVAILABLE")
    }

    private fun captureId() {
        if (ending || !loaded || !foreground) return
        if (!HuaweiIdWebPolicy.isMallPage(browser?.url)) return
        try {
            val cookies = CookieManager.getInstance()
            val id = HuaweiIdWebPolicy.idFromCookies(HuaweiIdWebPolicy.COOKIE_URLS.map { cookies.getCookie(it) }) ?: return
            end(id, null)
        } catch (_: Exception) { end(null, "READ_UNAVAILABLE") }
    }

    /** All terminal paths detach the page immediately. Physical cleanup is verified by the parent after the lease releases. */
    @Suppress("DEPRECATION")
    private fun end(id: String?, error: String?) {
        if (ending) return
        ending = true
        scheduleSelfExit()
        handler.removeCallbacksAndMessages(null)
        val web = browser
        browser = null
        // Refusal before WebView creation must not initialize/clear the default CookieManager or WebStorage.
        if (web == null) { finishResult(null, error); return }
        if (::content.isInitialized) statusText = getString(R.string.huawei_id_web_clearing)
        web.visibility = View.GONE
        (web.parent as? ViewGroup)?.removeView(web)
        runCatching { web.stopLoading(); web.settings.javaScriptEnabled = false }
        if (!loaded) {
            // Unsupported provider: never call any global storage/cookie/cache helper against an unverified profile.
            runCatching { web.removeAllViews(); web.destroy() }
            finishResult(null, error)
            return
        }
        runCatching { web.clearHistory(); web.clearFormData(); web.clearCache(true) }
        runCatching { WebStorage.getInstance().deleteAllData() }
        runCatching { WebViewDatabase.getInstance(this).apply { clearHttpAuthUsernamePassword(); clearFormData(); clearUsernamePassword() } }
        runCatching { web.removeAllViews(); web.destroy() }
        handler.postDelayed({ finishResult(id, error) }, 600)
        runCatching { CookieManager.getInstance().removeAllCookies { finishResult(id, error) } }
    }

    private fun finishResult(id: String?, error: String?) {
        if (returned) return
        returned = true
        ending = true
        handler.removeCallbacksAndMessages(null)
        scheduleSelfExit()
        val result = Intent().putExtra(EXTRA_REQUEST, request)
        if (HuaweiIdWebPolicy.validId(id)) {
            result.putExtra(EXTRA_ID, id)
            setResult(RESULT_OK, result)
        } else {
            result.putExtra(EXTRA_ERROR, error ?: "CANCELLED")
            setResult(RESULT_CANCELED, result)
        }
        finish()
    }

    override fun onPause() { foreground = false; handler.removeCallbacks(poll); super.onPause() }
    override fun onStop() { if (!ending && !isChangingConfigurations) end(null, "CANCELLED"); super.onStop() }
    override fun onResume() { super.onResume(); foreground = true; if (loaded && !ending) handler.post(poll) }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.clear() }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        val web = browser
        browser = null
        runCatching { web?.stopLoading(); (web?.parent as? ViewGroup)?.removeView(web); web?.destroy() }
        runCatching { owner?.unlinkToDeath(ownerDeath, 0) }
        super.onDestroy()
        scheduleSelfExit()
    }

    private val exitScheduled = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun scheduleSelfExit() {
        // This watchdog is independent of a blocked UI thread/onDestroy and only terminates its own private process.
        if (Build.VERSION.SDK_INT < 28 || Application.getProcessName() != packageName + ":huawei_id") return
        if (!exitScheduled.compareAndSet(false, true)) return
        Thread({
            try { Thread.sleep(1200) } finally { Process.killProcess(Process.myPid()) }
        }, "HuaweiIdOneUseExit").apply { isDaemon = true }.start()
    }

}
