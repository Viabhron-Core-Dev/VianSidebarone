package com.example.feature.pwa.floating

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.core.LogKeeper
import com.example.core.ipc.HeavyEvent
import com.example.core.ipc.HeavyEventType
import com.example.core.ipc.HeavyProcessHost
import com.example.data.AppDatabase
import com.example.data.PwaEntity
import com.example.feature.floating.EmbeddedImeBridge
import com.example.feature.floating.MiniAppInstance
import com.example.feature.floating.MiniAppLifecycleState
import com.example.feature.floating.MiniAppRecoverySnapshot
import com.example.feature.pwa.PwaPermissionManager
import com.example.feature.pwa.PwaPermissionPolicy
import com.example.feature.pwa.PwaStorageManager
import com.example.feature.reader.ReaderDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.net.URLConnection

/**
 * Pure URL & security-policy helpers for [FloatingPwaMiniAppInstance].
 */
object FloatingPwaUrlHelper {

    val STATIC_EXTENSIONS = setOf(
        "js", "mjs", "css", "json", "webmanifest",
        "png", "jpg", "jpeg", "gif", "svg", "webp", "ico",
        "wasm", "woff", "woff2", "ttf", "otf",
        "mp3", "mp4", "ogg", "wav", "webm", "map"
    )

    fun normalizeInputUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "about:blank"
        val lower = trimmed.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower == "about:blank") {
            return trimmed
        }
        return "https://$trimmed"
    }

    fun buildVirtualOriginUrl(pwaId: String, startUrl: String): String {
        val cleanStart = startUrl.trim().removePrefix("/").removePrefix("./")
        return "https://pwa-$pwaId.local/$cleanStart"
    }

    fun isVirtualOrLocalHost(host: String?, virtualHost: String?): Boolean {
        val cleanHost = host?.lowercase()?.trim() ?: return false
        if (!virtualHost.isNullOrEmpty() && cleanHost == virtualHost.lowercase()) return true
        return cleanHost.endsWith(".local") || cleanHost == "localhost" || cleanHost == "127.0.0.1"
    }

    fun shouldAllowNetworkRequest(
        hasActivePwa: Boolean,
        policy: PwaPermissionPolicy,
        scheme: String?,
        host: String?,
        virtualHost: String?
    ): Boolean {
        if (!hasActivePwa) return true
        val s = scheme?.lowercase() ?: ""
        if (s == "file" || s == "data" || s == "blob" || s == "about") return true
        if ((s == "https" || s == "http") && isVirtualOrLocalHost(host, virtualHost)) {
            return true
        }
        return policy.internetAllowed
    }

    fun resolveSafeLocalFile(webRoot: File, requestPath: String): File? {
        return try {
            val clean = requestPath.removePrefix("/")
            val candidate = File(webRoot, clean)
            val canonicalRoot = webRoot.canonicalPath
            val canonicalCandidate = candidate.canonicalPath
            if (canonicalCandidate == canonicalRoot || canonicalCandidate.startsWith(canonicalRoot + File.separator)) {
                candidate
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun guessMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "html", "htm" -> "text/html"
            "js", "mjs" -> "application/javascript"
            "css" -> "text/css"
            "json", "webmanifest" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "wasm" -> "application/wasm"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> URLConnection.guessContentTypeFromName(fileName) ?: "application/octet-stream"
        }
    }
}

/**
 * Embedded WebView subclass for SurfaceControlViewHost in :heavy.
 * Reports focus/InputConnection activation to Main's [EmbeddedImeBridge] so web text inputs can open the IME.
 */
class EmbeddedPwaWebView(
    context: Context,
    private val instanceIdProvider: () -> String?
) : WebView(context) {

    private var activeImeSessionId: String = ""

    override fun hasWindowFocus(): Boolean {
        return isAttachedToWindow || super.hasWindowFocus()
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        val result = super.onTouchEvent(event)
        if (event?.actionMasked == MotionEvent.ACTION_UP) {
            requestFocus()
        }
        return result
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo?): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs)
        if (ic != null) {
            activeImeSessionId = EmbeddedImeBridge.notifyFocus(
                context = context,
                instanceId = instanceIdProvider(),
                text = "",
                selStart = 0,
                selEnd = 0
            )
        }
        return ic
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        if (!focused && activeImeSessionId.isNotEmpty()) {
            val sess = activeImeSessionId
            activeImeSessionId = ""
            EmbeddedImeBridge.notifyBlur(context, instanceIdProvider(), sess)
        }
    }

    fun clearBridgeFocus() {
        clearFocus()
        if (activeImeSessionId.isNotEmpty()) {
            val sess = activeImeSessionId
            activeImeSessionId = ""
            EmbeddedImeBridge.notifyBlur(context, instanceIdProvider(), sess)
        }
    }
}

/**
 * FloatingPwaMiniAppInstance: Isolated WebView-based Floating PWA Loader hosted in :heavy.
 * Preserves full isolation from full-screen [com.example.feature.pwa.PwaPlayerActivity]
 * while reusing imported PWAs ([PwaEntity]), [PwaPermissionManager], and [PwaStorageManager].
 */
class FloatingPwaMiniAppInstance(
    override val instanceId: String,
    override val appType: String = APP_TYPE
) : MiniAppInstance {

    companion object {
        const val APP_TYPE = "floating_pwa"
        private const val TAG = "FloatingPwaMiniApp"

        const val KEY_PWA_ID = "pwa_id"
        const val KEY_CURRENT_URL = "current_url"
        const val KEY_CURRENT_TITLE = "current_title"

        // Events :heavy -> Main
        const val ACTION_PWA_STATE_CHANGED = "pwa_state_changed"
        const val ACTION_PWA_FILE_CHOOSER_REQUEST = "pwa_file_chooser_request"
        const val ACTION_PWA_PERMISSION_REQUEST = "pwa_permission_request"

        // Commands Main -> :heavy
        const val CMD_PWA_SELECT = "pwa_select"
        const val CMD_PWA_LOAD_URL = "pwa_load_url"
        const val CMD_PWA_BACK = "pwa_back"
        const val CMD_PWA_FORWARD = "pwa_forward"
        const val CMD_PWA_RELOAD = "pwa_reload"
        const val CMD_PWA_STOP = "pwa_stop"
        const val CMD_PWA_FILE_RESULT = "pwa_file_result"
        const val CMD_PWA_PERMISSION_RESULT = "pwa_permission_result"
    }

    override var lifecycleState: MiniAppLifecycleState = MiniAppLifecycleState.UNINITIALIZED
        private set

    private val instanceScope = CoroutineScope(SupervisorJob() + ReaderDispatchers.main)

    private var activePwaId: String = ""
    private var activePwaEntity: PwaEntity? = null
    private var permissionPolicy: PwaPermissionPolicy = PwaPermissionPolicy()
    private var currentUrl: String = ""
    private var currentTitle: String = "Floating PWA"
    private var currentProgress: Int = 100
    private var isLoading: Boolean = false
    private var canGoBackState: Boolean = false
    private var canGoForwardState: Boolean = false

    private var rootContainer: FrameLayout? = null
    private var emptyPlaceholderLayout: LinearLayout? = null
    private var webView: EmbeddedPwaWebView? = null

    // Pending SAF / Permission callbacks coordinated via Main relay Activity
    private var pendingFileRequestId: String? = null
    private var pendingFileChooserCallback: ValueCallback<Array<Uri>>? = null

    private var pendingMediaRequestId: String? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private var pendingGrantedMediaResources: MutableList<String> = mutableListOf()

    private var pendingLocationRequestId: String? = null
    private var pendingGeolocationCallback: GeolocationPermissions.Callback? = null
    private var pendingGeolocationOrigin: String? = null

    override fun onCreate(savedSnapshot: MiniAppRecoverySnapshot?) {
        lifecycleState = MiniAppLifecycleState.CREATED
        savedSnapshot?.let { restoreFromSnapshot(it) }
    }

    override fun onStart() {
        lifecycleState = MiniAppLifecycleState.STARTED
    }

    override fun onResume() {
        lifecycleState = MiniAppLifecycleState.RESUMED
        try {
            webView?.onResume()
        } catch (_: Throwable) {}
    }

    override fun onPause() {
        lifecycleState = MiniAppLifecycleState.PAUSED
        try {
            webView?.onPause()
        } catch (_: Throwable) {}
    }

    override fun onStop() {
        lifecycleState = MiniAppLifecycleState.STOPPED
    }

    override fun onDestroy() {
        lifecycleState = MiniAppLifecycleState.DESTROYED
        releaseRuntime()
        instanceScope.cancel()
    }

    override fun prepareForSleep(): Map<String, String> {
        lifecycleState = MiniAppLifecycleState.SLEEPING
        return buildStatePayload()
    }

    override fun onSaveState(): Map<String, String> {
        return buildStatePayload()
    }

    private fun buildStatePayload(): Map<String, String> {
        val urlToSave = try {
            webView?.url?.takeIf { it.isNotBlank() && it != "about:blank" } ?: currentUrl
        } catch (_: Throwable) {
            currentUrl
        }
        val titleToSave = try {
            webView?.title?.takeIf { it.isNotBlank() } ?: currentTitle
        } catch (_: Throwable) {
            currentTitle
        }
        val payload = LinkedHashMap<String, String>()
        payload[KEY_PWA_ID] = activePwaId
        payload[KEY_CURRENT_URL] = urlToSave
        payload[KEY_CURRENT_TITLE] = titleToSave
        payload["app_type"] = appType
        payload["timestamp"] = System.currentTimeMillis().toString()
        return payload
    }

    override fun restoreFromSnapshot(snapshot: MiniAppRecoverySnapshot) {
        val payload = snapshot.payload
        activePwaId = payload[KEY_PWA_ID]?.trim() ?: ""
        currentUrl = payload[KEY_CURRENT_URL]?.trim() ?: ""
        currentTitle = payload[KEY_CURRENT_TITLE]?.takeIf { it.isNotBlank() } ?: "Floating PWA"

        val container = rootContainer
        if (container != null) {
            loadInitialContent(container.context)
        }
    }

    override fun createContentView(context: Context): View {
        val container = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#181825"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootContainer = container

        val placeholder = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#181825"))
            setPadding(36, 36, 36, 64)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            addView(TextView(context).apply {
                text = "Floating PWA Loader"
                textSize = 16f
                setTextColor(Color.parseColor("#CDD6F4"))
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = "Use the toolbar above to choose an imported Offline PWA or enter a web URL."
                textSize = 12f
                setTextColor(Color.parseColor("#A6ADC8"))
                gravity = Gravity.CENTER
                setPadding(16, 12, 16, 0)
            })
        }
        emptyPlaceholderLayout = placeholder
        container.addView(placeholder)

        loadInitialContent(context)
        return container
    }

    private fun loadInitialContent(context: Context) {
        if (activePwaId.isNotEmpty()) {
            selectPwa(context, activePwaId, resumeUrl = currentUrl.takeIf { it.isNotBlank() && it != "about:blank" })
        } else if (currentUrl.isNotBlank() && currentUrl != "about:blank") {
            loadWebUrl(context, currentUrl, manualEntry = false)
        } else {
            emptyPlaceholderLayout?.visibility = View.VISIBLE
            emitStateEvent(context)
        }
    }

    fun handleCommand(context: Context, action: String, payload: Map<String, String>): Boolean {
        return when (action) {
            CMD_PWA_SELECT -> {
                val pwaId = payload[KEY_PWA_ID]?.trim() ?: return false
                selectPwa(context, pwaId, resumeUrl = null)
                true
            }
            CMD_PWA_LOAD_URL -> {
                val rawUrl = payload[KEY_CURRENT_URL] ?: payload["url"] ?: return false
                val manualEntry = payload["manual_entry"]?.toBooleanStrictOrNull() ?: true
                loadWebUrl(context, rawUrl, manualEntry)
                true
            }
            CMD_PWA_BACK -> {
                instanceScope.launch {
                    val wv = webView ?: return@launch
                    if (wv.canGoBack()) {
                        wv.goBack()
                    }
                    syncNavigationFlags(context)
                }
                true
            }
            CMD_PWA_FORWARD -> {
                instanceScope.launch {
                    val wv = webView ?: return@launch
                    if (wv.canGoForward()) {
                        wv.goForward()
                    }
                    syncNavigationFlags(context)
                }
                true
            }
            CMD_PWA_RELOAD -> {
                instanceScope.launch {
                    webView?.reload()
                }
                true
            }
            CMD_PWA_STOP -> {
                instanceScope.launch {
                    webView?.stopLoading()
                    isLoading = false
                    currentProgress = 100
                    emitStateEvent(context)
                }
                true
            }
            CMD_PWA_FILE_RESULT -> {
                val reqId = payload["requestId"]
                val cancelled = payload["cancelled"]?.toBooleanStrictOrNull() ?: false
                val rawUris = payload["uris"] ?: ""
                instanceScope.launch {
                    deliverFileChooserResult(context, reqId, cancelled, rawUris)
                }
                true
            }
            CMD_PWA_PERMISSION_RESULT -> {
                val reqId = payload["requestId"]
                val cap = payload["cap"] ?: ""
                val cameraGranted = payload["cameraGranted"]?.toBooleanStrictOrNull() ?: false
                val micGranted = payload["micGranted"]?.toBooleanStrictOrNull() ?: false
                val locationGranted = payload["locationGranted"]?.toBooleanStrictOrNull() ?: false
                instanceScope.launch {
                    deliverPermissionResult(context, reqId, cap, cameraGranted, micGranted, locationGranted)
                }
                true
            }
            else -> false
        }
    }

    private fun selectPwa(context: Context, pwaId: String, resumeUrl: String?) {
        activePwaId = pwaId
        instanceScope.launch {
            val entity = withContext(Dispatchers.IO) {
                try {
                    val dao = AppDatabase.getDatabase(context).pwaDao()
                    val found = dao.getById(pwaId)
                    if (found != null) {
                        dao.updateLastLaunched(pwaId, System.currentTimeMillis())
                    }
                    found
                } catch (t: Throwable) {
                    LogKeeper.logError(context, TAG, "Failed loading PWA id=$pwaId", t)
                    null
                }
            }

            if (entity == null) {
                activePwaEntity = null
                activePwaId = ""
                try {
                    Toast.makeText(context, "PWA not found", Toast.LENGTH_SHORT).show()
                } catch (_: Throwable) {}
                emitStateEvent(context)
                return@launch
            }

            activePwaEntity = entity
            permissionPolicy = PwaPermissionManager.getPolicy(context, entity.id)
            currentTitle = entity.name
            val virtualHost = "pwa-${entity.id}.local"
            val targetUrl = if (!resumeUrl.isNullOrBlank() &&
                (resumeUrl.contains(virtualHost) || FloatingPwaUrlHelper.shouldAllowNetworkRequest(
                    hasActivePwa = true,
                    policy = permissionPolicy,
                    scheme = Uri.parse(resumeUrl).scheme,
                    host = Uri.parse(resumeUrl).host,
                    virtualHost = virtualHost
                ))
            ) {
                resumeUrl
            } else {
                FloatingPwaUrlHelper.buildVirtualOriginUrl(entity.id, entity.startUrl)
            }

            val wv = ensureWebView(context)
            applyPolicyToWebView(context, wv, entity)
            emptyPlaceholderLayout?.visibility = View.GONE
            wv.visibility = View.VISIBLE
            currentUrl = targetUrl
            isLoading = true
            currentProgress = 10
            emitStateEvent(context)
            wv.loadUrl(targetUrl)
        }
    }

    private fun loadWebUrl(context: Context, rawUrl: String, manualEntry: Boolean) {
        val normalized = FloatingPwaUrlHelper.normalizeInputUrl(rawUrl)
        instanceScope.launch {
            val uri = try { Uri.parse(normalized) } catch (_: Throwable) { null }
            val currentEntity = activePwaEntity
            val virtualHost = currentEntity?.let { "pwa-${it.id}.local" }

            // If manually entering an external URL while an offline PWA without .local host is active,
            // switch into standard floating web mode so the user can load external PWAs cleanly.
            if (manualEntry && !FloatingPwaUrlHelper.isVirtualOrLocalHost(uri?.host, virtualHost)) {
                activePwaEntity = null
                activePwaId = ""
                permissionPolicy = PwaPermissionPolicy(internetAllowed = true, filesAllowed = true, soundAllowed = true)
            }

            val wv = ensureWebView(context)
            applyPolicyToWebView(context, wv, activePwaEntity)
            emptyPlaceholderLayout?.visibility = View.GONE
            wv.visibility = View.VISIBLE
            currentUrl = normalized
            isLoading = true
            currentProgress = 10
            emitStateEvent(context)
            wv.loadUrl(normalized)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(context: Context): EmbeddedPwaWebView {
        webView?.let { return it }
        val container = rootContainer
        val wv = EmbeddedPwaWebView(context) { instanceId }.apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFocusable = true
            isFocusableInTouchMode = true
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
                displayZoomControls = false
                builtInZoomControls = true
                setSupportZoom(true)
            }
        }
        configureWebClients(context, wv)
        webView = wv
        container?.addView(wv)
        return wv
    }

    private fun applyPolicyToWebView(context: Context, wv: WebView, entity: PwaEntity?) {
        if (entity != null) {
            try {
                wv.setBackgroundColor(Color.parseColor(entity.backgroundColor))
            } catch (_: Throwable) {
                wv.setBackgroundColor(Color.parseColor("#181825"))
            }
            wv.settings.apply {
                mediaPlaybackRequiresUserGesture = !permissionPolicy.soundAllowed
                setGeolocationEnabled(permissionPolicy.locationAllowed)
                val isolatedDataDir = PwaStorageManager.getIsolatedDataDir(context, entity.id)
                try {
                    @Suppress("DEPRECATION")
                    setDatabasePath(isolatedDataDir.absolutePath)
                } catch (_: Throwable) {}
            }
        } else {
            wv.setBackgroundColor(Color.parseColor("#181825"))
            wv.settings.apply {
                mediaPlaybackRequiresUserGesture = false
                setGeolocationEnabled(true)
            }
        }
    }

    private fun configureWebClients(context: Context, wv: EmbeddedPwaWebView) {
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                currentProgress = newProgress
                isLoading = newProgress < 100
                syncNavigationFlags(context)
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) {
                    currentTitle = title
                }
                emitStateEvent(context)
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage != null && consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    LogKeeper.log(context, TAG, "JS Console [ERROR] L${consoleMessage.lineNumber()}: ${consoleMessage.message()}")
                }
                return super.onConsoleMessage(consoleMessage)
            }

            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                cancelPendingFileChooser("superseded")
                val entity = activePwaEntity
                if (entity != null && !permissionPolicy.filesAllowed) {
                    LogKeeper.log(context, TAG, "PWA_FILE_CHOOSER_BLOCKED id=${entity.id}")
                    try { filePathCallback?.onReceiveValue(null) } catch (_: Throwable) {}
                    return true
                }
                if (filePathCallback == null) return false

                val reqId = "file_${System.currentTimeMillis()}"
                pendingFileRequestId = reqId
                pendingFileChooserCallback = filePathCallback

                val isMultiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                val acceptJoined = fileChooserParams?.acceptTypes?.joinToString(",") ?: "*/*"
                sendEvent(
                    context,
                    ACTION_PWA_FILE_CHOOSER_REQUEST,
                    mapOf(
                        "requestId" to reqId,
                        "pwaId" to (entity?.id ?: ""),
                        "isMultiple" to isMultiple.toString(),
                        "acceptTypes" to acceptJoined
                    )
                )
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                cancelPendingMediaPermission("superseded")

                val resources = request.resources ?: emptyArray()
                val wantsCamera = resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                val wantsMic = resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                val entity = activePwaEntity

                val granted = mutableListOf<String>()
                val needed = mutableListOf<String>()

                if (wantsCamera && (entity == null || permissionPolicy.cameraAllowed)) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        granted.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                    } else {
                        needed.add(Manifest.permission.CAMERA)
                    }
                }
                if (wantsMic && (entity == null || permissionPolicy.microphoneAllowed)) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        granted.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    } else {
                        needed.add(Manifest.permission.RECORD_AUDIO)
                    }
                }

                if (needed.isNotEmpty()) {
                    val reqId = "media_${System.currentTimeMillis()}"
                    pendingMediaRequestId = reqId
                    pendingPermissionRequest = request
                    pendingGrantedMediaResources = granted
                    sendEvent(
                        context,
                        ACTION_PWA_PERMISSION_REQUEST,
                        mapOf(
                            "requestId" to reqId,
                            "cap" to "media",
                            "pwaId" to (entity?.id ?: ""),
                            "wantsCamera" to needed.contains(Manifest.permission.CAMERA).toString(),
                            "wantsMic" to needed.contains(Manifest.permission.RECORD_AUDIO).toString()
                        )
                    )
                } else if (granted.isNotEmpty()) {
                    try { request.grant(granted.toTypedArray()) } catch (_: Throwable) {}
                } else {
                    try { request.deny() } catch (_: Throwable) {}
                }
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                if (callback == null) return
                cancelPendingGeolocation("superseded")
                val entity = activePwaEntity
                if (entity != null && !permissionPolicy.locationAllowed) {
                    try { callback.invoke(origin, false, false) } catch (_: Throwable) {}
                    return
                }
                val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (hasFine || hasCoarse) {
                    try { callback.invoke(origin, true, false) } catch (_: Throwable) {}
                } else {
                    val reqId = "loc_${System.currentTimeMillis()}"
                    pendingLocationRequestId = reqId
                    pendingGeolocationCallback = callback
                    pendingGeolocationOrigin = origin
                    sendEvent(
                        context,
                        ACTION_PWA_PERMISSION_REQUEST,
                        mapOf(
                            "requestId" to reqId,
                            "cap" to "location",
                            "pwaId" to (entity?.id ?: "")
                        )
                    )
                }
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                currentUrl = url ?: currentUrl
                isLoading = true
                val entity = activePwaEntity
                if (entity != null && !permissionPolicy.soundAllowed) {
                    injectAudioSuppressionScript(view)
                }
                syncNavigationFlags(context)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                currentUrl = url ?: currentUrl
                currentTitle = view?.title?.takeIf { it.isNotBlank() } ?: activePwaEntity?.name ?: currentTitle
                isLoading = false
                currentProgress = 100
                val entity = activePwaEntity
                if (entity != null && !permissionPolicy.soundAllowed) {
                    injectAudioSuppressionScript(view)
                }
                syncNavigationFlags(context)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (!url.isNullOrBlank()) currentUrl = url
                syncNavigationFlags(context)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                val entity = activePwaEntity ?: return false
                val virtualHost = "pwa-${entity.id}.local"
                val allowed = FloatingPwaUrlHelper.shouldAllowNetworkRequest(
                    hasActivePwa = true,
                    policy = permissionPolicy,
                    scheme = url.scheme,
                    host = url.host,
                    virtualHost = virtualHost
                )
                if (!allowed) {
                    LogKeeper.log(context, TAG, "PWA_BLOCKED_NAV id=${entity.id} url=$url")
                    return true
                }
                return false
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                LogKeeper.log(context, TAG, "WebView error code=${error?.errorCode} desc=${error?.description} url=${request?.url}")
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url ?: return null
                val entity = activePwaEntity ?: return super.shouldInterceptRequest(view, request)
                val virtualHost = "pwa-${entity.id}.local"
                val scheme = url.scheme?.lowercase() ?: ""
                val host = url.host?.lowercase() ?: ""

                if ((scheme == "https" || scheme == "http") && FloatingPwaUrlHelper.isVirtualOrLocalHost(host, virtualHost)) {
                    return handleLocalAssetRequest(File(entity.unpackedPath), url.path ?: "/")
                }
                if (scheme == "file") {
                    return handleLocalAssetRequest(File(entity.unpackedPath), url.path ?: "/")
                }
                if (!permissionPolicy.internetAllowed) {
                    return createTextResponse(403, "Forbidden", "External network access disabled for this Mini App")
                }
                return super.shouldInterceptRequest(view, request)
            }
        }
    }

    private fun handleLocalAssetRequest(webRoot: File, requestPath: String): WebResourceResponse {
        val cleanPath = requestPath.removePrefix("/")
        val localFile = FloatingPwaUrlHelper.resolveSafeLocalFile(webRoot, cleanPath)
        if (localFile != null && localFile.exists() && localFile.isFile) {
            return try {
                val mime = FloatingPwaUrlHelper.guessMimeType(localFile.name)
                createStreamResponse(mime, FileInputStream(localFile))
            } catch (e: Exception) {
                createTextResponse(404, "Not Found", "Failed reading asset: ${e.message}")
            }
        }

        val lastSegment = cleanPath.substringAfterLast('/', cleanPath)
        val ext = lastSegment.substringAfterLast('.', "").lowercase()
        val isStaticAsset = ext.isNotEmpty() && FloatingPwaUrlHelper.STATIC_EXTENSIONS.contains(ext)
        if (!isStaticAsset) {
            val indexFile = FloatingPwaUrlHelper.resolveSafeLocalFile(webRoot, "index.html")
            if (indexFile != null && indexFile.exists() && indexFile.isFile) {
                try {
                    return createStreamResponse("text/html", FileInputStream(indexFile))
                } catch (_: Exception) {}
            }
        }
        return createTextResponse(404, "Not Found", "Missing: $cleanPath")
    }

    private fun createStreamResponse(mime: String, stream: FileInputStream): WebResourceResponse {
        val encoding = if (mime.startsWith("text/") || mime == "application/javascript" || mime == "application/json") "utf-8" else null
        return WebResourceResponse(mime, encoding, 200, "OK", emptyMap(), stream).apply {
            responseHeaders = mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "no-cache"
            )
        }
    }

    private fun createTextResponse(code: Int, reason: String, message: String): WebResourceResponse {
        val stream = ByteArrayInputStream(message.toByteArray(Charsets.UTF_8))
        return WebResourceResponse("text/plain", "utf-8", code, reason, emptyMap(), stream).apply {
            responseHeaders = mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "no-cache"
            )
        }
    }

    private fun injectAudioSuppressionScript(view: WebView?) {
        val script = """
            (function() {
                try {
                    if (window.__pwaMuteEnforced) return;
                    window.__pwaMuteEnforced = true;
                    HTMLMediaElement.prototype.play = function() { return Promise.reject(new Error("Audio disabled")); };
                    if (window.AudioContext) {
                        var origA = window.AudioContext;
                        window.AudioContext = function() {
                            var ctx = new origA();
                            try { ctx.suspend(); } catch(e) {}
                            ctx.resume = function() { return Promise.reject(new Error("Audio disabled")); };
                            return ctx;
                        };
                    }
                } catch(e) {}
            })();
        """.trimIndent()
        view?.evaluateJavascript(script, null)
    }

    private fun deliverFileChooserResult(context: Context, reqId: String?, cancelled: Boolean, rawUris: String) {
        if (reqId != null && reqId != pendingFileRequestId) return
        val callback = pendingFileChooserCallback ?: return
        pendingFileChooserCallback = null
        pendingFileRequestId = null

        if (cancelled || rawUris.isBlank()) {
            try { callback.onReceiveValue(null) } catch (_: Throwable) {}
            return
        }
        val uris = rawUris.lines().mapNotNull { line ->
            line.trim().takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
        }.toTypedArray()
        try {
            callback.onReceiveValue(if (uris.isEmpty()) null else uris)
        } catch (t: Throwable) {
            LogKeeper.logError(context, TAG, "Error delivering file chooser result", t)
        }
    }

    private fun deliverPermissionResult(
        context: Context,
        reqId: String?,
        cap: String,
        cameraGranted: Boolean,
        micGranted: Boolean,
        locationGranted: Boolean
    ) {
        if (cap == "media") {
            if (reqId != null && reqId != pendingMediaRequestId) return
            val request = pendingPermissionRequest ?: return
            val granted = pendingGrantedMediaResources.toMutableList()
            pendingPermissionRequest = null
            pendingMediaRequestId = null
            pendingGrantedMediaResources.clear()

            if (cameraGranted && (activePwaEntity == null || permissionPolicy.cameraAllowed)) {
                granted.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
            }
            if (micGranted && (activePwaEntity == null || permissionPolicy.microphoneAllowed)) {
                granted.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
            }
            try {
                if (granted.isNotEmpty()) request.grant(granted.toTypedArray()) else request.deny()
            } catch (t: Throwable) {
                LogKeeper.logError(context, TAG, "Error resolving media permission", t)
            }
        } else if (cap == "location") {
            if (reqId != null && reqId != pendingLocationRequestId) return
            val cb = pendingGeolocationCallback ?: return
            val origin = pendingGeolocationOrigin
            pendingGeolocationCallback = null
            pendingGeolocationOrigin = null
            pendingLocationRequestId = null

            val allowed = locationGranted && (activePwaEntity == null || permissionPolicy.locationAllowed)
            try {
                cb.invoke(origin, allowed, false)
            } catch (t: Throwable) {
                LogKeeper.logError(context, TAG, "Error resolving geolocation callback", t)
            }
        }
    }

    private fun cancelPendingFileChooser(reason: String) {
        pendingFileChooserCallback?.let { cb ->
            try { cb.onReceiveValue(null) } catch (_: Throwable) {}
        }
        pendingFileChooserCallback = null
        pendingFileRequestId = null
    }

    private fun cancelPendingMediaPermission(reason: String) {
        pendingPermissionRequest?.let { req ->
            try { req.deny() } catch (_: Throwable) {}
        }
        pendingPermissionRequest = null
        pendingMediaRequestId = null
        pendingGrantedMediaResources.clear()
    }

    private fun cancelPendingGeolocation(reason: String) {
        pendingGeolocationCallback?.let { cb ->
            try { cb.invoke(pendingGeolocationOrigin, false, false) } catch (_: Throwable) {}
        }
        pendingGeolocationCallback = null
        pendingGeolocationOrigin = null
        pendingLocationRequestId = null
    }

    private fun syncNavigationFlags(context: Context) {
        canGoBackState = try { webView?.canGoBack() == true } catch (_: Throwable) { false }
        canGoForwardState = try { webView?.canGoForward() == true } catch (_: Throwable) { false }
        emitStateEvent(context)
    }

    private fun emitStateEvent(context: Context) {
        sendEvent(
            context,
            ACTION_PWA_STATE_CHANGED,
            mapOf(
                KEY_PWA_ID to activePwaId,
                "pwaName" to (activePwaEntity?.name ?: ""),
                "url" to currentUrl,
                "title" to currentTitle,
                "canGoBack" to canGoBackState.toString(),
                "canGoForward" to canGoForwardState.toString(),
                "isLoading" to isLoading.toString(),
                "progress" to currentProgress.toString()
            )
        )
    }

    private fun sendEvent(context: Context, action: String, extra: Map<String, String>) {
        try {
            val payload = LinkedHashMap<String, String>().apply {
                put("action", action)
                put("instanceId", instanceId)
                putAll(extra)
            }
            HeavyProcessHost.getInstance(context).reportEvent(
                HeavyEvent(
                    eventId = "${action}_${instanceId}_${System.currentTimeMillis()}",
                    type = HeavyEventType.STATE_UPDATED,
                    sourceId = instanceId,
                    payload = payload
                )
            )
        } catch (_: Throwable) {}
    }

    fun clearEmbeddedImeFocus() {
        webView?.clearBridgeFocus()
        EmbeddedImeBridge.clearFocusRecursive(rootContainer)
    }

    override fun releaseRuntime() {
        cancelPendingFileChooser("release_runtime")
        cancelPendingMediaPermission("release_runtime")
        cancelPendingGeolocation("release_runtime")
        try {
            webView?.let { wv ->
                wv.clearBridgeFocus()
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.clearHistory()
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.destroy()
            }
        } catch (_: Throwable) {}
        webView = null
        emptyPlaceholderLayout = null
        rootContainer?.removeAllViews()
        rootContainer = null
    }

    // Testing helpers
    fun getActivePwaIdForTesting(): String = activePwaId
    fun getCurrentUrlForTesting(): String = currentUrl
    fun getCurrentTitleForTesting(): String = currentTitle
    fun getWebViewForTesting(): WebView? = webView
}
