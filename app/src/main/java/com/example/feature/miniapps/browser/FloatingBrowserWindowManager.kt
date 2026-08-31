package com.example.feature.miniapps.browser




import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Environment
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.core.LogKeeper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FloatingBrowserWindowManager(private val context: Context, private val onClose: (FloatingBrowserWindowManager) -> Unit) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var floatingView: FrameLayout? = null
    private var webView: WebView? = null
    private val prefs: SharedPreferences = context.getSharedPreferences("FloatingBrowserPrefs", Context.MODE_PRIVATE)
    
    private val density = context.resources.displayMetrics.density
    
    private var jsEnabled = prefs.getBoolean("default_js_enabled", false)
    private var imagesEnabled = prefs.getBoolean("default_images_enabled", false)
    private var wrapContent = prefs.getBoolean("default_wrap_content", true)
    private val adBlocked = prefs.getBoolean("default_ad_block", true)
    private val adHosts = listOf("doubleclick.net", "admob.com", "googleadservices.com", "adsafeprotected.com", "adnxs.com")
    
    private var titleView: TextView? = null
    private var currentUrl: String = ""
    private var isMinimized = false

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    
    private var lastWidth = (320 * density).toInt()
    private var lastHeight = (450 * density).toInt()
    
    private var windowLayoutParams: WindowManager.LayoutParams? = null

    private var expandedContainer: LinearLayout? = null
    private var bubbleContainer: ImageView? = null
    private var resizeHandle: View? = null

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    fun show(url: String) {
        // Enforce HTTPS Strictly
        currentUrl = when {
            url.startsWith("http://") -> url.replaceFirst("http://", "https://")
            !url.startsWith("http") -> "https://$url"
            else -> url
        }
        
        floatingView = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
        
        windowLayoutParams = WindowManager.LayoutParams(
            lastWidth,
            lastHeight,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }
        
//         // com.example.utils.ActiveAppTracker.addApp("floating_browser_${this.hashCode()}", "Browser", "Tool", 60)
        
        initViews()
        
        try {
            windowManager.addView(floatingView, windowLayoutParams)
        } catch (e: Exception) {
            // com.example.core.LogKeeper.writeLog("FloatingBrowser", "Failed to add view: ${e.message}")
        }
    }
    
    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun initViews() {
        // 1. Setup Expanded Container
        expandedContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#121212"))
                cornerRadius = 12 * density
                setStroke((1 * density).toInt(), Color.parseColor("#333333"))
            }
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (40 * density).toInt())
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
        }
        
        titleView = TextView(context).apply {
            text = "Loading..."
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        
        val settingsBtn = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_preferences)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams((32 * density).toInt(), (32 * density).toInt()).apply { marginEnd = (4 * density).toInt() }
            setPadding((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
            setOnClickListener { showSettingsMenu() }
        }
        
        val minimizeBtn = ImageView(context).apply {
            setImageResource(android.R.drawable.arrow_down_float)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams((32 * density).toInt(), (32 * density).toInt()).apply { marginEnd = (4 * density).toInt() }
            setPadding((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
            setOnClickListener { toggleMinimize() }
        }
        
        val closeBtn = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams((32 * density).toInt(), (32 * density).toInt())
            setPadding((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
            setOnClickListener { close() }
        }
        
        topBar.addView(titleView)
        topBar.addView(settingsBtn)
        topBar.addView(minimizeBtn)
        topBar.addView(closeBtn)
        
        topBar.setOnTouchListener { _, event -> handleDrag(event) }
        
        val swipeRefreshLayout = SwipeRefreshLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        
        webView = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isVerticalScrollBarEnabled = true // Explicitly enable scrollbars
            settings.apply {
                javaScriptEnabled = jsEnabled
                loadsImagesAutomatically = imagesEnabled
                useWideViewPort = !wrapContent
                loadWithOverviewMode = !wrapContent
                domStorageEnabled = true
                userAgentString = "Mozilla/5.0 (Mobile; rv:10.0) Gecko/10.0 Firefox/10.0"
            }
            
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    titleView?.text = "Loading..."
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    titleView?.text = view?.title ?: url
                    swipeRefreshLayout.isRefreshing = false
                }
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: return null
                    if (adBlocked) {
                        for (host in adHosts) {
                            if (reqUrl.contains(host)) return WebResourceResponse("text/plain", "UTF-8", null)
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }
            
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress == 100) swipeRefreshLayout.isRefreshing = false
                }
            }
        }
        
        swipeRefreshLayout.addView(webView)
        swipeRefreshLayout.setOnRefreshListener { webView?.reload() }
        
        val fabContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        
        val fabLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(0, 0, (16 * density).toInt(), (16 * density).toInt())
            }
        }
        
        val saveTxtFab = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_save)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#4ea8de"))
            }
            layoutParams = LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt()).apply { marginEnd = (8 * density).toInt() }
            setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
            setOnClickListener { saveAsTxt() }
        }
        
        val saveMhtFab = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_agenda)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1DB954"))
            }
            layoutParams = LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt())
            setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
            setOnClickListener { saveAsMht() }
        }
        
        fabLayout.addView(saveTxtFab)
        fabLayout.addView(saveMhtFab)
        
        fabContainer.addView(swipeRefreshLayout)
        fabContainer.addView(fabLayout)
        
        expandedContainer?.addView(topBar)
        expandedContainer?.addView(fabContainer)
        
        resizeHandle = View(context).apply {
            layoutParams = FrameLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt()).apply {
                gravity = Gravity.BOTTOM or Gravity.END
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#88FFFFFF"))
                shape = GradientDrawable.OVAL
            }
            setOnTouchListener { _, event -> handleResize(event) }
        }

        // 2. Setup Bubble Container
        bubbleContainer = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_directions) // Bubble icon
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#121212"))
                setStroke((2 * density).toInt(), Color.parseColor("#4ea8de"))
            }
            layoutParams = FrameLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt())
            setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
            visibility = View.GONE
            setOnTouchListener { _, event -> handleDrag(event, isBubble = true) }
        }
        
        // 3. Add to floating view
        floatingView?.addView(expandedContainer)
        floatingView?.addView(resizeHandle)
        floatingView?.addView(bubbleContainer)
        
        // Ensure webview accepts focus
        expandedContainer?.setOnTouchListener { _, _ ->
            windowLayoutParams?.flags = (windowLayoutParams?.flags ?: 0) and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            windowManager.updateViewLayout(floatingView, windowLayoutParams)
            false
        }
        
        webView?.loadUrl(currentUrl)
    }

    private fun toggleMinimize() {
        isMinimized = !isMinimized
        if (isMinimized) {
            // Pause webview, hide expanded, show bubble
            webView?.onPause()
            expandedContainer?.visibility = View.GONE
            resizeHandle?.visibility = View.GONE
            bubbleContainer?.visibility = View.VISIBLE
            
            // Save current width/height
            lastWidth = windowLayoutParams?.width ?: (320 * density).toInt()
            lastHeight = windowLayoutParams?.height ?: (450 * density).toInt()
            
            windowLayoutParams?.let {
                it.width = (48 * density).toInt()
                it.height = (48 * density).toInt()
                it.flags = it.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
        } else {
            // Resume webview, show expanded, hide bubble
            webView?.onResume()
            expandedContainer?.visibility = View.VISIBLE
            resizeHandle?.visibility = View.VISIBLE
            bubbleContainer?.visibility = View.GONE
            
            windowLayoutParams?.let {
                it.width = lastWidth
                it.height = lastHeight
                it.flags = it.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            }
        }
        windowManager.updateViewLayout(floatingView, windowLayoutParams)
    }
    
    private fun handleDrag(event: MotionEvent, isBubble: Boolean = false): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = windowLayoutParams?.x ?: 0
                initialY = windowLayoutParams?.y ?: 0
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                windowLayoutParams?.x = initialX + (event.rawX - initialTouchX).toInt()
                windowLayoutParams?.y = initialY + (event.rawY - initialTouchY).toInt()
                windowManager.updateViewLayout(floatingView, windowLayoutParams)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isBubble) {
                    val diffX = Math.abs(event.rawX - initialTouchX)
                    val diffY = Math.abs(event.rawY - initialTouchY)
                    if (diffX < 10 && diffY < 10) {
                        toggleMinimize()
                    }
                }
                return true
            }
        }
        return false
    }
    
    private fun handleResize(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = windowLayoutParams?.width ?: (320 * density).toInt()
                initialY = windowLayoutParams?.height ?: (450 * density).toInt()
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val newWidth = initialX + (event.rawX - initialTouchX).toInt()
                val newHeight = initialY + (event.rawY - initialTouchY).toInt()
                windowLayoutParams?.width = newWidth.coerceAtLeast((200 * density).toInt())
                windowLayoutParams?.height = newHeight.coerceAtLeast((200 * density).toInt())
                windowManager.updateViewLayout(floatingView, windowLayoutParams)
                return true
            }
        }
        return false
    }
    
    private fun showSettingsMenu() {
        val options = arrayOf(
            if (jsEnabled) "Disable JavaScript" else "Enable JavaScript",
            if (imagesEnabled) "Disable Images" else "Enable Images",
            if (wrapContent) "Disable Wrap Content" else "Enable Wrap Content"
        )
        
        android.app.AlertDialog.Builder(context)
            .setTitle("Browser Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        jsEnabled = !jsEnabled
                        webView?.settings?.javaScriptEnabled = jsEnabled
                        webView?.reload()
                    }
                    1 -> {
                        imagesEnabled = !imagesEnabled
                        webView?.settings?.loadsImagesAutomatically = imagesEnabled
                        webView?.reload()
                    }
                    2 -> {
                        wrapContent = !wrapContent
                        webView?.settings?.useWideViewPort = !wrapContent
                        webView?.settings?.loadWithOverviewMode = !wrapContent
                        webView?.reload()
                    }
                }
                Toast.makeText(context, "Applied. Reloading...", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val dialog = create()
                    dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                    dialog.show()
                } else {
                    val dialog = create()
                    dialog.window?.setType(WindowManager.LayoutParams.TYPE_PHONE)
                    dialog.show()
                }
            }
    }
    
    private fun saveAsTxt() {
        webView?.evaluateJavascript(
            "(function(){return document.body.innerText;})();"
        ) { text ->
            val cleanText = text?.removePrefix("\"")?.removeSuffix("\"")?.replace("\\n", "\n")?.replace("\\u003C", "<") ?: ""
            saveFile("txt", cleanText.toByteArray())
        }
    }
    
    private fun saveAsMht() {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) downloadsDir.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "Webpage_$timestamp.mht"
        val file = File(downloadsDir, fileName)
        webView?.saveWebArchive(file.absolutePath)
        Toast.makeText(context, "Saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
    }
    
    private fun saveFile(ext: String, data: ByteArray) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val fileName = "Webpage_$timestamp.$ext"
                val file = File(downloadsDir, fileName)
                FileOutputStream(file).use {
                    it.write(data)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun close() {
//         // com.example.utils.ActiveAppTracker.removeApp("floating_browser_${this.hashCode()}")
        try {
            floatingView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {}
        
        // Aggressive Cleanup
        webView?.clearCache(true)
        webView?.clearHistory()
        webView?.destroy()
        
        onClose(this)
    }
}
