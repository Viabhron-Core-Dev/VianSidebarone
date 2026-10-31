package com.example.feature.floating

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * TestNotesMiniAppInstance: Concrete Heavy-owned MiniAppInstance for appType = "test_notes".
 *
 * Designed to exercise and validate the real floating mini-app framework end-to-end:
 * 1. Title: "Test Notes"
 * 2. Editable text field with initial instructional text.
 * 3. Saves and restores text across Sleep / Wake cycles.
 * 4. Pure Android Views (no Compose, no network, no database, no heavy dependencies).
 * 5. Releases memory-heavy views on sleep so Heavy can safely teardown when idle.
 */
class TestNotesMiniAppInstance(
    override val instanceId: String,
    override val appType: String = APP_TYPE
) : MiniAppInstance {

    override var lifecycleState: MiniAppLifecycleState = MiniAppLifecycleState.UNINITIALIZED
        private set

    var noteText: String = DEFAULT_INSTRUCTIONAL_TEXT
        private set

    private var noteEditText: EditText? = null
    private var statusView: TextView? = null

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
        payload["app_type"] = APP_TYPE
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
        val restored = payload[KEY_TEXT] ?: payload["note_content"]
        if (!restored.isNullOrEmpty()) {
            noteText = restored
        }
        syncUi()
    }

    override fun onSaveState(): Map<String, String> {
        saveTextFromUi()
        return mapOf(KEY_TEXT to noteText)
    }

    private fun saveTextFromUi() {
        noteEditText?.let {
            noteText = it.text.toString()
        }
    }

    private fun syncUi() {
        noteEditText?.let {
            if (it.text.toString() != noteText) {
                it.setText(noteText)
                it.setSelection(noteText.length)
            }
        }
        statusView?.text = "State: ${lifecycleState.name}"
    }

    override fun createContentView(context: Context): View {
        val density = context.resources.displayMetrics.density
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#181825"))
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
        }

        // Title Header
        val titleView = TextView(context).apply {
            text = "Test Notes"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(titleView)

        // Subtitle / Instructions label
        val subtitleView = TextView(context).apply {
            text = "Floating framework test • Heavy Process Runtime"
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

        // Editable Note Content
        noteEditText = EditText(context).apply {
            hint = "Type here... Text is persisted durably on Sleep."
            setHintTextColor(Color.parseColor("#6C7086"))
            setTextColor(Color.WHITE)
            textSize = 13f
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * density
                setColor(Color.parseColor("#313244"))
                setStroke((1 * density).toInt(), Color.parseColor("#45475A"))
            }
            background = bg
            setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
            gravity = Gravity.TOP or Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setText(noteText)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    noteText = s?.toString() ?: ""
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        root.addView(noteEditText)

        return root
    }

    companion object {
        const val APP_TYPE = "test_notes"
        const val KEY_TEXT = "note_text"
        const val DEFAULT_INSTRUCTIONAL_TEXT =
            "Floating framework test.\nMove, resize, maximize, sleep, wake, and edit this text."
    }
}

/**
 * TestNotesMiniAppFactory: Factory creating TestNotesMiniAppInstance in HeavyFloatingHost.
 */
class TestNotesMiniAppFactory : MiniAppFactory {
    override fun createAppInstance(instanceId: String, appType: String): MiniAppInstance? {
        if (appType == TestNotesMiniAppInstance.APP_TYPE) {
            return TestNotesMiniAppInstance(instanceId, appType)
        }
        return null
    }
}
