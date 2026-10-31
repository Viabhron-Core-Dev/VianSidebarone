package com.example.feature.floating

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * DefaultMiniAppInstance: A clean, responsive mini-app implementation in :heavy.
 *
 * Implements the complete sleep contract:
 * - [prepareForSleep]: Captures editable text and counter into compact payload map.
 * - [releaseRuntime]: Dismantles view tree, callbacks, and memory buffers.
 * - [restoreFromSnapshot]: Reconstructs live state from snapshot payload.
 */
class DefaultMiniAppInstance(
    override val instanceId: String,
    override val appType: String
) : MiniAppInstance {

    override var lifecycleState: MiniAppLifecycleState = MiniAppLifecycleState.UNINITIALIZED
        private set

    private var noteContent: String = ""
    private var counter: Int = 0
    private var lastSavedTimestamp: Long = 0L

    private var noteEditText: EditText? = null
    private var counterView: TextView? = null
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
        updateUi()
    }

    override fun onPause() {
        lifecycleState = MiniAppLifecycleState.PAUSED
        saveCurrentContentFromUi()
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
        saveCurrentContentFromUi()
        val payload = LinkedHashMap<String, String>()
        payload["note_content"] = noteContent
        payload["counter"] = counter.toString()
        payload["last_saved"] = System.currentTimeMillis().toString()
        payload["app_type"] = appType
        return payload
    }

    override fun releaseRuntime() {
        noteEditText = null
        counterView = null
        statusView = null
    }

    override fun restoreFromSnapshot(snapshot: MiniAppRecoverySnapshot) {
        val payload = snapshot.payload
        noteContent = payload["note_content"] ?: ""
        counter = payload["counter"]?.toIntOrNull() ?: 0
        lastSavedTimestamp = payload["last_saved"]?.toLongOrNull() ?: snapshot.timestamp
        updateUi()
    }

    override fun onSaveState(): Map<String, String> {
        saveCurrentContentFromUi()
        return mapOf(
            "note_content" to noteContent,
            "counter" to counter.toString(),
            "last_saved" to System.currentTimeMillis().toString()
        )
    }

    private fun saveCurrentContentFromUi() {
        noteEditText?.let {
            noteContent = it.text.toString()
        }
    }

    private fun updateUi() {
        noteEditText?.let {
            if (it.text.toString() != noteContent) {
                it.setText(noteContent)
                it.setSelection(noteContent.length)
            }
        }
        counterView?.text = "Edits: $counter"
        statusView?.text = "State: ${lifecycleState.name}"
    }

    override fun createContentView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E2E"))
            setPadding(24, 24, 24, 24)
        }

        // Header info
        val header = TextView(context).apply {
            text = "Mini-App: ${appType.replace('_', ' ').replaceFirstChar { it.uppercase() }}"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(header)

        // Status view
        statusView = TextView(context).apply {
            text = "State: ${lifecycleState.name}"
            setTextColor(Color.parseColor("#89B4FA"))
            textSize = 12f
            setPadding(0, 4, 0, 16)
        }
        root.addView(statusView)

        // Editable note content
        noteEditText = EditText(context).apply {
            hint = "Type here... State is persisted durably on sleep."
            setHintTextColor(Color.parseColor("#6C7086"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundColor(Color.parseColor("#313244"))
            setPadding(16, 16, 16, 16)
            gravity = Gravity.TOP or Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setText(noteContent)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    noteContent = s?.toString() ?: ""
                    counter++
                    counterView?.text = "Edits: $counter"
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        root.addView(noteEditText)

        // Footer row with counter and quick action
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 0)
        }

        counterView = TextView(context).apply {
            text = "Edits: $counter"
            setTextColor(Color.parseColor("#A6ADC8"))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        footer.addView(counterView)

        val clearButton = Button(context).apply {
            text = "Clear"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#45475A"))
            textSize = 12f
            setOnClickListener {
                noteEditText?.setText("")
                noteContent = ""
            }
        }
        footer.addView(clearButton)

        root.addView(footer)
        return root
    }
}

/**
 * DefaultMiniAppFactory: Factory registering default mini-apps in HeavyFloatingHost.
 */
class DefaultMiniAppFactory : MiniAppFactory {
    override fun createAppInstance(instanceId: String, appType: String): MiniAppInstance {
        return DefaultMiniAppInstance(instanceId, appType)
    }
}
