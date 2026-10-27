package com.example.feature.sidebar

import android.annotation.SuppressLint
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.R
import com.example.core.ipc.HeavyProcessHost
import com.example.core.ipc.MainActionType
import com.example.core.ipc.MainCommandRequest
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

    // UI View References
    private val llBlank: View?
    private val tvBlankStatus: TextView?
    private val ivBlankIcon: ImageView?

    private val llPlayer: View?
    private val llAppHeader: View?
    private val ivAppIcon: ImageView?
    private val tvAppName: TextView?
    private val tvSessionStatus: TextView?
    private val flArtworkContainer: FrameLayout?
    private val ivArtwork: ImageView?
    private val tvTitle: TextView?
    private val tvArtist: TextView?
    private val tvAlbum: TextView?

    private val llProgressContainer: View?
    private val pbProgress: ProgressBar?
    private val tvTimeCurrent: TextView?
    private val tvTimeTotal: TextView?

    private val llControls: LinearLayout?

    private var currentHeightPx = 0
    private var isListening = false

    var themeBackgroundDrawable: Drawable? = null
        private set

    // Horizontal Swipe Detection
    private var downX = 0f
    private var downY = 0f
    private var isHorizontalSwipeConsumed = false
    private val touchSlop = try { ViewConfiguration.get(context).scaledTouchSlop } catch (_: Throwable) { 16 }

    // Media Session Callback
    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            playbackState = state
            updateUI()
        }

        override fun onMetadataChanged(metadataUpdate: MediaMetadata?) {
            metadata = metadataUpdate
            updateUI()
        }

        override fun onSessionDestroyed() {
            activeController = null
            updateState()
        }
    }

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        post { updateStateWithControllers(controllers) }
    }

    // Notification broadcast receiver to detect notification action or artwork updates
    private val notificationUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            post {
                if (activeController != null) {
                    updateUI()
                } else {
                    updateState()
                }
            }
        }
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
        tvBlankStatus = findViewById(R.id.tv_blank_status)
        ivBlankIcon = findViewById(R.id.iv_blank_icon)

        llPlayer = findViewById(R.id.ll_player)
        llAppHeader = findViewById(R.id.ll_app_header)
        ivAppIcon = findViewById(R.id.iv_app_icon)
        tvAppName = findViewById(R.id.tv_app_name)
        tvSessionStatus = findViewById(R.id.tv_session_status)
        flArtworkContainer = findViewById(R.id.fl_artwork_container)
        ivArtwork = findViewById(R.id.iv_artwork)
        tvTitle = findViewById(R.id.tv_title)
        tvArtist = findViewById(R.id.tv_artist)
        tvAlbum = findViewById(R.id.tv_album)

        llProgressContainer = findViewById(R.id.ll_progress_container)
        pbProgress = findViewById(R.id.pb_progress)
        tvTimeCurrent = findViewById(R.id.tv_time_current)
        tvTimeTotal = findViewById(R.id.tv_time_total)

        llControls = findViewById(R.id.ll_controls)

        // Clip artwork container outline
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            flArtworkContainer?.clipToOutline = true
        }

        llAppHeader?.setOnClickListener {
            openActiveMediaApp()
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
                    HeavyProcessHost.getInstance(context).requestMainAction(
                        MainCommandRequest(
                            action = MainActionType.CUSTOM,
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
        val targetH = if (activeController != null) (180 * density).toInt() else (100 * density).toInt()
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
                floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius)
            } else {
                floatArrayOf(0f, 0f, radius, radius, radius, radius, 0f, 0f)
            }
        }
        themeBackgroundDrawable = bgDrawable
        background = bgDrawable
    }

    fun cleanup() {
        stopListening()
        ivArtwork?.setImageDrawable(null)
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

        try {
            val filter = IntentFilter().apply {
                addAction(AppNotificationListener.ACTION_NOTIFICATION_POSTED)
                addAction(AppNotificationListener.ACTION_NOTIFICATION_REMOVED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(notificationUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(notificationUpdateReceiver, filter)
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
            context.unregisterReceiver(notificationUpdateReceiver)
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
        val targetH = if (activeController != null) (180 * density).toInt() else (100 * density).toInt()
        if (targetH != currentHeightPx) {
            currentHeightPx = targetH
            onHeightChanged?.invoke(targetH)
        }
    }

    private fun updateUI() {
        val controller = activeController
        if (controller == null) {
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
                tvBlankStatus?.text = "No media playing"
                llBlank?.setOnClickListener(null)
            }
        } else {
            llBlank?.visibility = View.GONE
            llPlayer?.visibility = View.VISIBLE

            // 1. Source App Information
            val pkg = controller.packageName ?: ""
            val pm = context.packageManager
            val appName = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Throwable) {
                pkg.substringAfterLast('.').replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
            tvAppName?.text = appName

            val appIconDrawable = try {
                pm.getApplicationIcon(pkg)
            } catch (_: Throwable) {
                null
            }
            if (appIconDrawable != null) {
                ivAppIcon?.setImageDrawable(appIconDrawable)
                ivAppIcon?.visibility = View.VISIBLE
            } else {
                ivAppIcon?.visibility = View.GONE
            }

            val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING
            tvSessionStatus?.text = if (isPlaying) "Playing" else "Paused"

            // 2. Track Title & Artist Metadata
            val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
                ?: "Unknown Title"
            val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.takeIf { it.isNotBlank() }
                ?: appName
            val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }

            tvTitle?.text = title
            tvArtist?.text = artist

            if (album != null && album != title) {
                tvAlbum?.text = album
                tvAlbum?.visibility = View.VISIBLE
            } else {
                tvAlbum?.visibility = View.GONE
            }

            // 3. Artwork Extraction (High-res / Album Art / Notification fallback)
            updateArtwork(controller)

            // 4. Progress Bar (Secondary, only when duration > 0 without polling)
            updateProgress()

            // 5. Dynamic Controls Row
            populateDynamicControls(controller)
        }
    }

    private fun updateArtwork(controller: MediaController) {
        var bitmap: Bitmap? = null
        try {
            bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        } catch (_: Throwable) {}

        // Fallback: Check matching active notification in AppNotificationListener
        if (bitmap == null) {
            try {
                val matchingSbn = AppNotificationListener.instance?.activeNotifications?.firstOrNull {
                    it.packageName == controller.packageName
                }
                val extras = matchingSbn?.notification?.extras
                if (extras != null) {
                    val notifBitmap = extras.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON)
                        ?: extras.getParcelable(Notification.EXTRA_LARGE_ICON_BIG)
                    if (notifBitmap != null) {
                        bitmap = notifBitmap
                    }
                }
            } catch (_: Throwable) {}
        }

        if (bitmap != null) {
            ivArtwork?.setImageBitmap(bitmap)
            ivArtwork?.scaleType = ImageView.ScaleType.CENTER_CROP
        } else {
            // Clean fallback: music note with themed background
            ivArtwork?.setImageResource(R.drawable.ic_music_note)
            ivArtwork?.scaleType = ImageView.ScaleType.CENTER_INSIDE
            ivArtwork?.setColorFilter(Color.parseColor("#A0A0C0"))
        }
    }

    private fun updateProgress() {
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val position = playbackState?.position ?: 0L

        if (duration > 0 && position >= 0) {
            llProgressContainer?.visibility = View.VISIBLE
            val progressFraction = ((position.toFloat() / duration.toFloat()) * 1000).toInt().coerceIn(0, 1000)
            pbProgress?.progress = progressFraction
            tvTimeCurrent?.text = formatDuration(position)
            tvTimeTotal?.text = formatDuration(duration)
        } else {
            llProgressContainer?.visibility = View.GONE
        }
    }

    private fun formatDuration(millis: Long): String {
        val totalSecs = (millis / 1000).coerceAtLeast(0)
        val minutes = totalSecs / 60
        val seconds = totalSecs % 60
        return "%d:%02d".format(minutes, seconds)
    }

    /**
     * Builds and populates the dynamic media controls row based on the active session's supported actions.
     * Adapts to the source application rather than assuming fixed buttons.
     */
    private fun populateDynamicControls(controller: MediaController) {
        val container = llControls ?: return
        container.removeAllViews()

        val actions = mutableListOf<DynamicActionItem>()
        val actionsMask = playbackState?.actions ?: 0L
        val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING

        // 1. Previous Track (if supported)
        if ((actionsMask and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L) {
            actions.add(
                DynamicActionItem(
                    id = "previous",
                    title = "Previous",
                    iconResId = android.R.drawable.ic_media_previous,
                    tintColor = Color.WHITE,
                    onClick = {
                        try {
                            controller.transportControls.skipToPrevious()
                        } catch (_: Throwable) {}
                    }
                )
            )
        }

        // 2. Rewind (if supported and space permits)
        if ((actionsMask and PlaybackState.ACTION_REWIND) != 0L) {
            actions.add(
                DynamicActionItem(
                    id = "rewind",
                    title = "Rewind",
                    iconResId = android.R.drawable.ic_media_rew,
                    tintColor = Color.parseColor("#C8C8DC"),
                    onClick = {
                        try {
                            controller.transportControls.rewind()
                        } catch (_: Throwable) {}
                    }
                )
            )
        }

        // 3. Play / Pause (Primary Action - Centered & Elevated)
        val playPauseSupported = (actionsMask and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)) != 0L || playbackState != null
        if (playPauseSupported) {
            actions.add(
                DynamicActionItem(
                    id = "play_pause",
                    title = if (isPlaying) "Pause" else "Play",
                    iconResId = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    isPrimary = true,
                    tintColor = Color.parseColor("#181824"),
                    onClick = {
                        try {
                            if (playbackState?.state == PlaybackState.STATE_PLAYING) {
                                controller.transportControls.pause()
                            } else {
                                controller.transportControls.play()
                            }
                        } catch (_: Throwable) {}
                    }
                )
            )
        }

        // 4. Fast Forward (if supported)
        if ((actionsMask and PlaybackState.ACTION_FAST_FORWARD) != 0L) {
            actions.add(
                DynamicActionItem(
                    id = "fast_forward",
                    title = "Fast Forward",
                    iconResId = android.R.drawable.ic_media_ff,
                    tintColor = Color.parseColor("#C8C8DC"),
                    onClick = {
                        try {
                            controller.transportControls.fastForward()
                        } catch (_: Throwable) {}
                    }
                )
            )
        }

        // 5. Next Track (if supported)
        if ((actionsMask and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L) {
            actions.add(
                DynamicActionItem(
                    id = "next",
                    title = "Next",
                    iconResId = android.R.drawable.ic_media_next,
                    tintColor = Color.WHITE,
                    onClick = {
                        try {
                            controller.transportControls.skipToNext()
                        } catch (_: Throwable) {}
                    }
                )
            )
        }

        // 6. Custom Actions from PlaybackState (e.g. Favorite, Like, Shuffle, Repeat, Bookmark)
        playbackState?.customActions?.forEach { customAction ->
            val name = customAction.name?.toString() ?: customAction.action
            val lower = name.lowercase()
            val isFavorite = lower.contains("like") || lower.contains("fav")
            val isShuffle = lower.contains("shuffle")
            val isRepeat = lower.contains("repeat")
            val iconRes = when {
                isFavorite -> R.drawable.ic_media_favorite
                isShuffle -> R.drawable.ic_media_shuffle
                isRepeat -> R.drawable.ic_media_repeat
                else -> R.drawable.ic_music_note
            }
            actions.add(
                DynamicActionItem(
                    id = "custom_${customAction.action}",
                    title = name,
                    iconResId = iconRes,
                    tintColor = when {
                        isFavorite -> Color.parseColor("#F43F5E")
                        isShuffle || isRepeat -> Color.parseColor("#818CF8")
                        else -> Color.parseColor("#C8C8DC")
                    },
                    onClick = {
                        try {
                            controller.transportControls.sendCustomAction(customAction.action, customAction.extras)
                        } catch (_: Throwable) {}
                    }
                )
            )
        }

        // 7. Extra actions from matching active notification (e.g. thumbs up/down, like, shuffle) if not redundant
        try {
            val matchingSbn = AppNotificationListener.instance?.activeNotifications?.firstOrNull {
                it.packageName == controller.packageName
            }
            matchingSbn?.notification?.actions?.forEach { notifAction ->
                val titleStr = notifAction.title?.toString()?.trim() ?: ""
                val lowerTitle = titleStr.lowercase()
                val isStandard = lowerTitle.contains("play") || lowerTitle.contains("pause") ||
                        lowerTitle.contains("next") || lowerTitle.contains("prev") || lowerTitle.contains("rew")
                if (!isStandard && titleStr.isNotBlank() && actions.none { it.title.equals(titleStr, ignoreCase = true) }) {
                    val isLike = lowerTitle.contains("like") || lowerTitle.contains("thumb") || lowerTitle.contains("fav")
                    val isShuffle = lowerTitle.contains("shuffle")
                    val isRepeat = lowerTitle.contains("repeat")
                    val iconRes = when {
                        isLike -> R.drawable.ic_media_favorite
                        isShuffle -> R.drawable.ic_media_shuffle
                        isRepeat -> R.drawable.ic_media_repeat
                        else -> R.drawable.ic_music_note
                    }
                    actions.add(
                        DynamicActionItem(
                            id = "notif_${titleStr}",
                            title = titleStr,
                            iconResId = iconRes,
                            tintColor = when {
                                isLike -> Color.parseColor("#F43F5E")
                                isShuffle || isRepeat -> Color.parseColor("#818CF8")
                                else -> Color.parseColor("#C8C8DC")
                            },
                            onClick = {
                                try {
                                    notifAction.actionIntent?.send()
                                } catch (_: Throwable) {}
                            }
                        )
                    )
                }
            }
        } catch (_: Throwable) {}

        // Fallback if no actions discovered: at least show Play/Pause
        if (actions.isEmpty()) {
            actions.add(
                DynamicActionItem(
                    id = "play_pause_fallback",
                    title = if (isPlaying) "Pause" else "Play",
                    iconResId = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    isPrimary = true,
                    tintColor = Color.parseColor("#181824"),
                    onClick = {
                        try {
                            if (playbackState?.state == PlaybackState.STATE_PLAYING) {
                                controller.transportControls.pause()
                            } else {
                                controller.transportControls.play()
                            }
                        } catch (_: Throwable) {}
                    }
                )
            )
        }

        // Render actions as interactive views
        val density = try { resources?.displayMetrics?.density ?: 1f } catch (_: Throwable) { 1f }
        val maxActionsToShow = 5 // Keep comfortable spacing within compact sidebar width
        val finalActions = if (actions.size > maxActionsToShow) {
            // Prioritize primary action and adjacent standard controls
            val primary = actions.firstOrNull { it.isPrimary }
            val others = actions.filterNot { it.isPrimary }
            val subset = others.take(maxActionsToShow - (if (primary != null) 1 else 0)).toMutableList()
            if (primary != null) {
                subset.add(subset.size / 2, primary)
            }
            subset
        } else {
            actions
        }

        for (action in finalActions) {
            val btn = ImageView(context).apply {
                contentDescription = action.title
                if (action.isPrimary) {
                    val sizePx = (38 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                        setMargins((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                    }
                    background = ContextCompat.getDrawable(context, R.drawable.bg_media_play_btn)
                    val padPx = (7 * density).toInt()
                    setPadding(padPx, padPx, padPx, padPx)
                } else {
                    val sizePx = (36 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                        setMargins((4 * density).toInt(), 0, (4 * density).toInt(), 0)
                    }
                    val typedValue = android.util.TypedValue()
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, typedValue, true)
                    setBackgroundResource(typedValue.resourceId)
                    val padPx = (6 * density).toInt()
                    setPadding(padPx, padPx, padPx, padPx)
                }
                setImageResource(action.iconResId)
                setColorFilter(action.tintColor)
                isClickable = true
                isFocusable = true
                setOnClickListener { action.onClick() }
            }
            container.addView(btn)
        }
    }

    private fun openActiveMediaApp() {
        val controller = activeController ?: return
        val pkg = controller.packageName ?: return
        try {
            val sessionPendingIntent = controller.sessionActivity
            if (sessionPendingIntent != null) {
                sessionPendingIntent.send()
                onCloseSidebar()
                return
            }
        } catch (_: Throwable) {}

        try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                onCloseSidebar()
            }
        } catch (_: Throwable) {}
    }

    private data class DynamicActionItem(
        val id: String,
        val title: String,
        val iconResId: Int,
        val tintColor: Int,
        val isPrimary: Boolean = false,
        val onClick: () -> Unit
    )
}
