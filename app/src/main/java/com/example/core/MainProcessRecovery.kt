package com.example.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.example.service.BootReceiver

/**
 * MainProcessRecovery: Minimal, resilient recovery coordinator for the resident Main process.
 *
 * Architectural & Lifecycle Guarantees:
 * 1. Strictly scoped to the Main process (com.example). Never triggers, wakes, or depends on :heavy.
 * 2. Preserves the exact two-process architecture (Main + :heavy) without introducing a 3rd watchdog process.
 * 3. Does not run aggressive periodic polling or background monitor loops that drain battery or RAM.
 * 4. Combines Android's native START_STICKY service lifecycle with a one-shot system-delivered
 *    AlarmManager trigger as a safety net if Android's ActivityManager delays or drops the service.
 * 5. Prevents restart loops: caps rapid consecutive crashes to [MAX_CONSECUTIVE_CRASHES] within
 *    [CRASH_WINDOW_MS], applying exponential backoff (2s, 5s, 10s).
 * 6. Resets crash counters only after the service runs stably for [STABLE_RUN_THRESHOLD_MS] (15s).
 * 7. Strictly respects user configuration: aborts recovery if auto_start_on_boot is false or
 *    if SYSTEM_ALERT_WINDOW (overlay permission) is not granted.
 */
object MainProcessRecovery {
    private const val TAG = "MainRecovery"
    private const val PREFS_NAME = "main_process_recovery_prefs"
    private const val KEY_CONSECUTIVE_CRASHES = "consecutive_crashes"
    private const val KEY_LAST_CRASH_TIME = "last_crash_time"

    private const val MAX_CONSECUTIVE_CRASHES = 3
    private const val CRASH_WINDOW_MS = 60_000L // 1 minute window
    private const val STABLE_RUN_THRESHOLD_MS = 15_000L // 15 seconds to verify stable recovery
    private const val RECOVERY_REQUEST_CODE = 9901

    private val mainHandler = Handler(Looper.getMainLooper())
    private var stableResetRunnable: Runnable? = null

    /**
     * Called when HandleService has successfully started and entered the foreground.
     * Cancels any pending recovery alarms and schedules a reset of the consecutive crash counter
     * once stable runtime is confirmed.
     */
    fun onServiceStarted(context: Context) {
        cancelStableReset()
        cancelPendingRecovery(context)
        stableResetRunnable = Runnable {
            markStable(context)
        }.also {
            mainHandler.postDelayed(it, STABLE_RUN_THRESHOLD_MS)
        }
    }

    /**
     * Cancels any pending one-shot recovery alarm so redundant restart triggers do not fire.
     */
    fun cancelPendingRecovery(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (alarmManager != null) {
                val intent = Intent(context, BootReceiver::class.java).apply {
                    action = BootReceiver.ACTION_RECOVER_MAIN
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    RECOVERY_REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                if (pendingIntent != null) {
                    alarmManager.cancel(pendingIntent)
                    pendingIntent.cancel()
                    LogKeeper.log(context, TAG, "Cancelled pending one-shot recovery alarm")
                }
            }
        } catch (e: Exception) {
            // Ignored
        }
    }

    /**
     * Called when HandleService is destroyed cleanly.
     */
    fun onServiceDestroyed() {
        cancelStableReset()
    }

    private fun cancelStableReset() {
        stableResetRunnable?.let { mainHandler.removeCallbacks(it) }
        stableResetRunnable = null
    }

    private fun markStable(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val count = prefs.getInt(KEY_CONSECUTIVE_CRASHES, 0)
            if (count > 0) {
                prefs.edit().putInt(KEY_CONSECUTIVE_CRASHES, 0).apply()
                LogKeeper.log(
                    context,
                    TAG,
                    "HandleService achieved stable runtime (${STABLE_RUN_THRESHOLD_MS / 1000}s). Crash counter reset."
                )
            }
        } catch (e: Exception) {
            // Ignored
        }
    }

    /**
     * Evaluates crash conditions, enforces loop guards, and schedules a system-delivered
     * restart trigger before process death.
     */
    fun handleProcessCrash(context: Context, threadName: String, throwable: Throwable) {
        try {
            // 1. Strictly Main process only. Do NOT trigger recovery if crash occurred in :heavy.
            val processName = LogKeeper.getProcessName(context)
            if (processName.endsWith(":heavy")) {
                LogKeeper.log(context, TAG, "Crash occurred in :heavy process; skipping Main recovery.")
                return
            }

            // 2. Check configuration and permissions
            val handlePrefs = context.getSharedPreferences(HandleManager.PREFS_NAME, Context.MODE_PRIVATE)
            val autoStart = handlePrefs.getBoolean("auto_start_on_boot", true)
            val hasOverlay = Settings.canDrawOverlays(context)

            if (!autoStart) {
                LogKeeper.log(context, TAG, "Automatic recovery aborted: auto_start_on_boot is disabled.")
                return
            }
            if (!hasOverlay) {
                LogKeeper.log(context, TAG, "Automatic recovery aborted: overlay permission (SYSTEM_ALERT_WINDOW) missing.")
                return
            }

            // 3. Crash loop detection and backoff calculation
            val recoveryPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val lastCrashTime = recoveryPrefs.getLong(KEY_LAST_CRASH_TIME, 0L)
            var consecutiveCrashes = recoveryPrefs.getInt(KEY_CONSECUTIVE_CRASHES, 0)

            if (now - lastCrashTime < CRASH_WINDOW_MS) {
                consecutiveCrashes++
            } else {
                consecutiveCrashes = 1
            }

            recoveryPrefs.edit()
                .putInt(KEY_CONSECUTIVE_CRASHES, consecutiveCrashes)
                .putLong(KEY_LAST_CRASH_TIME, now)
                .commit()

            if (consecutiveCrashes > MAX_CONSECUTIVE_CRASHES) {
                LogKeeper.logError(
                    context,
                    TAG,
                    "Persistent startup crash loop detected ($consecutiveCrashes crashes within ${CRASH_WINDOW_MS / 1000}s). " +
                            "Aborting automatic restart to prevent restart loop and protect device stability."
                )
                return
            }

            // 4. Calculate exponential backoff (2s for 1st crash, 5s for 2nd, 10s for 3rd)
            val delayMillis = when (consecutiveCrashes) {
                1 -> 2000L
                2 -> 5000L
                else -> 10000L
            }

            // 5. Schedule one-shot system-delivered restart trigger via AlarmManager
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (alarmManager != null) {
                val intent = Intent(context, BootReceiver::class.java).apply {
                    action = BootReceiver.ACTION_RECOVER_MAIN
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    RECOVERY_REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val triggerAt = SystemClock.elapsedRealtime() + delayMillis
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)

                LogKeeper.log(
                    context,
                    TAG,
                    "Scheduled system restart trigger via AlarmManager in ${delayMillis}ms (attempt $consecutiveCrashes/$MAX_CONSECUTIVE_CRASHES)."
                )
            }
        } catch (e: Exception) {
            LogKeeper.logError(context, TAG, "Failed to schedule crash recovery", e)
        }
    }
}
