package com.example.feature.sidebar

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.example.R
import com.example.service.AppNotificationListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@SuppressLint("ViewConstructor")
class MediaPlayerPageView(
    context: Context,
    private val onCloseSidebar: () -> Unit = {},
    private val onHeightChanged: ((Int) -> Unit)? = null,
    val containerId: String = "sidebar",
    val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
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
    private val llPlayer: View?
    private val ivArtwork: ImageView?
    private val tvTitle: TextView?
    private val tvArtist: TextView?
    private val btnPrev: ImageView?
    private val btnPlayPause: ImageView?
    private val btnNext: ImageView?

    private var currentHeightPx = 0
    private var updateJob: Job? = null

    var themeBackgroundDrawable: Drawable? = null
        private set

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            playbackState = state
            updateUI()
        }

        override fun onMetadataChanged(metadataUpdate: MediaMetadata?) {
            metadata = metadataUpdate
            updateUI()
        }
    }

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        try {
            LayoutInflater.from(context).inflate(R.layout.page_media_player, this, true)
        } catch (_: Throwable) {}

        llBlank = findViewById(R.id.ll_blank)
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

        addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val height = bottom - top
            if (currentHeightPx != height && height > 0) {
                currentHeightPx = height
                onHeightChanged?.invoke(height)
            }
        }

        updateState()
        updateUI()
    }

    fun getCurrentHeightPx(): Int {
        if (currentHeightPx > 0) return currentHeightPx
        val density = try { resources?.displayMetrics?.density ?: 1f } catch (_: Throwable) { 1f }
        val fallbackW = (300 * density).toInt()
        val w = if (width > 0) width else fallbackW
        try {
            measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            val minH = (80 * density).toInt()
            val maxH = (120 * density).toInt()
            currentHeightPx = measuredHeight.coerceIn(minH, maxH)
        } catch (_: Throwable) {
            currentHeightPx = (100 * density).toInt()
        }
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
        stopUpdates()
        scope.coroutineContext[Job]?.cancelChildren()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateState()
        updateUI()
        startUpdates()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopUpdates()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) {
            updateState()
            updateUI()
            startUpdates()
        } else {
            stopUpdates()
        }
    }

    override fun onPageSelected() {
        startUpdates()
    }

    override fun onPageUnselected() {
        stopUpdates()
        ivArtwork?.setImageDrawable(null)
    }

    private fun startUpdates() {
        if (updateJob?.isActive == true) return
        updateJob = scope.launch {
            while (true) {
                updateState()
                delay(1000)
            }
        }
    }

    private fun stopUpdates() {
        updateJob?.cancel()
        updateJob = null
        try {
            activeController?.unregisterCallback(callback)
        } catch (_: Throwable) {}
    }

    private fun updateState() {
        try {
            val manager = mediaSessionManager ?: return
            val componentName = ComponentName(context, AppNotificationListener::class.java)
            val controllers = manager.getActiveSessions(componentName)
            val controller = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: controllers.firstOrNull()

            if (controller != activeController) {
                try {
                    activeController?.unregisterCallback(callback)
                } catch (_: Throwable) {}
                activeController = controller
                try {
                    activeController?.registerCallback(callback)
                } catch (_: Throwable) {}
            }
            playbackState = activeController?.playbackState
            metadata = activeController?.metadata
            updateUI()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateUI() {
        if (activeController == null) {
            llBlank?.visibility = View.VISIBLE
            llPlayer?.visibility = View.GONE
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
