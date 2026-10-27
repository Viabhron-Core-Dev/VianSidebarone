package com.example.feature.sidebar

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.example.R
import com.example.service.AppNotificationListener
import kotlin.math.abs

@SuppressLint("ViewConstructor")
class MediaPlayerPageView(
    context: Context,
    private val onCloseSidebar: () -> Unit = {},
    private val onHeightChanged: ((Int) -> Unit)? = null,
    val containerId: String = "sidebar"
) : FrameLayout(context), SidebarPageControllable {

    private val mediaSessionManager = try {
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    } catch (_: Throwable) {
        null
    }
    private var activeController: MediaController? = null
    private var playbackState: PlaybackState? = null
    private var metadata: MediaMetadata? = null

    private val llBlank: View?
    private val tvBlankStatus: TextView?
    private val llPlayer: View?
    private val ivArtwork: ImageView?
    private val tvTitle: TextView?
    private val tvArtist: TextView?
    private val btnPrev: ImageView?
    private val btnPlayPause: ImageView?
    private val btnNext: ImageView?

    private var currentHeightPx = 0
    private var isListening = false

    var themeBackgroundDrawable: Drawable? = null
        private set

    private var downX = 0f
    private var downY = 0f
    private var isHorizontalSwipeConsumed = false
    private val touchSlop = try { ViewConfiguration.get(context).scaledTouchSlop } catch (_: Throwable) { 16 }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            playbackState = state
            updateUI()
        }

        override fun onMetadataChanged(metadataUpdate: MediaMetadata?) {
            metadata = metadataUpdate
            updateUI()
        }
    }

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        post { updateStateWithControllers(controllers) }
    }

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        try {
            LayoutInflater.from(context).inflate(R.layout.page_media_player, this, true)
        } catch (_: Throwable) {}

        llBlank = findViewById(R.id.ll_blank)
        tvBlankStatus = llBlank?.findViewById(android.R.id.text1) ?: llBlank?.findViewWithTag("status") ?: run {
            // Find any TextView in llBlank
            (llBlank as? ViewGroup)?.let { vg ->
                (0 until vg.childCount).map { vg.getChildAt(it) }.filterIsInstance<TextView>().firstOrNull()
            }
        }
        llPlayer = findViewById(R.id.ll_player)
        ivArtwork = findViewById(R.id.iv_artwork)
        tvTitle = findViewById(R.id.tv_title)
        tvArtist = findViewById(R.id.tv_artist)
        btnPrev = findViewById(R.id.btn_prev)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        btnNext = findViewById(R.id.btn_next)

        btnPrev?.setOnClickListener {
            try {
                activeController?.transportControls?.skipToPrevious()
            } catch (_: Throwable) {}
        }
        btnNext?.setOnClickListener {
            try {
                activeController?.transportControls?.skipToNext()
            } catch (_: Throwable) {}
        }
        btnPlayPause?.setOnClickListener {
            try {
                if (playbackState?.state == PlaybackState.STATE_PLAYING) {
                    activeController?.transportControls?.pause()
                } else {
                    activeController?.transportControls?.play()
                }
            } catch (_: Throwable) {}
        }

        updateState()
        updateUI()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                isHorizontalSwipeConsumed = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (!isHorizontalSwipeConsumed && abs(dx) > touchSlop * 2 && abs(dx) > abs(dy) * 1.5f) {
                    isHorizontalSwipeConsumed = true
                    val delta = if (dx < 0) 1 else -1
                    com.example.core.ipc.HeavyProcessHost.getInstance(context).requestMainAction(
                        com.example.core.ipc.MainCommandRequest(
                            action = com.example.core.ipc.MainActionType.CUSTOM,
                            targetId = "NAVIGATE_PAGE",
                            parameters = mapOf("delta" to delta.toString())
                        )
                    )
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isHorizontalSwipeConsumed = false
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    fun getCurrentHeightPx(): Int {
        if (currentHeightPx > 0) return currentHeightPx
        val density = try { resources?.displayMetrics?.density ?: 1f } catch (_: Throwable) { 1f }
        val targetH = if (activeController != null) (110 * density).toInt() else (90 * density).toInt()
        currentHeightPx = targetH
        return currentHeightPx
    }

    fun applyThemeBackground(bgColor: Int = 0, isRight: Boolean = true) {
        val finalColor = if (bgColor != 0) {
            bgColor
        } else {
            try {
                val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
                val opacity = prefs.getFloat("sidebar_transparency", 0.9f)
                val alphaInt = (opacity * 255).toInt().coerceIn(0, 255)
                val colorHex = prefs.getString("sidebar_color", "#1E1E2E") ?: "#1E1E2E"
                val baseColor = try { Color.parseColor(colorHex) } catch (_: Exception) { Color.parseColor("#1E1E2E") }
                val r = Color.red(baseColor)
                val g = Color.green(baseColor)
                val b = Color.blue(baseColor)
                Color.argb(alphaInt, r, g, b)
            } catch (_: Throwable) {
                Color.parseColor("#1E1E2E")
            }
        }

        val density = try { resources?.displayMetrics?.density ?: 1f } catch (_: Throwable) { 1f }
        val radius = 16f * density
        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(finalColor)
            cornerRadii = if (isRight) {
                floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, radius, radius)
            } else {
                floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            }
        }
        themeBackgroundDrawable = bgDrawable
        background = bgDrawable
    }

    fun cleanup() {
        stopListening()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startListening()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopListening()
    }

    override fun onPageSelected() {
        startListening()
    }

    override fun onPageUnselected() {
        stopListening()
        ivArtwork?.setImageDrawable(null)
    }

    private fun startListening() {
        if (isListening) return
        isListening = true
        try {
            val pkg = context.packageName ?: ""
            if (pkg.isNotEmpty()) {
                val componentName = ComponentName(pkg, AppNotificationListener::class.java.name)
                mediaSessionManager?.addOnActiveSessionsChangedListener(sessionsChangedListener, componentName)
            }
        } catch (_: Throwable) {}
        updateState()
    }

    private fun stopListening() {
        isListening = false
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (_: Throwable) {}
        try {
            activeController?.unregisterCallback(controllerCallback)
        } catch (_: Throwable) {}
        activeController = null
    }

    private fun hasNotificationAccess(): Boolean {
        return try {
            val cr = context.contentResolver ?: return false
            val enabled = Settings.Secure.getString(cr, "enabled_notification_listeners") ?: return false
            val pkg = context.packageName ?: return false
            val myService = ComponentName(pkg, AppNotificationListener::class.java.name).flattenToString()
            enabled.contains(myService)
        } catch (_: Throwable) {
            false
        }
    }

    private fun updateState() {
        try {
            val manager = mediaSessionManager ?: return
            val pkg = context.packageName ?: return
            val componentName = ComponentName(pkg, AppNotificationListener::class.java.name)
            val controllers = manager.getActiveSessions(componentName)
            updateStateWithControllers(controllers)
        } catch (_: SecurityException) {
            updateStateWithControllers(emptyList())
        } catch (_: Throwable) {
            updateStateWithControllers(emptyList())
        }
    }

    private fun updateStateWithControllers(controllers: List<MediaController>?) {
        val list = controllers ?: emptyList()
        val controller = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull()

        if (controller != activeController) {
            try {
                activeController?.unregisterCallback(controllerCallback)
            } catch (_: Throwable) {}
            activeController = controller
            try {
                activeController?.registerCallback(controllerCallback)
            } catch (_: Throwable) {}
        }
        playbackState = activeController?.playbackState
        metadata = activeController?.metadata
        updateUI()

        val density = try { resources?.displayMetrics?.density ?: 1f } catch (_: Throwable) { 1f }
        val targetH = if (activeController != null) (110 * density).toInt() else (90 * density).toInt()
        if (targetH != currentHeightPx) {
            currentHeightPx = targetH
            onHeightChanged?.invoke(targetH)
        }
    }

    private fun updateUI() {
        if (activeController == null) {
            llBlank?.visibility = View.VISIBLE
            llPlayer?.visibility = View.GONE

            if (!hasNotificationAccess()) {
                tvBlankStatus?.text = "Grant Notification Access to mirror media"
                llBlank?.setOnClickListener {
                    try {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    } catch (_: Throwable) {}
                }
            } else {
                tvBlankStatus?.text = "Nothing playing"
                llBlank?.setOnClickListener(null)
            }
        } else {
            llBlank?.visibility = View.GONE
            llPlayer?.visibility = View.VISIBLE
            val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown Title"
            val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown Artist"
            val artwork = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING

            tvTitle?.text = title
            tvArtist?.text = artist

            if (artwork != null) {
                ivArtwork?.setImageBitmap(artwork)
            } else {
                ivArtwork?.setImageResource(android.R.drawable.ic_media_play)
            }

            btnPlayPause?.setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        }
    }
}
