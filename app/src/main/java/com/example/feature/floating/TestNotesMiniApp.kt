package com.example.feature.floating

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.method.ArrowKeyMovementMethod
import android.text.method.KeyListener
import android.text.method.MovementMethod
import android.text.method.TextKeyListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.example.core.LogKeeper
import com.example.core.ipc.HeavyEvent
import com.example.core.ipc.HeavyEventType
import com.example.core.ipc.HeavyProcessHost

/**
 * Lightweight Editable wrapper used only when running inside local JVM unit tests
 * (`unitTests.isReturnDefaultValues = true`) where `super.getText()` on Android stub classes returns null.
 * On a real Android device, `super.getText()` is always non-null and this class is never used.
 */
internal class JvmStubEditable(private var current: String) : Editable {
    override val length: Int get() = current.length
    override fun get(index: Int): Char = current[index]
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = current.subSequence(startIndex, endIndex)
    override fun toString(): String = current
    override fun replace(st: Int, en: Int, source: CharSequence?, start: Int, end: Int): Editable {
        val sub = source?.subSequence(start, end)?.toString() ?: ""
        current = current.replaceRange(st.coerceIn(0, current.length), en.coerceIn(0, current.length), sub)
        return this
    }
    override fun replace(st: Int, en: Int, text: CharSequence?): Editable = replace(st, en, text, 0, text?.length ?: 0)
    override fun insert(where: Int, text: CharSequence?, start: Int, end: Int): Editable = replace(where, where, text, start, end)
    override fun insert(where: Int, text: CharSequence?): Editable = replace(where, where, text)
    override fun delete(st: Int, en: Int): Editable = replace(st, en, "")
    override fun append(text: CharSequence?): Editable {
        current += (text?.toString() ?: "")
        return this
    }
    override fun append(text: CharSequence?, start: Int, end: Int): Editable {
        current += (text?.subSequence(start, end)?.toString() ?: "")
        return this
    }
    override fun append(text: Char): Editable {
        current += text
        return this
    }
    override fun clear() { current = "" }
    override fun clearSpans() {}
    override fun setFilters(filters: Array<out InputFilter>?) {}
    override fun getFilters(): Array<InputFilter> = emptyArray()
    override fun getChars(start: Int, end: Int, dest: CharArray?, destoff: Int) {
        if (dest != null) current.toCharArray(dest, destoff, start, end)
    }
    override fun setSpan(what: Any?, start: Int, end: Int, flags: Int) {}
    override fun removeSpan(what: Any?) {}
    override fun <T : Any?> getSpans(start: Int, end: Int, type: Class<T>?): Array<T> {
        @Suppress("UNCHECKED_CAST")
        return java.lang.reflect.Array.newInstance(type ?: Any::class.java, 0) as Array<T>
    }
    override fun getSpanStart(tag: Any?): Int = -1
    override fun getSpanEnd(tag: Any?): Int = -1
    override fun getSpanFlags(tag: Any?): Int = 0
    override fun nextSpanTransition(start: Int, limit: Int, type: Class<*>?): Int = limit
}

/**
 * EmbeddedNoteEditText: Multiline editable EditText designed to operate inside a cross-process
 * SurfaceControlViewHost (WindowlessWindowManager) as well as standard window hierarchies.
 *
 * Root cause addressed:
 * Inside SurfaceControlViewHost, ViewRootImpl.mHasWindowFocus is false by default because
 * WindowlessWindowManager does not register a top-level WindowState in WindowManagerService.
 * When hasWindowFocus() returns false, Android's TextView/Editor disables cursor blinking
 * (Editor.shouldBlink()), suppresses touch-driven IME activation in TextView.onTouchEvent(),
 * and prevents selection handles from engaging.
 *
 * Overriding hasWindowFocus() to return true and reporting focus/selection/text events back to
 * the host FloatingMiniAppWindow enables touch focus, cursor placement, text selection, and
 * soft-keyboard input inside the embedded floating surface.
 */
class EmbeddedNoteEditText(
    context: Context,
    private val onEditorFocusedOrTouched: (text: String, selStart: Int, selEnd: Int, sessionId: String) -> Unit,
    private val onEditorSelectionChanged: (selStart: Int, selEnd: Int) -> Unit
) : EditText(context) {

    private val viewContext: Context = context
    var isApplyingHostUpdate: Boolean = false
    private var activeTouchSessionId: String? = null

    private var mirroredText: String = ""
    private var mirroredSelStart: Int = 0
    private var mirroredSelEnd: Int = 0
    private var mirroredEnabled: Boolean = true
    private var mirroredFocusable: Boolean = true
    private var mirroredFocusableInTouchMode: Boolean = true
    private var mirroredCursorVisible: Boolean = true
    private var mirroredInputType: Int = InputType.TYPE_CLASS_TEXT or
        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    private var mirroredMovementMethod: MovementMethod? = null
    private var mirroredKeyListener: KeyListener? = null
    private val mirroredWatchers = mutableListOf<TextWatcher>()

    init {
        id = android.R.id.edit
        tag = TAG_EDIT_TEXT
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
        val arrowMovement = ArrowKeyMovementMethod.getInstance() ?: object : MovementMethod {
            override fun initialize(widget: TextView?, text: android.text.Spannable?) {}
            override fun onKeyDown(widget: TextView?, text: android.text.Spannable?, keyCode: Int, event: android.view.KeyEvent?): Boolean = false
            override fun onKeyUp(widget: TextView?, text: android.text.Spannable?, keyCode: Int, event: android.view.KeyEvent?): Boolean = false
            override fun onKeyOther(view: TextView?, text: android.text.Spannable?, event: android.view.KeyEvent?): Boolean = false
            override fun onTakeFocus(widget: TextView?, text: android.text.Spannable?, direction: Int) {}
            override fun onTrackballEvent(widget: TextView?, text: android.text.Spannable?, event: MotionEvent?): Boolean = false
            override fun onTouchEvent(widget: TextView?, text: android.text.Spannable?, event: MotionEvent?): Boolean = false
            override fun onGenericMotionEvent(widget: TextView?, text: android.text.Spannable?, event: MotionEvent?): Boolean = false
            override fun canSelectArbitrarily(): Boolean = true
        }
        mirroredMovementMethod = arrowMovement
        movementMethod = arrowMovement
        val capKeyListener = TextKeyListener.getInstance(true, TextKeyListener.Capitalize.SENTENCES) ?: object : KeyListener {
            override fun getInputType(): Int = mirroredInputType
            override fun onKeyDown(view: View?, content: Editable?, keyCode: Int, event: android.view.KeyEvent?): Boolean = false
            override fun onKeyUp(view: View?, content: Editable?, keyCode: Int, event: android.view.KeyEvent?): Boolean = false
            override fun onKeyOther(view: View?, content: Editable?, event: android.view.KeyEvent?): Boolean = false
            override fun clearMetaKeyState(view: View?, content: Editable?, states: Int) {}
        }
        mirroredKeyListener = capKeyListener
        keyListener = capKeyListener
        gravity = Gravity.TOP or Gravity.START
    }

    override fun hasWindowFocus(): Boolean = true

    override fun onCheckIsTextEditor(): Boolean = true

    override fun setEnabled(enabled: Boolean) {
        mirroredEnabled = enabled
        super.setEnabled(enabled)
    }

    override fun isEnabled(): Boolean {
        return if (super.getText() != null) super.isEnabled() else mirroredEnabled
    }

    override fun setFocusable(focusable: Boolean) {
        mirroredFocusable = focusable
        super.setFocusable(focusable)
    }

    fun isConfiguredFocusable(): Boolean {
        return if (super.getText() != null) super.isFocusable() else mirroredFocusable
    }

    override fun setFocusableInTouchMode(focusableInTouchMode: Boolean) {
        mirroredFocusableInTouchMode = focusableInTouchMode
        super.setFocusableInTouchMode(focusableInTouchMode)
    }

    fun isConfiguredFocusableInTouchMode(): Boolean {
        return if (super.getText() != null) super.isFocusableInTouchMode() else mirroredFocusableInTouchMode
    }

    override fun setCursorVisible(visible: Boolean) {
        mirroredCursorVisible = visible
        super.setCursorVisible(visible)
    }

    override fun isCursorVisible(): Boolean {
        return if (super.getText() != null) super.isCursorVisible() else mirroredCursorVisible
    }

    override fun setInputType(type: Int) {
        mirroredInputType = type
        super.setInputType(type)
    }

    override fun getInputType(): Int {
        return if (super.getText() != null) super.getInputType() else mirroredInputType
    }

    fun getConfiguredMovementMethod(): MovementMethod? {
        return super.getMovementMethod() ?: mirroredMovementMethod
    }

    override fun setKeyListener(input: KeyListener?) {
        mirroredKeyListener = input
        super.setKeyListener(input)
    }

    fun getConfiguredKeyListener(): KeyListener? {
        return super.getKeyListener() ?: mirroredKeyListener
    }

    override fun addTextChangedListener(watcher: TextWatcher?) {
        if (watcher != null && !mirroredWatchers.contains(watcher)) {
            mirroredWatchers.add(watcher)
        }
        super.addTextChangedListener(watcher)
    }

    override fun removeTextChangedListener(watcher: TextWatcher?) {
        if (watcher != null) {
            mirroredWatchers.remove(watcher)
        }
        super.removeTextChangedListener(watcher)
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        val updated = text?.toString() ?: ""
        val previous = mirroredText
        mirroredText = updated
        mirroredSelStart = mirroredSelStart.coerceIn(0, updated.length)
        mirroredSelEnd = mirroredSelEnd.coerceIn(0, updated.length)
        super.setText(text, type)
        if (super.getText() == null && mirroredWatchers != null) {
            val editable = JvmStubEditable(updated)
            for (watcher in ArrayList(mirroredWatchers)) {
                watcher.beforeTextChanged(previous, 0, previous.length, updated.length)
                watcher.onTextChanged(updated, 0, previous.length, updated.length)
                watcher.afterTextChanged(editable)
            }
        }
    }

    fun applyFormatting(fontSizeSp: Float, fontStyle: String, wordWrap: Boolean, lineSpacingMult: Float) {
        textSize = fontSizeSp
        val tf = when (fontStyle.lowercase()) {
            "bold" -> Typeface.DEFAULT_BOLD
            "italic" -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            "monospace" -> Typeface.MONOSPACE
            else -> Typeface.DEFAULT
        }
        typeface = tf
        setHorizontallyScrolling(!wordWrap)
        setLineSpacing(0f, lineSpacingMult)
    }

    fun getMirroredText(): String = mirroredText

    override fun getText(): Editable? {
        return super.getText()
    }

    override fun setSelection(start: Int, stop: Int) {
        val len = (super.getText()?.length ?: mirroredText.length).coerceAtLeast(0)
        mirroredSelStart = start.coerceIn(0, len)
        mirroredSelEnd = stop.coerceIn(0, len)
        super.setSelection(start, stop)
        if (super.getText() == null) {
            onSelectionChanged(mirroredSelStart, mirroredSelEnd)
        }
    }

    override fun setSelection(index: Int) {
        val len = (super.getText()?.length ?: mirroredText.length).coerceAtLeast(0)
        mirroredSelStart = index.coerceIn(0, len)
        mirroredSelEnd = mirroredSelStart
        super.setSelection(index)
        if (super.getText() == null) {
            onSelectionChanged(mirroredSelStart, mirroredSelEnd)
        }
    }

    override fun getSelectionStart(): Int {
        return if (super.getText() != null) super.getSelectionStart() else mirroredSelStart
    }

    override fun getSelectionEnd(): Int {
        return if (super.getText() != null) super.getSelectionEnd() else mirroredSelEnd
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            val sess = "ime_${System.currentTimeMillis() % 100000}"
            activeTouchSessionId = sess
            LogKeeper.log(
                viewContext,
                "TestNotesImeDebug",
                "STAGE_1_HEAVY_DOWN sess=$sess hasFocus=${hasFocus()} winFocus=${hasWindowFocus()}"
            )
            if (!hasFocus()) {
                requestFocus()
            }
            isCursorVisible = true
        }
        val handled = try {
            super.onTouchEvent(event)
        } catch (_: Throwable) {
            false
        }
        if (action == MotionEvent.ACTION_UP) {
            val sess = activeTouchSessionId ?: "ime_${System.currentTimeMillis() % 100000}"
            if (!hasFocus()) {
                requestFocus()
            }
            isCursorVisible = true
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    windowInsetsController?.show(android.view.WindowInsets.Type.ime())
                }
            } catch (_: Throwable) {}
            try {
                val imm = viewContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.viewClicked(this)
                imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            } catch (_: Throwable) {}

            val currentText = text?.toString() ?: ""
            val sStart = selectionStart.coerceIn(0, currentText.length)
            val sEnd = selectionEnd.coerceIn(0, currentText.length)
            LogKeeper.log(
                viewContext,
                "TestNotesImeDebug",
                "HEAVY_EDITOR_FOCUS_REQUEST sess=$sess hasFocus=${hasFocus()} winFocus=${hasWindowFocus()} textLen=${currentText.length} sel=($sStart,$sEnd)"
            )
            onEditorFocusedOrTouched(currentText, sStart, sEnd, sess)
        }
        return handled || true
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo?): InputConnection? {
        val ic = try {
            super.onCreateInputConnection(outAttrs)
        } catch (_: Throwable) {
            null
        }
        LogKeeper.log(
            viewContext,
            "TestNotesImeDebug",
            "INPUT_CONNECTION_CREATED target=heavy_embedded icNull=${ic == null} outAttrsType=${outAttrs?.inputType} imeOptions=${outAttrs?.imeOptions}"
        )
        return ic
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (!isApplyingHostUpdate && hasFocus()) {
            onEditorSelectionChanged(selStart.coerceAtLeast(0), selEnd.coerceAtLeast(0))
        }
    }

    companion object {
        const val TAG_EDIT_TEXT = "test_notes_edit_text"
    }
}

/**
 * TestNotesMiniAppInstance: Concrete Heavy-owned MiniAppInstance for appType = "test_notes".
 *
 * Designed to exercise and validate the real floating mini-app framework end-to-end:
 * 1. Title: "Test Notes"
 * 2. Genuinely editable multiline EditText with initial instructional text.
 * 3. Supports touch, focus, cursor placement, text selection, and soft-keyboard input.
 * 4. Saves and restores edited text across Sleep (Fold) / Wake cycles and discards on Close.
 * 5. Pure Android Views (no Compose, no network, no database, no heavy dependencies).
 * 6. Releases memory-heavy views on sleep so Heavy can safely teardown when idle.
 */
class TestNotesMiniAppInstance(
    override val instanceId: String,
    override val appType: String = APP_TYPE
) : MiniAppInstance {

    override var lifecycleState: MiniAppLifecycleState = MiniAppLifecycleState.UNINITIALIZED
        private set

    @Volatile
    var noteText: String = DEFAULT_INSTRUCTIONAL_TEXT
        private set

    @Volatile
    var hasUserEditedText: Boolean = false
        private set

    @Volatile
    var fontSizeSp: Float = 15f
        private set

    @Volatile
    var fontStyle: String = "regular"
        private set

    @Volatile
    var wordWrap: Boolean = true
        private set

    @Volatile
    var lineSpacingMult: Float = 1.2f
        private set

    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    @Volatile
    private var isPerformingUndoRedo: Boolean = false

    private var appContext: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var noteEditText: EmbeddedNoteEditText? = null
    private var statusView: TextView? = null

    fun getEditTextForTesting(): EditText? = noteEditText

    fun applyFormat(sizeSp: Float, style: String, wrap: Boolean, lineSpacing: Float) {
        fontSizeSp = sizeSp
        fontStyle = style
        wordWrap = wrap
        lineSpacingMult = lineSpacing
        val updateBlock = {
            noteEditText?.applyFormatting(sizeSp, style, wrap, lineSpacing)
            Unit
        }
        if (Looper.myLooper() == Looper.getMainLooper() || noteEditText?.isAttachedToWindow != true) {
            updateBlock()
        } else {
            mainHandler.post(updateBlock)
        }
    }

    fun pushUndoState(text: String) {
        if (isPerformingUndoRedo) return
        if (undoStack.isEmpty() || undoStack.last() != text) {
            if (undoStack.size >= 50) undoStack.removeFirst()
            undoStack.addLast(text)
            redoStack.clear()
        }
    }

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        isPerformingUndoRedo = true
        try {
            val current = noteText
            redoStack.addLast(current)
            val prev = undoStack.removeLast()
            applyLiveTextFromHost(prev)
            reportTextChangedToMain(prev, prev.length, prev.length)
            return true
        } finally {
            isPerformingUndoRedo = false
        }
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        isPerformingUndoRedo = true
        try {
            val current = noteText
            undoStack.addLast(current)
            val next = redoStack.removeLast()
            applyLiveTextFromHost(next)
            reportTextChangedToMain(next, next.length, next.length)
            return true
        } finally {
            isPerformingUndoRedo = false
        }
    }

    fun clearContent() {
        pushUndoState(noteText)
        applyLiveTextFromHost("")
        reportTextChangedToMain("", 0, 0)
    }

    fun handleNoteCommand(action: String, params: Map<String, String>) {
        when (action) {
            ACTION_NOTE_FORMAT -> {
                val size = params["font_size"]?.toFloatOrNull() ?: fontSizeSp
                val style = params["font_style"] ?: fontStyle
                val wrap = params["word_wrap"]?.toBooleanStrictOrNull() ?: wordWrap
                val spacing = params["line_spacing"]?.toFloatOrNull() ?: lineSpacingMult
                applyFormat(size, style, wrap, spacing)
            }
            ACTION_NOTE_UNDO -> undo()
            ACTION_NOTE_REDO -> redo()
            ACTION_NOTE_CLEAR -> clearContent()
        }
    }

    override fun onCreate(savedSnapshot: MiniAppRecoverySnapshot?) {
        lifecycleState = MiniAppLifecycleState.CREATED
        savedSnapshot?.let { restoreFromSnapshot(it) }
    }

    override fun onStart() {
        lifecycleState = MiniAppLifecycleState.STARTED
    }

    override fun onResume() {
        lifecycleState = MiniAppLifecycleState.RESUMED
        syncUi()
    }

    override fun onPause() {
        lifecycleState = MiniAppLifecycleState.PAUSED
        saveTextFromUi()
    }

    override fun onStop() {
        lifecycleState = MiniAppLifecycleState.STOPPED
        saveTextFromUi()
    }

    override fun onDestroy() {
        lifecycleState = MiniAppLifecycleState.DESTROYED
        releaseRuntime()
    }

    override fun prepareForSleep(): Map<String, String> {
        lifecycleState = MiniAppLifecycleState.SLEEPING
        saveTextFromUi()
        val payload = LinkedHashMap<String, String>()
        payload[KEY_TEXT] = noteText
        payload["text"] = noteText
        payload["app_type"] = appType
        payload["font_size"] = fontSizeSp.toString()
        payload["font_style"] = fontStyle
        payload["word_wrap"] = wordWrap.toString()
        payload["line_spacing"] = lineSpacingMult.toString()
        payload["timestamp"] = System.currentTimeMillis().toString()
        releaseRuntime()
        return payload
    }

    override fun releaseRuntime() {
        noteEditText = null
        statusView = null
    }

    override fun restoreFromSnapshot(snapshot: MiniAppRecoverySnapshot) {
        val payload = snapshot.payload
        val restored = payload[KEY_TEXT] ?: payload["text"] ?: payload["note_content"]
        if (restored != null) {
            noteText = restored
        }
        payload["font_size"]?.toFloatOrNull()?.let { fontSizeSp = it }
        payload["font_style"]?.let { fontStyle = it }
        payload["word_wrap"]?.toBooleanStrictOrNull()?.let { wordWrap = it }
        payload["line_spacing"]?.toFloatOrNull()?.let { lineSpacingMult = it }
        syncUi()
        noteEditText?.applyFormatting(fontSizeSp, fontStyle, wordWrap, lineSpacingMult)
    }

    override fun onSaveState(): Map<String, String> {
        saveTextFromUi()
        return mapOf(
            KEY_TEXT to noteText,
            "text" to noteText,
            "font_size" to fontSizeSp.toString(),
            "font_style" to fontStyle,
            "word_wrap" to wordWrap.toString(),
            "line_spacing" to lineSpacingMult.toString()
        )
    }

    /**
     * Updates the live note text and selection from Main's soft-keyboard InputConnection bridge
     * or direct programmatic edits.
     */
    fun applyLiveTextFromHost(newText: String, selStart: Int = -1, selEnd: Int = -1, sessionId: String? = null) {
        noteText = newText
        hasUserEditedText = true
        val ctx = appContext ?: noteEditText?.context
        if (ctx != null) {
            LogKeeper.log(
                ctx,
                "TestNotesImeDebug",
                "STAGE_10_HEAVY_APPLY_TEXT sess=${sessionId ?: "none"} id=$instanceId textLen=${newText.length} sel=($selStart,$selEnd)"
            )
        }
        val applyBlock = {
            val edit = noteEditText
            if (edit != null) {
                edit.isApplyingHostUpdate = true
                try {
                    if (edit.text?.toString() != newText) {
                        edit.setText(newText, TextView.BufferType.EDITABLE)
                    }
                    val len = (edit.text?.length ?: newText.length).coerceAtLeast(0)
                    val targetStart = if (selStart >= 0) selStart.coerceIn(0, len) else len
                    val targetEnd = if (selEnd >= 0) selEnd.coerceIn(targetStart, len) else targetStart
                    edit.setSelection(targetStart, targetEnd)
                    if (!edit.hasFocus()) {
                        edit.requestFocus()
                    }
                    edit.isCursorVisible = true
                } finally {
                    edit.isApplyingHostUpdate = false
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper() || noteEditText?.isAttachedToWindow != true) {
            applyBlock()
        } else {
            mainHandler.post(applyBlock)
        }
    }

    private fun saveTextFromUi() {
        val edit = noteEditText ?: return
        try {
            val uiText = edit.text?.toString()
            if (uiText != null) {
                noteText = uiText
            }
        } catch (_: Throwable) {}
    }

    private fun syncUi() {
        val updateBlock = {
            noteEditText?.let { edit ->
                if (edit.text?.toString() != noteText) {
                    edit.isApplyingHostUpdate = true
                    try {
                        edit.setText(noteText, TextView.BufferType.EDITABLE)
                        val len = edit.text?.length ?: 0
                        edit.setSelection(len)
                    } finally {
                        edit.isApplyingHostUpdate = false
                    }
                }
                edit.applyFormatting(fontSizeSp, fontStyle, wordWrap, lineSpacingMult)
            }
            statusView?.text = "State: ${lifecycleState.name}"
        }
        if (Looper.myLooper() == Looper.getMainLooper() || noteEditText?.isAttachedToWindow != true) {
            updateBlock()
        } else {
            mainHandler.post(updateBlock)
        }
    }

    private fun reportEditorFocusToMain(currentText: String, selStart: Int, selEnd: Int, sessionId: String) {
        val ctx = appContext ?: return
        LogKeeper.log(
            ctx,
            "TestNotesImeDebug",
            "STAGE_3_HEAVY_DISPATCH_FOCUS sess=$sessionId id=$instanceId sel=($selStart,$selEnd) textLen=${currentText.length}"
        )
        val event = HeavyEvent(
            eventId = "ime_focus_${instanceId}_${System.currentTimeMillis()}",
            type = HeavyEventType.STATE_UPDATED,
            sourceId = instanceId,
            payload = mapOf(
                "action" to ACTION_MINIAPP_EDITOR_FOCUS,
                "instanceId" to instanceId,
                "sessionId" to sessionId,
                KEY_TEXT to currentText,
                "selStart" to selStart.toString(),
                "selEnd" to selEnd.toString()
            )
        )
        HeavyProcessHost.getInstance(ctx).reportEvent(event)
    }

    private fun reportSelectionChangedToMain(selStart: Int, selEnd: Int) {
        val ctx = appContext ?: return
        val event = HeavyEvent(
            eventId = "ime_sel_${instanceId}_${System.currentTimeMillis()}",
            type = HeavyEventType.STATE_UPDATED,
            sourceId = instanceId,
            payload = mapOf(
                "action" to ACTION_MINIAPP_SELECTION_CHANGED,
                "instanceId" to instanceId,
                "selStart" to selStart.toString(),
                "selEnd" to selEnd.toString()
            )
        )
        HeavyProcessHost.getInstance(ctx).reportEvent(event)
    }

    private fun reportTextChangedToMain(updatedText: String, selStart: Int, selEnd: Int) {
        val ctx = appContext ?: return
        val event = HeavyEvent(
            eventId = "ime_txt_${instanceId}_${System.currentTimeMillis()}",
            type = HeavyEventType.STATE_UPDATED,
            sourceId = instanceId,
            payload = mapOf(
                "action" to ACTION_MINIAPP_TEXT_CHANGED,
                "instanceId" to instanceId,
                KEY_TEXT to updatedText,
                "selStart" to selStart.toString(),
                "selEnd" to selEnd.toString()
            )
        )
        HeavyProcessHost.getInstance(ctx).reportEvent(event)
    }

    override fun createContentView(context: Context): View {
        val tCreateStart = SystemClock.elapsedRealtime()
        LogKeeper.log(context, "FloatingMiniAppTiming", "HEAVY_VIEW_CREATION_START id=$instanceId elapsedRealtime=$tCreateStart")
        this.appContext = context.applicationContext
        val density = context.resources.displayMetrics.density
        val root = object : LinearLayout(context) {
            override fun hasWindowFocus(): Boolean = true
        }.apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = false
            descendantFocusability = LinearLayout.FOCUS_AFTER_DESCENDANTS
            setBackgroundColor(Color.parseColor("#181825"))
            setPadding(
                (16 * density).toInt(),
                (14 * density).toInt(),
                (16 * density).toInt(),
                (48 * density).toInt()
            )
        }

        // Title Header: Clean production "Note" title
        val titleView = TextView(context).apply {
            text = "Note"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(titleView)

        // Subtitle / Instructions label
        val subtitleView = TextView(context).apply {
            text = "Multiline editor • Floating runtime"
            setTextColor(Color.parseColor("#89B4FA"))
            textSize = 11f
            setPadding(0, (2 * density).toInt(), 0, (8 * density).toInt())
        }
        root.addView(subtitleView)

        // Status view
        statusView = TextView(context).apply {
            text = "State: ${lifecycleState.name}"
            setTextColor(Color.parseColor("#A6ADC8"))
            textSize = 11f
            setPadding(0, 0, 0, (10 * density).toInt())
        }
        root.addView(statusView)

        // Editable Multiline Note Content
        val editor = EmbeddedNoteEditText(
            context = context,
            onEditorFocusedOrTouched = { text, selStart, selEnd, sess ->
                reportEditorFocusToMain(text, selStart, selEnd, sess)
            },
            onEditorSelectionChanged = { selStart, selEnd ->
                reportSelectionChangedToMain(selStart, selEnd)
            }
        ).apply {
            hint = "Type here... Text is persisted durably on Sleep."
            setHintTextColor(Color.parseColor("#6C7086"))
            setTextColor(Color.WHITE)
            highlightColor = Color.parseColor("#585B70")
            textSize = fontSizeSp
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * density
                setColor(Color.parseColor("#313244"))
                setStroke((1 * density).toInt(), Color.parseColor("#45475A"))
            }
            background = bg
            setPadding(
                (12 * density).toInt(),
                (12 * density).toInt(),
                (12 * density).toInt(),
                (12 * density).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            applyFormatting(fontSizeSp, fontStyle, wordWrap, lineSpacingMult)
            isApplyingHostUpdate = true
            setText(noteText, TextView.BufferType.EDITABLE)
            setSelection(noteText.length)
            isApplyingHostUpdate = false

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val updated = s?.toString() ?: ""
                    if (!isApplyingHostUpdate && !isPerformingUndoRedo) {
                        pushUndoState(noteText)
                    }
                    noteText = updated
                    hasUserEditedText = true
                    if (!isApplyingHostUpdate) {
                        val sStart = selectionStart.coerceIn(0, updated.length)
                        val sEnd = selectionEnd.coerceIn(0, updated.length)
                        reportTextChangedToMain(updated, sStart, sEnd)
                    }
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        noteEditText = editor
        root.addView(editor)

        val tCreateDone = SystemClock.elapsedRealtime()
        LogKeeper.log(context, "FloatingMiniAppTiming", "HEAVY_VIEW_CREATION_DONE id=$instanceId durationMs=${tCreateDone - tCreateStart} elapsedRealtime=$tCreateDone")

        return root
    }

    companion object {
        const val APP_TYPE = "test_notes"
        const val APP_TYPE_NOTE = "note"
        const val KEY_TEXT = "note_text"
        const val ACTION_MINIAPP_EDITOR_FOCUS = "miniapp_editor_focus"
        const val ACTION_MINIAPP_SELECTION_CHANGED = "miniapp_selection_changed"
        const val ACTION_MINIAPP_TEXT_CHANGED = "miniapp_text_changed"
        const val ACTION_MINIAPP_FRAME_COMMITTED = "miniapp_frame_committed"
        const val ACTION_NOTE_FORMAT = "note_format"
        const val ACTION_NOTE_UNDO = "note_undo"
        const val ACTION_NOTE_REDO = "note_redo"
        const val ACTION_NOTE_CLEAR = "note_clear"
        const val DEFAULT_INSTRUCTIONAL_TEXT =
            "Floating framework test.\nMove, resize, maximize, sleep, wake, and edit this text."
    }
}

/**
 * TestNotesMiniAppFactory: Factory creating TestNotesMiniAppInstance in HeavyFloatingHost.
 */
class TestNotesMiniAppFactory : MiniAppFactory {
    override fun createAppInstance(instanceId: String, appType: String): MiniAppInstance? {
        if (appType == TestNotesMiniAppInstance.APP_TYPE || appType == TestNotesMiniAppInstance.APP_TYPE_NOTE) {
            return TestNotesMiniAppInstance(instanceId, appType)
        }
        return null
    }
}

