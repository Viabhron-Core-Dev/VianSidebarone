package com.example.feature.floating

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.core.FloatingWindow
import com.example.core.FloatingWindowManager
import com.example.core.LogKeeper
import com.example.core.WindowBounds
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

/**
 * FloatingMiniAppWindow: A specialized FloatingWindow representation in the Main process
 * for floating mini-apps whose business logic runs in :heavy.
 *
 * Implements the reusable floating shell:
 * 1. Top bar: Mini-app icon, title, sleep/minimize button, maximize/restore button, close button.
 * 2. Gestures: Dragging top bar moves window; double-tap on top bar toggles maximize/restore.
 * 3. Bottom-right corner: Interactive resize handle updating width and height.
 * 4. IME / Keyboard: Shifts upward temporarily when occluded by keyboard; restores exact position on close.
 * 5. Multi-window: Supports multiple concurrent windows without arbitrary limits.
 * 6. Independent Geometry: Separate real window bounds, maximize restore bounds, temporary IME position,
 *    and persistent button position.
 */
class FloatingMiniAppWindow(
    val instanceId: String,
    context: Context,
    val appType: String,
    private var windowTitle: String = ""
) : FloatingWindow(instanceId, context, TYPE_MINIAPP) {

    private val durableStore = DurableMiniAppStore.getInstance(context)
    private var isMaximized: Boolean = false
    private var maximizeRestoreBounds: WindowBounds? = null

    // IME temporary state
    private var isImeShifted: Boolean = false
    private var globalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    // View references
    private var contentContainer: FrameLayout? = null
    private var titleView: TextView? = null
    private var noteEditText: android.widget.EditText? = null
    private var currentNoteText: String = ""

    init {
        // Load existing maximize restore bounds if present
        val saved = durableStore.loadSnapshot(instanceId)
        maximizeRestoreBounds = saved?.maximizeRestoreBounds
        if (windowTitle.isEmpty()) {
            windowTitle = saved?.title?.takeIf { it.isNotEmpty() }
                ?: if (appType == "test_notes") "Test Notes" else appType.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }

    override fun createContentView(context: Context): View {
        val density = context.resources.displayMetrics.density
        val root = FrameLayout(context).apply {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14 * density
                setColor(Color.parseColor("#181825"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#45475A"))
            }
            background = bg
            elevation = 16 * density
        }

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
        contentContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setBackgroundColor(Color.parseColor("#1E1E2E"))
        }

        // Embed Heavy content view or fallback
        val heavyHost = HeavyFloatingHost.getInstance(context)
        val activeInstance = heavyHost.getActiveInstance(instanceId)
        val embeddedView = activeInstance?.createContentView(context)
        if (embeddedView != null) {
            contentContainer?.addView(embeddedView)
        } else {
            val savedSnapshot = durableStore.loadSnapshot(instanceId)
            currentNoteText = savedSnapshot?.payload?.get(TestNotesMiniAppInstance.KEY_TEXT)
                ?: savedSnapshot?.payload?.get("note_content")
                ?: TestNotesMiniAppInstance.DEFAULT_INSTRUCTIONAL_TEXT

            val clientView = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#181825"))
                setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
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

            contentContainer?.addView(clientView)
        }
        mainLayout.addView(contentContainer)

        root.addView(mainLayout)

        // 3. Bottom-Right Resize Handle
        val resizeHandle = createResizeHandle(context)
        root.addView(resizeHandle)

        // 4. Attach IME GlobalLayoutListener
        setupImeObserver(root)

        return root
    }

    private fun createTopBar(context: Context): LinearLayout {
        val density = context.resources.displayMetrics.density
        val topBarHeight = (44 * density).toInt()

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#2B2D42"))
            setPadding((12 * density).toInt(), 0, (8 * density).toInt(), 0)
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

        // Sleep / Minimize Button (_)
        val sleepBtn = createHeaderButton(context, "—") {
            FloatingMiniAppBridge.getInstance(context).sleepMiniApp(instanceId)
        }
        bar.addView(sleepBtn)

        // Maximize / Restore Button (□)
        val maxBtn = createHeaderButton(context, if (isMaximized) "❐" else "□") {
            toggleMaximize()
        }
        bar.addView(maxBtn)

        // Close Button (✕)
        val closeBtn = createHeaderButton(context, "✕") {
            FloatingMiniAppBridge.getInstance(context).closeMiniApp(instanceId, deleteDurable = true)
        }
        bar.addView(closeBtn)

        // Setup Top Bar Drag & Double-Tap Gestures
        setupTopBarGestures(bar, context)

        return bar
    }

    private fun createHeaderButton(context: Context, label: String, onClick: () -> Unit): TextView {
        val density = context.resources.displayMetrics.density
        return TextView(context).apply {
            text = label
            setTextColor(Color.parseColor("#CDD6F4"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            setOnClickListener { onClick() }
        }
    }

    private fun setupTopBarGestures(topBar: View, context: Context) {
        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleMaximize()
                return true
            }
        })

        var dragStartRawX = 0f
        var dragStartRawY = 0f
        var initialBoundsX = 0
        var initialBoundsY = 0

        topBar.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    initialBoundsX = bounds.x
                    initialBoundsY = bounds.y
                    manager?.bringToFront(windowId)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - dragStartRawX).toInt()
                    val dy = (event.rawY - dragStartRawY).toInt()

                    val wm = manager ?: FloatingWindowManager.getInstance(context)
                    val screenW = wm.screenWidth
                    val screenH = wm.screenHeight

                    val newX = (initialBoundsX + dx).coerceIn(0, max(0, screenW - bounds.width))
                    val newY = (initialBoundsY + dy).coerceIn(0, max(0, screenH - bounds.height))

                    if (newX != bounds.x || newY != bounds.y) {
                        bounds.x = newX
                        bounds.y = newY
                        layoutParams.x = newX
                        layoutParams.y = newY
                        wm.updateBounds(windowId, bounds)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Commit real window bounds exclusively
                    durableStore.updateWindowBounds(instanceId, bounds)
                    true
                }

                else -> false
            }
        }
    }

    private fun createResizeHandle(context: Context): View {
        val density = context.resources.displayMetrics.density
        val handleSizePx = (36 * density).toInt()

        val handle = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                handleSizePx,
                handleSizePx,
                Gravity.BOTTOM or Gravity.END
            )

            // Visual corner indicator
            val indicator = View(context).apply {
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#6C7086"))
                }
                background = bg
                layoutParams = FrameLayout.LayoutParams(
                    (10 * density).toInt(),
                    (10 * density).toInt(),
                    Gravity.BOTTOM or Gravity.END
                ).apply {
                    setMargins(0, 0, (6 * density).toInt(), (6 * density).toInt())
                }
            }
            addView(indicator)
        }

        var resizeStartRawX = 0f
        var resizeStartRawY = 0f
        var startWidth = 0
        var startHeight = 0

        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resizeStartRawX = event.rawX
                    resizeStartRawY = event.rawY
                    startWidth = bounds.width
                    startHeight = bounds.height
                    manager?.bringToFront(windowId)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dw = (event.rawX - resizeStartRawX).toInt()
                    val dh = (event.rawY - resizeStartRawY).toInt()

                    val wm = manager ?: FloatingWindowManager.getInstance(context)
                    val screenW = wm.screenWidth
                    val screenH = wm.screenHeight

                    val minW = (240 * density).toInt()
                    val minH = (200 * density).toInt()
                    val maxW = screenW - bounds.x
                    val maxH = screenH - bounds.y

                    val newW = (startWidth + dw).coerceIn(minW, max(minW, maxW))
                    val newH = (startHeight + dh).coerceIn(minH, max(minH, maxH))

                    if (newW != bounds.width || newH != bounds.height) {
                        bounds.width = newW
                        bounds.height = newH
                        layoutParams.width = newW
                        layoutParams.height = newH
                        wm.updateBounds(windowId, bounds)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    durableStore.updateWindowBounds(instanceId, bounds)
                    true
                }

                else -> false
            }
        }

        return handle
    }

    /**
     * Toggles between maximized state and restored geometric bounds.
     */
    fun toggleMaximize() {
        val wm = manager ?: FloatingWindowManager.getInstance(context)
        val density = context.resources.displayMetrics.density

        if (isMaximized) {
            // Restore previous bounds
            val target = maximizeRestoreBounds ?: WindowBounds(
                (20 * density).toInt(),
                (40 * density).toInt(),
                (320 * density).toInt(),
                (420 * density).toInt()
            )
            bounds = target.copyBounds()
            isMaximized = false
            durableStore.updateMaximizeRestoreBounds(instanceId, null)
        } else {
            // Save restore bounds before maximizing
            maximizeRestoreBounds = bounds.copyBounds()
            durableStore.updateMaximizeRestoreBounds(instanceId, maximizeRestoreBounds)

            val marginH = (12 * density).toInt()
            val marginV = (28 * density).toInt()
            bounds.x = marginH
            bounds.y = marginV
            bounds.width = max(300, wm.screenWidth - (marginH * 2))
            bounds.height = max(400, wm.screenHeight - (marginV * 2))
            isMaximized = true
        }

        layoutParams.x = bounds.x
        layoutParams.y = bounds.y
        layoutParams.width = bounds.width
        layoutParams.height = bounds.height
        wm.updateBounds(windowId, bounds)
        durableStore.updateWindowBounds(instanceId, bounds)
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
                val windowBottom = bounds.y + bounds.height
                if (windowBottom > r.bottom) {
                    val shift = (windowBottom - r.bottom) + (16 * context.resources.displayMetrics.density).toInt()
                    val shiftedY = max(0, bounds.y - shift)

                    if (layoutParams.y != shiftedY) {
                        layoutParams.y = shiftedY
                        isImeShifted = true
                        applyLayoutUpdate()
                    }
                }
            } else if (isImeShifted) {
                // Restore exact persistent bounds.y on keyboard dismiss
                layoutParams.y = bounds.y
                isImeShifted = false
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
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
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
        val connectionManager = com.example.core.ipc.HeavyProcessConnectionManager.getInstance(context)
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

        val cmd = com.example.core.ipc.HeavyCommand(
            commandId = UUID.randomUUID().toString(),
            type = com.example.core.ipc.HeavyCommandType.START_OPERATION,
            targetId = instanceId,
            payload = payloadMap
        )
        val res = connectionManager.sendCommand(cmd, autoConnect = true)
        if (!res.success) {
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
        val connectionManager = com.example.core.ipc.HeavyProcessConnectionManager.getInstance(context)
        val cmd = com.example.core.ipc.HeavyCommand(
            commandId = UUID.randomUUID().toString(),
            type = com.example.core.ipc.HeavyCommandType.PAUSE_OPERATION,
            targetId = instanceId,
            payload = mapOf("instance_id" to instanceId)
        )
        val res = connectionManager.sendCommand(cmd, autoConnect = true)
        if (!res.success) {
            FloatingMiniAppBridge.dispatchToHeavy(
                context,
                Intent(MiniAppIpcContracts.ACTION_PAUSE_MINIAPP).apply {
                    putExtra(MiniAppIpcContracts.EXTRA_INSTANCE_ID, instanceId)
                }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
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

        contentContainer?.removeAllViews()
        contentContainer = null
    }

    companion object {
        const val TYPE_MINIAPP = "miniapp"
    }
}
