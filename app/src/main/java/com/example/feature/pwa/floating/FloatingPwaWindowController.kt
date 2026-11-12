package com.example.feature.pwa.floating

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.core.LogKeeper
import com.example.core.ipc.ConnectionState
import com.example.core.ipc.HeavyCommand
import com.example.core.ipc.HeavyCommandType
import com.example.core.ipc.HeavyEvent
import com.example.core.ipc.HeavyProcessConnectionManager
import com.example.data.AppDatabase
import com.example.feature.floating.HeavyFloatingHost
import com.example.feature.pwa.PwaListActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * FloatingPwaWindowController: Main-process browser toolbar + PWA picker controller
 * attached inside [com.example.feature.floating.FloatingMiniAppWindow] when `appType == "floating_pwa"`.
 *
 * Completely independent from full-screen [com.example.feature.pwa.PwaPlayerActivity].
 */
class FloatingPwaWindowController(
    private val context: Context,
    private val instanceId: String,
    private val onRequestWindowFocusable: (Boolean) -> Unit,
    private val onTitleChanged: (String) -> Unit
) {

    private val density: Float = (context.resources.displayMetrics.density).takeIf { it > 0f } ?: 2f
    private fun dp(v: Int): Int = (v * density).toInt()
    private fun dpF(v: Float): Float = v * density

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var toolbarContainer: LinearLayout? = null
    private var backButton: TextView? = null
    private var forwardButton: TextView? = null
    private var reloadStopButton: TextView? = null
    private var urlEditText: EditText? = null
    private var progressBar: ProgressBar? = null

    private var currentUrl: String = ""
    private var canGoBack: Boolean = false
    private var canGoForward: Boolean = false
    private var isLoading: Boolean = false
    private var isEditingUrl: Boolean = false
    private var pickerDialog: AlertDialog? = null

    fun getToolbarHeightPx(): Int = dp(40)

    fun createToolbarView(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E2E"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(38)
            )
        }

        val back = createNavButton("◀", "Back") {
            sendCommandToHeavy(FloatingPwaMiniAppInstance.CMD_PWA_BACK)
        }.apply { alpha = 0.4f }
        backButton = back
        row.addView(back)

        val fwd = createNavButton("▶", "Forward") {
            sendCommandToHeavy(FloatingPwaMiniAppInstance.CMD_PWA_FORWARD)
        }.apply { alpha = 0.4f }
        forwardButton = fwd
        row.addView(fwd)

        val reload = createNavButton("⟳", "Reload or Stop") {
            if (isLoading) {
                sendCommandToHeavy(FloatingPwaMiniAppInstance.CMD_PWA_STOP)
            } else {
                sendCommandToHeavy(FloatingPwaMiniAppInstance.CMD_PWA_RELOAD)
            }
        }
        reloadStopButton = reload
        row.addView(reload)

        val urlInput = object : EditText(context) {
            override fun onTouchEvent(event: MotionEvent?): Boolean {
                if (event?.actionMasked == MotionEvent.ACTION_DOWN) {
                    isEditingUrl = true
                    onRequestWindowFocusable(true)
                    post {
                        requestFocus()
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
                return super.onTouchEvent(event)
            }

            override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
                super.onFocusChanged(focused, direction, previouslyFocusedRect)
                if (!focused && isEditingUrl) {
                    isEditingUrl = false
                    onRequestWindowFocusable(false)
                }
            }
        }.apply {
            hint = "Enter URL or pick PWA..."
            setHintTextColor(Color.parseColor("#6C7086"))
            setTextColor(Color.WHITE)
            textSize = 11.5f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#313244"))
                cornerRadius = dpF(8f)
                setStroke(dp(1).coerceAtLeast(1), Color.parseColor("#45475A"))
            }
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
            setOnEditorActionListener { v, actionId, event ->
                val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE || isEnter) {
                    val raw = v.text?.toString()?.trim().orEmpty()
                    if (raw.isNotEmpty()) {
                        sendCommandToHeavy(
                            FloatingPwaMiniAppInstance.CMD_PWA_LOAD_URL,
                            mapOf(
                                FloatingPwaMiniAppInstance.KEY_CURRENT_URL to raw,
                                "manual_entry" to "true"
                            )
                        )
                    }
                    isEditingUrl = false
                    clearFocus()
                    hideKeyboard(this)
                    onRequestWindowFocusable(false)
                    true
                } else false
            }
        }
        urlEditText = urlInput
        row.addView(urlInput)

        val pwaPickerBtn = TextView(context).apply {
            text = "PWAs"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#89B4FA"))
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = false
            contentDescription = "Choose installed PWA"
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#313244"))
                cornerRadius = dpF(6f)
                setStroke(dp(1).coerceAtLeast(1), Color.parseColor("#89B4FA"))
            }
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setOnClickListener {
                clearUrlBarFocus()
                showPwaPickerDialog()
            }
        }
        row.addView(pwaPickerBtn)
        root.addView(row)

        val pBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(2).coerceAtLeast(2)
            )
        }
        progressBar = pBar
        root.addView(pBar)

        toolbarContainer = root
        return root
    }

    private fun createNavButton(label: String, desc: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#CDD6F4"))
            gravity = Gravity.CENTER
            contentDescription = desc
            isClickable = true
            isFocusable = false
            minWidth = dp(28)
            setPadding(dp(4), dp(2), dp(4), dp(2))
            setOnClickListener { onClick() }
        }
    }

    fun handleHeavyEvent(event: HeavyEvent): Boolean {
        val action = event.payload["action"] ?: return false
        when (action) {
            FloatingPwaMiniAppInstance.ACTION_PWA_STATE_CHANGED -> {
                val url = event.payload["url"] ?: ""
                val title = event.payload["title"]?.takeIf { it.isNotBlank() } ?: "Floating PWA"
                canGoBack = event.payload["canGoBack"]?.toBooleanStrictOrNull() ?: false
                canGoForward = event.payload["canGoForward"]?.toBooleanStrictOrNull() ?: false
                isLoading = event.payload["isLoading"]?.toBooleanStrictOrNull() ?: false
                val progress = event.payload["progress"]?.toIntOrNull() ?: 100

                currentUrl = url
                backButton?.alpha = if (canGoBack) 1.0f else 0.35f
                forwardButton?.alpha = if (canGoForward) 1.0f else 0.35f
                reloadStopButton?.text = if (isLoading) "✕" else "⟳"

                progressBar?.apply {
                    this.progress = progress.coerceIn(0, 100)
                    visibility = if (isLoading && progress < 100) View.VISIBLE else View.GONE
                }

                if (!isEditingUrl) {
                    val displayUrl = if (url == "about:blank") "" else url
                    if (urlEditText?.text?.toString() != displayUrl) {
                        urlEditText?.setText(displayUrl)
                    }
                }
                onTitleChanged(title)
                return true
            }
            FloatingPwaMiniAppInstance.ACTION_PWA_FILE_CHOOSER_REQUEST -> {
                val reqId = event.payload["requestId"] ?: return true
                val pwaId = event.payload["pwaId"] ?: ""
                val isMultiple = event.payload["isMultiple"]?.toBooleanStrictOrNull() ?: false
                val acceptTypes = event.payload["acceptTypes"] ?: "*/*"
                launchRelayActivity(
                    Intent(context, FloatingPwaRelayActivity::class.java).apply {
                        putExtra(FloatingPwaRelayActivity.EXTRA_MODE, FloatingPwaRelayActivity.MODE_FILE_CHOOSER)
                        putExtra(FloatingPwaRelayActivity.EXTRA_INSTANCE_ID, instanceId)
                        putExtra(FloatingPwaRelayActivity.EXTRA_REQUEST_ID, reqId)
                        putExtra(FloatingPwaRelayActivity.EXTRA_PWA_ID, pwaId)
                        putExtra(FloatingPwaRelayActivity.EXTRA_IS_MULTIPLE, isMultiple)
                        putExtra(FloatingPwaRelayActivity.EXTRA_ACCEPT_TYPES, acceptTypes)
                    }
                )
                return true
            }
            FloatingPwaMiniAppInstance.ACTION_PWA_PERMISSION_REQUEST -> {
                val reqId = event.payload["requestId"] ?: return true
                val cap = event.payload["cap"] ?: return true
                val pwaId = event.payload["pwaId"] ?: ""
                val wantsCamera = event.payload["wantsCamera"]?.toBooleanStrictOrNull() ?: false
                val wantsMic = event.payload["wantsMic"]?.toBooleanStrictOrNull() ?: false
                launchRelayActivity(
                    Intent(context, FloatingPwaRelayActivity::class.java).apply {
                        putExtra(FloatingPwaRelayActivity.EXTRA_MODE, FloatingPwaRelayActivity.MODE_PERMISSION)
                        putExtra(FloatingPwaRelayActivity.EXTRA_INSTANCE_ID, instanceId)
                        putExtra(FloatingPwaRelayActivity.EXTRA_REQUEST_ID, reqId)
                        putExtra(FloatingPwaRelayActivity.EXTRA_CAPABILITY, cap)
                        putExtra(FloatingPwaRelayActivity.EXTRA_PWA_ID, pwaId)
                        putExtra(FloatingPwaRelayActivity.EXTRA_WANTS_CAMERA, wantsCamera)
                        putExtra(FloatingPwaRelayActivity.EXTRA_WANTS_MIC, wantsMic)
                    }
                )
                return true
            }
        }
        return false
    }

    fun showPwaPickerDialog() {
        scope.launch {
            val pwas = withContext(Dispatchers.IO) {
                try {
                    AppDatabase.getDatabase(context).pwaDao().getAllSync()
                } catch (t: Throwable) {
                    LogKeeper.logError(context, "FloatingPwaController", "Failed loading PWAs", t)
                    emptyList()
                }
            }

            try {
                pickerDialog?.dismiss()
            } catch (_: Throwable) {}

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#181825"))
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }

            val scroll = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(280)
                )
            }
            val listLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }

            if (pwas.isEmpty()) {
                listLayout.addView(TextView(context).apply {
                    text = "No offline PWAs imported yet. Import a PWA bundle or type a web URL in the address bar."
                    setTextColor(Color.parseColor("#A6ADC8"))
                    textSize = 13f
                    setPadding(dp(8), dp(20), dp(8), dp(20))
                })
            } else {
                for (pwa in pwas) {
                    val itemBtn = Button(context).apply {
                        text = "${pwa.name}  •  ${pwa.version}"
                        setTextColor(Color.parseColor("#CDD6F4"))
                        textSize = 13f
                        isAllCaps = false
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        minHeight = dp(48)
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#252538"))
                            cornerRadius = dpF(8f)
                            setStroke(1, Color.parseColor("#313244"))
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = dp(6)
                        }
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                        setOnClickListener {
                            pickerDialog?.dismiss()
                            sendCommandToHeavy(
                                FloatingPwaMiniAppInstance.CMD_PWA_SELECT,
                                mapOf(FloatingPwaMiniAppInstance.KEY_PWA_ID to pwa.id)
                            )
                        }
                    }
                    listLayout.addView(itemBtn)
                }
            }

            scroll.addView(listLayout)
            container.addView(scroll)

            val dialog = AlertDialog.Builder(context)
                .setTitle("Select Offline PWA")
                .setView(container)
                .setNeutralButton("Manage / Import PWAs") { d, _ ->
                    d.dismiss()
                    try {
                        context.startActivity(Intent(context, PwaListActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } catch (_: Throwable) {}
                }
                .setNegativeButton("Close", null)
                .create()

            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            pickerDialog = dialog
            dialog.show()
        }
    }

    private fun launchRelayActivity(intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (t: Throwable) {
            LogKeeper.logError(context, "FloatingPwaController", "Failed launching relay activity", t)
            Toast.makeText(context, "Cannot open chooser/permission prompt", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendCommandToHeavy(action: String, extra: Map<String, String> = emptyMap()) {
        val payload = LinkedHashMap<String, String>().apply {
            put("action", action)
            put("instance_id", instanceId)
            putAll(extra)
        }
        val cm = HeavyProcessConnectionManager.getInstance(context)
        if (cm.state == ConnectionState.CONNECTED) {
            cm.sendCommand(
                HeavyCommand(
                    commandId = UUID.randomUUID().toString(),
                    type = HeavyCommandType.PAUSE_OPERATION,
                    targetId = instanceId,
                    payload = payload
                ),
                autoConnect = false
            )
        } else {
            val inst = HeavyFloatingHost.getInstance(context).getActiveInstance(instanceId)
            if (inst is FloatingPwaMiniAppInstance) {
                inst.handleCommand(context, action, payload)
            }
        }
    }

    fun isEditingUrlActive(): Boolean = isEditingUrl

    fun clearUrlBarFocus() {
        isEditingUrl = false
        urlEditText?.clearFocus()
        urlEditText?.let { hideKeyboard(it) }
    }

    private fun hideKeyboard(view: View) {
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
        } catch (_: Throwable) {}
    }

    fun release() {
        try { pickerDialog?.dismiss() } catch (_: Throwable) {}
        pickerDialog = null
        scope.cancel()
        toolbarContainer = null
        backButton = null
        forwardButton = null
        reloadStopButton = null
        urlEditText = null
        progressBar = null
    }
}
