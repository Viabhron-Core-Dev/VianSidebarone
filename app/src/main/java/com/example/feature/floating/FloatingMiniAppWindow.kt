package com.example.feature.floating

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceControlViewHost
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.core.FloatingWindow
import com.example.core.FloatingWindowManager
import com.example.core.LogKeeper
import com.example.core.WindowBounds
import com.example.core.ipc.ConnectionState
import com.example.core.ipc.HeavyCommand
import com.example.core.ipc.HeavyCommandType
import com.example.core.ipc.HeavyConnectionListener
import com.example.core.ipc.HeavyProcessConnectionManager
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

/**
 * FloatingMiniAppWindow: A specialized FloatingWindow representation in the Main process
 * for floating mini-apps whose business logic runs in :heavy.
 *
 * Implements the reusable floating shell:
 * 1. Rounded Card: Consistent rounded corners (16dp) clipped with clipToOutline across all children.
 * 2. Top bar: Mini-app identity and gestures (drag moves, double-tap maximizes/restores).
 * 3. Bottom-right sticker: Exactly three controls in a rounded pill [ ×  —  ↘ ].
 *    The ↘ region is the resize touch target; no separate resize handle exists.
 * 4. True Safe Maximize: Uses authoritative Android insets/cutouts; fills maximum safe usable rectangle.
 * 5. Strict Geometry Separation: normalFloatingBounds, maximizeRestoreBounds, and temporaryImeBounds.
 * 6. IME / Keyboard: Shifts upward temporarily when occluded; restores exact position on close.
 * 7. Heavy Embedded Content: Uses SurfaceControlViewHost / SurfacePackage on API 30+, explicit fallback otherwise.
 */
class FloatingMiniAppWindow(
    val instanceId: String,
    context: Context,
    val appType: String,
    private var windowTitle: String = ""
) : FloatingWindow(instanceId, context, TYPE_MINIAPP) {

    private val durableStore = DurableMiniAppStore.getInstance(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Geometry separation
    var isMaximized: Boolean = false
        private set
    var maximizeRestoreBounds: WindowBounds? = null
        private set
    var normalFloatingBounds: WindowBounds = bounds.copyBounds()
        private set

    // IME temporary state
    private var isImeShifted: Boolean = false
    private var globalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    // View references
    private var rootCardView: FrameLayout? = null
    private var rootCardBackground: GradientDrawable? = null
    private var contentContainer: FrameLayout? = null
    private var titleView: TextView? = null
    private var noteEditText: EditText? = null
    private var currentNoteText: String = ""
    private var embeddedSurfaceView: SurfaceView? = null
    private var currentSurfacePackage: Any? = null // SurfaceControlViewHost.SurfacePackage
    private var surfaceReady: Boolean = false
    private var isSurfaceAttached: Boolean = false
    private var lastRelayoutWidth: Int = -1
    private var lastRelayoutHeight: Int = -1

    private fun getContentViewportHeight(windowHeight: Int): Int {
        val density = context.resources.displayMetrics.density
        val topBarHeight = (38 * density).toInt()
        return maxOf(0, windowHeight - topBarHeight)
    }

    private val connectionListener = object : HeavyConnectionListener {
        override fun onConnected() {
            mainHandler.post {
                tryAttachSurfacePackage()
            }
        }
        override fun onDisconnected() {}
        override fun onHeavyProcessDied() {
            mainHandler.post {
                if (isAttached && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    contentContainer?.let { attachFallbackContentView(context, it, "heavy_process_died") }
                }
            }
        }
    }

    init {
        val saved = durableStore.loadSnapshot(instanceId)
        maximizeRestoreBounds = saved?.maximizeRestoreBounds
        normalFloatingBounds = saved?.windowBounds?.copyBounds() ?: bounds.copyBounds()
        if (windowTitle.isEmpty()) {
            windowTitle = saved?.title?.takeIf { it.isNotEmpty() }
                ?: if (appType == "test_notes") "Test Notes" else appType.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }

    override fun createContentView(context: Context): View {
        val density = context.resources.displayMetrics.density
        val cardCornerRadius = 16 * density

        val root = FrameLayout(context).apply {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = cardCornerRadius
                setColor(Color.parseColor("#181825"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#45475A"))
            }
            rootCardBackground = bg
            background = bg
            // Clip all children (including top bar and content) to the card's rounded outer shape
            clipToOutline = true
            elevation = 16 * density
        }
        rootCardView = root

        val mainLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // 1. Top Bar
        val topBar = createTopBar(context)
        mainLayout.addView(topBar)

        // 2. Content Container
        val container = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setBackgroundColor(Color.parseColor("#1E1E2E"))
        }
        contentContainer = container
        mainLayout.addView(container)
        root.addView(mainLayout)

        // 3. Embedded Heavy Content Setup
        setupEmbeddedContent(context, container)

        // 4. Three-Control Sticker [ ×  —  ↘ ]
        val sticker = createBottomRightSticker(context)
        root.addView(sticker)

        // 5. Attach IME GlobalLayoutListener
        setupImeObserver(root)

        return root
    }

    private fun applyCardCornerRadius(maximized: Boolean) {
        val density = context.resources.displayMetrics.density
        val radius = if (maximized) 0f else 16 * density
        rootCardBackground?.cornerRadius = radius
        rootCardView?.clipToOutline = !maximized
    }

    private fun setupEmbeddedContent(context: Context, container: FrameLayout) {
        LogKeeper.log(context, "FloatingMiniAppWindow", "CONTENT_REQUEST id=$instanceId type=$appType")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sv = SurfaceView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setZOrderMediaOverlay(true)
                holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
            }
            embeddedSurfaceView = sv

            sv.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    surfaceReady = true
                    tryAttachSurfacePackage()
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    relayoutEmbeddedSurface(width, height)
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    surfaceReady = false
                    isSurfaceAttached = false
                    try {
                        (currentSurfacePackage as? SurfaceControlViewHost.SurfacePackage)?.release()
                    } catch (_: Throwable) {}
                    currentSurfacePackage = null
                    LogKeeper.log(context, "FloatingMiniAppWindow", "SURFACE_DETACHED id=$instanceId")
                }
            })
            container.addView(sv)
        } else {
            LogKeeper.log(context, "FloatingMiniAppWindow", "CONTENT_MODE_SELECTED mode=fallback reason=SDK_${Build.VERSION.SDK_INT}_below_30")
            attachFallbackContentView(context, container, "SDK_${Build.VERSION.SDK_INT}_below_30")
        }
    }

    private fun tryAttachSurfacePackage() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            contentContainer?.let { attachFallbackContentView(context, it, "SDK_${Build.VERSION.SDK_INT}_below_30") }
            return
        }

        if (isSurfaceAttached) return
        if (!surfaceReady) return

        val sv = embeddedSurfaceView ?: return
        val container = contentContainer ?: return
        val hostToken = sv.hostToken
        if (hostToken == null) {
            LogKeeper.log(context, "FloatingMiniAppWindow", "SURFACE_WAITING_FOR_TOKEN id=$instanceId")
            return
        }

        val connectionManager = HeavyProcessConnectionManager.getInstance(context)
        if (connectionManager.state != ConnectionState.CONNECTED) {
            LogKeeper.log(context, "FloatingMiniAppWindow", "SURFACE_WAITING_FOR_CONNECTION id=$instanceId state=${connectionManager.state}")
            connectionManager.connect(autoCreate = true, reason = "tryAttachSurfacePackage:$instanceId")
            return
        }

        val displayId = try {
            sv.display?.displayId ?: Display.DEFAULT_DISPLAY
        } catch (_: Exception) {
            Display.DEFAULT_DISPLAY
        }
        val w = if (container.width > 0) container.width else bounds.width
        val h = if (container.height > 0) container.height else getContentViewportHeight(bounds.height)

        val snapshot = durableStore.loadSnapshot(instanceId)
        val initialPayloadBundle = Bundle()
        snapshot?.payload?.forEach { (k, v) -> initialPayloadBundle.putString(k, v) }

        val params = Bundle().apply {
            putString("pageId", instanceId)
            putString("pageType", appType)
            putBoolean("isMiniApp", true)
            putBinder("hostToken", hostToken)
            putInt("displayId", displayId)
            putInt("width", w)
            putInt("height", h)
            putInt("bounds_x", bounds.x)
            putInt("bounds_y", bounds.y)
            putBundle("initialPayload", initialPayloadBundle)
        }

        val reply = connectionManager.createSurfacePackage(params)
        if (reply != null && reply.getBoolean("success")) {
            reply.classLoader = SurfaceControlViewHost.SurfacePackage::class.java.classLoader
            @Suppress("DEPRECATION")
            val sp = reply.getParcelable<SurfaceControlViewHost.SurfacePackage>("surfacePackage")
            if (sp != null) {
                currentSurfacePackage = sp
                sv.setChildSurfacePackage(sp)
                container.background = null
                isSurfaceAttached = true
                val fallback = container.findViewById<View>(android.R.id.text1)
                if (fallback != null) {
                    container.removeView(fallback)
                }
                LogKeeper.log(context, "FloatingMiniAppWindow", "CONTENT_MODE_SELECTED mode=surface_package reason=API30+_surface_package_embedded")
                LogKeeper.log(context, "FloatingMiniAppWindow", "CONTENT_CREATED id=$instanceId mode=embedded_heavy")
                LogKeeper.log(context, "FloatingMiniAppWindow", "SURFACE_ATTACHED id=$instanceId mode=embedded_heavy")
                return
            }
        }

        LogKeeper.log(context, "FloatingMiniAppWindow", "SURFACE_PACKAGE_PENDING id=$instanceId replyNull=${reply == null}")
        if (connectionManager.state == ConnectionState.DEAD) {
            LogKeeper.log(context, "FloatingMiniAppWindow", "CONTENT_MODE_SELECTED mode=fallback reason=heavy_connection_dead")
            attachFallbackContentView(context, container, "heavy_connection_dead")
        }
    }

    private fun relayoutEmbeddedSurface(viewportWidth: Int, viewportHeight: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && embeddedSurfaceView != null) {
            if (viewportWidth <= 0 || viewportHeight <= 0) return
            if (viewportWidth == lastRelayoutWidth && viewportHeight == lastRelayoutHeight) return
            lastRelayoutWidth = viewportWidth
            lastRelayoutHeight = viewportHeight
            try {
                HeavyProcessConnectionManager.getInstance(context).relayoutSurfaceHost(instanceId, viewportWidth, viewportHeight)
            } catch (_: Throwable) {}
        }
    }

    private fun attachFallbackContentView(context: Context, container: FrameLayout, reason: String = "fallback") {
        if (container.findViewById<View>(android.R.id.text1) != null) return

        val density = context.resources.displayMetrics.density
        val savedSnapshot = durableStore.loadSnapshot(instanceId)
        currentNoteText = savedSnapshot?.payload?.get(TestNotesMiniAppInstance.KEY_TEXT)
            ?: savedSnapshot?.payload?.get("note_content")
            ?: TestNotesMiniAppInstance.DEFAULT_INSTRUCTIONAL_TEXT

        LogKeeper.log(context, "FloatingMiniAppWindow", "CONTENT_CREATED id=$instanceId mode=in_process_fallback reason=$reason textLength=${currentNoteText.length}")

        val clientView = LinearLayout(context).apply {
            id = android.R.id.text1
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#181825"))
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (38 * density).toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val subtitleView = TextView(context).apply {
            text = "Floating framework test • Heavy Process Runtime"
            setTextColor(Color.parseColor("#89B4FA"))
            textSize = 11f
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        clientView.addView(subtitleView)

        val edit = EditText(context).apply {
            hint = "Type here... Text is persisted durably on Sleep."
            setHintTextColor(Color.parseColor("#6C7086"))
            setTextColor(Color.WHITE)
            textSize = 13f
            val editBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * density
                setColor(Color.parseColor("#313244"))
                setStroke((1 * density).toInt(), Color.parseColor("#45475A"))
            }
            background = editBg
            setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
            gravity = Gravity.TOP or Gravity.START
            this.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setText(currentNoteText)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    currentNoteText = s?.toString() ?: ""
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        noteEditText = edit
        clientView.addView(edit)

        container.addView(clientView)
    }

    private fun createTopBar(context: Context): LinearLayout {
        val density = context.resources.displayMetrics.density
        val topBarHeight = (38 * density).toInt()

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#2B2D42"))
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                topBarHeight
            )
        }

        // Mini-App Icon Indicator
        val iconView = ImageView(context).apply {
            val iconDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#89B4FA"))
            }
            setImageDrawable(iconDrawable)
            layoutParams = LinearLayout.LayoutParams((14 * density).toInt(), (14 * density).toInt()).apply {
                marginEnd = (10 * density).toInt()
            }
        }
        bar.addView(iconView)

        // Title
        titleView = TextView(context).apply {
            text = windowTitle
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(titleView)

        // Setup Top Bar Drag & Double-Tap Gestures
        setupTopBarGestures(bar, context)

        return bar
    }

    private fun setupTopBarGestures(topBar: View, context: Context) {
        val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        var isDragging = false
        var dragStartRawX = 0f
        var dragStartRawY = 0f
        var initialBoundsX = 0
        var initialBoundsY = 0

        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                LogKeeper.log(context, "FloatingMiniAppWindow", "DOUBLE_TAP id=$instanceId wasMaximized=$isMaximized")
                toggleMaximize()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!isMaximized) {
                    isDragging = true
                    LogKeeper.log(context, "FloatingMiniAppWindow", "MOVE_START id=$instanceId trigger=long_press initialBounds=($initialBoundsX,$initialBoundsY)")
                }
            }

            override fun onDown(e: MotionEvent): Boolean {
                return true
            }
        })

        topBar.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    initialBoundsX = bounds.x
                    initialBoundsY = bounds.y
                    isDragging = false
                    LogKeeper.log(context, "FloatingMiniAppWindow", "TOPBAR_DOWN id=$instanceId rawX=${event.rawX} rawY=${event.rawY} bounds=$bounds")

                    manager?.setFocus(windowId)
                    if (manager?.getTopVisibleWindow()?.windowId != windowId) {
                        manager?.bringToFront(windowId)
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isMaximized) return@setOnTouchListener true

                    val dx = event.rawX - dragStartRawX
                    val dy = event.rawY - dragStartRawY

                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        isDragging = true
                        LogKeeper.log(context, "FloatingMiniAppWindow", "MOVE_START id=$instanceId initialBounds=($initialBoundsX,$initialBoundsY)")
                    }

                    if (isDragging) {
                        val safeBounds = getSafeDisplayBounds(context)
                        val newX = (initialBoundsX + dx.toInt()).coerceIn(safeBounds.x, maxOf(safeBounds.x, safeBounds.right - bounds.width))
                        val newY = (initialBoundsY + dy.toInt()).coerceIn(safeBounds.y, maxOf(safeBounds.y, safeBounds.bottom - bounds.height))

                        if (newX != bounds.x || newY != bounds.y) {
                            bounds.x = newX
                            bounds.y = newY
                            normalFloatingBounds.x = newX
                            normalFloatingBounds.y = newY
                            layoutParams.x = newX
                            layoutParams.y = newY
                            manager?.updateBounds(windowId, bounds)
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        isDragging = false
                        LogKeeper.log(context, "FloatingMiniAppWindow", "MOVE_END id=$instanceId finalBounds=$bounds")
                        // Commit normal floating bounds exclusively
                        normalFloatingBounds = bounds.copyBounds()
                        durableStore.updateWindowBounds(instanceId, normalFloatingBounds)
                        LogKeeper.log(context, "FloatingMiniAppWindow", "WINDOW_BOUNDS_PERSISTED id=$instanceId bounds=$normalFloatingBounds source=drag")
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        isDragging = false
                        LogKeeper.log(context, "FloatingMiniAppWindow", "MOVE_CANCEL id=$instanceId bounds=$bounds")
                    }
                    true
                }

                else -> false
            }
        }
    }

    /**
     * Creates the attached three-control rounded sticker [ ×  —  ↘ ] at the bottom-right of the card.
     */
    private fun createBottomRightSticker(context: Context): View {
        val density = context.resources.displayMetrics.density
        val sticker = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10 * density // Rounded box / pill
                setColor(Color.parseColor("#252639"))
                setStroke((1 * density).toInt(), Color.parseColor("#45475A"))
            }
            background = bg
            elevation = 8 * density
            setPadding((4 * density).toInt(), (2 * density).toInt(), (4 * density).toInt(), (2 * density).toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                (32 * density).toInt(),
                Gravity.BOTTOM or Gravity.END
            ).apply {
                marginEnd = (10 * density).toInt()
                bottomMargin = (10 * density).toInt()
            }
        }

        // 1. Close Button (×)
        val closeBtn = createStickerButton(context, "×", Color.parseColor("#F38BA8")) {
            LogKeeper.log(context, "FloatingMiniAppWindow", "CONTROL_CLOSE id=$instanceId")
            FloatingMiniAppBridge.getInstance(context).closeMiniApp(instanceId, deleteDurable = true)
        }
        sticker.addView(closeBtn)

        // Divider
        sticker.addView(createStickerDivider(context))

        // 2. Fold / Sleep Button (—)
        val sleepBtn = createStickerButton(context, "—", Color.parseColor("#CDD6F4")) {
            LogKeeper.log(context, "FloatingMiniAppWindow", "CONTROL_SLEEP id=$instanceId")
            FloatingMiniAppBridge.getInstance(context).sleepMiniApp(instanceId)
        }
        sticker.addView(sleepBtn)

        // Divider
        sticker.addView(createStickerDivider(context))

        // 3. Resize Handle (↘) - The ↘ region itself is the touch/drag target for resizing
        val resizeBtn = createStickerButton(context, "↘", Color.parseColor("#89B4FA")) {
            // Touch handled via setupStickerResizeTouch below
        }
        setupStickerResizeTouch(resizeBtn, context)
        sticker.addView(resizeBtn)

        return sticker
    }

    private fun createStickerButton(context: Context, label: String, textColor: Int, onClick: () -> Unit): TextView {
        val density = context.resources.displayMetrics.density
        return TextView(context).apply {
            text = label
            setTextColor(textColor)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = false
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            setOnClickListener { onClick() }
        }
    }

    private fun createStickerDivider(context: Context): View {
        val density = context.resources.displayMetrics.density
        return View(context).apply {
            setBackgroundColor(Color.parseColor("#45475A"))
            layoutParams = LinearLayout.LayoutParams((1 * density).toInt(), (14 * density).toInt()).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        }
    }

    private fun setupStickerResizeTouch(resizeView: View, context: Context) {
        val density = context.resources.displayMetrics.density
        var resizeStartRawX = 0f
        var resizeStartRawY = 0f
        var startWidth = 0
        var startHeight = 0

        resizeView.setOnTouchListener { _, event ->
            if (isMaximized) {
                // Cannot resize while maximized
                return@setOnTouchListener false
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resizeStartRawX = event.rawX
                    resizeStartRawY = event.rawY
                    startWidth = bounds.width
                    startHeight = bounds.height
                    LogKeeper.log(context, "FloatingMiniAppWindow", "RESIZE_START id=$instanceId initialSize=($startWidth,$startHeight)")
                    manager?.setFocus(windowId)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dw = (event.rawX - resizeStartRawX).toInt()
                    val dh = (event.rawY - resizeStartRawY).toInt()

                    val safeBounds = getSafeDisplayBounds(context)
                    val minW = (240 * density).toInt()
                    val minH = (200 * density).toInt()
                    val maxW = safeBounds.right - bounds.x
                    val maxH = safeBounds.bottom - bounds.y

                    val newW = (startWidth + dw).coerceIn(minW, maxOf(minW, maxW))
                    val newH = (startHeight + dh).coerceIn(minH, maxOf(minH, maxH))

                    if (newW != bounds.width || newH != bounds.height) {
                        bounds.width = newW
                        bounds.height = newH
                        normalFloatingBounds.width = newW
                        normalFloatingBounds.height = newH
                        layoutParams.width = newW
                        layoutParams.height = newH
                        manager?.updateBounds(windowId, bounds)
                        relayoutEmbeddedSurface(newW, getContentViewportHeight(newH))
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    LogKeeper.log(context, "FloatingMiniAppWindow", "RESIZE_END id=$instanceId finalSize=(${bounds.width},${bounds.height})")
                    normalFloatingBounds = bounds.copyBounds()
                    durableStore.updateWindowBounds(instanceId, normalFloatingBounds)
                    LogKeeper.log(context, "FloatingMiniAppWindow", "WINDOW_BOUNDS_PERSISTED id=$instanceId bounds=$normalFloatingBounds source=resize")
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    LogKeeper.log(context, "FloatingMiniAppWindow", "RESIZE_CANCEL id=$instanceId size=(${bounds.width},${bounds.height})")
                    true
                }

                else -> false
            }
        }
    }

    /**
     * Determines ONE authoritative safe display rectangle using Android system bar, navigation bar, and cutout insets.
     */
    fun getSafeDisplayBounds(context: Context): WindowBounds {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val safeBounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            val rootInsets = getView()?.rootWindowInsets ?: metrics.windowInsets
            val insets = rootInsets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            val rect = metrics.bounds
            val left = insets.left
            val top = insets.top
            val right = rect.right - insets.right
            val bottom = rect.bottom - insets.bottom
            val width = maxOf(300, right - left)
            val height = maxOf(400, bottom - top)
            WindowBounds(left, top, width, height)
        } else {
            val dm = context.resources.displayMetrics
            val statusBarRes = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            val statusBarH = if (statusBarRes > 0) context.resources.getDimensionPixelSize(statusBarRes) else (24 * dm.density).toInt()
            val navBarRes = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
            val navBarH = if (navBarRes > 0) context.resources.getDimensionPixelSize(navBarRes) else (48 * dm.density).toInt()
            WindowBounds(0, statusBarH, dm.widthPixels, maxOf(400, dm.heightPixels - statusBarH - navBarH))
        }
        LogKeeper.log(context, "FloatingMiniAppWindow", "SAFE_DISPLAY_BOUNDS bounds=$safeBounds")
        return safeBounds
    }

    /**
     * Toggles between maximized state (filling maximum safe display bounds) and restored normal floating bounds.
     */
    fun toggleMaximize() {
        val wm = manager ?: FloatingWindowManager.getInstance(context)
        val safeBounds = getSafeDisplayBounds(context)

        if (isMaximized) {
            // Restore previous normal floating bounds
            val target = maximizeRestoreBounds?.copyBounds() ?: normalFloatingBounds.copyBounds()
            bounds = target.copyBounds()
            isMaximized = false
            maximizeRestoreBounds = null
            durableStore.updateMaximizeRestoreBounds(instanceId, null)

            // Restore rounded card shape
            applyCardCornerRadius(false)

            LogKeeper.log(context, "FloatingMiniAppWindow", "RESTORE id=$instanceId target=$target")
            LogKeeper.log(context, "FloatingMiniAppWindow", "RESTORE_BOUNDS bounds=$target")
            durableStore.updateWindowBounds(instanceId, target)
        } else {
            // Save current normal floating bounds before maximizing
            normalFloatingBounds = bounds.copyBounds()
            maximizeRestoreBounds = bounds.copyBounds()
            durableStore.updateMaximizeRestoreBounds(instanceId, maximizeRestoreBounds)
            LogKeeper.log(context, "FloatingMiniAppWindow", "MAXIMIZE_RESTORE_BOUNDS id=$instanceId restoreBounds=$maximizeRestoreBounds")

            // Fill maximum safe usable rectangle
            bounds.x = safeBounds.x
            bounds.y = safeBounds.y
            bounds.width = safeBounds.width
            bounds.height = safeBounds.height
            isMaximized = true

            // Flat edge-to-edge corners when maximized
            applyCardCornerRadius(true)

            LogKeeper.log(context, "FloatingMiniAppWindow", "MAXIMIZE id=$instanceId bounds=$bounds")
            LogKeeper.log(context, "FloatingMiniAppWindow", "MAXIMIZE_BOUNDS bounds=$bounds")
            // CRITICAL: DO NOT overwrite normal floating bounds in durable store with maximized bounds!
        }

        layoutParams.x = bounds.x
        layoutParams.y = bounds.y
        layoutParams.width = bounds.width
        layoutParams.height = bounds.height
        wm.updateBounds(windowId, bounds)
        relayoutEmbeddedSurface(bounds.width, getContentViewportHeight(bounds.height))
    }

    /**
     * Attaches global layout observer to temporarily shift window upward when obscured by soft keyboard.
     */
    private fun setupImeObserver(root: View) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val r = Rect()
            root.getWindowVisibleDisplayFrame(r)
            val wm = manager ?: FloatingWindowManager.getInstance(context)
            val screenH = wm.screenHeight
            val keypadHeight = screenH - r.bottom

            val isKeyboardOpen = keypadHeight > screenH * 0.15

            if (isKeyboardOpen) {
                val windowBottom = layoutParams.y + layoutParams.height
                if (windowBottom > r.bottom) {
                    val shift = (windowBottom - r.bottom) + (16 * context.resources.displayMetrics.density).toInt()
                    val shiftedY = max(0, layoutParams.y - shift)

                    if (layoutParams.y != shiftedY) {
                        LogKeeper.log(context, "FloatingMiniAppWindow", "IME_VISIBLE keypadHeight=$keypadHeight")
                        LogKeeper.log(context, "FloatingMiniAppWindow", "IME_TEMP_SHIFT id=$instanceId shift=$shift newY=$shiftedY previousY=${layoutParams.y}")
                        layoutParams.y = shiftedY
                        isImeShifted = true
                        LogKeeper.log(context, "FloatingMiniAppWindow", "IME_SHIFT id=$instanceId y=$shiftedY")
                        applyLayoutUpdate()
                    }
                }
            } else if (isImeShifted) {
                // Restore exact persistent bounds.y on keyboard dismiss
                LogKeeper.log(context, "FloatingMiniAppWindow", "IME_HIDDEN")
                LogKeeper.log(context, "FloatingMiniAppWindow", "IME_TEMP_RESTORE id=$instanceId restoredY=${bounds.y}")
                layoutParams.y = bounds.y
                isImeShifted = false
                LogKeeper.log(context, "FloatingMiniAppWindow", "IME_RESTORE id=$instanceId y=${bounds.y}")
                applyLayoutUpdate()
            }
        }
        globalLayoutListener = listener
        root.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    private fun applyLayoutUpdate() {
        val view = getView()
        if (view != null && isAttached) {
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.updateViewLayout(view, layoutParams)
            } catch (e: Exception) {
                LogKeeper.log(context, "FloatingMiniAppWindow", "IME layout update: ${e.message}")
            }
        }
    }

    fun getCurrentPayload(): Map<String, String> {
        noteEditText?.let {
            currentNoteText = it.text.toString()
        }
        return if (currentNoteText.isNotEmpty()) {
            mapOf(TestNotesMiniAppInstance.KEY_TEXT to currentNoteText)
        } else {
            emptyMap()
        }
    }

    override fun onShow() {
        super.onShow()
        val connectionManager = HeavyProcessConnectionManager.getInstance(context)
        val snapshot = durableStore.loadSnapshot(instanceId)

        val payloadMap = LinkedHashMap<String, String>().apply {
            put("instance_id", instanceId)
            put("app_type", appType)
            put("bounds_x", bounds.x.toString())
            put("bounds_y", bounds.y.toString())
            put("bounds_w", bounds.width.toString())
            put("bounds_h", bounds.height.toString())
            if (snapshot != null) {
                putAll(snapshot.payload)
            }
            putAll(getCurrentPayload())
        }

        val cmd = HeavyCommand(
            commandId = UUID.randomUUID().toString(),
            type = HeavyCommandType.START_OPERATION,
            targetId = instanceId,
            payload = payloadMap
        )
        val res = connectionManager.sendCommand(cmd, autoConnect = true)
        LogKeeper.log(context, "FloatingMiniAppWindow", "CMD_START_RESULT id=$instanceId success=${res.success} msg=${res.message}")
        // Only dispatch intent fallback if connection manager is completely DEAD to prevent duplicate start
        if (!res.success && connectionManager.state == com.example.core.ipc.ConnectionState.DEAD) {
            FloatingMiniAppBridge.dispatchToHeavy(
                context,
                Intent(MiniAppIpcContracts.ACTION_START_MINIAPP).apply {
                    putExtra(MiniAppIpcContracts.EXTRA_INSTANCE_ID, instanceId)
                    putExtra(MiniAppIpcContracts.EXTRA_APP_TYPE, appType)
                    putExtra(MiniAppIpcContracts.EXTRA_BOUNDS_X, bounds.x)
                    putExtra(MiniAppIpcContracts.EXTRA_BOUNDS_Y, bounds.y)
                    putExtra(MiniAppIpcContracts.EXTRA_BOUNDS_W, bounds.width)
                    putExtra(MiniAppIpcContracts.EXTRA_BOUNDS_H, bounds.height)
                }
            )
        }
    }

    override fun onHide() {
        super.onHide()
        val connectionManager = HeavyProcessConnectionManager.getInstance(context)
        val cmd = HeavyCommand(
            commandId = UUID.randomUUID().toString(),
            type = HeavyCommandType.PAUSE_OPERATION,
            targetId = instanceId,
            payload = mapOf("instance_id" to instanceId)
        )
        val res = connectionManager.sendCommand(cmd, autoConnect = true)
        if (!res.success && connectionManager.state == com.example.core.ipc.ConnectionState.DEAD) {
            FloatingMiniAppBridge.dispatchToHeavy(
                context,
                Intent(MiniAppIpcContracts.ACTION_PAUSE_MINIAPP).apply {
                    putExtra(MiniAppIpcContracts.EXTRA_INSTANCE_ID, instanceId)
                }
            )
        }
    }

    override fun onAttach(wm: WindowManager) {
        super.onAttach(wm)
        HeavyProcessConnectionManager.getInstance(context).addListener(connectionListener)
    }

    override fun onDetach(wm: WindowManager) {
        super.onDetach(wm)
        HeavyProcessConnectionManager.getInstance(context).removeListener(connectionListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        HeavyProcessConnectionManager.getInstance(context).removeListener(connectionListener)
        globalLayoutListener?.let {
            getView()?.viewTreeObserver?.removeOnGlobalLayoutListener(it)
        }
        globalLayoutListener = null

        noteEditText?.let {
            currentNoteText = it.text.toString()
            if (currentNoteText.isNotEmpty()) {
                durableStore.updatePayload(instanceId, mapOf(TestNotesMiniAppInstance.KEY_TEXT to currentNoteText))
            }
        }
        noteEditText = null

        try {
            (currentSurfacePackage as? SurfaceControlViewHost.SurfacePackage)?.release()
        } catch (_: Throwable) {}
        currentSurfacePackage = null
        embeddedSurfaceView = null

        LogKeeper.log(context, "FloatingMiniAppWindow", "SURFACE_DETACHED id=$instanceId")
        contentContainer?.removeAllViews()
        contentContainer = null
        LogKeeper.log(context, "FloatingMiniAppWindow", "CONTENT_RELEASED id=$instanceId")
    }

    companion object {
        const val TYPE_MINIAPP = "miniapp"
    }
}
