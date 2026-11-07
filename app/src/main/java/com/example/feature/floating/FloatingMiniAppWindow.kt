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
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.ArrowKeyMovementMethod
import android.text.method.TextKeyListener
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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.core.FloatingWindow
import com.example.core.FloatingWindowManager
import com.example.core.LogKeeper
import com.example.core.WindowBounds
import com.example.core.ipc.ConnectionState
import com.example.core.ipc.HeavyCommand
import com.example.core.ipc.HeavyCommandType
import com.example.core.ipc.HeavyConnectionListener
import com.example.core.ipc.HeavyEvent
import com.example.core.ipc.HeavyProcessConnectionManager
import com.example.feature.floating.HeavyFloatingHost
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
    var isImeShifted: Boolean = false
        internal set
    var imeShiftOffsetY: Int = 0
        internal set
    private var globalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    // Resize state
    var isResizing: Boolean = false
        internal set

    // Safe display bounds cache
    private var cachedSafeDisplayBounds: WindowBounds? = null
    private var lastSafeBoundsQueryTime: Long = 0L

    // View references
    private var rootCardView: FrameLayout? = null
    private var rootCardBackground: GradientDrawable? = null
    private var contentContainer: FrameLayout? = null
    private var titleView: TextView? = null
    private var noteEditText: EditText? = null
    private var imeBridgeEditText: EditText? = null
    private var isSyncingImeBridge: Boolean = false
    enum class TextAuthority {
        NONE,
        SNAPSHOT,
        HEAVY,
        IME_BRIDGE,
        FALLBACK
    }

    private var activeTextAuthority: TextAuthority = TextAuthority.NONE
    private var hasUserEditedText: Boolean = false
    private var hasRealWindowFocus: Boolean = false
    private var isAwaitingWindowFocusForIme: Boolean = false
    private var activeImeSessionId: String = "none"
    private var currentNoteText: String = ""
    private var embeddedSurfaceView: SurfaceView? = null
    private var currentSurfacePackage: Any? = null // SurfaceControlViewHost.SurfacePackage
    private var surfaceReady: Boolean = false
    private var isSurfaceAttached: Boolean = false
    private var lastRelayoutWidth: Int = -1
    private var lastRelayoutHeight: Int = -1

    private var editPopoverView: View? = null
    var currentFontSizeSp: Float = 15f
        private set
    var currentFontStyle: String = "regular"
        private set
    var currentWordWrap: Boolean = true
        private set
    var currentLineSpacingMult: Float = 1.2f
        private set

    var isCardClippedToOutline: Boolean = true
        private set

    // Diagnostic Mode (Test A: Isolated Main-Process IME Test)
    var isDiagnosticMode: Boolean = false
        private set
    private var diagnosticContainer: LinearLayout? = null
    private var diagnosticEditText: EditText? = null
    private var diagnosticModeButton: TextView? = null
    private var isDiagnosticAwaitingWindowFocus: Boolean = false
    private var diagnosticSessionId: String = "none"

    // Content reveal & timing state
    private var contentPlaceholderView: View? = null
    var isContentRevealed: Boolean = false
        private set
    private var surfaceAttachTime: Long = 0L
    private val revealSurfaceTimeoutRunnable = Runnable {
        revealEmbeddedContent("timeout_safeguard")
    }

    fun isContentRevealedForTesting(): Boolean = isContentRevealed
    fun getContentPlaceholderViewForTesting(): View? = contentPlaceholderView
    fun resetContentRevealForTesting() {
        isContentRevealed = false
        contentPlaceholderView?.visibility = View.VISIBLE
    }
    fun revealEmbeddedContentForTesting(reason: String = "test") {
        revealEmbeddedContent(reason)
    }

    fun isDiagnosticModeForTesting(): Boolean = isDiagnosticMode
    fun getDiagnosticEditTextForTesting(): EditText? = diagnosticEditText
    fun getDiagnosticModeButtonForTesting(): TextView? = diagnosticModeButton

    fun getImeBridgeEditTextForTesting(): EditText? = imeBridgeEditText
    fun getFallbackEditTextForTesting(): EditText? = noteEditText
    fun getAuthoritativeNoteText(): String = currentNoteText
    fun getEditPopoverForTesting(): View? = editPopoverView

    fun getContentViewportHeight(windowHeight: Int): Int {
        val density = context.resources.displayMetrics.density
        val topBarHeight = (38 * density).toInt()
        return maxOf(0, windowHeight - topBarHeight)
    }

    override fun setWindowBounds(newBounds: WindowBounds) {
        super.setWindowBounds(newBounds)
        if (!isMaximized && !isImeShifted) {
            normalFloatingBounds = newBounds.copyBounds()
        }
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
        override fun onHeavyEvent(event: HeavyEvent) {
            val target = event.payload["instanceId"] ?: event.sourceId
            if (target != instanceId) return
            val action = event.payload["action"] ?: return
            mainHandler.post {
                when (action) {
                    TestNotesMiniAppInstance.ACTION_MINIAPP_EDITOR_FOCUS -> {
                        val text = event.payload[TestNotesMiniAppInstance.KEY_TEXT] ?: currentNoteText
                        val selStart = event.payload["selStart"]?.toIntOrNull() ?: text.length
                        val selEnd = event.payload["selEnd"]?.toIntOrNull() ?: selStart
                        val sess = event.payload["sessionId"] ?: "ime_${System.currentTimeMillis() % 100000}"
                        LogKeeper.log(
                            context,
                            "TestNotesImeDebug",
                            "MAIN_RECEIVE_FOCUS_REQUEST sess=$sess id=$instanceId sel=($selStart,$selEnd) textLen=${text.length}"
                        )
                        if (activeTextAuthority != TextAuthority.IME_BRIDGE) {
                            currentNoteText = text
                            activeTextAuthority = TextAuthority.HEAVY
                        }
                        activateEmbeddedEditorIme(text, selStart, selEnd, sess)
                    }
                    TestNotesMiniAppInstance.ACTION_MINIAPP_SELECTION_CHANGED -> {
                        val selStart = event.payload["selStart"]?.toIntOrNull() ?: 0
                        val selEnd = event.payload["selEnd"]?.toIntOrNull() ?: selStart
                        updateImeBridgeSelection(selStart, selEnd)
                    }
                    TestNotesMiniAppInstance.ACTION_MINIAPP_TEXT_CHANGED -> {
                        val text = event.payload[TestNotesMiniAppInstance.KEY_TEXT] ?: return@post
                        val selStart = event.payload["selStart"]?.toIntOrNull() ?: text.length
                        val selEnd = event.payload["selEnd"]?.toIntOrNull() ?: selStart
                        currentNoteText = text
                        activeTextAuthority = TextAuthority.HEAVY
                        hasUserEditedText = true
                        LogKeeper.log(
                            context,
                            "TestNotesImeDebug",
                            "TEXT_CHANGE_SYNC_ACK direction=heavy_to_main textLen=${text.length} sel=($selStart,$selEnd)"
                        )
                        updateImeBridgeTextAndSelection(text, selStart, selEnd)
                    }
                    "miniapp_frame_committed" -> {
                        val commitTime = event.payload["commitTime"]?.toLongOrNull() ?: SystemClock.elapsedRealtime()
                        val now = SystemClock.elapsedRealtime()
                        LogKeeper.log(
                            context,
                            "FloatingMiniAppTiming",
                            "MAIN_RECEIVE_FRAME_COMMITTED id=$instanceId commitTime=$commitTime now=$now latencyMs=${now - commitTime}"
                        )
                        revealEmbeddedContent("frame_committed")
                    }
                }
            }
        }
    }

    init {
        layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        layoutParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        val saved = durableStore.loadSnapshot(instanceId)
        maximizeRestoreBounds = saved?.maximizeRestoreBounds
        normalFloatingBounds = saved?.windowBounds?.copyBounds() ?: bounds.copyBounds()
        val initialSavedText = saved?.payload?.get(TestNotesMiniAppInstance.KEY_TEXT)
            ?: saved?.payload?.get("text")
            ?: saved?.payload?.get("note_content")
        if (initialSavedText != null) {
            currentNoteText = initialSavedText
            activeTextAuthority = TextAuthority.SNAPSHOT
            hasUserEditedText = true
        }
        saved?.payload?.get("font_size")?.toFloatOrNull()?.let { currentFontSizeSp = it }
        saved?.payload?.get("font_style")?.let { currentFontStyle = it }
        saved?.payload?.get("word_wrap")?.toBooleanStrictOrNull()?.let { currentWordWrap = it }
        saved?.payload?.get("line_spacing")?.toFloatOrNull()?.let { currentLineSpacingMult = it }
        if (windowTitle.isEmpty()) {
            windowTitle = saved?.title?.takeIf { it.isNotEmpty() }
                ?: if (appType == "test_notes" || appType == "note") "Note" else appType.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }

    override fun createContentView(context: Context): View {
        val density = context.resources.displayMetrics.density
        val cardCornerRadius = 16 * density
        isCardClippedToOutline = true

        val root = object : FrameLayout(context) {
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                hasRealWindowFocus = hasWindowFocus
                val sess = if (isDiagnosticMode) diagnosticSessionId else activeImeSessionId
                LogKeeper.log(
                    context,
                    "TestNotesImeDebug",
                    "ACTUAL_MAIN_WINDOW_FOCUS_CALLBACK sess=$sess hasFocus=$hasWindowFocus winFocus=${hasWindowFocus()}"
                )
                if (hasWindowFocus && isDiagnosticMode && isDiagnosticAwaitingWindowFocus) {
                    isDiagnosticAwaitingWindowFocus = false
                    triggerDiagnosticImeActivation(diagnosticSessionId)
                } else if (hasWindowFocus && isAwaitingWindowFocusForIme) {
                    isAwaitingWindowFocusForIme = false
                    triggerImeFocusOnBridge()
                }
            }
        }.apply {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = cardCornerRadius
                setColor(Color.parseColor("#181825"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#45475A"))
            }
            rootCardBackground = bg
            background = bg
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    val r = if (isMaximized) 0f else cardCornerRadius
                    outline.setRoundRect(0, 0, view.width, view.height, r)
                }
            }
            // Clip all children (including top bar, content, and surface) to the card's 16dp rounded outer shape
            clipToOutline = true
            elevation = 16 * density
        }
        root.viewTreeObserver?.addOnWindowFocusChangeListener { hasFocus ->
            hasRealWindowFocus = hasFocus
            val sess = if (isDiagnosticMode) diagnosticSessionId else activeImeSessionId
            LogKeeper.log(
                context,
                "TestNotesImeDebug",
                "ACTUAL_MAIN_WINDOW_FOCUS_CALLBACK sess=$sess hasFocus=$hasFocus winFocus=${root.hasWindowFocus()}"
            )
            if (hasFocus && isDiagnosticMode && isDiagnosticAwaitingWindowFocus) {
                isDiagnosticAwaitingWindowFocus = false
                triggerDiagnosticImeActivation(diagnosticSessionId)
            } else if (hasFocus && isAwaitingWindowFocusForIme) {
                isAwaitingWindowFocusForIme = false
                triggerImeFocusOnBridge()
            }
        }
        rootCardView = root

        // Window-level IME InputConnection bridge for cross-process SurfaceControlViewHost editors
        val bridgeEdit = createImeBridgeEditText(context)
        imeBridgeEditText = bridgeEdit
        root.addView(bridgeEdit)

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

        // 2. Content Container with matching 16dp bottom rounded corners
        val container = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            val containerBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, cardCornerRadius, cardCornerRadius, cardCornerRadius, cardCornerRadius)
                setColor(Color.parseColor("#1E1E2E"))
            }
            background = containerBg
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    val r = if (isMaximized) 0f else cardCornerRadius
                    outline.setRoundRect(0, 0, view.width, view.height, r)
                }
            }
            clipToOutline = true
        }
        contentContainer = container
        mainLayout.addView(container)
        // 3a. Neutral Content Placeholder (prevents initial transparency / un-rendered hole)
        val placeholder = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            val pBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, cardCornerRadius, cardCornerRadius, cardCornerRadius, cardCornerRadius)
                setColor(Color.parseColor("#181825"))
            }
            background = pBg
            visibility = View.VISIBLE
        }
        contentPlaceholderView = placeholder
        container.addView(placeholder)

        // 3. Embedded Heavy Content Setup
        setupEmbeddedContent(context, container)

        // 3b. Diagnostic Main-Process IME Container (Test A: Isolated Main-process IME)
        val diagContainer = createDiagnosticContainer(context)
        container.addView(diagContainer)

        root.addView(mainLayout)

        // 4. Compact in-window Edit Popover
        val popover = createEditPopover(context)
        editPopoverView = popover
        root.addView(popover)

        // 5. Three-Control Sticker [ ×  —  ↘ ]
        val sticker = createBottomRightSticker(context)
        root.addView(sticker)

        // 5. Attach IME Insets Listener
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            LogKeeper.log(
                context,
                "TestNotesImeDebug",
                "IME_VISIBILITY_INSETS visible=$imeVisible height=$imeHeight"
            )
            val isWaitingForIme = isAwaitingWindowFocusForIme || isDiagnosticAwaitingWindowFocus || (isDiagnosticMode && diagnosticEditText?.hasFocus() == true)
            if (!imeVisible && (layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0 && !isResizing && !isWaitingForIme) {
                layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                applyLayoutUpdate()
            }
            insets
        }

        // 6. Attach IME GlobalLayoutListener
        setupImeObserver(root)

        return root
    }

    private fun createImeBridgeEditText(context: Context): EditText {
        val bridge = object : EditText(context) {
            private var stubSelStart: Int = 0
            private var stubSelEnd: Int = 0

            override fun hasWindowFocus(): Boolean {
                return hasRealWindowFocus || super.hasWindowFocus()
            }
            override fun onCheckIsTextEditor(): Boolean = true
            override fun onTouchEvent(event: MotionEvent?): Boolean = false
            override fun onCreateInputConnection(outAttrs: EditorInfo?): InputConnection? {
                val ic = try {
                    super.onCreateInputConnection(outAttrs)
                } catch (_: Throwable) {
                    null
                }
                LogKeeper.log(
                    context,
                    "TestNotesImeDebug",
                    "INPUT_CONNECTION_CREATED target=main_bridge icNull=${ic == null} outAttrsType=${outAttrs?.inputType} imeOptions=${outAttrs?.imeOptions}"
                )
                return ic
            }
            override fun getText(): Editable? {
                return super.getText()
            }
            override fun setSelection(start: Int, stop: Int) {
                stubSelStart = start
                stubSelEnd = stop
                try { super.setSelection(start, stop) } catch (_: Throwable) {}
            }
            override fun getSelectionStart(): Int {
                return if (super.getText() != null) super.getSelectionStart() else stubSelStart
            }
            override fun getSelectionEnd(): Int {
                return if (super.getText() != null) super.getSelectionEnd() else stubSelEnd
            }
            override fun onSelectionChanged(selStart: Int, selEnd: Int) {
                super.onSelectionChanged(selStart, selEnd)
                if (!isSyncingImeBridge && hasFocus()) {
                    val txt = text?.toString() ?: ""
                    syncLiveTextToHeavy(txt, selStart.coerceAtLeast(0), selEnd.coerceAtLeast(0))
                }
            }
        }.apply {
            alpha = 0.01f
            isEnabled = true
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = false
            isLongClickable = false
            isCursorVisible = false
            isSingleLine = false
            maxLines = Int.MAX_VALUE
            setHorizontallyScrolling(false)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            movementMethod = ArrowKeyMovementMethod.getInstance()
            keyListener = TextKeyListener.getInstance(true, TextKeyListener.Capitalize.SENTENCES)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )

            // Attach native TextWatcher only after EditText has finished construction
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (isSyncingImeBridge) return
                    val updated = s?.toString() ?: ""
                    currentNoteText = updated
                    activeTextAuthority = TextAuthority.IME_BRIDGE
                    hasUserEditedText = true
                    val sStart = selectionStart.coerceIn(0, updated.length)
                    val sEnd = selectionEnd.coerceIn(0, updated.length)
                    syncLiveTextToHeavy(updated, sStart, sEnd)
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

        // Programmatic initialization via helper
        val initialText = if (hasUserEditedText || activeTextAuthority != TextAuthority.NONE) {
            currentNoteText
        } else {
            currentNoteText.ifEmpty { TestNotesMiniAppInstance.DEFAULT_INSTRUCTIONAL_TEXT }
        }
        updateImeBridgeTextAndSelection(initialText, initialText.length, initialText.length)

        return bridge
    }

    /**
     * Activates the Main window's IME InputConnection bridge when the user taps the embedded
     * EditText inside SurfaceControlViewHost in :heavy.
     */
    fun activateEmbeddedEditorIme(
        text: String,
        selStart: Int,
        selEnd: Int,
        sessionId: String = "ime_${System.currentTimeMillis() % 100000}"
    ) {
        activeImeSessionId = sessionId
        manager?.setFocus(windowId)
        if (manager?.getTopVisibleWindow()?.windowId != windowId) {
            manager?.bringToFront(windowId)
        }

        val flagsBefore = layoutParams.flags
        val focusClearedFlags = (layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) and
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM.inv()
        LogKeeper.log(
            context,
            "TestNotesImeDebug",
            "OVERLAY_FOCUS_FLAGS sess=$sessionId flagsBefore=0x${Integer.toHexString(flagsBefore)} flagsAfter=0x${Integer.toHexString(focusClearedFlags)} changed=${flagsBefore != focusClearedFlags}"
        )
        if (layoutParams.flags != focusClearedFlags) {
            layoutParams.flags = focusClearedFlags
            applyLayoutUpdate()
        }

        updateImeBridgeTextAndSelection(text, selStart, selEnd)

        val root = getView()
        val bridge = imeBridgeEditText
        bridge?.requestFocus()

        val alreadyHasFocus = (root?.hasWindowFocus() == true) || hasRealWindowFocus
        if (alreadyHasFocus && bridge != null) {
            isAwaitingWindowFocusForIme = false
            triggerImeFocusOnBridge()
        } else {
            isAwaitingWindowFocusForIme = true
            root?.post {
                if (((root.hasWindowFocus()) || hasRealWindowFocus) && isAwaitingWindowFocusForIme) {
                    isAwaitingWindowFocusForIme = false
                    triggerImeFocusOnBridge()
                } else if (isAwaitingWindowFocusForIme && bridge != null && bridge.windowToken != null) {
                    triggerImeFocusOnBridge()
                }
            }
        }

        LogKeeper.log(
            context,
            "FloatingMiniAppWindow",
            "EDITOR_FOCUS_ACTIVATED id=$instanceId sess=$sessionId sel=($selStart,$selEnd) textLength=${text.length}"
        )
    }

    private fun triggerImeFocusOnBridge() {
        val bridge = imeBridgeEditText ?: return
        val sess = activeImeSessionId
        val isFocused = bridge.requestFocus()
        val hasWinFocus = bridge.hasWindowFocus() || hasRealWindowFocus
        val isAttached = bridge.isAttachedToWindow
        val hasToken = bridge.windowToken != null
        LogKeeper.log(
            context,
            "TestNotesImeDebug",
            "MAIN_EDIT_TEXT_STATE sess=$sess isFocused=$isFocused hasWindowFocus=$hasWinFocus isAttached=$isAttached hasToken=$hasToken"
        )

        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val showResult = imm?.showSoftInput(bridge, InputMethodManager.SHOW_IMPLICIT) ?: false
        var insetsResult = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                bridge.windowInsetsController?.show(WindowInsets.Type.ime())
                insetsResult = true
            } catch (_: Throwable) {}
        }
        LogKeeper.log(
            context,
            "TestNotesImeDebug",
            "SHOW_SOFT_INPUT_RESULT sess=$sess result=$showResult insetsControllerResult=$insetsResult"
        )
    }

    fun updateImeBridgeTextAndSelection(text: String, selStart: Int, selEnd: Int) {
        val bridge = imeBridgeEditText ?: return
        isSyncingImeBridge = true
        try {
            currentNoteText = text
            if (bridge.text?.toString() != text) {
                bridge.setText(text, TextView.BufferType.EDITABLE)
            }
            val len = text.length
            val sStart = selStart.coerceIn(0, len)
            val sEnd = selEnd.coerceIn(sStart, len)
            bridge.setSelection(sStart, sEnd)
            try {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.updateSelection(bridge, sStart, sEnd, -1, -1)
            } catch (_: Throwable) {}
        } finally {
            isSyncingImeBridge = false
        }
    }

    /**
     * Programmatic / test helper to simulate user typing or clearing text on the IME bridge.
     * In real Android runtime, physical typing triggers the attached native TextWatcher.
     * In headless JVM tests with mockable android.jar, this helper updates the bridge text and dispatches live sync.
     */
    fun simulateBridgeTextInputForTesting(text: String, selStart: Int = text.length, selEnd: Int = selStart) {
        currentNoteText = text
        activeTextAuthority = TextAuthority.IME_BRIDGE
        hasUserEditedText = true
        updateImeBridgeTextAndSelection(text, selStart, selEnd)
        syncLiveTextToHeavy(text, selStart, selEnd)
    }

    private fun updateImeBridgeSelection(selStart: Int, selEnd: Int) {
        val bridge = imeBridgeEditText ?: return
        isSyncingImeBridge = true
        try {
            val len = bridge.text?.length ?: 0
            val sStart = selStart.coerceIn(0, len)
            val sEnd = selEnd.coerceIn(sStart, len)
            bridge.setSelection(sStart, sEnd)
            try {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.updateSelection(bridge, sStart, sEnd, -1, -1)
            } catch (_: Throwable) {}
        } finally {
            isSyncingImeBridge = false
        }
    }

    private fun syncLiveTextToHeavy(
        text: String,
        selStart: Int,
        selEnd: Int,
        sessionId: String = activeImeSessionId
    ) {
        LogKeeper.log(
            context,
            "TestNotesImeDebug",
            "TEXT_CHANGE_SYNC_EVENT direction=main_to_heavy textLen=${text.length} sel=($selStart,$selEnd)"
        )
        val connectionManager = HeavyProcessConnectionManager.getInstance(context)
        val cmd = HeavyCommand(
            commandId = UUID.randomUUID().toString(),
            type = HeavyCommandType.PAUSE_OPERATION,
            targetId = instanceId,
            payload = mapOf(
                "action" to "sync_live_text",
                "instance_id" to instanceId,
                "sessionId" to sessionId,
                "app_type" to appType,
                TestNotesMiniAppInstance.KEY_TEXT to text,
                "text" to text,
                "sel_start" to selStart.toString(),
                "sel_end" to selEnd.toString()
            )
        )
        if (connectionManager.state == ConnectionState.CONNECTED) {
            connectionManager.sendCommand(cmd, autoConnect = false)
        } else {
            HeavyFloatingHost.getInstance(context).updateLiveInstanceText(instanceId, text, selStart, selEnd, sessionId)
        }
    }

    private fun applyCardCornerRadius(maximized: Boolean) {
        val density = context.resources.displayMetrics.density
        val radius = if (maximized) 0f else 16 * density
        isCardClippedToOutline = !maximized
        rootCardBackground?.cornerRadius = radius
        rootCardView?.clipToOutline = !maximized
        rootCardView?.invalidateOutline()
        contentContainer?.clipToOutline = !maximized
        contentContainer?.invalidateOutline()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && embeddedSurfaceView != null) {
            try {
                val method = SurfaceView::class.java.getMethod("setCornerRadius", Float::class.javaPrimitiveType)
                method.invoke(embeddedSurfaceView, radius)
            } catch (_: Throwable) {}
            embeddedSurfaceView?.clipToOutline = !maximized
            embeddedSurfaceView?.invalidateOutline()
        }
    }

    private fun setupEmbeddedContent(context: Context, container: FrameLayout) {
        LogKeeper.log(context, "FloatingMiniAppWindow", "CONTENT_REQUEST id=$instanceId type=$appType")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val density = context.resources.displayMetrics.density
            val cardCornerRadius = 16 * density
            val sv = SurfaceView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                alpha = 0f
                setZOrderMediaOverlay(true)
                holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        val r = if (isMaximized) 0f else cardCornerRadius
                        outline.setRoundRect(0, 0, view.width, view.height, r)
                    }
                }
                clipToOutline = true
            }
            try {
                val method = SurfaceView::class.java.getMethod("setCornerRadius", Float::class.javaPrimitiveType)
                method.invoke(sv, cardCornerRadius)
            } catch (_: Throwable) {}
            embeddedSurfaceView = sv

            sv.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    val tCreated = SystemClock.elapsedRealtime()
                    LogKeeper.log(context, "FloatingMiniAppTiming", "MAIN_SURFACE_CREATED id=$instanceId elapsedRealtime=$tCreated")
                    surfaceReady = true
                    tryAttachSurfacePackage()
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    val tChanged = SystemClock.elapsedRealtime()
                    LogKeeper.log(context, "FloatingMiniAppTiming", "MAIN_SURFACE_CHANGED id=$instanceId width=$width height=$height elapsedRealtime=$tChanged")
                    if (isResizing) return
                    val expectedViewportH = getContentViewportHeight(bounds.height)
                    if (width != bounds.width || height != expectedViewportH) {
                        return
                    }
                    relayoutEmbeddedSurface(width, height)
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    surfaceReady = false
                    isSurfaceAttached = false
                    isContentRevealed = false
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

        val tAttachStart = SystemClock.elapsedRealtime()
        LogKeeper.log(context, "FloatingMiniAppTiming", "MAIN_SURFACE_ATTACH_START id=$instanceId width=$w height=$h elapsedRealtime=$tAttachStart")

        val reply = connectionManager.createSurfacePackage(params)
        if (reply != null && reply.getBoolean("success")) {
            reply.classLoader = SurfaceControlViewHost.SurfacePackage::class.java.classLoader
            @Suppress("DEPRECATION")
            val sp = reply.getParcelable<SurfaceControlViewHost.SurfacePackage>("surfacePackage")
            if (sp != null) {
                val tPkgReceived = SystemClock.elapsedRealtime()
                LogKeeper.log(context, "FloatingMiniAppTiming", "MAIN_SURFACE_PACKAGE_RECEIVED id=$instanceId elapsedRealtime=$tPkgReceived latencyMs=${tPkgReceived - tAttachStart}")
                currentSurfacePackage = sp
                sv.setChildSurfacePackage(sp)
                // Prime relayout dimensions to prevent immediate redundant IPC from surfaceChanged
                lastRelayoutWidth = w
                lastRelayoutHeight = h
                isSurfaceAttached = true
                surfaceAttachTime = tPkgReceived

                // Schedule safeguard timeout so placeholder never hangs if frame committed event is delayed
                mainHandler.removeCallbacks(revealSurfaceTimeoutRunnable)
                mainHandler.postDelayed(revealSurfaceTimeoutRunnable, 400L)

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

    /**
     * Seamlessly reveals the embedded SurfaceView and dismisses the neutral placeholder
     * once the first complete frame is committed by Heavy (or after the safeguard timeout).
     */
    fun revealEmbeddedContent(reason: String = "frame_committed") {
        mainHandler.removeCallbacks(revealSurfaceTimeoutRunnable)
        val wasAlreadyRevealed = isContentRevealed
        isContentRevealed = true

        val now = SystemClock.elapsedRealtime()
        val durationSinceAttach = if (surfaceAttachTime > 0L) now - surfaceAttachTime else 0L
        if (!wasAlreadyRevealed) {
            LogKeeper.log(
                context,
                "FloatingMiniAppTiming",
                "MAIN_SURFACE_REVEALED id=$instanceId reason=$reason durationSinceAttachMs=$durationSinceAttach elapsedRealtime=$now"
            )
        }

        embeddedSurfaceView?.apply {
            visibility = View.VISIBLE
            alpha = 1f
        }
        contentPlaceholderView?.visibility = View.GONE
    }

    fun relayoutEmbeddedSurface(viewportWidth: Int, viewportHeight: Int) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        if (viewportWidth == lastRelayoutWidth && viewportHeight == lastRelayoutHeight) return
        val tRelayout = SystemClock.elapsedRealtime()
        LogKeeper.log(context, "FloatingMiniAppTiming", "MAIN_RELAYOUT_SURFACE id=$instanceId width=$viewportWidth height=$viewportHeight elapsedRealtime=$tRelayout")
        lastRelayoutWidth = viewportWidth
        lastRelayoutHeight = viewportHeight
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && embeddedSurfaceView != null) {
            try {
                HeavyProcessConnectionManager.getInstance(context).relayoutSurfaceHost(instanceId, viewportWidth, viewportHeight)
            } catch (_: Throwable) {}
        }
    }

    fun getLastRelayoutDimensions(): Pair<Int, Int> = Pair(lastRelayoutWidth, lastRelayoutHeight)

    private fun attachFallbackContentView(context: Context, container: FrameLayout, reason: String = "fallback") {
        if (container.findViewById<View>(android.R.id.text1) != null) return
        contentPlaceholderView?.visibility = View.GONE
        isContentRevealed = true

        val density = context.resources.displayMetrics.density
        val savedSnapshot = durableStore.loadSnapshot(instanceId)
        currentNoteText = savedSnapshot?.payload?.get(TestNotesMiniAppInstance.KEY_TEXT)
            ?: savedSnapshot?.payload?.get("text")
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

        val edit = EmbeddedNoteEditText(
            context = context,
            onEditorFocusedOrTouched = { text, selStart, selEnd, _ ->
                currentNoteText = text
                manager?.setFocus(windowId)
                if (manager?.getTopVisibleWindow()?.windowId != windowId) {
                    manager?.bringToFront(windowId)
                }
            },
            onEditorSelectionChanged = { _, _ -> }
        ).apply {
            hint = "Type here... Text is persisted durably on Sleep."
            setHintTextColor(Color.parseColor("#6C7086"))
            setTextColor(Color.WHITE)
            highlightColor = Color.parseColor("#585B70")
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
            setSelection(currentNoteText.length)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    currentNoteText = s?.toString() ?: ""
                    activeTextAuthority = TextAuthority.FALLBACK
                    hasUserEditedText = true
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        noteEditText = edit
        clientView.addView(edit)

        container.addView(clientView)
    }

    fun toggleDiagnosticMode() {
        if (isDiagnosticMode) {
            exitDiagnosticMode()
        } else {
            enterDiagnosticMode()
        }
    }

    fun enterDiagnosticMode() {
        if (isDiagnosticMode) return
        isDiagnosticMode = true
        LogKeeper.log(context, "TestNotesImeDebug", "DIAGNOSTIC_MODE_ENTERED id=$instanceId")
        titleView?.text = "$windowTitle [Diag Test A]"
        val density = context.resources.displayMetrics.density
        diagnosticModeButton?.apply {
            text = "Exit Diag"
            val activeBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 4 * density
                setColor(Color.parseColor("#A6E3A1"))
            }
            background = activeBg
            setTextColor(Color.parseColor("#181825"))
        }

        // Disable and hide embedded SurfaceView / fallback editor so they cannot cover or receive touches
        embeddedSurfaceView?.visibility = View.GONE
        embeddedSurfaceView?.isEnabled = false
        noteEditText?.visibility = View.GONE
        contentPlaceholderView?.visibility = View.GONE

        // Show diagnostic container & diagnostic EditText
        diagnosticContainer?.visibility = View.VISIBLE
        diagnosticEditText?.visibility = View.VISIBLE
        diagnosticEditText?.isEnabled = true
    }

    fun exitDiagnosticMode() {
        if (!isDiagnosticMode) return
        isDiagnosticMode = false
        isDiagnosticAwaitingWindowFocus = false
        LogKeeper.log(context, "TestNotesImeDebug", "DIAGNOSTIC_MODE_EXITED id=$instanceId")
        titleView?.text = windowTitle
        val density = context.resources.displayMetrics.density
        diagnosticModeButton?.apply {
            text = "Test A"
            val idleBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 4 * density
                setColor(Color.parseColor("#313244"))
                setStroke((1 * density).toInt(), Color.parseColor("#F9E2AF"))
            }
            background = idleBg
            setTextColor(Color.parseColor("#F9E2AF"))
        }

        // Hide diagnostic container
        diagnosticContainer?.visibility = View.GONE
        diagnosticEditText?.visibility = View.GONE
        diagnosticEditText?.clearFocus()

        // Hide soft keyboard if open
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            diagnosticEditText?.let { imm?.hideSoftInputFromWindow(it.windowToken, 0) }
        } catch (_: Throwable) {}

        // Restore embedded surface or fallback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && embeddedSurfaceView != null) {
            embeddedSurfaceView?.visibility = View.VISIBLE
            embeddedSurfaceView?.isEnabled = true
        } else {
            noteEditText?.visibility = View.VISIBLE
        }

        // Re-apply FLAG_NOT_FOCUSABLE if no other IME is active
        if ((layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0 && !isAwaitingWindowFocusForIme) {
            layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            applyLayoutUpdate()
        }
    }

    private fun createDiagnosticContainer(context: Context): LinearLayout {
        val density = context.resources.displayMetrics.density
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#181825"))
            setPadding(
                (14 * density).toInt(),
                (12 * density).toInt(),
                (14 * density).toInt(),
                (48 * density).toInt() // 48dp bottom clearance to clear [ ×  —  ↘ ]
            )
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }

        // Header Banner
        val banner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val bannerBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * density
                setColor(Color.parseColor("#313244"))
                setStroke((1 * density).toInt(), Color.parseColor("#89B4FA"))
            }
            background = bannerBg
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val bannerTitle = TextView(context).apply {
            text = "Main-Process IME Test A"
            setTextColor(Color.parseColor("#89B4FA"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }
        val bannerDesc = TextView(context).apply {
            text = "Visible Main-owned EditText • Direct InputConnection\nTap the editor below to test genuine WindowManager focus & soft keyboard."
            setTextColor(Color.parseColor("#CDD6F4"))
            textSize = 11f
            setPadding(0, (2 * density).toInt(), 0, 0)
        }
        banner.addView(bannerTitle)
        banner.addView(bannerDesc)
        container.addView(banner)

        // Diagnostic EditText
        val diagEdit = object : EditText(context) {
            override fun onTouchEvent(event: MotionEvent?): Boolean {
                if (event != null) {
                    val actionName = when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> "ACTION_DOWN"
                        MotionEvent.ACTION_UP -> "ACTION_UP"
                        MotionEvent.ACTION_MOVE -> "ACTION_MOVE"
                        else -> "ACTION_${event.actionMasked}"
                    }
                    if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP) {
                        LogKeeper.log(
                            context,
                            "TestNotesImeDebug",
                            "DIAG_TOUCH_EVENT action=$actionName x=${event.x} y=${event.y} hasWinFocus=${hasWindowFocus()} rootWinFocus=${rootCardView?.hasWindowFocus()}"
                        )
                    }
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        handleDiagnosticTouchFocus()
                    }
                }
                return super.onTouchEvent(event)
            }

            override fun onCreateInputConnection(outAttrs: EditorInfo?): InputConnection? {
                val ic = try {
                    super.onCreateInputConnection(outAttrs)
                } catch (t: Throwable) {
                    LogKeeper.log(context, "TestNotesImeDebug", "DIAG_INPUT_CONNECTION_EXCEPTION err=${t.message}")
                    null
                }
                LogKeeper.log(
                    context,
                    "TestNotesImeDebug",
                    "DIAG_INPUT_CONNECTION_CREATED target=main_diagnostic icNull=${ic == null} outAttrsType=${outAttrs?.inputType} imeOptions=${outAttrs?.imeOptions} hasWinFocus=${hasWindowFocus()}"
                )
                return ic
            }

            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                LogKeeper.log(
                    context,
                    "TestNotesImeDebug",
                    "DIAG_EDIT_TEXT_ON_WINDOW_FOCUS_CHANGED hasFocus=$hasWindowFocus editWinFocus=${hasWindowFocus()} rootWinFocus=${rootCardView?.hasWindowFocus()}"
                )
            }

            override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
                super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
                LogKeeper.log(
                    context,
                    "TestNotesImeDebug",
                    "DIAG_EDIT_TEXT_VIEW_FOCUS_CHANGED gainFocus=$gainFocus isFocused=$isFocused"
                )
            }
        }.apply {
            hint = "Tap here to test typing (e.g. IME-BASELINE-123)..."
            setHintTextColor(Color.parseColor("#6C7086"))
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.TOP or Gravity.START
            val editBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * density
                setColor(Color.parseColor("#11111B"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#45475A"))
            }
            background = editBg
            setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
            isEnabled = true
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            isLongClickable = true
            isCursorVisible = true
            isSingleLine = false
            maxLines = Int.MAX_VALUE
            setHorizontallyScrolling(false)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            movementMethod = ArrowKeyMovementMethod.getInstance()
            keyListener = TextKeyListener.getInstance(true, TextKeyListener.Capitalize.SENTENCES)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = (10 * density).toInt()
            }

            // Zero-PII TextWatcher: Logs only length and selection, NEVER typed characters
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val len = s?.length ?: 0
                    val selStart = selectionStart
                    val selEnd = selectionEnd
                    LogKeeper.log(
                        context,
                        "TestNotesImeDebug",
                        "DIAG_TEXT_CHANGE len=$len before=$before count=$count selStart=$selStart selEnd=$selEnd"
                    )
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        diagnosticEditText = diagEdit
        container.addView(diagEdit)

        // Actions row (Clear / Exit)
        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        val clearBtn = TextView(context).apply {
            text = "Clear"
            setTextColor(Color.parseColor("#F38BA8"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            isClickable = true
            isFocusable = false
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            setOnClickListener {
                diagEdit.setText("")
            }
        }
        actionsRow.addView(clearBtn)
        container.addView(actionsRow)

        diagnosticContainer = container
        return container
    }

    private fun handleDiagnosticTouchFocus() {
        val sess = "diag_${System.currentTimeMillis() % 100000}"
        diagnosticSessionId = sess
        val flagsBefore = layoutParams.flags
        val focusClearedFlags = (layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) and
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM.inv()
        LogKeeper.log(
            context,
            "TestNotesImeDebug",
            "DIAG_OVERLAY_FOCUS_FLAGS sess=$sess flagsBefore=0x${Integer.toHexString(flagsBefore)} flagsAfter=0x${Integer.toHexString(focusClearedFlags)} changed=${flagsBefore != focusClearedFlags}"
        )
        if (layoutParams.flags != focusClearedFlags) {
            layoutParams.flags = focusClearedFlags
            applyLayoutUpdate()
        }

        val root = rootCardView
        val diagEdit = diagnosticEditText
        val rootHasFocus = root?.hasWindowFocus() == true
        val editHasFocus = diagEdit?.hasWindowFocus() == true
        LogKeeper.log(
            context,
            "TestNotesImeDebug",
            "DIAG_TOUCH_WINDOW_FOCUS_CHECK sess=$sess rootHasFocus=$rootHasFocus editHasFocus=$editHasFocus"
        )

        if (rootHasFocus || editHasFocus) {
            isDiagnosticAwaitingWindowFocus = false
            triggerDiagnosticImeActivation(sess)
        } else {
            isDiagnosticAwaitingWindowFocus = true
            root?.postDelayed({
                if (isDiagnosticAwaitingWindowFocus) {
                    val rFocus = root.hasWindowFocus()
                    val eFocus = diagEdit?.hasWindowFocus() == true
                    val token = diagEdit?.windowToken != null
                    LogKeeper.log(
                        context,
                        "TestNotesImeDebug",
                        "DIAG_AWAITING_WINDOW_FOCUS_POLL sess=$sess rootWinFocus=$rFocus editWinFocus=$eFocus hasToken=$token"
                    )
                    if (rFocus || eFocus || token) {
                        isDiagnosticAwaitingWindowFocus = false
                        triggerDiagnosticImeActivation(sess)
                    }
                }
            }, 150)
        }
    }

    private fun triggerDiagnosticImeActivation(sess: String = diagnosticSessionId) {
        val diagEdit = diagnosticEditText ?: return
        val root = rootCardView
        val isViewFocused = diagEdit.requestFocus()
        val rootWinFocus = root?.hasWindowFocus() == true
        val editWinFocus = diagEdit.hasWindowFocus()
        val isAttached = diagEdit.isAttachedToWindow
        val hasToken = diagEdit.windowToken != null
        LogKeeper.log(
            context,
            "TestNotesImeDebug",
            "DIAG_MAIN_EDIT_TEXT_STATE sess=$sess isViewFocused=$isViewFocused rootWinFocus=$rootWinFocus editWinFocus=$editWinFocus isAttached=$isAttached hasToken=$hasToken"
        )

        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val showResult = imm?.showSoftInput(diagEdit, InputMethodManager.SHOW_IMPLICIT) ?: false
        var insetsResult = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                diagEdit.windowInsetsController?.show(WindowInsets.Type.ime())
                insetsResult = true
            } catch (t: Throwable) {
                LogKeeper.log(context, "TestNotesImeDebug", "DIAG_INSETS_CONTROLLER_FAIL sess=$sess err=${t.message}")
            }
        }
        LogKeeper.log(
            context,
            "TestNotesImeDebug",
            "DIAG_SHOW_SOFT_INPUT_RESULT sess=$sess result=$showResult insetsControllerResult=$insetsResult"
        )
    }

    fun simulateDiagnosticTouchForTesting() {
        val diagEdit = diagnosticEditText ?: return
        handleDiagnosticTouchFocus()
        try {
            diagEdit.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10f, 10f, 0))
            diagEdit.dispatchTouchEvent(MotionEvent.obtain(0, 50, MotionEvent.ACTION_UP, 10f, 10f, 0))
        } catch (_: Throwable) {}
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

        // Test A and Edit buttons for Note mini-app
        if (appType == "note" || appType == "test_notes") {
            val diagBtn = TextView(context).apply {
                text = "Test A"
                setTextColor(Color.parseColor("#F9E2AF"))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                isClickable = true
                isFocusable = false
                val diagBg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 4 * density
                    setColor(Color.parseColor("#313244"))
                    setStroke((1 * density).toInt(), Color.parseColor("#F9E2AF"))
                }
                background = diagBg
                setPadding((8 * density).toInt(), (3 * density).toInt(), (8 * density).toInt(), (3 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (6 * density).toInt()
                }
                setOnClickListener {
                    toggleDiagnosticMode()
                }
            }
            diagnosticModeButton = diagBtn
            bar.addView(diagBtn)

            val editBtn = TextView(context).apply {
                text = "Edit"
                setTextColor(Color.parseColor("#89B4FA"))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                isClickable = true
                isFocusable = false
                val editBg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 4 * density
                    setColor(Color.parseColor("#313244"))
                    setStroke((1 * density).toInt(), Color.parseColor("#45475A"))
                }
                background = editBg
                setPadding((8 * density).toInt(), (3 * density).toInt(), (8 * density).toInt(), (3 * density).toInt())
                setOnClickListener {
                    toggleEditPopover()
                }
            }
            bar.addView(editBtn)
        }

        // Setup Top Bar Drag & Double-Tap Gestures
        setupTopBarGestures(bar, context)

        return bar
    }

    private fun createEditPopover(context: Context): View {
        val density = context.resources.displayMetrics.density
        val popover = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10 * density
                setColor(Color.parseColor("#252639"))
                setStroke((1 * density).toInt(), Color.parseColor("#45475A"))
            }
            background = bg
            elevation = 20 * density
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = (42 * density).toInt()
                marginStart = (12 * density).toInt()
                marginEnd = (12 * density).toInt()
            }
            layoutParams = lp
            visibility = View.GONE
        }

        // Header with Title and Close
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = "Editor Options"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(title)
        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(Color.parseColor("#F38BA8"))
            textSize = 14f
            isClickable = true
            setPadding((6 * density).toInt(), 0, (6 * density).toInt(), 0)
            setOnClickListener { popover.visibility = View.GONE }
        }
        headerRow.addView(closeBtn)
        popover.addView(headerRow)

        // Divider
        popover.addView(View(context).apply {
            setBackgroundColor(Color.parseColor("#45475A"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()).apply {
                topMargin = (6 * density).toInt()
                bottomMargin = (8 * density).toInt()
            }
        })

        // 1. Font Size Row: [-] 15sp [+]
        val fontSizeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (2 * density).toInt(), 0, (4 * density).toInt())
        }
        val sizeLabel = TextView(context).apply {
            text = "Size: "
            setTextColor(Color.parseColor("#CDD6F4"))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        fontSizeRow.addView(sizeLabel)

        val sizeDisplay = TextView(context).apply {
            text = "${currentFontSizeSp.toInt()}sp"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
        }
        val minusBtn = createPopoverButton(context, " - ") {
            if (currentFontSizeSp > 10f) {
                currentFontSizeSp -= 2f
                sizeDisplay.text = "${currentFontSizeSp.toInt()}sp"
                dispatchFormatCommand()
            }
        }
        val plusBtn = createPopoverButton(context, " + ") {
            if (currentFontSizeSp < 28f) {
                currentFontSizeSp += 2f
                sizeDisplay.text = "${currentFontSizeSp.toInt()}sp"
                dispatchFormatCommand()
            }
        }
        fontSizeRow.addView(minusBtn)
        fontSizeRow.addView(sizeDisplay)
        fontSizeRow.addView(plusBtn)
        popover.addView(fontSizeRow)

        // 2. Font Style Row: [Regular] [Bold] [Italic] [Mono]
        val styleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (2 * density).toInt(), 0, (4 * density).toInt())
        }
        val styleLabel = TextView(context).apply {
            text = "Style: "
            setTextColor(Color.parseColor("#CDD6F4"))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        styleRow.addView(styleLabel)
        val regBtn = createPopoverButton(context, "Norm") {
            currentFontStyle = "regular"
            dispatchFormatCommand()
        }
        val boldBtn = createPopoverButton(context, "Bold") {
            currentFontStyle = "bold"
            dispatchFormatCommand()
        }
        val italicBtn = createPopoverButton(context, "Ital") {
            currentFontStyle = "italic"
            dispatchFormatCommand()
        }
        val monoBtn = createPopoverButton(context, "Mono") {
            currentFontStyle = "monospace"
            dispatchFormatCommand()
        }
        styleRow.addView(regBtn)
        styleRow.addView(boldBtn)
        styleRow.addView(italicBtn)
        styleRow.addView(monoBtn)
        popover.addView(styleRow)

        // 3. Word Wrap & Line Spacing Row
        val wrapSpacingRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (2 * density).toInt(), 0, (4 * density).toInt())
        }
        val wrapBtn = createPopoverButton(context, if (currentWordWrap) "Wrap: ON" else "Wrap: OFF") {
            currentWordWrap = !currentWordWrap
            (it as? TextView)?.text = if (currentWordWrap) "Wrap: ON" else "Wrap: OFF"
            dispatchFormatCommand()
        }
        val spacing1x = createPopoverButton(context, "1.0x") {
            currentLineSpacingMult = 1.0f
            dispatchFormatCommand()
        }
        val spacing12x = createPopoverButton(context, "1.2x") {
            currentLineSpacingMult = 1.2f
            dispatchFormatCommand()
        }
        val spacing15x = createPopoverButton(context, "1.5x") {
            currentLineSpacingMult = 1.5f
            dispatchFormatCommand()
        }
        wrapSpacingRow.addView(wrapBtn)
        wrapSpacingRow.addView(spacing1x)
        wrapSpacingRow.addView(spacing12x)
        wrapSpacingRow.addView(spacing15x)
        popover.addView(wrapSpacingRow)

        // 4. Action Row: Undo, Redo, Clear
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (4 * density).toInt(), 0, (2 * density).toInt())
        }
        val undoBtn = createPopoverButton(context, "↶ Undo") {
            sendNoteCommand("note_undo")
        }
        val redoBtn = createPopoverButton(context, "↷ Redo") {
            sendNoteCommand("note_redo")
        }
        val clearBtn = createPopoverButton(context, "Clear", textColor = Color.parseColor("#F38BA8")) {
            sendNoteCommand("note_clear")
        }
        actionRow.addView(undoBtn)
        actionRow.addView(redoBtn)
        actionRow.addView(clearBtn)
        popover.addView(actionRow)

        return popover
    }

    private fun createPopoverButton(
        context: Context,
        label: String,
        textColor: Int = Color.parseColor("#CDD6F4"),
        onClick: (View) -> Unit
    ): TextView {
        val density = context.resources.displayMetrics.density
        return TextView(context).apply {
            text = label
            setTextColor(textColor)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            isClickable = true
            isFocusable = false
            val btnBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 4 * density
                setColor(Color.parseColor("#313244"))
                setStroke((1 * density).toInt(), Color.parseColor("#45475A"))
            }
            background = btnBg
            setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (3 * density).toInt()
                marginEnd = (3 * density).toInt()
            }
            layoutParams = lp
            setOnClickListener { onClick(it) }
        }
    }

    private fun toggleEditPopover() {
        editPopoverView?.let { popover ->
            popover.visibility = if (popover.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (popover.visibility == View.VISIBLE) {
                popover.bringToFront()
            }
        }
    }

    private fun dispatchFormatCommand() {
        val params = mapOf(
            "font_size" to currentFontSizeSp.toString(),
            "font_style" to currentFontStyle,
            "word_wrap" to currentWordWrap.toString(),
            "line_spacing" to currentLineSpacingMult.toString()
        )
        sendNoteCommand("note_format", params)
    }

    private fun sendNoteCommand(action: String, params: Map<String, String> = emptyMap()) {
        val connectionManager = HeavyProcessConnectionManager.getInstance(context)
        val cmd = HeavyCommand(
            commandId = UUID.randomUUID().toString(),
            type = HeavyCommandType.PAUSE_OPERATION,
            targetId = instanceId,
            payload = mapOf(
                "action" to action,
                "instance_id" to instanceId,
                "sessionId" to activeImeSessionId,
                "app_type" to appType
            ) + params
        )
        if (connectionManager.state == ConnectionState.CONNECTED) {
            connectionManager.sendCommand(cmd, autoConnect = false)
        } else {
            HeavyFloatingHost.getInstance(context).dispatchNoteCommand(instanceId, action, params)
        }
        applyNoteCommandLocally(action, params)
    }

    private fun applyNoteCommandLocally(action: String, params: Map<String, String>) {
        val edit = noteEditText ?: return
        when (action) {
            "note_format" -> {
                val size = params["font_size"]?.toFloatOrNull() ?: currentFontSizeSp
                val style = params["font_style"] ?: currentFontStyle
                val wrap = params["word_wrap"]?.toBooleanStrictOrNull() ?: currentWordWrap
                val spacing = params["line_spacing"]?.toFloatOrNull() ?: currentLineSpacingMult
                edit.textSize = size
                val tf = when (style.lowercase()) {
                    "bold" -> Typeface.DEFAULT_BOLD
                    "italic" -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                    "monospace" -> Typeface.MONOSPACE
                    else -> Typeface.DEFAULT
                }
                edit.typeface = tf
                edit.setHorizontallyScrolling(!wrap)
                edit.setLineSpacing(0f, spacing)
            }
            "note_clear" -> {
                currentNoteText = ""
                edit.setText("")
            }
        }
    }

    private fun setupTopBarGestures(topBar: View, context: Context) {
        val touchSlop = try {
            android.view.ViewConfiguration.get(context)?.scaledTouchSlop ?: (8 * context.resources.displayMetrics.density).toInt()
        } catch (_: Throwable) {
            (8 * context.resources.displayMetrics.density).toInt()
        }
        var isDragging = false
        var dragStartRawX = 0f
        var dragStartRawY = 0f
        var initialBoundsX = 0
        var initialBoundsY = 0
        var cachedDragSafeBounds: WindowBounds? = null

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
                    if (isImeShifted) {
                        bounds.y = layoutParams.y
                        normalFloatingBounds.y = layoutParams.y
                        isImeShifted = false
                        imeShiftOffsetY = 0
                    }
                    initialBoundsX = bounds.x
                    initialBoundsY = bounds.y
                    cachedDragSafeBounds = getSafeDisplayBounds(context)
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
                        val safeBounds = cachedDragSafeBounds ?: getSafeDisplayBounds(context).also { cachedDragSafeBounds = it }
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
                    cachedDragSafeBounds = null
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
                    cachedDragSafeBounds = null
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
        var cachedSafeBounds: WindowBounds? = null
        var lastRelayoutDispatchTime = 0L

        resizeView.setOnTouchListener { _, event ->
            if (isMaximized) {
                // Cannot resize while maximized
                return@setOnTouchListener false
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isResizing = true
                    resizeStartRawX = event.rawX
                    resizeStartRawY = event.rawY
                    startWidth = bounds.width
                    startHeight = bounds.height
                    cachedSafeBounds = getSafeDisplayBounds(context)
                    lastRelayoutDispatchTime = SystemClock.uptimeMillis()
                    LogKeeper.log(context, "FloatingMiniAppWindow", "RESIZE_START id=$instanceId initialSize=($startWidth,$startHeight)")
                    manager?.setFocus(windowId)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dw = (event.rawX - resizeStartRawX).toInt()
                    val dh = (event.rawY - resizeStartRawY).toInt()

                    val safeBounds = cachedSafeBounds ?: getSafeDisplayBounds(context).also { cachedSafeBounds = it }
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

                        val now = SystemClock.uptimeMillis()
                        val viewportH = getContentViewportHeight(newH)
                        val deltaW = abs(newW - lastRelayoutWidth)
                        val deltaH = abs(viewportH - lastRelayoutHeight)
                        if ((now - lastRelayoutDispatchTime >= 16L && (deltaW >= 2 || deltaH >= 2)) || deltaW >= 8 || deltaH >= 8) {
                            lastRelayoutDispatchTime = now
                            relayoutEmbeddedSurface(newW, viewportH)
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    isResizing = false
                    cachedSafeBounds = null
                    LogKeeper.log(context, "FloatingMiniAppWindow", "RESIZE_END id=$instanceId finalSize=(${bounds.width},${bounds.height})")
                    normalFloatingBounds = bounds.copyBounds()
                    durableStore.updateWindowBounds(instanceId, normalFloatingBounds)
                    LogKeeper.log(context, "FloatingMiniAppWindow", "WINDOW_BOUNDS_PERSISTED id=$instanceId bounds=$normalFloatingBounds source=resize")
                    relayoutEmbeddedSurface(bounds.width, getContentViewportHeight(bounds.height))
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    isResizing = false
                    cachedSafeBounds = null
                    LogKeeper.log(context, "FloatingMiniAppWindow", "RESIZE_CANCEL id=$instanceId size=(${bounds.width},${bounds.height})")
                    relayoutEmbeddedSurface(bounds.width, getContentViewportHeight(bounds.height))
                    true
                }

                else -> false
            }
        }
    }

    /**
     * Determines ONE authoritative safe display rectangle using Android system bar, navigation bar, and cutout insets.
     */
    fun getSafeDisplayBounds(context: Context, forceRefresh: Boolean = false): WindowBounds {
        val now = SystemClock.uptimeMillis()
        val cached = cachedSafeDisplayBounds
        if (!forceRefresh && cached != null && (now - lastSafeBoundsQueryTime < 1000L)) {
            return cached
        }
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
        cachedSafeDisplayBounds = safeBounds
        lastSafeBoundsQueryTime = now
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
            normalFloatingBounds = target.copyBounds()
            isMaximized = false
            maximizeRestoreBounds = null
            isImeShifted = false
            imeShiftOffsetY = 0
            durableStore.updateMaximizeRestoreBounds(instanceId, null)

            // Restore rounded card shape
            applyCardCornerRadius(false)

            LogKeeper.log(context, "FloatingMiniAppWindow", "RESTORE id=$instanceId target=$target")
            LogKeeper.log(context, "FloatingMiniAppWindow", "RESTORE_BOUNDS bounds=$target")
            durableStore.updateWindowBounds(instanceId, target)
        } else {
            // If IME was shifted, reset IME shift to preserve pristine normal coordinates
            if (isImeShifted) {
                isImeShifted = false
                imeShiftOffsetY = 0
            }
            // Save current normal floating bounds before maximizing
            val normalTarget = normalFloatingBounds.copyBounds()
            maximizeRestoreBounds = normalTarget.copyBounds()
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
                if (isMaximized) {
                    return@OnGlobalLayoutListener
                }

                val baselineY = normalFloatingBounds.y
                val windowBottom = baselineY + normalFloatingBounds.height
                if (windowBottom > r.bottom) {
                    val density = context.resources.displayMetrics.density
                    val shift = (windowBottom - r.bottom) + (16 * density).toInt()
                    val safeBounds = getSafeDisplayBounds(context)
                    val shiftedY = maxOf(safeBounds.y, baselineY - shift)

                    if (layoutParams.y != shiftedY) {
                        LogKeeper.log(context, "FloatingMiniAppWindow", "IME_VISIBLE keypadHeight=$keypadHeight")
                        LogKeeper.log(context, "FloatingMiniAppWindow", "IME_TEMP_SHIFT id=$instanceId shift=$shift newY=$shiftedY previousY=${layoutParams.y}")
                        layoutParams.y = shiftedY
                        isImeShifted = (shiftedY != baselineY)
                        imeShiftOffsetY = baselineY - shiftedY
                        LogKeeper.log(context, "FloatingMiniAppWindow", "IME_SHIFT id=$instanceId y=$shiftedY")
                        applyLayoutUpdate()
                    }
                } else if (isImeShifted) {
                    val restoredY = normalFloatingBounds.y
                    if (layoutParams.y != restoredY) {
                        layoutParams.y = restoredY
                        bounds.y = restoredY
                        isImeShifted = false
                        imeShiftOffsetY = 0
                        LogKeeper.log(context, "FloatingMiniAppWindow", "IME_RESTORE id=$instanceId y=$restoredY")
                        applyLayoutUpdate()
                    }
                }
            } else if (isImeShifted) {
                // Restore exact persistent normalFloatingBounds.y on keyboard dismiss
                val restoredY = normalFloatingBounds.y
                LogKeeper.log(context, "FloatingMiniAppWindow", "IME_HIDDEN")
                LogKeeper.log(context, "FloatingMiniAppWindow", "IME_TEMP_RESTORE id=$instanceId restoredY=$restoredY")
                layoutParams.y = restoredY
                bounds.y = restoredY
                isImeShifted = false
                imeShiftOffsetY = 0
                LogKeeper.log(context, "FloatingMiniAppWindow", "IME_RESTORE id=$instanceId y=$restoredY")
                applyLayoutUpdate()
            }
        }
        globalLayoutListener = listener
        root.viewTreeObserver?.addOnGlobalLayoutListener(listener)
    }

    /**
     * Testing hook to simulate IME state changes and test window positioning without a physical display.
     */
    fun simulateImeVisibilityForTesting(isKeyboardOpen: Boolean, keypadHeight: Int, displayBottom: Int) {
        val r = Rect(0, 0, bounds.width, displayBottom)
        if (isKeyboardOpen) {
            if (isMaximized) return
            val baselineY = normalFloatingBounds.y
            val windowBottom = baselineY + normalFloatingBounds.height
            if (windowBottom > r.bottom) {
                val density = context.resources.displayMetrics.density
                val shift = (windowBottom - r.bottom) + (16 * density).toInt()
                val safeBounds = getSafeDisplayBounds(context)
                val shiftedY = maxOf(safeBounds.y, baselineY - shift)

                if (layoutParams.y != shiftedY) {
                    layoutParams.y = shiftedY
                    isImeShifted = (shiftedY != baselineY)
                    imeShiftOffsetY = baselineY - shiftedY
                    applyLayoutUpdate()
                }
            }
        } else if (isImeShifted) {
            val restoredY = normalFloatingBounds.y
            layoutParams.y = restoredY
            bounds.y = restoredY
            isImeShifted = false
            imeShiftOffsetY = 0
            applyLayoutUpdate()
        }
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
        val authoritativeText = when (activeTextAuthority) {
            TextAuthority.IME_BRIDGE -> imeBridgeEditText?.text?.toString() ?: currentNoteText
            TextAuthority.FALLBACK -> noteEditText?.text?.toString() ?: currentNoteText
            TextAuthority.HEAVY -> currentNoteText
            TextAuthority.SNAPSHOT -> currentNoteText
            TextAuthority.NONE -> {
                when {
                    imeBridgeEditText != null -> imeBridgeEditText?.text?.toString() ?: currentNoteText
                    noteEditText != null -> noteEditText?.text?.toString() ?: currentNoteText
                    else -> currentNoteText
                }
            }
        }
        currentNoteText = authoritativeText

        return if (hasUserEditedText || activeTextAuthority != TextAuthority.NONE || noteEditText != null || imeBridgeEditText != null) {
            mapOf(
                TestNotesMiniAppInstance.KEY_TEXT to currentNoteText,
                "text" to currentNoteText,
                "font_size" to currentFontSizeSp.toString(),
                "font_style" to currentFontStyle,
                "word_wrap" to currentWordWrap.toString(),
                "line_spacing" to currentLineSpacingMult.toString(),
                "source" to when (activeTextAuthority) {
                    TextAuthority.IME_BRIDGE -> "ime_bridge"
                    TextAuthority.FALLBACK -> "fallback_editor"
                    TextAuthority.HEAVY -> "heavy"
                    TextAuthority.SNAPSHOT -> "snapshot"
                    TextAuthority.NONE -> "default"
                }
            )
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

        val payload = getCurrentPayload()
        if (payload.isNotEmpty()) {
            durableStore.updatePayload(
                instanceId,
                mapOf(
                    TestNotesMiniAppInstance.KEY_TEXT to currentNoteText,
                    "text" to currentNoteText
                )
            )
        }
        noteEditText = null
        imeBridgeEditText = null

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
