package com.example.feature.floating

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.core.LogKeeper
import com.example.core.WindowBounds
import org.json.JSONArray
import java.util.ArrayDeque
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * NotePersistenceCoordinator: Authoritative, debounced, Main-owned persistence
 * and undo/redo history manager for an individual Note mini-app instance.
 *
 * Responsibilities:
 * 1. Continuous debounced persistence for typing edits to prevent disk I/O thrashing.
 * 2. Background thread execution for disk writes to keep the UI thread responsive.
 * 3. Safe lifecycle flushing with bounded background completion (no main-thread disk I/O).
 * 4. Serialized per-instance commit transactions with monotonic stale-write protection.
 * 5. Bounded undo/redo history (50 states) serialized cleanly via JSONArray.
 * 6. Full restoration of text, formatting, and history across reconstruction and sleep/wake.
 * 7. Zero Heavy / Binder / IPC dependencies.
 */
class NotePersistenceCoordinator(
    val instanceId: String,
    private val context: Context,
    private val durableStore: DurableMiniAppStore,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val backgroundExecutor: ExecutorService = defaultExecutor
) {
    val undoStack = ArrayDeque<String>()
    val redoStack = ArrayDeque<String>()

    @Volatile
    var isPerformingUndoRedo: Boolean = false

    @Volatile
    var isRestoringState: Boolean = false

    private val saveSequence = AtomicLong(0L)
    private val latestCommittedSequence = AtomicLong(0L)

    private val debounceLock = Any()
    private var pendingSaveRunnable: Runnable? = null

    // Per-instance commit lock: ensures loading, merging, and writing to disk
    // are strictly serialized per note instance, preventing interleaving or data corruption.
    private val instanceCommitLock = getInstanceLock(instanceId)

    // Latest state snapshot cached for immediate flush
    @Volatile
    var latestText: String = ""
        private set

    @Volatile
    var latestFontSize: Float = 15f
        private set

    @Volatile
    var latestFontStyle: String = "regular"
        private set

    @Volatile
    var latestWordWrap: Boolean = true
        private set

    @Volatile
    var latestLineSpacing: Float = 1.2f
        private set

    @Volatile
    var latestBounds: WindowBounds? = null
        private set

    @Volatile
    var latestAccentColor: String = DEFAULT_ACCENT_COLOR
        private set

    fun pushUndoState(text: String) {
        if (isPerformingUndoRedo || isRestoringState) return
        if (undoStack.isEmpty() || undoStack.last() != text) {
            if (undoStack.size >= MAX_HISTORY) {
                undoStack.removeFirst()
            }
            undoStack.addLast(text)
            redoStack.clear()
        }
    }

    fun performUndo(currentText: String): String? {
        if (undoStack.isEmpty()) return null
        val wasPerforming = isPerformingUndoRedo
        isPerformingUndoRedo = true
        try {
            redoStack.addLast(currentText)
            val prev = undoStack.removeLast()
            latestText = prev
            scheduleDebouncedSave()
            return prev
        } finally {
            if (!wasPerforming) {
                isPerformingUndoRedo = false
            }
        }
    }

    fun performRedo(currentText: String): String? {
        if (redoStack.isEmpty()) return null
        val wasPerforming = isPerformingUndoRedo
        isPerformingUndoRedo = true
        try {
            undoStack.addLast(currentText)
            val next = redoStack.removeLast()
            latestText = next
            scheduleDebouncedSave()
            return next
        } finally {
            if (!wasPerforming) {
                isPerformingUndoRedo = false
            }
        }
    }

    fun prepareClear(currentText: String) {
        if (currentText.isEmpty()) return
        pushUndoState(currentText)
        latestText = ""
        // Schedule debounced save rather than blocking the UI thread with a synchronous disk write
        scheduleDebouncedSave()
    }

    fun updateStateSnapshot(
        text: String,
        fontSize: Float,
        fontStyle: String,
        wordWrap: Boolean,
        lineSpacing: Float,
        bounds: WindowBounds? = null,
        accentColor: String? = null
    ) {
        latestText = text
        latestFontSize = fontSize
        latestFontStyle = fontStyle
        latestWordWrap = wordWrap
        latestLineSpacing = lineSpacing
        if (bounds != null) {
            latestBounds = bounds.copyBounds()
        }
        if (!accentColor.isNullOrBlank()) {
            latestAccentColor = accentColor
        }
    }

    /**
     * Schedules a debounced background write for frequent typing edits.
     */
    fun scheduleDebouncedSave(delayMs: Long = DEFAULT_DEBOUNCE_MS) {
        val seq = saveSequence.incrementAndGet()
        val snapshotPayload = createPayloadMap()
        val boundsCopy = latestBounds?.copyBounds()

        if (TEST_DISPATCH_IMMEDIATELY || delayMs <= 0) {
            commitSave(seq, snapshotPayload, boundsCopy)
            return
        }

        synchronized(debounceLock) {
            pendingSaveRunnable?.let { mainHandler.removeCallbacks(it) }
            val runnable = Runnable {
                backgroundExecutor.execute {
                    commitSave(seq, snapshotPayload, boundsCopy)
                }
            }
            pendingSaveRunnable = runnable
            mainHandler.postDelayed(runnable, delayMs)
        }
    }

    private val currentOperationGeneration = AtomicLong(0L)
    private val activeOperationGeneration = AtomicLong(0L)

    fun hasPendingEdits(): Boolean {
        return saveSequence.get() > latestCommittedSequence.get() || pendingSaveRunnable != null
    }

    /**
     * Flushes any pending write safely without performing disk I/O on the Android main/UI thread.
     * Guaranteed to persist the latest state before fold, close, or window teardown.
     * Uses a bounded timeout (2000ms) to ensure the UI thread cannot deadlock.
     */
    fun flushPendingSave(
        timeoutMs: Long = DEFAULT_FLUSH_TIMEOUT_MS,
        targetState: MiniAppState? = null
    ): Boolean {
        synchronized(debounceLock) {
            pendingSaveRunnable?.let {
                mainHandler.removeCallbacks(it)
                pendingSaveRunnable = null
            }
        }
        val seq = saveSequence.incrementAndGet()
        val opGen = if (targetState != null) currentOperationGeneration.incrementAndGet() else 0L
        if (targetState != null) {
            activeOperationGeneration.set(opGen)
        }
        val snapshotPayload = createPayloadMap()
        val boundsCopy = latestBounds?.copyBounds()

        val isMainThread = Looper.myLooper() == Looper.getMainLooper()
        if (TEST_DISPATCH_IMMEDIATELY || (!isMainThread && timeoutMs <= 0)) {
            return commitSave(seq, snapshotPayload, boundsCopy, targetState, opGen)
        }

        // Offload disk I/O to backgroundExecutor and safely wait up to timeoutMs
        val future: Future<Boolean> = backgroundExecutor.submit(Callable {
            commitSave(seq, snapshotPayload, boundsCopy, targetState, opGen)
        })

        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            if (targetState != null) {
                // Invalidate this lifecycle transition operation generation so background worker cannot commit targetState later
                activeOperationGeneration.compareAndSet(opGen, 0L)
            }
            LogKeeper.log(context, TAG, "NOTE_PERSIST_FLUSH_TIMEOUT id=$instanceId seq=$seq opGen=$opGen timeoutMs=$timeoutMs")
            false
        } catch (t: Throwable) {
            if (targetState != null) {
                activeOperationGeneration.compareAndSet(opGen, 0L)
            }
            LogKeeper.logError(context, TAG, "Error waiting for flush save on instance: $instanceId", t)
            false
        }
    }

    /**
     * Authoritative, non-blocking asynchronous flush for Android UI lifecycle transitions.
     * Dispatches disk I/O on background executor without blocking the caller thread.
     * Guarantees callback is dispatched on the Android main thread.
     * Prevents stale completions or unauthorized lifecycle commits if timed out or aborted.
     */
    fun flushPendingSaveAsync(
        timeoutMs: Long = DEFAULT_FLUSH_TIMEOUT_MS,
        targetState: MiniAppState? = null,
        onComplete: (Boolean) -> Unit
    ) {
        synchronized(debounceLock) {
            pendingSaveRunnable?.let {
                mainHandler.removeCallbacks(it)
                pendingSaveRunnable = null
            }
        }
        val seq = saveSequence.incrementAndGet()
        val opGen = if (targetState != null) currentOperationGeneration.incrementAndGet() else 0L
        if (targetState != null) {
            activeOperationGeneration.set(opGen)
        }
        val snapshotPayload = createPayloadMap()
        val boundsCopy = latestBounds?.copyBounds()

        if (TEST_DISPATCH_IMMEDIATELY) {
            val res = commitSave(seq, snapshotPayload, boundsCopy, targetState, opGen)
            onComplete(res)
            return
        }

        val completed = java.util.concurrent.atomic.AtomicBoolean(false)

        val timeoutFuture: java.util.concurrent.ScheduledFuture<*>? = if (timeoutMs > 0) {
            timeoutScheduler.schedule({
                if (completed.compareAndSet(false, true)) {
                    if (targetState != null) {
                        // Invalidate this lifecycle transition so worker cannot commit targetState later
                        activeOperationGeneration.compareAndSet(opGen, 0L)
                    }
                    LogKeeper.log(context, TAG, "NOTE_PERSIST_FLUSH_ASYNC_TIMEOUT id=$instanceId seq=$seq opGen=$opGen timeoutMs=$timeoutMs")
                    dispatchCallback { onComplete(false) }
                }
            }, timeoutMs, TimeUnit.MILLISECONDS)
        } else null

        backgroundExecutor.execute {
            val success = try {
                commitSave(seq, snapshotPayload, boundsCopy, targetState, opGen)
            } catch (t: Throwable) {
                LogKeeper.logError(context, TAG, "Error in flushPendingSaveAsync on instance: $instanceId", t)
                false
            }

            timeoutFuture?.cancel(false)
            if (completed.compareAndSet(false, true)) {
                dispatchCallback { onComplete(success) }
            } else {
                LogKeeper.log(context, TAG, "NOTE_PERSIST_FLUSH_ASYNC_IGNORED_LATE id=$instanceId seq=$seq opGen=$opGen success=$success")
            }
        }
    }

    private fun dispatchCallback(action: () -> Unit) {
        if (Looper.getMainLooper() != null && Looper.myLooper() != Looper.getMainLooper()) {
            val posted = mainHandler.post(action)
            if (!posted) {
                action()
            }
        } else {
            action()
        }
    }

    private fun commitSave(
        seq: Long,
        payloadMap: Map<String, String>,
        bounds: WindowBounds?
    ): Boolean = commitSave(seq, payloadMap, bounds, null, 0L)

    private fun commitSave(
        seq: Long,
        payloadMap: Map<String, String>,
        bounds: WindowBounds?,
        targetState: MiniAppState?
    ): Boolean = commitSave(seq, payloadMap, bounds, targetState, 0L)

    private fun commitSave(
        seq: Long,
        payloadMap: Map<String, String>,
        bounds: WindowBounds?,
        targetState: MiniAppState?,
        opGen: Long
    ): Boolean {
        synchronized(instanceCommitLock) {
            if (seq < latestCommittedSequence.get()) {
                // Stale write protection: a newer save sequence has already committed
                LogKeeper.log(context, TAG, "NOTE_PERSIST_STALE_SKIPPED id=$instanceId seq=$seq latest=${latestCommittedSequence.get()}")
                return false
            }

            return try {
                val existing = durableStore.loadSnapshot(instanceId)

                // Verify lifecycle operation authorization after loading snapshot
                // to guard against timeouts that occurred during snapshot loading or thread scheduling
                val effectiveTargetState: MiniAppState? = if (targetState != null) {
                    if (opGen == 0L || activeOperationGeneration.get() != opGen) {
                        LogKeeper.log(context, TAG, "NOTE_PERSIST_LIFECYCLE_UNAUTHORIZED id=$instanceId opGen=$opGen active=${activeOperationGeneration.get()} - target state ignored")
                        null // Save content only, do NOT commit unauthorized lifecycle state
                    } else {
                        targetState
                    }
                } else {
                    null
                }

                val snapshotToSave = existing ?: MiniAppRecoverySnapshot(
                    instanceId = instanceId,
                    appType = "test_notes",
                    state = effectiveTargetState ?: MiniAppState.ACTIVE,
                    windowBounds = bounds?.copyBounds() ?: WindowBounds(120, 150, 650, 850)
                )

                if (effectiveTargetState != null) {
                    snapshotToSave.state = effectiveTargetState
                }
                if (bounds != null) {
                    snapshotToSave.windowBounds = bounds.copyBounds()
                }
                snapshotToSave.payload.putAll(payloadMap)
                snapshotToSave.timestamp = System.currentTimeMillis()

                val success = durableStore.saveSnapshot(snapshotToSave)
                if (success) {
                    latestCommittedSequence.updateAndGet { current -> maxOf(current, seq) }
                    LogKeeper.log(context, TAG, "NOTE_PERSIST_SUCCESS id=$instanceId seq=$seq textLen=${latestText.length} state=${snapshotToSave.state}")
                } else {
                    LogKeeper.log(context, TAG, "NOTE_PERSIST_FAILURE id=$instanceId seq=$seq")
                }
                success
            } catch (e: Throwable) {
                LogKeeper.logError(context, TAG, "Error persisting note instance: $instanceId", e)
                false
            }
        }
    }

    fun createPayloadMap(): Map<String, String> {
        val undoJson = serializeHistory(undoStack)
        val redoJson = serializeHistory(redoStack)
        return mapOf(
            TestNotesMiniAppInstance.KEY_TEXT to latestText,
            "text" to latestText,
            "font_size" to latestFontSize.toString(),
            "font_style" to latestFontStyle,
            "word_wrap" to latestWordWrap.toString(),
            "line_spacing" to latestLineSpacing.toString(),
            KEY_ACCENT_COLOR to latestAccentColor,
            KEY_UNDO_HISTORY to undoJson,
            KEY_REDO_HISTORY to redoJson
        )
    }

    fun restoreHistoryFromSnapshot(snapshot: MiniAppRecoverySnapshot?) {
        isRestoringState = true
        try {
            undoStack.clear()
            redoStack.clear()
            val savedUndo = snapshot?.payload?.get(KEY_UNDO_HISTORY)
            val savedRedo = snapshot?.payload?.get(KEY_REDO_HISTORY)
            if (!savedUndo.isNullOrEmpty()) {
                undoStack.addAll(deserializeHistory(savedUndo))
            }
            if (!savedRedo.isNullOrEmpty()) {
                redoStack.addAll(deserializeHistory(savedRedo))
            }
        } finally {
            isRestoringState = false
        }
    }

    fun serializeHistory(stack: ArrayDeque<String>): String {
        val arr = JSONArray()
        for (item in stack) {
            arr.put(item)
        }
        return arr.toString()
    }

    fun deserializeHistory(jsonStr: String?): ArrayDeque<String> {
        val stack = ArrayDeque<String>()
        if (jsonStr.isNullOrEmpty()) return stack
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                stack.addLast(arr.getString(i))
            }
        } catch (_: Throwable) {
            // Gracefully ignore corrupt JSON and return empty stack
        }
        return stack
    }

    companion object {
        private const val TAG = "NotePersistenceCoordinator"
        const val KEY_UNDO_HISTORY = "undo_history"
        const val KEY_REDO_HISTORY = "redo_history"
        const val KEY_ACCENT_COLOR = "accent_color"
        const val DEFAULT_ACCENT_COLOR = "#89B4FA"
        const val MAX_HISTORY = 50
        const val DEFAULT_DEBOUNCE_MS = 400L
        const val DEFAULT_FLUSH_TIMEOUT_MS = 2000L

        private val instanceLocks = ConcurrentHashMap<String, Any>()

        fun getInstanceLock(instanceId: String): Any {
            return instanceLocks.computeIfAbsent(instanceId) { Any() }
        }

        /**
         * Test hook: when true, schedules execute synchronously without delay.
         */
        @Volatile
        var TEST_DISPATCH_IMMEDIATELY: Boolean = false

        private val defaultExecutor: ExecutorService by lazy {
            Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "NotePersistenceWorker").apply {
                    isDaemon = true
                }
            }
        }

        private val timeoutScheduler: ScheduledExecutorService by lazy {
            Executors.newScheduledThreadPool(1) { runnable ->
                Thread(runnable, "NoteFlushTimeout").apply {
                    isDaemon = true
                }
            }
        }
    }
}
