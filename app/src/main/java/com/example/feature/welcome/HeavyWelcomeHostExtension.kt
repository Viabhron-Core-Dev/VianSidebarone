package com.example.feature.welcome

import android.content.Context
import android.content.Intent
import com.example.core.LogKeeper
import com.example.core.ipc.HeavyCommand
import com.example.core.ipc.IpcErrorCode
import com.example.core.ipc.IpcResult
import java.util.concurrent.atomic.AtomicLong

/**
 * Extension point for handling SHOW_WELCOME commands in the Heavy process (:heavy).
 */
interface HeavyWelcomeHostExtension {
    fun onShowWelcome(command: HeavyCommand): IpcResult
}

/**
 * Default implementation of HeavyWelcomeHostExtension running in the :heavy process.
 *
 * Responsibilities:
 * 1. Launches WelcomeActivity with Intent.FLAG_ACTIVITY_NEW_TASK and FLAG_ACTIVITY_SINGLE_TOP.
 * 2. Deduplicates incoming requests: checks if Welcome is already active or was recently requested.
 * 3. Gracefully reports status across the IPC boundary.
 */
class DefaultHeavyWelcomeHostExtension(
    private val context: Context? = null,
    private val debounceWindowMs: Long = 800L
) : HeavyWelcomeHostExtension {

    private val lastLaunchTime = AtomicLong(0L)

    override fun onShowWelcome(command: HeavyCommand): IpcResult {
        val now = System.currentTimeMillis()
        val last = lastLaunchTime.get()

        // 1. Deduplication check: if WelcomeActivity is already active in foreground
        if (WelcomeActivity.isWelcomeActive) {
            safeLog(TAG, "WelcomeActivity is already active; skipping duplicate launch")
            return IpcResult.success("Welcome already active")
        }

        // 2. Debounce check: prevent rapid repeated launches
        if (now - last < debounceWindowMs) {
            safeLog(TAG, "Duplicate Welcome launch debounced (${now - last}ms < ${debounceWindowMs}ms)")
            return IpcResult.success("Welcome launch debounced")
        }

        lastLaunchTime.set(now)

        val ctx = context
        if (ctx == null) {
            safeLog(TAG, "Context is null; acknowledged SHOW_WELCOME without launching activity")
            return IpcResult.success("Acknowledged SHOW_WELCOME (no context)")
        }

        return try {
            val intent = Intent(ctx, WelcomeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("reason", command.payload["reason"] ?: "setup")
            }
            ctx.startActivity(intent)
            safeLog(TAG, "Successfully launched WelcomeActivity from SHOW_WELCOME command")
            IpcResult.success("Welcome launched successfully")
        } catch (e: Throwable) {
            safeLogCrash(TAG, e)
            IpcResult.error(
                IpcErrorCode.UNKNOWN_ERROR,
                "Failed to launch WelcomeActivity: ${e.message}"
            )
        }
    }

    private fun safeLog(tag: String, msg: String) {
        context?.let { LogKeeper.log(it, tag, msg) }
    }

    private fun safeLogCrash(tag: String, t: Throwable) {
        context?.let { LogKeeper.logCrash(it, tag, t) }
    }

    companion object {
        private const val TAG = "HeavyWelcomeHostExtension"
    }
}
