package com.example.feature.sidebar

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.viewpager2.widget.ViewPager2
import com.example.core.LogKeeper
import com.example.core.SidebarPage
import com.example.core.ipc.HeavyCommand
import com.example.core.ipc.HeavyCommandType
import com.example.core.ipc.HeavyConnectionListener
import com.example.core.ipc.HeavyEvent
import com.example.core.ipc.HeavyProcessConnectionManager
import java.util.UUID

/**
 * HeavyPagePlaceholderView: Lightweight Main-process placeholder for pages hosted in :heavy (e.g. Calculator).
 *
 * Runs exclusively in the Main process (com.example).
 * Responsibilities:
 * 1. On API 30+, embeds Calculator directly via SurfaceView and SurfaceControlViewHost,
 *    eliminating the need for a secondary TYPE_APPLICATION_OVERLAY window.
 * 2. On API 26-29, falls back to the legacy overlay placeholder window.
 * 3. Occupies the ViewPager2 page slot with generic bootstrap height derived from host geometry.
 * 4. Listens for Heavy page-height events across Binder (HeavyProcessConnectionManager), applying the
 *    real desired height to Main's Sidebar layout and relayouting the embedded surface.
 * 5. On unselection or detachment, cleanly detaches the SurfacePackage and releases its Heavy host.
 */
class HeavyPagePlaceholderView(
    context: Context,
    private val config: SidebarPage,
    private val containerId: String,
    private val onHeightChanged: ((Int) -> Unit)? = null
) : FrameLayout(context), SidebarPageControllable, HeavyConnectionListener {

    private val connectionManager = HeavyProcessConnectionManager.getInstance(context)
    private val consumerTag = "HeavyPage_${config.id}_${UUID.randomUUID().toString().take(6)}"

    private var isSelected = false
    private var isMountRequested = false
    private var isMounted = false
    private var isRevealed = false
    private var hasReceivedDesiredHeight = false
    private var isScrollIdle = true

    // Embedded surface state for API 30+
    private var embeddedSurfaceView: SurfaceView? = null
    private var isSurfaceReady = false
    private var isEmbeddedAttached = false
    private var isSurfaceCreationPending = false
    private var currentSurfacePackage: Any? = null
    private var currentHostWidth = 0
    private var currentHostHeight = 0
    private var pendingDesiredHeightPx = -1

    private var desiredHeightPx = -1
    private val lastDispatchedRect = Rect()

    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var pendingStableUpdateRunnable: Runnable? = null
    private var isFallbackRevealTriggered = false
    private var fallbackRevealRunnable: Runnable? = null

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        connectionManager.addListener(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            initEmbeddedSurfaceApi30()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun initEmbeddedSurfaceApi30() {
        val sv = SurfaceView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setZOrderMediaOverlay(true)
            holder.setFormat(PixelFormat.TRANSLUCENT)
            alpha = 0f
        }
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                LogKeeper.log(context, TAG, "Embedded SurfaceView created for '${config.id}'")
                isSurfaceReady = true
                if (isSelected && connectionManager.isConnected) {
                    requestEmbeddedSurfaceCreation()
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                LogKeeper.log(context, TAG, "Embedded SurfaceView changed for '${config.id}' size ${width}x$height")
                currentHostWidth = width
                currentHostHeight = height
                if (isSelected) {
                    if (isEmbeddedAttached) {
                        connectionManager.relayoutSurfaceHost(config.id, width, height)
                    } else if (isSurfaceReady && connectionManager.isConnected) {
                        requestEmbeddedSurfaceCreation()
                    }
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                LogKeeper.log(context, TAG, "Embedded SurfaceView destroyed for '${config.id}'")
                isSurfaceReady = false
                isSurfaceCreationPending = false
                detachEmbeddedSurface()
            }
        })
        embeddedSurfaceView = sv
        addView(sv)
    }

    private fun requestEmbeddedSurfaceCreation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (!isSelected || !isMountRequested || !isSurfaceReady || !connectionManager.isConnected) return
        if (isEmbeddedAttached || isSurfaceCreationPending) return

        val sv = embeddedSurfaceView ?: return
        val hostToken = sv.hostToken
        if (hostToken == null) {
            LogKeeper.log(context, TAG, "SurfaceView hostToken not ready yet for '${config.id}'")
            return
        }
        val width = sv.width.takeIf { it > 0 } ?: measuredWidth.takeIf { it > 0 } ?: return
        val height = sv.height.takeIf { it > 0 } ?: measuredHeight.takeIf { it > 0 } ?: return
        val displayId = sv.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY

        if (isEmbeddedAttached) {
            connectionManager.relayoutSurfaceHost(config.id, width, height)
            return
        }

        isSurfaceCreationPending = true
        post {
            try {
                if (!isSelected || !isMountRequested || !isSurfaceReady || !connectionManager.isConnected || isEmbeddedAttached) {
                    LogKeeper.log(context, TAG, "Aborting createSurfacePackage: state changed before execution (isSelected=$isSelected, isSurfaceReady=$isSurfaceReady, isConnected=${connectionManager.isConnected}, isEmbeddedAttached=$isEmbeddedAttached)")
                    isSurfaceCreationPending = false
                    return@post
                }
                val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
                val opacity = if (config.useCustomSettings) config.transparency else prefs.getFloat("handle_${containerId}_sidebar_transparency", prefs.getFloat("sidebar_transparency", 0.9f))
                val alphaInt = (opacity * 255).toInt().coerceIn(0, 255)
                val colorHex = prefs.getString("handle_${containerId}_sidebar_color", prefs.getString("sidebar_color", "#1E1E2E")) ?: "#1E1E2E"
                val baseColor = try { Color.parseColor(colorHex) } catch (e: Exception) { Color.parseColor("#1E1E2E") }
                val r = Color.red(baseColor)
                val g = Color.green(baseColor)
                val b = Color.blue(baseColor)
                val backgroundColor = Color.argb(alphaInt, r, g, b)

                val legacyEdge = if (prefs.getBoolean("sidebar_position_left", false)) "left" else "right"
                val isRight = prefs.getString("handle_${containerId}_edge", if (containerId == "sidebar") legacyEdge else "right") == "right"

                val params = Bundle().apply {
                    putString("pageId", config.id)
                    putString("pageType", config.type)
                    putBinder("hostToken", hostToken)
                    putInt("displayId", displayId)
                    putInt("width", width)
                    putInt("height", height)
                    putInt("backgroundColor", backgroundColor)
                    putBoolean("isRight", isRight)
                }
                LogKeeper.log(context, TAG, "Requesting createSurfacePackage for '${config.id}' size ${width}x$height with theme color #$colorHex (alpha=$alphaInt, isRight=$isRight)")
                val reply = connectionManager.createSurfacePackage(params)
                if (reply != null && reply.getBoolean("success")) {
                    @Suppress("DEPRECATION")
                    val surfacePackage = reply.getParcelable<android.view.SurfaceControlViewHost.SurfacePackage>("surfacePackage")
                    if (surfacePackage != null && isSelected && isMountRequested) {
                        try {
                            (currentSurfacePackage as? android.view.SurfaceControlViewHost.SurfacePackage)?.release()
                        } catch (_: Throwable) {}
                        currentSurfacePackage = surfacePackage
                        sv.setChildSurfacePackage(surfacePackage)
                        isEmbeddedAttached = true
                        sv.alpha = 1f
                        LogKeeper.log(context, TAG, "Successfully embedded SurfacePackage for '${config.id}' in Main SurfaceView")
                        if (pendingDesiredHeightPx > 0) {
                            applyDesiredHeight(pendingDesiredHeightPx)
                        }
                    } else {
                        LogKeeper.log(context, TAG, "SurfacePackage received but null or unselected (isSelected=$isSelected)")
                        try {
                            surfacePackage?.release()
                        } catch (_: Throwable) {}
                    }
                } else {
                    LogKeeper.log(context, TAG, "createSurfacePackage reply unsuccessful for '${config.id}'")
                }
            } catch (e: Throwable) {
                LogKeeper.logCrash(context, TAG, e)
            } finally {
                isSurfaceCreationPending = false
            }
        }
    }

    private fun detachEmbeddedSurface() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            isSurfaceCreationPending = false
            embeddedSurfaceView?.alpha = 0f
            try {
                (currentSurfacePackage as? android.view.SurfaceControlViewHost.SurfacePackage)?.release()
            } catch (_: Throwable) {}
            currentSurfacePackage = null
            isEmbeddedAttached = false
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        connectionManager.addListener(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        connectionManager.removeListener(this)
        if (isSelected || isMountRequested) {
            onPageUnselected()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = if (desiredHeightPx > 0) {
            desiredHeightPx
        } else if (layoutParams.height > 0) {
            layoutParams.height
        } else {
            // Keep placeholder at existing container/parent height until real desired height is applied from Heavy
            val parentH = (parent as? View)?.height?.takeIf { it > 0 } ?: (parent as? View)?.measuredHeight?.takeIf { it > 0 }
            parentH ?: ((parent?.parent as? View)?.height?.takeIf { it > 0 }) ?: 0
        }
        setMeasuredDimension(width, height)
        // Measure children (including embedded SurfaceView if present)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onPageSelected() {
        if (isSelected && isMountRequested) {
            LogKeeper.log(context, TAG, "Suppressed duplicate onPageSelected for '${config.id}' (already active/mounting)")
            return
        }
        isSelected = true
        isMountRequested = true
        isRevealed = false
        hasReceivedDesiredHeight = false
        isFallbackRevealTriggered = false
        LogKeeper.log(context, TAG, "Placeholder onPageSelected for '${config.id}' in Main (mount requested)")

        // Acquire connection to Heavy on demand
        connectionManager.acquireConsumer(consumerTag)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (isSurfaceReady && connectionManager.isConnected) {
                requestEmbeddedSurfaceCreation()
            }
        } else {
            if (isScrollIdle) {
                scheduleStableMountOrUpdate(debounceMs = 0L)
            }
        }
    }

    override fun onPageUnselected() {
        if (!isSelected && !isMountRequested) return
        isSelected = false
        isMountRequested = false
        val wasMounted = isMounted
        isMounted = false
        isRevealed = false
        hasReceivedDesiredHeight = false
        isFallbackRevealTriggered = false
        desiredHeightPx = -1
        pendingDesiredHeightPx = -1
        LogKeeper.log(context, TAG, "Placeholder onPageUnselected for '${config.id}' in Main (wasMounted=$wasMounted)")

        cancelPendingStableUpdate()
        cancelFallbackReveal()
        removeLayoutMonitoring()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            isSurfaceCreationPending = false
            detachEmbeddedSurface()
            connectionManager.releaseSurfaceHost(config.id)
        } else {
            if (wasMounted) {
                dispatchUnmountCommand()
            }
        }
        connectionManager.releaseConsumer(consumerTag)
    }

    override fun onPageScrollStateChanged(state: Int) {
        val idle = (state == ViewPager2.SCROLL_STATE_IDLE)
        isScrollIdle = idle
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Embedded surface moves with ViewPager2 natively via SurfaceControl
        } else {
            if (idle && isSelected) {
                scheduleStableMountOrUpdate(debounceMs = 40L)
            }
        }
    }

    // --- IpcConnectionListener implementation ---

    override fun onConnected() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            post {
                if (isSelected && isMountRequested && isSurfaceReady && connectionManager.isConnected && !isEmbeddedAttached && !isSurfaceCreationPending) {
                    requestEmbeddedSurfaceCreation()
                }
            }
        }
    }

    override fun onHeavyProcessDied() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            isSurfaceCreationPending = false
            detachEmbeddedSurface()
        }
    }

    override fun onHeavyEvent(event: HeavyEvent) {
        val action = event.payload["action"]
        val targetPageId = event.payload["pageId"] ?: event.sourceId
        if (action == "page_height_changed" && targetPageId == config.id) {
            val reportedH = event.payload["height"]?.toIntOrNull() ?: return
            if (reportedH > 0) {
                post {
                    applyDesiredHeight(reportedH)
                }
            }
        }
    }

    private fun applyDesiredHeight(newHeight: Int) {
        if (newHeight <= 0) return
        pendingDesiredHeightPx = newHeight
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !isEmbeddedAttached) {
            LogKeeper.log(context, TAG, "Deferring desired height $newHeight for '${config.id}' until SurfacePackage is attached")
            return
        }
        hasReceivedDesiredHeight = true
        val changed = (newHeight != desiredHeightPx)
        if (changed) {
            LogKeeper.log(context, TAG, "Applying desired height $newHeight for '${config.id}' in Main")
            desiredHeightPx = newHeight
            requestLayout()
            onHeightChanged?.invoke(newHeight)
        } else {
            LogKeeper.log(context, TAG, "Desired height $newHeight confirmed unchanged for '${config.id}' in Main")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (isEmbeddedAttached && currentHostWidth > 0) {
                connectionManager.relayoutSurfaceHost(config.id, currentHostWidth, newHeight)
            }
        } else {
            // Layout shift triggered or confirmed; schedule stable bounds update and reveal
            scheduleStableMountOrUpdate(debounceMs = if (changed) 50L else 0L)
        }
    }

    // --- Geometry Synchronization ---

    private fun cancelPendingStableUpdate() {
        pendingStableUpdateRunnable?.let { removeCallbacks(it) }
        pendingStableUpdateRunnable = null
    }

    private fun scheduleFallbackReveal() {
        cancelFallbackReveal()
        val r = object : Runnable {
            override fun run() {
                if (isSelected && isMounted && !isRevealed) {
                    if (hasReceivedDesiredHeight || desiredHeightPx > 0) {
                        LogKeeper.log(context, TAG, "Fallback reveal triggered for '${config.id}' in Main (desired height available: $desiredHeightPx)")
                        isFallbackRevealTriggered = true
                        scheduleStableMountOrUpdate(debounceMs = 0L)
                    } else {
                        LogKeeper.log(context, TAG, "Fallback timeout elapsed for '${config.id}': desired height not yet reported by Heavy; remaining hidden to prevent pre-height bootstrap flash")
                        // Reschedule fallback check so we don't prematurely reveal before desired height geometry is established
                        postDelayed(this, 350L)
                    }
                }
            }
        }
        fallbackRevealRunnable = r
        postDelayed(r, 350L)
    }

    private fun cancelFallbackReveal() {
        fallbackRevealRunnable?.let { removeCallbacks(it) }
        fallbackRevealRunnable = null
        isFallbackRevealTriggered = false
    }

    private fun logGeometryDiagnostics(finalRect: Rect) {
        try {
            val dm = context.resources.displayMetrics
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels

            val insetsStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                rootWindowInsets?.getInsets(WindowInsets.Type.systemBars())?.let {
                    "systemBars[L=${it.left}, T=${it.top}, R=${it.right}, B=${it.bottom}]"
                } ?: "insets=null"
            } else {
                @Suppress("DEPRECATION")
                rootWindowInsets?.let {
                    "stableInsets[L=${it.stableInsetLeft}, T=${it.stableInsetTop}, R=${it.stableInsetRight}, B=${it.stableInsetBottom}]"
                } ?: "insets=null"
            }

            fun getGlobalRect(v: View?): Rect? {
                if (v == null || !v.isAttachedToWindow || v.width <= 0 || v.height <= 0) return null
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                return Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
            }

            val placeholderRect = getGlobalRect(this)
            val hostFrame = parent as? View
            val hostFrameRect = getGlobalRect(hostFrame)
            val rcv = hostFrame?.parent as? View
            val vp = (rcv?.parent as? ViewPager2) ?: (hostFrame?.parent?.parent as? ViewPager2)
            val vpRect = getGlobalRect(vp)
            val containerView = vp?.parent as? View
            val sidebarRoot = rootView
            val sidebarRootRect = getGlobalRect(sidebarRoot)
            val headerView = (sidebarRoot as? ViewGroup)?.let { vg ->
                var found: View? = null
                for (i in 0 until vg.childCount) {
                    val child = vg.getChildAt(i)
                    if (child !is ViewPager2 && child != containerView && child.height > 0 && child.height < (60 * dm.density)) {
                        found = child
                        break
                    }
                }
                found
            }
            val headerRect = getGlobalRect(headerView)

            val svLp = sidebarRoot?.layoutParams as? WindowManager.LayoutParams

            LogKeeper.log(context, TAG, """
                [GEOMETRY_DIAGNOSTICS for '${config.id}']
                * Screen dimensions: ${screenW}x$screenH (density=${dm.density})
                * System bar/inset values: $insetsStr
                * Main Sidebar WindowManager LP: x=${svLp?.x}, y=${svLp?.y}, w=${svLp?.width}, h=${svLp?.height}, gravity=0x${Integer.toHexString(svLp?.gravity ?: 0)}
                * Sidebar root global rect: $sidebarRootRect
                * Header global rect: $headerRect
                * ViewPager global rect: $vpRect
                * Current page-content host global rect: $hostFrameRect
                * HeavyPagePlaceholder global rect: $placeholderRect
                * Reported Calculator desired height: $desiredHeightPx
                * Final rectangle sent to Heavy: $finalRect
            """.trimIndent())
        } catch (e: Throwable) {
            LogKeeper.log(context, TAG, "Failed to capture geometry diagnostics: ${e.message}")
        }
    }

    private fun scheduleStableMountOrUpdate(debounceMs: Long = 40L) {
        cancelPendingStableUpdate()
        val r = Runnable {
            if (!isSelected || !isScrollIdle) return@Runnable
            val rect = getCurrentScreenRect() ?: run {
                // If not yet measured, retry shortly once
                if (isSelected && isScrollIdle) {
                    scheduleStableMountOrUpdate(debounceMs = 50L)
                }
                return@Runnable
            }
            if (rect.width() <= 0 || rect.height() <= 0) return@Runnable

            if (!isMounted) {
                // Initial bootstrap mount: always hidden (readyToShow=false)
                dispatchMountCommand(rect)
                setupLayoutMonitoring()
                scheduleFallbackReveal()
            } else {
                // Stable bounds update: reveal ONLY when desired height is received or already known
                val readyToReveal = !isRevealed && (hasReceivedDesiredHeight || desiredHeightPx > 0)
                val boundsChanged = (rect.left != lastDispatchedRect.left ||
                        rect.top != lastDispatchedRect.top ||
                        rect.width() != lastDispatchedRect.width() ||
                        rect.height() != lastDispatchedRect.height())

                if (!boundsChanged && isRevealed) {
                    LogKeeper.log(context, TAG, "Bounds unchanged for '${config.id}' $rect and already revealed; update suppressed")
                    return@Runnable
                }

                val willBeRevealed = readyToReveal || isRevealed
                if (readyToReveal) {
                    logGeometryDiagnostics(rect)
                }
                dispatchBoundsUpdate(rect, readyToShow = willBeRevealed)
                if (readyToReveal) {
                    isRevealed = true
                    cancelFallbackReveal()
                }
            }
        }
        pendingStableUpdateRunnable = r
        if (debounceMs <= 0L) {
            post(r)
        } else {
            postDelayed(r, debounceMs)
        }
    }

    private fun setupLayoutMonitoring() {
        removeLayoutMonitoring()
        val observer = viewTreeObserver
        if (observer.isAlive) {
            val listener = ViewTreeObserver.OnGlobalLayoutListener {
                if (isSelected && isScrollIdle) {
                    // Debounce layout shifts (orientation, keyboard, sidebar height adjustments)
                    scheduleStableMountOrUpdate(debounceMs = 60L)
                }
            }
            layoutListener = listener
            observer.addOnGlobalLayoutListener(listener)
        }
    }

    private fun removeLayoutMonitoring() {
        layoutListener?.let { listener ->
            try {
                if (viewTreeObserver.isAlive) {
                    viewTreeObserver.removeOnGlobalLayoutListener(listener)
                }
            } catch (_: Throwable) {}
        }
        layoutListener = null
    }

    private fun getCurrentScreenRect(): Rect? {
        if (!isAttachedToWindow || width <= 0 || height <= 0) return null
        val location = IntArray(2)
        getLocationOnScreen(location)
        val x = location[0]
        val y = location[1]
        return Rect(x, y, x + width, y + height)
    }

    private fun dispatchMountCommand(rect: Rect) {
        lastDispatchedRect.set(rect)
        isMounted = true
        isRevealed = false

        val command = HeavyCommand(
            commandId = "mount_${config.id}_${System.currentTimeMillis()}",
            type = HeavyCommandType.MOUNT_PAGE,
            targetId = config.id,
            payload = mapOf(
                "pageId" to config.id,
                "pageType" to config.type,
                "containerId" to containerId,
                "x" to rect.left.toString(),
                "y" to rect.top.toString(),
                "width" to rect.width().toString(),
                "height" to rect.height().toString(),
                "readyToShow" to "false"
            )
        )

        val result = connectionManager.sendCommand(command, autoConnect = true)
        LogKeeper.log(context, TAG, "Dispatched bootstrap MOUNT_PAGE for '${config.id}' at $rect (readyToShow=false) to :heavy. Result: ${result.success}")
    }

    private fun dispatchBoundsUpdate(rect: Rect, readyToShow: Boolean) {
        lastDispatchedRect.set(rect)

        val command = HeavyCommand(
            commandId = "bounds_${config.id}_${System.currentTimeMillis()}",
            type = HeavyCommandType.UPDATE_PAGE_BOUNDS,
            targetId = config.id,
            payload = mapOf(
                "pageId" to config.id,
                "x" to rect.left.toString(),
                "y" to rect.top.toString(),
                "width" to rect.width().toString(),
                "height" to rect.height().toString(),
                "readyToShow" to if (readyToShow) "true" else "false"
            )
        )

        connectionManager.sendCommand(command, autoConnect = false)
        LogKeeper.log(context, TAG, "Dispatched stable UPDATE_PAGE_BOUNDS for '${config.id}' to $rect (readyToShow=$readyToShow) in :heavy")
    }

    private fun dispatchUnmountCommand() {
        val command = HeavyCommand(
            commandId = "unmount_${config.id}_${System.currentTimeMillis()}",
            type = HeavyCommandType.UNMOUNT_PAGE,
            targetId = config.id,
            payload = mapOf(
                "pageId" to config.id
            )
        )
        connectionManager.sendCommand(command, autoConnect = false)
        LogKeeper.log(context, TAG, "Dispatched UNMOUNT_PAGE for '${config.id}' to :heavy")
    }

    // Testing hooks
    internal fun getDesiredHeightPx(): Int = desiredHeightPx
    internal fun isMountedState(): Boolean = isMounted
    internal fun isMountRequestedState(): Boolean = isMountRequested
    internal fun isRevealedState(): Boolean = isRevealed
    internal fun hasReceivedDesiredHeightState(): Boolean = hasReceivedDesiredHeight
    internal fun getLastDispatchedRect(): Rect = Rect(lastDispatchedRect)
    internal fun isEmbeddedAttachedState(): Boolean = isEmbeddedAttached
    internal fun isSurfaceReadyState(): Boolean = isSurfaceReady

    companion object {
        private const val TAG = "HeavyPagePlaceholder"
    }
}
