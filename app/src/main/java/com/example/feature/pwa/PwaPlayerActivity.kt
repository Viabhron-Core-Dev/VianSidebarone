package com.example.feature.pwa

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.example.data.AppDatabase
import com.example.data.PwaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.net.URLConnection

/**
 * PwaPlayerActivity: Dedicated full-screen Offline PWA runtime in the :heavy process.
 *
 * Requirements enforced:
 * 1. Hosts PWA in full-screen (no Sidebar top bar, not treated as a Sidebar page).
 * 2. Android Back handled internally by WebView first, clean exit when back stack is empty.
 * 3. Self-contained runtime exit button returning to previous context.
 * 4. Offline asset loading with local file mapping and proper MIME type resolution.
 * 5. Isolated storage per PWA (DOM storage, app cache, database path).
 */
class PwaPlayerActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var pwaEntity: PwaEntity? = null
    private var progressBar: ProgressBar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pwaId = intent.getStringExtra("PWA_ID") ?: run {
            finish()
            return
        }

        val rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        // Loading indicator
        progressBar = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            visibility = View.VISIBLE
        }
        rootLayout.addView(progressBar)

        // Exit / Close button (subtle floating pill at top-right)
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
            setOnClickListener { finish() }
        }
        rootLayout.addView(closeButton)

        setContentView(rootLayout)

        // Handle Android Back key
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webView
                if (wv != null && wv.canGoBack()) {
                    wv.goBack()
                } else {
                    finish()
                }
            }
        })

        // Load PwaEntity from database and initialize player
        lifecycleScope.launch {
            val entity = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(this@PwaPlayerActivity).pwaDao().getById(pwaId)
            }
            if (entity == null) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        this@PwaPlayerActivity,
                        "PWA not found: $pwaId",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
                return@launch
            }
            pwaEntity = entity

            withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(this@PwaPlayerActivity).pwaDao().updateLastLaunched(pwaId, System.currentTimeMillis())
            }

            withContext(Dispatchers.Main) {
                initializeWebView(rootLayout, entity)
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

        // Apply background color from PWA manifest
        try {
            wv.setBackgroundColor(Color.parseColor(entity.backgroundColor))
        } catch (_: Throwable) {
            wv.setBackgroundColor(Color.parseColor("#121212"))
        }

        // Configure modern offline PWA web settings
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            displayZoomControls = false
            builtInZoomControls = true
            setSupportZoom(true)

            // Isolated storage directory
            val isolatedDataDir = PwaStorageManager.getIsolatedDataDir(this@PwaPlayerActivity, entity.id)
            setDatabasePath(isolatedDataDir.absolutePath)
        }

        val unpackedDir = File(entity.unpackedPath)

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress >= 100) {
                    progressBar?.visibility = View.GONE
                }
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar?.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar?.visibility = View.GONE
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                val scheme = url.scheme?.lowercase()

                // Intercept file requests or requests mapped to local unpacked assets
                if (scheme == "file" || scheme == "http" || scheme == "https") {
                    val path = url.path ?: ""
                    val localFile = resolveLocalFile(unpackedDir, path)
                    if (localFile != null && localFile.exists() && localFile.isFile) {
                        return try {
                            val mime = getMimeType(localFile.name)
                            val stream = FileInputStream(localFile)
                            val response = WebResourceResponse(mime, "utf-8", stream)
                            response.responseHeaders = mapOf(
                                "Access-Control-Allow-Origin" to "*",
                                "Cache-Control" to "no-cache"
                            )
                            response
                        } catch (_: Throwable) {
                            null
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        // Add WebView below floating exit button
        container.addView(wv, 0)

        // Load entrypoint file
        val entryFile = File(unpackedDir, entity.startUrl)
        if (entryFile.exists()) {
            wv.loadUrl("file://${entryFile.absolutePath}")
        } else {
            val fallbackIndex = File(unpackedDir, "index.html")
            if (fallbackIndex.exists()) {
                wv.loadUrl("file://${fallbackIndex.absolutePath}")
            } else {
                wv.loadDataWithBaseURL(
                    "file://${unpackedDir.absolutePath}/",
                    "<html><body style='color:white;background:#121212;padding:24px;font-family:sans-serif;'>" +
                            "<h2>${entity.name}</h2><p>Offline entrypoint not found: ${entity.startUrl}</p></body></html>",
                    "text/html",
                    "utf-8",
                    null
                )
            }
        }
    }

    private fun resolveLocalFile(webRoot: File, requestPath: String): File? {
        val cleanPath = requestPath.removePrefix("/")
        val candidate = File(webRoot, cleanPath)
        val canonicalRoot = webRoot.canonicalPath
        return if (candidate.canonicalPath.startsWith(canonicalRoot)) {
            candidate
        } else null
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
        webView?.let { wv ->
            wv.stopLoading()
            wv.clearHistory()
            wv.destroy()
        }
        webView = null
        super.onDestroy()
    }
}
