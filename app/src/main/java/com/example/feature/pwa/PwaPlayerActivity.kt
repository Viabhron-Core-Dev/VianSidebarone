package com.example.feature.pwa

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.core.LogKeeper
import com.example.data.AppDatabase
import com.example.data.PwaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.net.URLConnection
import java.util.concurrent.atomic.AtomicInteger

/**
 * PwaPlayerActivity: Dedicated full-screen Offline PWA Mini App runtime in the :heavy process.
 *
 * Core architecture & reliability enhancements:
 * 1. Hosts PWA in full-screen (isolated runtime outside Sidebar page containers).
 * 2. Virtual domain origin per PWA (https://pwa-${id}.local) for origin isolation (DOM, IDB, Cache)
 *    and elimination of file:// ES module / CORS restrictions.
 * 3. Enforceable capability permission policy (Internet, Files SAF, Camera, Microphone, Location, Sound)
 *    defaulting to OFF for zero-trust sandbox execution.
 * 4. Android Back navigation handling: iterates WebView history first, exits to previous context at root.
 * 5. Orientation customization: User override + manifest fallback.
 * 6. Deep diagnostics via LogKeeper: PWA_START, capability actions, resource counters, console warnings/errors,
 *    and 10-second PWA_LOAD_STALLED watchdog.
 */
class PwaPlayerActivity : ComponentActivity() {

    companion object {
        private const val TAG = "PWA"
        private const val WATCHDOG_TIMEOUT_MS = 10_000L

        private val STATIC_EXTENSIONS = setOf(
            "js", "mjs", "css", "json", "webmanifest",
            "png", "jpg", "jpeg", "gif", "svg", "webp", "ico",
            "wasm", "woff", "woff2", "ttf", "otf",
            "mp3", "mp4", "ogg", "wav", "webm", "map"
        )
    }

    private var webView: WebView? = null
    private var pwaEntity: PwaEntity? = null
    private var progressBar: ProgressBar? = null

    // Capability policy
    private var permissionPolicy: PwaPermissionPolicy = PwaPermissionPolicy()

    // File chooser / SAF state
    private var pendingFileChooserCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>

    // Web media permission state (Camera & Microphone)
    private var pendingPermissionRequest: PermissionRequest? = null
    private var pendingGrantedMediaResources: MutableList<String> = mutableListOf()
    private lateinit var mediaPermissionLauncher: ActivityResultLauncher<Array<String>>

    // Geolocation permission state
    private var pendingGeolocationCallback: GeolocationPermissions.Callback? = null
    private var pendingGeolocationOrigin: String? = null
    private lateinit var locationPermissionLauncher: ActivityResultLauncher<Array<String>>

    // Diagnostic state
    private val assetsRequestedCount = AtomicInteger(0)
    private val assetsFailedCount = AtomicInteger(0)
    private var currentProgress = 0
    private var currentUrl = ""
    private var isPageFinished = false
    private var lastError = "None"
    private var watchdogJob: Job? = null
    private var hasClearedInitialHistory = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pwaId = intent.getStringExtra("PWA_ID") ?: run {
            LogKeeper.log(this, TAG, "PwaPlayerActivity started without PWA_ID, finishing")
            finish()
            return
        }

        // Initialize ActivityResult launchers for SAF file chooser & device permissions
        initActivityLaunchers(pwaId)

        // Resolve capability policy (Intent extras first with fallback to stored policy)
        permissionPolicy = if (intent.hasExtra("PERM_INTERNET")) {
            PwaPermissionPolicy(
                internetAllowed = intent.getBooleanExtra("PERM_INTERNET", false),
                filesAllowed = intent.getBooleanExtra("PERM_FILES", false),
                cameraAllowed = intent.getBooleanExtra("PERM_CAMERA", false),
                microphoneAllowed = intent.getBooleanExtra("PERM_MICROPHONE", false),
                locationAllowed = intent.getBooleanExtra("PERM_LOCATION", false),
                soundAllowed = intent.getBooleanExtra("PERM_SOUND", false)
            )
        } else {
            PwaPermissionManager.getPolicy(this, pwaId)
        }

        val rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        // Loading progress indicator
        progressBar = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            visibility = View.VISIBLE
        }
        rootLayout.addView(progressBar)

        // Floating exit/close button
        val density = resources.displayMetrics.density
        val closeButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.WHITE)
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#99000000"))
                setStroke((1 * density).toInt(), Color.parseColor("#44FFFFFF"))
            }
            background = bg
            layoutParams = FrameLayout.LayoutParams(
                (40 * density).toInt(),
                (40 * density).toInt(),
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = (24 * density).toInt()
                marginEnd = (16 * density).toInt()
            }
            elevation = 16f * density
            contentDescription = "Close PWA"
            setOnClickListener {
                LogKeeper.log(this@PwaPlayerActivity, TAG, "PWA close button clicked: exiting runtime")
                finish()
            }
        }
        val closeButtonExtra = intent.getStringExtra("PREF_CLOSE_BUTTON")
        val prefs = getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
        val closeButtonPref = closeButtonExtra ?: prefs.getString("pwa_pref_${pwaId}_close_button", "show") ?: "show"
        val showCloseButton = closeButtonPref != "hide"
        if (!showCloseButton) {
            closeButton.visibility = View.GONE
            LogKeeper.log(this@PwaPlayerActivity, TAG, "PWA close button hidden per preference for $pwaId")
        }
        rootLayout.addView(closeButton)

        setContentView(rootLayout)

        // Lock down Android Back behavior
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webView
                if (wv != null && wv.canGoBack()) {
                    LogKeeper.log(this@PwaPlayerActivity, TAG, "PWA_BACK webHistory=true -> goBack")
                    wv.goBack()
                } else {
                    LogKeeper.log(this@PwaPlayerActivity, TAG, "PWA_BACK root -> finish")
                    finish()
                }
            }
        })

        // Load entity, resolve orientation, initialize WebView
        lifecycleScope.launch {
            val entity = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(this@PwaPlayerActivity).pwaDao().getById(pwaId)
            }
            if (entity == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@PwaPlayerActivity,
                        "PWA not found: $pwaId",
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
                return@launch
            }
            pwaEntity = entity

            withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(this@PwaPlayerActivity).pwaDao().updateLastLaunched(pwaId, System.currentTimeMillis())
            }

            // Orientation resolution
            val orientationExtra = intent.getStringExtra("PREF_ORIENTATION")
            val userOverride = orientationExtra ?: prefs.getString("pwa_pref_${entity.id}_orientation", "manifest") ?: "manifest"
            val resolvedOrientation = resolveOrientation(userOverride, entity.orientation)
            requestedOrientation = resolvedOrientation

            val orientationLabel = when (resolvedOrientation) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> "portrait"
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> "landscape"
                else -> "unspecified"
            }

            LogKeeper.log(
                this@PwaPlayerActivity,
                TAG,
                "PWA_START id=${entity.id} root=${entity.unpackedPath} startUrl=${entity.startUrl} scope=${entity.scope} orientation=$orientationLabel (override=$userOverride) display=${entity.displayMode} perms=[net=${permissionPolicy.internetAllowed},files=${permissionPolicy.filesAllowed},cam=${permissionPolicy.cameraAllowed},mic=${permissionPolicy.microphoneAllowed},loc=${permissionPolicy.locationAllowed},snd=${permissionPolicy.soundAllowed}]"
            )

            withContext(Dispatchers.Main) {
                initializeWebView(rootLayout, entity)
            }
        }
    }

    private fun initActivityLaunchers(pwaId: String) {
        // SAF file chooser contract
        fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingFileChooserCallback
            pendingFileChooserCallback = null
            if (callback == null) return@registerForActivityResult

            if (result.resultCode == RESULT_OK && result.data != null) {
                val data = result.data!!
                val uris = mutableListOf<Uri>()

                val clipData = data.clipData
                if (clipData != null) {
                    for (i in 0 until clipData.itemCount) {
                        val itemUri = clipData.getItemAt(i).uri
                        uris.add(itemUri)
                    }
                } else if (data.data != null) {
                    uris.add(data.data!!)
                }

                // Persist permissions and link grants to pwaId
                for (u in uris) {
                    try {
                        contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: Throwable) {}
                    PwaPermissionManager.addGrantedUri(this, pwaId, u)
                }

                LogKeeper.log(this, TAG, "PWA_SAF_CHOOSER_SUCCESS id=$pwaId count=${uris.size}")
                callback.onReceiveValue(uris.toTypedArray())
            } else {
                LogKeeper.log(this, TAG, "PWA_SAF_CHOOSER_CANCELLED id=$pwaId")
                callback.onReceiveValue(null)
            }
        }

        // Camera & Microphone permission contract
        mediaPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val request = pendingPermissionRequest
            val granted = pendingGrantedMediaResources.toMutableList()
            pendingPermissionRequest = null
            pendingGrantedMediaResources.clear()

            if (request == null) return@registerForActivityResult

            if (results[Manifest.permission.CAMERA] == true && permissionPolicy.cameraAllowed) {
                granted.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
            }
            if (results[Manifest.permission.RECORD_AUDIO] == true && permissionPolicy.microphoneAllowed) {
                granted.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
            }

            if (granted.isNotEmpty()) {
                request.grant(granted.toTypedArray())
                LogKeeper.log(this, TAG, "PWA_RUNTIME_MEDIA_GRANTED id=$pwaId granted=$granted")
            } else {
                request.deny()
                LogKeeper.log(this, TAG, "PWA_RUNTIME_MEDIA_DENIED id=$pwaId")
            }
        }

        // Location permission contract
        locationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val callback = pendingGeolocationCallback
            val origin = pendingGeolocationOrigin
            pendingGeolocationCallback = null
            pendingGeolocationOrigin = null

            val fineGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
            val coarseGranted = results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            val isAllowed = (fineGranted || coarseGranted) && permissionPolicy.locationAllowed

            callback?.invoke(origin, isAllowed, false)
            LogKeeper.log(this, TAG, "PWA_RUNTIME_LOCATION_RESULT id=$pwaId allowed=$isAllowed")
        }
    }

    private fun resolveOrientation(override: String, manifestOrientation: String): Int {
        return when (override) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "unspecified" -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else -> {
                when (manifestOrientation.lowercase()) {
                    "portrait", "portrait-primary", "portrait-secondary" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    "landscape", "landscape-primary", "landscape-secondary" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initializeWebView(container: FrameLayout, entity: PwaEntity) {
        val wv = WebView(this)
        this.webView = wv

        wv.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        try {
            wv.setBackgroundColor(Color.parseColor(entity.backgroundColor))
        } catch (_: Throwable) {
            wv.setBackgroundColor(Color.parseColor("#121212"))
        }

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            // Enforce sound policy: require user gesture if sound is disallowed
            mediaPlaybackRequiresUserGesture = !permissionPolicy.soundAllowed
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            displayZoomControls = false
            builtInZoomControls = true
            setSupportZoom(true)

            // Geolocation enable setting
            setGeolocationEnabled(permissionPolicy.locationAllowed)

            // Dedicated isolated database path
            val isolatedDataDir = PwaStorageManager.getIsolatedDataDir(this@PwaPlayerActivity, entity.id)
            setDatabasePath(isolatedDataDir.absolutePath)
        }

        LogKeeper.log(
            this,
            TAG,
            "WebView initialized: isolatedDir=${PwaStorageManager.getIsolatedDataDir(this, entity.id).name} soundAllowed=${permissionPolicy.soundAllowed}"
        )

        val unpackedDir = File(entity.unpackedPath)
        val virtualHost = "pwa-${entity.id}.local"

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                currentProgress = newProgress
                if (newProgress >= 100) {
                    progressBar?.visibility = View.GONE
                }
            }

            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                if (consoleMessage != null) {
                    val level = consoleMessage.messageLevel()
                    if (level == android.webkit.ConsoleMessage.MessageLevel.WARNING || level == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                        val msg = "JS Console [${level.name}] L${consoleMessage.lineNumber()}: ${consoleMessage.message()}"
                        lastError = msg
                        LogKeeper.log(this@PwaPlayerActivity, TAG, msg)
                    }
                }
                return super.onConsoleMessage(consoleMessage)
            }

            // Real SAF File Chooser enforcement
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (!permissionPolicy.filesAllowed) {
                    LogKeeper.log(this@PwaPlayerActivity, TAG, "PWA_BLOCKED_FILES id=${entity.id}")
                    Toast.makeText(this@PwaPlayerActivity, "File access disabled for this Mini App", Toast.LENGTH_SHORT).show()
                    filePathCallback?.onReceiveValue(null)
                    return true
                }

                pendingFileChooserCallback?.onReceiveValue(null)
                pendingFileChooserCallback = filePathCallback

                return try {
                    val pickIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        val acceptTypes = fileChooserParams?.acceptTypes?.filter { it.isNotBlank() }
                        if (!acceptTypes.isNullOrEmpty()) {
                            if (acceptTypes.size == 1) {
                                type = acceptTypes[0]
                            } else {
                                putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes.toTypedArray())
                            }
                        }
                        val isMultiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, isMultiple)
                        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    fileChooserLauncher.launch(pickIntent)
                    true
                } catch (e: Exception) {
                    LogKeeper.logError(this@PwaPlayerActivity, TAG, "Failed to launch SAF document picker", e)
                    pendingFileChooserCallback?.onReceiveValue(null)
                    pendingFileChooserCallback = null
                    false
                }
            }

            // Real Camera & Microphone enforcement
            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val requestedResources = request.resources
                val wantsCamera = requestedResources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                val wantsMic = requestedResources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)

                val granted = mutableListOf<String>()
                val neededPermissions = mutableListOf<String>()

                if (wantsCamera) {
                    if (permissionPolicy.cameraAllowed) {
                        if (ContextCompat.checkSelfPermission(this@PwaPlayerActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            granted.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                        } else {
                            neededPermissions.add(Manifest.permission.CAMERA)
                        }
                    } else {
                        LogKeeper.log(this@PwaPlayerActivity, TAG, "PWA_BLOCKED_CAMERA id=${entity.id}")
                    }
                }

                if (wantsMic) {
                    if (permissionPolicy.microphoneAllowed) {
                        if (ContextCompat.checkSelfPermission(this@PwaPlayerActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            granted.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                        } else {
                            neededPermissions.add(Manifest.permission.RECORD_AUDIO)
                        }
                    } else {
                        LogKeeper.log(this@PwaPlayerActivity, TAG, "PWA_BLOCKED_MICROPHONE id=${entity.id}")
                    }
                }

                if (neededPermissions.isNotEmpty()) {
                    pendingPermissionRequest = request
                    pendingGrantedMediaResources = granted
                    mediaPermissionLauncher.launch(neededPermissions.toTypedArray())
                } else if (granted.isNotEmpty()) {
                    request.grant(granted.toTypedArray())
                    LogKeeper.log(this@PwaPlayerActivity, TAG, "PWA_GRANTED_MEDIA id=${entity.id} res=$granted")
                } else {
                    request.deny()
                    LogKeeper.log(this@PwaPlayerActivity, TAG, "PWA_DENIED_MEDIA id=${entity.id}")
                }
            }

            // Real Geolocation enforcement
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                if (!permissionPolicy.locationAllowed) {
                    LogKeeper.log(this@PwaPlayerActivity, TAG, "PWA_BLOCKED_LOCATION id=${entity.id}")
                    callback?.invoke(origin, false, false)
                    return
                }

                val hasFine = ContextCompat.checkSelfPermission(this@PwaPlayerActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val hasCoarse = ContextCompat.checkSelfPermission(this@PwaPlayerActivity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

                if (hasFine || hasCoarse) {
                    callback?.invoke(origin, true, false)
                    LogKeeper.log(this@PwaPlayerActivity, TAG, "PWA_GRANTED_LOCATION id=${entity.id}")
                } else {
                    pendingGeolocationCallback = callback
                    pendingGeolocationOrigin = origin
                    locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                currentUrl = url ?: ""
                isPageFinished = false
                progressBar?.visibility = View.VISIBLE
                LogKeeper.log(this@PwaPlayerActivity, TAG, "WebView page started: $url")

                // Enforce sound mute script if sound is disallowed
                if (!permissionPolicy.soundAllowed) {
                    injectAudioSuppressionScript(view)
                }

                // Start 10-second loading watchdog
                startLoadingWatchdog(entity.id)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                currentUrl = url ?: ""
                isPageFinished = true
                progressBar?.visibility = View.GONE
                watchdogJob?.cancel()

                if (!hasClearedInitialHistory) {
                    hasClearedInitialHistory = true
                    wv.clearHistory()
                }

                // Enforce sound mute script on finished page as well
                if (!permissionPolicy.soundAllowed) {
                    injectAudioSuppressionScript(view)
                }

                LogKeeper.log(
                    this@PwaPlayerActivity,
                    TAG,
                    "WebView page finished: $url (assets requested=${assetsRequestedCount.get()}, failed=${assetsFailedCount.get()})"
                )
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                val host = url.host?.lowercase() ?: ""
                val scheme = url.scheme?.lowercase() ?: ""

                // Local assets / virtual host stay inside WebView
                if ((scheme == "https" || scheme == "http") && (host == virtualHost || host.endsWith(".local") || host == "localhost")) {
                    return false
                }

                // External network navigation
                if (!permissionPolicy.internetAllowed) {
                    LogKeeper.log(this@PwaPlayerActivity, TAG, "PWA_BLOCKED_NAV id=${entity.id} url=$url")
                    Toast.makeText(this@PwaPlayerActivity, "External internet navigation disabled for this Mini App", Toast.LENGTH_SHORT).show()
                    return true // Block external navigation
                }

                return super.shouldOverrideUrlLoading(view, request)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                assetsFailedCount.incrementAndGet()
                lastError = "Code=${error?.errorCode}, Desc=${error?.description}"
                LogKeeper.log(
                    this@PwaPlayerActivity,
                    TAG,
                    "WebView load error: code=${error?.errorCode}, desc=${error?.description}, url=${request?.url}"
                )
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                assetsFailedCount.incrementAndGet()
                lastError = "HTTP Status=${errorResponse?.statusCode}"
                LogKeeper.log(
                    this@PwaPlayerActivity,
                    TAG,
                    "WebView HTTP error: status=${errorResponse?.statusCode}, url=${request?.url}"
                )
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                val scheme = url.scheme?.lowercase() ?: ""
                val host = url.host?.lowercase() ?: ""

                // 1. Intercept virtual domain requests or fallback file scheme requests (local package assets)
                if ((scheme == "https" || scheme == "http") && (host == virtualHost || host.endsWith(".local") || host == "localhost")) {
                    assetsRequestedCount.incrementAndGet()
                    val path = url.path ?: "/"
                    return handleLocalAssetRequest(unpackedDir, path)
                } else if (scheme == "file") {
                    assetsRequestedCount.incrementAndGet()
                    val path = url.path ?: "/"
                    return handleLocalAssetRequest(unpackedDir, path)
                }

                // 2. External network requests
                if (!permissionPolicy.internetAllowed) {
                    LogKeeper.log(this@PwaPlayerActivity, TAG, "PWA_BLOCKED_NETWORK id=${entity.id} url=$url")
                    return create403Response("Network access disabled for this Mini App")
                }

                return super.shouldInterceptRequest(view, request)
            }
        }

        container.addView(wv, 0)

        // Launch via virtual domain origin https://pwa-${id}.local/
        val cleanStart = entity.startUrl.removePrefix("/").removePrefix("./")
        val launchUrl = "https://$virtualHost/$cleanStart"
        LogKeeper.log(this, TAG, "Loading PWA entrypoint via virtual origin: $launchUrl")
        wv.loadUrl(launchUrl)
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
                            return ctx;
                        };
                    }
                    if (window.webkitAudioContext) {
                        var origW = window.webkitAudioContext;
                        window.webkitAudioContext = function() {
                            var ctx = new origW();
                            try { ctx.suspend(); } catch(e) {}
                            return ctx;
                        };
                    }
                } catch(e) {}
            })();
        """.trimIndent()
        view?.evaluateJavascript(script, null)
    }

    private fun handleLocalAssetRequest(webRoot: File, requestPath: String): WebResourceResponse {
        val cleanPath = requestPath.removePrefix("/")
        val localFile = resolveLocalFile(webRoot, cleanPath)

        // Case 1: Exact static file exists
        if (localFile != null && localFile.exists() && localFile.isFile) {
            return try {
                val mime = getMimeType(localFile.name)
                val stream = FileInputStream(localFile)
                createWebResourceResponse(mime, stream)
            } catch (e: Exception) {
                assetsFailedCount.incrementAndGet()
                create404Response("Failed to read asset: ${e.message}")
            }
        }

        // Case 2: SPA client-side routing fallback (request has no static extension)
        val extension = cleanPath.substringAfterLast('.', "").lowercase()
        if (extension.isEmpty() || extension !in STATIC_EXTENSIONS) {
            val indexFile = File(webRoot, "index.html")
            if (indexFile.exists() && indexFile.isFile) {
                return try {
                    val stream = FileInputStream(indexFile)
                    createWebResourceResponse("text/html", stream)
                } catch (_: Exception) {
                    assetsFailedCount.incrementAndGet()
                    create404Response("Failed to read SPA fallback index.html")
                }
            }
        }

        // Case 3: Missing local static asset
        assetsFailedCount.incrementAndGet()
        lastError = "Local asset not found: $requestPath"
        LogKeeper.log(this, TAG, "Local asset not found: path=$requestPath")
        return create404Response("Asset not found: $requestPath")
    }

    private fun createWebResourceResponse(mime: String, stream: java.io.InputStream): WebResourceResponse {
        val response = WebResourceResponse(mime, "utf-8", stream)
        response.responseHeaders = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
            "Access-Control-Allow-Headers" to "*",
            "Cache-Control" to "no-cache"
        )
        return response
    }

    private fun create403Response(message: String): WebResourceResponse {
        val bytes = message.toByteArray(Charsets.UTF_8)
        val stream = ByteArrayInputStream(bytes)
        val response = WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), stream)
        response.responseHeaders = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "no-cache"
        )
        return response
    }

    private fun create404Response(message: String): WebResourceResponse {
        val bytes = message.toByteArray(Charsets.UTF_8)
        val stream = ByteArrayInputStream(bytes)
        val response = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), stream)
        response.responseHeaders = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "no-cache"
        )
        return response
    }

    private fun startLoadingWatchdog(pwaId: String) {
        watchdogJob?.cancel()
        watchdogJob = lifecycleScope.launch {
            delay(WATCHDOG_TIMEOUT_MS)
            if (!isPageFinished) {
                LogKeeper.log(
                    this@PwaPlayerActivity,
                    TAG,
                    "PWA_LOAD_STALLED id=$pwaId elapsedMs=$WATCHDOG_TIMEOUT_MS url=$currentUrl progress=$currentProgress requestedAssets=${assetsRequestedCount.get()} failedAssets=${assetsFailedCount.get()} lastError=$lastError"
                )
            }
        }
    }

    private fun resolveLocalFile(webRoot: File, requestPath: String): File? {
        val candidate = File(webRoot, requestPath)
        val canonicalRoot = webRoot.canonicalPath
        return if (candidate.canonicalPath.startsWith(canonicalRoot)) {
            candidate
        } else {
            assetsFailedCount.incrementAndGet()
            LogKeeper.log(this, TAG, "Blocked invalid local request outside web root: $requestPath")
            null
        }
    }

    private fun getMimeType(fileName: String): String {
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

    override fun onDestroy() {
        watchdogJob?.cancel()
        LogKeeper.log(
            this,
            TAG,
            "PWA_EXIT id=${pwaEntity?.id ?: "unknown"} requestedAssets=${assetsRequestedCount.get()} failedAssets=${assetsFailedCount.get()}"
        )
        webView?.let { wv ->
            wv.stopLoading()
            wv.clearHistory()
            wv.destroy()
        }
        webView = null
        super.onDestroy()
    }
}
