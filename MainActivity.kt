package com.shadow.store

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * Shadow Store: customer app. Shows the site's public pages from the same server.
 * The site already refreshes itself in-page (assets/js/live.js polls ?r=api/live), so admin changes
 * reach the app without reinstalling. Admin routes are never opened here; the server enforces permissions anyway.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var errorView: View

    private val baseUri: Uri = Uri.parse(BuildConfig.SERVER_URL)
    private var failed = false
    private var lastPausedAt = 0L

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: PermissionRequest? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        val uris: Array<Uri>? = when {
            res.resultCode != RESULT_OK || data == null -> null
            data.clipData != null -> Array(data.clipData!!.itemCount) { i -> data.clipData!!.getItemAt(i).uri }
            data.data != null -> arrayOf(data.data!!)
            else -> null
        }
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val req = pendingPermission
        pendingPermission = null
        if (req != null) {
            if (granted) req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else req.deny()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        swipe = findViewById(R.id.swipe)
        progress = findViewById(R.id.progress)
        errorView = findViewById(R.id.errorView)
        findViewById<Button>(R.id.retryButton).setOnClickListener { retry() }

        swipe.setColorSchemeResources(R.color.shadow_accent)
        swipe.setOnRefreshListener { webView.reload() }

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, false)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = false
            userAgentString = userAgentString + " ShadowStoreApp/" + BuildConfig.VERSION_NAME
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return handleUrl(request.url)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                failed = false
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                swipe.isRefreshing = false
                progress.visibility = View.GONE
                errorView.visibility = if (failed) View.VISIBLE else View.GONE
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                // Only a failed main page counts; a missing image or script must not blank the screen.
                if (request.isForMainFrame) {
                    failed = true
                    errorView.visibility = View.VISIBLE
                }
            }
            // TLS errors: the default (cancel) is kept on purpose; we never call handler.proceed().
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    filePicker.launch(params.createIntent())
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                // Microphone only, only for our own origin (the site's voice assistant).
                val fromSite = request.origin.host == baseUri.host
                val wantsMic = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (!fromSite || !wantsMic) { request.deny(); return }
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                } else {
                    pendingPermission?.deny()
                    pendingPermission = request
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack() && errorView.visibility != View.VISIBLE) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (savedInstanceState != null) webView.restoreState(savedInstanceState) else webView.loadUrl(BuildConfig.SERVER_URL)
    }

    /** Returns true when the app handled the URL (so the WebView must not load it itself). */
    private fun handleUrl(uri: Uri): Boolean {
        val scheme = uri.scheme ?: return true
        if (scheme == "https" && uri.host == baseUri.host) {
            if (isAdminRoute(uri)) {
                Toast.makeText(this, R.string.blocked_admin, Toast.LENGTH_SHORT).show()
                return true
            }
            return false // normal site page: load inside the app
        }
        // tel:, mailto:, WhatsApp, maps, other sites... go to the proper external app
        if (scheme == "https" || scheme == "tel" || scheme == "mailto" || scheme == "sms" || scheme == "geo") {
            try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (e: ActivityNotFoundException) { /* no handler */ }
        }
        return true // http (cleartext) and unknown schemes are dropped
    }

    private fun isAdminRoute(uri: Uri): Boolean {
        val r = (uri.getQueryParameter("r") ?: "").trim('/').lowercase()
        if (r == "admin" || r.startsWith("admin/") || r == "setup") return true
        if (uri.getQueryParameter("staff") != null) return true
        val path = (uri.path ?: "").trim('/').lowercase()
        return path == "admin" || path.endsWith("/admin") || path.contains("/admin/") || path.startsWith("admin/")
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetwork != null
    }

    private fun retry() {
        errorView.visibility = View.GONE
        failed = false
        if (webView.url.isNullOrEmpty() || webView.url == "about:blank") webView.loadUrl(BuildConfig.SERVER_URL) else webView.reload()
    }

    override fun onPause() {
        super.onPause()
        lastPausedAt = SystemClock.elapsedRealtime()
        webView.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        // Timers are throttled while the app is in the background; after a long absence reload to catch up with the server.
        if (lastPausedAt != 0L && SystemClock.elapsedRealtime() - lastPausedAt > 60_000 && isOnline()) {
            webView.reload()
        }
        lastPausedAt = 0L
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        webView.destroy()
        super.onDestroy()
    }
}
