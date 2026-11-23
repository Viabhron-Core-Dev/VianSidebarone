package com.example.feature.htmlviewer

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * Snapshot of security flags enforced on every ephemeral HTML/MHT WebView instance.
 */
data class WebViewSecurityConfig(
    val javaScriptEnabled: Boolean = false,
    val javaScriptCanOpenWindowsAutomatically: Boolean = false,
    val blockNetworkLoads: Boolean = true,
    val blockNetworkImage: Boolean = true,
    val loadsImagesAutomatically: Boolean = false,
    val allowFileAccess: Boolean = false,
    val allowContentAccess: Boolean = false,
    val allowFileAccessFromFileURLs: Boolean = false,
    val allowUniversalAccessFromFileURLs: Boolean = false,
    val domStorageEnabled: Boolean = false,
    val databaseEnabled: Boolean = false,
    val geolocationEnabled: Boolean = false,
    val supportMultipleWindows: Boolean = false,
    val cacheMode: Int = WebSettings.LOAD_NO_CACHE,
    val mixedContentMode: Int = WebSettings.MIXED_CONTENT_NEVER_ALLOW
)

/**
 * Narrow runtime handle around Android's WebView so lifecycle, security policy,
 * and fold/unfold/close destruction can be tested deterministically without an emulator.
 */
interface HtmlWebViewHandle {
    val view: View
    val isDestroyed: Boolean
    val appliedSecurityConfig: WebViewSecurityConfig?
    fun applySecurityRestrictions(
        config: WebViewSecurityConfig,
        onNavigationBlocked: ((String) -> Unit)? = null
    )
    fun loadOfflineHtml(html: String)
    fun onPause() {}
    fun onResume() {}
    fun stopAndDestroy()
}

/**
 * Authoritative security policy for the ephemeral HTML/MHT floating WebView.
 */
object HtmlMhtWebViewSecurity {

    const val BASE_URL = "about:blank"

    val STRICT_OFFLINE_CONFIG = WebViewSecurityConfig()

    /**
     * Blocks all navigation away from the loaded local document.
     * Allows ONLY exact "about:blank" or intra-document fragment jumps ("about:blank#...").
     */
    fun shouldBlockNavigation(url: String?): Boolean {
        if (url.isNullOrBlank()) return true
        val normalized = url.trim()
        if (normalized.equals(BASE_URL, ignoreCase = true)) return false
        if (normalized.lowercase(Locale.US).startsWith("$BASE_URL#")) return false
        return true
    }

    /**
     * Blocks every subresource load (remote images, CSS, JS, fonts, iframes, file://, content://, data:).
     * Returns true for all URLs so no external or embedded resource is fetched.
     */
    fun shouldBlockSubresource(url: String?): Boolean {
        if (url.isNullOrBlank()) return true
        return !url.trim().equals(BASE_URL, ignoreCase = true)
    }

    fun createBlockedEmptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            403,
            "Blocked",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    fun applySettings(settings: WebSettings, config: WebViewSecurityConfig = STRICT_OFFLINE_CONFIG) {
        settings.javaScriptEnabled = config.javaScriptEnabled
        settings.javaScriptCanOpenWindowsAutomatically = config.javaScriptCanOpenWindowsAutomatically
        settings.blockNetworkLoads = config.blockNetworkLoads
        settings.blockNetworkImage = config.blockNetworkImage
        settings.loadsImagesAutomatically = config.loadsImagesAutomatically
        settings.allowFileAccess = config.allowFileAccess
        settings.allowContentAccess = config.allowContentAccess
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = config.allowFileAccessFromFileURLs
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = config.allowUniversalAccessFromFileURLs
        settings.domStorageEnabled = config.domStorageEnabled
        settings.databaseEnabled = config.databaseEnabled
        @Suppress("DEPRECATION")
        settings.setGeolocationEnabled(config.geolocationEnabled)
        settings.setSupportMultipleWindows(config.supportMultipleWindows)
        settings.cacheMode = config.cacheMode
        settings.mixedContentMode = config.mixedContentMode
        settings.setNeedInitialFocus(false)
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.defaultTextEncodingName = "UTF-8"
    }
}

/**
 * Production Android WebView wrapper enforcing strict offline, no-JS, image-free rendering,
 * overlay crash-immunity (no long-press action-mode popups or Activity-token dialogs),
 * pause/resume lifecycle hooks, and complete resource teardown on fold or close.
 */
class RealAndroidWebViewHandle(context: Context) : HtmlWebViewHandle {

    private var webView: WebView? = WebView(context).apply {
        isVerticalScrollBarEnabled = true
        isHorizontalScrollBarEnabled = true
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        setBackgroundColor(Color.WHITE)
        isFocusable = false
        isFocusableInTouchMode = false
        // Disable long-click action-mode popups inside TYPE_APPLICATION_OVERLAY to prevent
        // WindowManager$BadTokenException on strict OEM Android builds without an Activity token.
        isLongClickable = false
        isHapticFeedbackEnabled = false
        setOnLongClickListener { true }
    }

    override val view: View
        get() = checkNotNull(webView) { "WebView has already been destroyed" }

    override var isDestroyed: Boolean = false
        private set

    override var appliedSecurityConfig: WebViewSecurityConfig? = null
        private set

    override fun applySecurityRestrictions(
        config: WebViewSecurityConfig,
        onNavigationBlocked: ((String) -> Unit)?
    ) {
        val wv = webView ?: return
        HtmlMhtWebViewSecurity.applySettings(wv.settings, config)
        appliedSecurityConfig = config

        wv.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                result?.cancel()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                result?.cancel()
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?
            ): Boolean {
                result?.cancel()
                return true
            }

            override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                result?.cancel()
                return true
            }
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val targetUrl = request?.url?.toString()
                val blocked = HtmlMhtWebViewSecurity.shouldBlockNavigation(targetUrl)
                if (blocked) {
                    onNavigationBlocked?.invoke(request?.url?.scheme ?: "unknown")
                }
                return blocked
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val blocked = HtmlMhtWebViewSecurity.shouldBlockNavigation(url)
                if (blocked) {
                    onNavigationBlocked?.invoke(url?.substringBefore(':') ?: "unknown")
                }
                return blocked
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val targetUrl = request?.url?.toString()
                return if (HtmlMhtWebViewSecurity.shouldBlockSubresource(targetUrl)) {
                    HtmlMhtWebViewSecurity.createBlockedEmptyResponse()
                } else {
                    null
                }
            }
        }
    }

    override fun loadOfflineHtml(html: String) {
        val wv = webView ?: return
        wv.loadDataWithBaseURL(
            HtmlMhtWebViewSecurity.BASE_URL,
            html,
            "text/html",
            "UTF-8",
            null
        )
    }

    override fun onPause() {
        if (isDestroyed) return
        try {
            webView?.onPause()
        } catch (_: Throwable) {}
    }

    override fun onResume() {
        if (isDestroyed) return
        try {
            webView?.onResume()
        } catch (_: Throwable) {}
    }

    override fun stopAndDestroy() {
        if (isDestroyed) return
        isDestroyed = true
        val wv = webView ?: return
        webView = null
        try {
            wv.stopLoading()
        } catch (_: Throwable) {}
        try {
            wv.webViewClient = WebViewClient()
            wv.webChromeClient = null
            wv.setOnTouchListener(null)
            wv.setOnLongClickListener(null)
            wv.clearHistory()
            wv.clearFormData()
            wv.clearCache(false)
            wv.onPause()
            wv.removeAllViews()
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        } catch (_: Throwable) {}
    }
}
