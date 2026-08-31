package com.example.feature.sidebar

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.example.R
import com.example.service.AppNotificationListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@SuppressLint("ViewConstructor")
class MediaPlayerPageView(
    context: Context,
    private val onCloseSidebar: () -> Unit,
    private val onHeightChanged: (Int) -> Unit
) : FrameLayout(context), SidebarPageControllable {

    private val mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private var activeController: MediaController? = null
    private var playbackState: PlaybackState? = null
    private var metadata: MediaMetadata? = null

    private val llBlank: View
    private val llPlayer: View
    private val ivArtwork: ImageView
    private val tvTitle: TextView
    private val tvArtist: TextView
    private val btnPrev: ImageView
    private val btnPlayPause: ImageView
    private val btnNext: ImageView

    private var currentHeightPx = 0
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var updateJob: Job? = null

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
        LayoutInflater.from(context).inflate(R.layout.page_media_player, this, true)

        llBlank = findViewById(R.id.ll_blank)
        llPlayer = findViewById(R.id.ll_player)
        ivArtwork = findViewById(R.id.iv_artwork)
        tvTitle = findViewById(R.id.tv_title)
        tvArtist = findViewById(R.id.tv_artist)
        btnPrev = findViewById(R.id.btn_prev)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        btnNext = findViewById(R.id.btn_next)

        btnPrev.setOnClickListener { activeController?.transportControls?.skipToPrevious() }
        btnNext.setOnClickListener { activeController?.transportControls?.skipToNext() }
        btnPlayPause.setOnClickListener {
            if (playbackState?.state == PlaybackState.STATE_PLAYING) {
                activeController?.transportControls?.pause()
            } else {
                activeController?.transportControls?.play()
            }
        }

        addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val height = bottom - top
            if (currentHeightPx != height && height > 0) {
                currentHeightPx = height
                onHeightChanged(height)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopUpdates()
    }

    override fun onPageSelected() {
        startUpdates()
    }

    override fun onPageUnselected() {
        stopUpdates()
        ivArtwork.setImageDrawable(null)
    }

    private fun startUpdates() {
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
        activeController?.unregisterCallback(callback)
    }

    private fun updateState() {
        try {
            val componentName = ComponentName(context, AppNotificationListener::class.java)
            val controllers = mediaSessionManager.getActiveSessions(componentName)
            val controller = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: controllers.firstOrNull()

            if (controller != activeController) {
                activeController?.unregisterCallback(callback)
                activeController = controller
                activeController?.registerCallback(callback)
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
            llBlank.visibility = View.VISIBLE
            llPlayer.visibility = View.GONE
        } else {
            llBlank.visibility = View.GONE
            llPlayer.visibility = View.VISIBLE
            val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown Title"
            val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown Artist"
            val artwork = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING

            tvTitle.text = title
            tvArtist.text = artist

            if (artwork != null) {
                ivArtwork.setImageBitmap(artwork)
            } else {
                ivArtwork.setImageResource(android.R.drawable.ic_media_play)
            }

            btnPlayPause.setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        }
    }
}
