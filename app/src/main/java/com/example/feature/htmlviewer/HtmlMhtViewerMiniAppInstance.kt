package com.example.feature.htmlviewer

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.core.LogKeeper
import com.example.core.WindowBounds
import com.example.feature.language.LanguageMiniAppUiHelper
import java.io.File

/**
 * In-memory-only runtime session descriptor for an ephemeral HTML/MHT floating viewer.
 * Never serialized to DurableMiniAppStore or any database.
 */
data class EphemeralHtmlViewerSession(
    val instanceId: String,
    val displayTitle: String,
    val tempHtmlFile: File,
    val formatLabel: String,
    var windowBounds: WindowBounds,
    var buttonPosition: Pair<Int, Int>,
    var isFolded: Boolean = false
)

/**
 * Main-process transient runtime for viewing a single local HTML/HTM/MHT/MHTML document inside
 * Sidebar's existing floating window shell.
 *
 * Lifecycle guarantees:
 * - Bounded viewport: WebView fills the floating container with MATCH_PARENT and scrolls vertically.
 * - Fold: Stops loading, destroys WebView, removes view tree & callbacks, retains ONLY the minimal
 *   temporary cache file reference in [session].
 * - Unfold: Recreates a single fresh WebView, reapplies all security restrictions, reloads from
 *   the temporary cache file without creating duplicate WebViews.
 * - Close: Destroys WebView, releases all references, and deletes the temporary cache file.
 * - Zero durable storage, zero Heavy process involvement, zero document history.
 */
class HtmlMhtViewerMiniAppInstance(
    val instanceId: String,
    val session: EphemeralHtmlViewerSession,
    private var onInitializationFailure: ((reasonCode: String, userMessage: String) -> Unit)? = null
) {
    private var rootContainer: FrameLayout? = null
    private var webViewHandle: HtmlWebViewHandle? = null
    private var errorTextView: TextView? = null
    private var hostContext: Context? = null

    var isReady: Boolean = false
        private set

    var lastErrorCode: String? = null
        private set

    var lastErrorMessage: String? = null
        private set

    var webViewCreateCount: Int = 0
        private set

    var webViewDestroyCount: Int = 0
        private set

    fun hasLiveWebView(): Boolean {
        val handle = webViewHandle ?: return false
        return !handle.isDestroyed
    }

    internal fun getWebViewHandleForTesting(): HtmlWebViewHandle? = webViewHandle

    fun createContentView(context: Context): View {
        hostContext = context.applicationContext
        val container = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            tag = TAG_VIEWPORT_CONTAINER
        }
        rootContainer = container
        mountWebViewAndLoad(context)
        return container
    }

    /**
     * Releases the live WebView and clears all child views/callbacks when the floating viewer folds.
     * Retains ONLY [session] (containing the minimal temporary cache file reference) for unfold.
     */
    fun releaseWebViewForFold() {
        val ctx = hostContext
        destroyCurrentWebViewOnly()
        rootContainer?.removeAllViews()
        errorTextView = null
        isReady = false
        if (ctx != null) {
            LogKeeper.log(ctx, TAG, "HTML_VIEWER_FOLD_RELEASED id=$instanceId destroys=$webViewDestroyCount")
        }
    }

    /**
     * Recreates the WebView after unfolding, reapplying all security restrictions and reloading
     * the local HTML document from the temporary cache file without creating duplicate WebViews.
     */
    fun recreateWebViewAfterUnfold(context: Context): Boolean {
        hostContext = context.applicationContext
        val container = rootContainer ?: return false
        // Guarantee no duplicate WebView can ever remain alive across repeated fold/unfold calls
        destroyCurrentWebViewOnly()
        container.removeAllViews()
        errorTextView = null
        return mountWebViewAndLoad(context)
    }

    /**
     * Permanently tears down the viewer runtime and optionally deletes the temporary cache file.
     */
    fun destroyAndRelease(deleteTempFile: Boolean) {
        val ctx = hostContext
        destroyCurrentWebViewOnly()
        rootContainer?.removeAllViews()
        rootContainer = null
        errorTextView = null
        onInitializationFailure = null
        isReady = false
        if (deleteTempFile) {
            HtmlMhtCacheManager.deleteTempFile(session.tempHtmlFile)
        }
        hostContext = null
        if (ctx != null) {
            LogKeeper.log(
                ctx,
                TAG,
                "HTML_VIEWER_DESTROYED id=$instanceId deletedTemp=$deleteTempFile destroys=$webViewDestroyCount"
            )
        }
    }

    fun onPause() {
        webViewHandle?.onPause()
    }

    fun onResume() {
        webViewHandle?.onResume()
    }

    fun applyThemeRefresh(context: Context) {
        val err = errorTextView ?: return
        val palette = LanguageMiniAppUiHelper.resolvePalette(context)
        rootContainer?.setBackgroundColor(palette.surfaceBg)
        err.setTextColor(palette.textPrimary)
    }

    private fun mountWebViewAndLoad(context: Context): Boolean {
        val container = rootContainer ?: return false
        val html = HtmlMhtCacheManager.readTempHtml(context, session.tempHtmlFile)
        if (html == null) {
            showErrorState(
                context = context,
                container = container,
                reasonCode = "TEMP_FILE_UNAVAILABLE",
                userMessage = "Temporary document cache is no longer available."
            )
            return false
        }

        val handle = try {
            val factory = testWebViewFactory ?: { ctx -> RealAndroidWebViewHandle(ctx) }
            factory(context)
        } catch (t: Throwable) {
            LogKeeper.logError(
                context,
                TAG,
                "WEBVIEW_INIT_FAILED id=$instanceId type=${t.javaClass.simpleName}",
                t
            )
            showErrorState(
                context = context,
                container = container,
                reasonCode = "WEBVIEW_INIT_FAILED",
                userMessage = "Unable to initialize Android WebView to render this document."
            )
            return false
        }

        return try {
            webViewCreateCount++
            webViewHandle = handle
            handle.applySecurityRestrictions(
                config = HtmlMhtWebViewSecurity.STRICT_OFFLINE_CONFIG,
                onNavigationBlocked = { scheme ->
                    LogKeeper.log(context, TAG, "HTML_VIEWER_NAV_BLOCKED id=$instanceId scheme=$scheme")
                }
            )
            val webViewNode = handle.view
            webViewNode.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            container.removeAllViews()
            container.addView(webViewNode)
            handle.loadOfflineHtml(html)
            lastErrorCode = null
            lastErrorMessage = null
            isReady = true
            LogKeeper.log(
                context,
                TAG,
                "HTML_VIEWER_MOUNTED id=$instanceId format=${session.formatLabel} creates=$webViewCreateCount"
            )
            true
        } catch (t: Throwable) {
            destroyCurrentWebViewOnly()
            LogKeeper.logError(
                context,
                TAG,
                "WEBVIEW_LOAD_FAILED id=$instanceId type=${t.javaClass.simpleName}",
                t
            )
            showErrorState(
                context = context,
                container = container,
                reasonCode = "WEBVIEW_LOAD_FAILED",
                userMessage = "Unable to render this document in the floating viewer."
            )
            false
        }
    }

    private fun destroyCurrentWebViewOnly() {
        val current = webViewHandle ?: return
        webViewHandle = null
        if (!current.isDestroyed) {
            current.stopAndDestroy()
            webViewDestroyCount++
        }
    }

    private fun showErrorState(
        context: Context,
        container: FrameLayout,
        reasonCode: String,
        userMessage: String
    ) {
        isReady = false
        lastErrorCode = reasonCode
        lastErrorMessage = userMessage
        val palette = LanguageMiniAppUiHelper.resolvePalette(context)
        val density = context.resources.displayMetrics.density
        container.removeAllViews()
        container.setBackgroundColor(palette.surfaceBg)

        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val pad = (20 * density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val title = TextView(context).apply {
            text = "Unable to display document"
            setTextColor(palette.dangerText)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val body = TextView(context).apply {
            text = userMessage
            setTextColor(palette.textPrimary)
            textSize = 13f
            gravity = Gravity.CENTER
            tag = TAG_ERROR_MESSAGE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * density).toInt()
            }
        }
        errorTextView = body
        wrapper.addView(title)
        wrapper.addView(body)
        container.addView(wrapper)
        onInitializationFailure?.invoke(reasonCode, userMessage)
    }

    companion object {
        private const val TAG = "HtmlMhtViewerInstance"
        const val APP_TYPE = "html_mht_viewer"
        const val TAG_VIEWPORT_CONTAINER = "html_mht_viewer_viewport"
        const val TAG_ERROR_MESSAGE = "html_mht_viewer_error"

        @Volatile
        internal var testWebViewFactory: ((Context) -> HtmlWebViewHandle)? = null

        fun computeDefaultViewerBounds(
            context: Context,
            screenWidth: Int,
            screenHeight: Int,
            staggerSlot: Int = 0
        ): WindowBounds {
            val density = context.resources.displayMetrics.density
            val safeScreenW = if (screenWidth > 0) screenWidth else (360 * density).toInt()
            val safeScreenH = if (screenHeight > 0) screenHeight else (640 * density).toInt()

            val targetW = (safeScreenW * 0.88f).toInt()
                .coerceIn((300 * density).toInt(), (460 * density).toInt())
                .coerceAtMost(maxOf(240, safeScreenW - (16 * density).toInt()))
            val targetH = (safeScreenH * 0.66f).toInt()
                .coerceIn((360 * density).toInt(), (680 * density).toInt())
                .coerceAtMost(maxOf(280, safeScreenH - (96 * density).toInt()))

            val stepPx = (24 * density).toInt().coerceAtLeast(12)
            val baseX = ((safeScreenW - targetW) / 2).coerceAtLeast((8 * density).toInt())
            val baseY = (72 * density).toInt()
            val x = (baseX + (staggerSlot % 5) * stepPx).coerceIn(0, maxOf(0, safeScreenW - targetW))
            val y = (baseY + (staggerSlot % 5) * stepPx).coerceIn(0, maxOf(0, safeScreenH - targetH))
            return WindowBounds(x, y, targetW, targetH)
        }
    }
}
