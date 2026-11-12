package com.example.feature.floating

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import com.example.core.LogKeeper
import com.example.core.ipc.HeavyEvent
import com.example.core.ipc.HeavyEventType
import com.example.core.ipc.HeavyProcessHost

/**
 * Generic embedded IME focus/blur bridge between :heavy mini-app UI controls
 * (Reader search, SAF folder search, Floating PWA web inputs, etc.) and Main's
 * FloatingMiniAppWindow overlay focus carrier.
 */
object EmbeddedImeBridge {

    const val ACTION_MINIAPP_EDITOR_FOCUS = "miniapp_editor_focus"
    const val ACTION_MINIAPP_EDITOR_BLUR = "miniapp_editor_blur"
    const val CMD_EMBEDDED_IME_BLUR = "embedded_ime_blur"

    fun notifyFocus(
        context: Context,
        instanceId: String?,
        text: String = "",
        selStart: Int = text.length,
        selEnd: Int = text.length,
        sessionId: String = newSessionId(instanceId)
    ): String {
        val id = instanceId?.takeIf { it.isNotEmpty() } ?: return sessionId
        try {
            HeavyProcessHost.getInstance(context).reportEvent(
                HeavyEvent(
                    eventId = "ime_focus_${id}_${System.currentTimeMillis()}",
                    type = HeavyEventType.STATE_UPDATED,
                    sourceId = id,
                    payload = mapOf(
                        "action" to ACTION_MINIAPP_EDITOR_FOCUS,
                        "instanceId" to id,
                        "sessionId" to sessionId,
                        TestNotesMiniAppInstance.KEY_TEXT to text,
                        "selStart" to selStart.toString(),
                        "selEnd" to selEnd.toString()
                    )
                )
            )
            LogKeeper.log(context, "EmbeddedImeBridge", "NOTIFY_FOCUS id=$id sess=$sessionId len=${text.length}")
        } catch (t: Throwable) {
            LogKeeper.log(context, "EmbeddedImeBridge", "NOTIFY_FOCUS_ERROR id=$id err=${t.message}")
        }
        return sessionId
    }

    fun notifyBlur(
        context: Context,
        instanceId: String?,
        sessionId: String = "force"
    ) {
        val id = instanceId?.takeIf { it.isNotEmpty() } ?: return
        try {
            HeavyProcessHost.getInstance(context).reportEvent(
                HeavyEvent(
                    eventId = "ime_blur_${id}_${System.currentTimeMillis()}",
                    type = HeavyEventType.STATE_UPDATED,
                    sourceId = id,
                    payload = mapOf(
                        "action" to ACTION_MINIAPP_EDITOR_BLUR,
                        "instanceId" to id,
                        "sessionId" to sessionId
                    )
                )
            )
            LogKeeper.log(context, "EmbeddedImeBridge", "NOTIFY_BLUR id=$id sess=$sessionId")
        } catch (t: Throwable) {
            LogKeeper.log(context, "EmbeddedImeBridge", "NOTIFY_BLUR_ERROR id=$id err=${t.message}")
        }
    }

    fun clearFocusRecursive(view: View?) {
        if (view == null) return
        if (view.hasFocus()) {
            view.clearFocus()
            try {
                val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(view.windowToken, 0)
            } catch (_: Throwable) {}
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                clearFocusRecursive(view.getChildAt(i))
            }
        }
    }

    fun newSessionId(instanceId: String?): String {
        val prefix = instanceId?.takeLast(6) ?: "embed"
        return "ime_${prefix}_${System.currentTimeMillis() % 100000}"
    }
}

/**
 * Reusable EditText for mini-apps running inside :heavy SurfaceControlViewHost.
 * Coordinates overlay focusable flag clearing with Main via [EmbeddedImeBridge].
 */
class EmbeddedInputEditText(
    context: Context,
    private val instanceIdProvider: () -> String?
) : EditText(context) {

    private var activeSessionId: String = ""
    private val mainHandler = Handler(Looper.getMainLooper())
    private val showImeRunnable = Runnable {
        if (isAttachedToWindow && hasFocus()) {
            try {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.restartInput(this)
                imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            } catch (_: Throwable) {}
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isEnabled = true
    }

    override fun hasWindowFocus(): Boolean {
        return isAttachedToWindow || super.hasWindowFocus()
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        val res = super.onTouchEvent(event)
        if (event?.actionMasked == MotionEvent.ACTION_UP && isEnabled) {
            requestFocus()
            activateBridgeSession()
        }
        return res
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        if (focused) {
            activateBridgeSession()
        } else {
            deactivateBridgeSession()
        }
    }

    fun activateBridgeSession() {
        val textStr = text?.toString() ?: ""
        val sess = EmbeddedImeBridge.notifyFocus(
            context = context,
            instanceId = instanceIdProvider(),
            text = textStr,
            selStart = selectionStart.coerceAtLeast(0),
            selEnd = selectionEnd.coerceAtLeast(0)
        )
        activeSessionId = sess
        mainHandler.removeCallbacks(showImeRunnable)
        mainHandler.postDelayed(showImeRunnable, 60L)
    }

    fun deactivateBridgeSession() {
        mainHandler.removeCallbacks(showImeRunnable)
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(windowToken, 0)
        } catch (_: Throwable) {}
        val sess = activeSessionId.ifEmpty { "force" }
        activeSessionId = ""
        EmbeddedImeBridge.notifyBlur(context, instanceIdProvider(), sess)
    }

    override fun onDetachedFromWindow() {
        mainHandler.removeCallbacks(showImeRunnable)
        if (activeSessionId.isNotEmpty()) {
            EmbeddedImeBridge.notifyBlur(context, instanceIdProvider(), activeSessionId)
            activeSessionId = ""
        }
        super.onDetachedFromWindow()
    }
}
