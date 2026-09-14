package com.example.core.ipc

/**
 * Minimal IPC Contract for requesting the Welcome / Permission Setup page from Main to Heavy.
 *
 * Characteristics:
 * 1. Narrow IPC boundary: Sends an explicit request with minimal parameters (reason, timestamp).
 * 2. Privacy preserved: No runtime hierarchies, managers, or internal memory models transferred.
 * 3. Authority: Main process requests on-demand; Heavy process displays and hosts the UI.
 */
object WelcomeIpcContract {
    const val TARGET_WELCOME = "welcome"

    // Payload keys
    const val KEY_REASON = "reason"
    const val KEY_TIMESTAMP = "timestamp"

    // Canonical reasons
    const val REASON_SETUP = "setup"
    const val REASON_OVERLAY_REQUIRED = "overlay_required"
    const val REASON_USER_REQUEST = "user_request"

    /**
     * Creates a minimal immutable HeavyCommand for showing the Welcome page.
     */
    fun createShowWelcomeCommand(
        reason: String = REASON_SETUP,
        timestamp: Long = System.currentTimeMillis()
    ): HeavyCommand {
        return HeavyCommand(
            commandId = "welcome_${timestamp}_${reason}",
            type = HeavyCommandType.SHOW_WELCOME,
            targetId = TARGET_WELCOME,
            payload = mapOf(
                KEY_REASON to reason,
                KEY_TIMESTAMP to timestamp.toString()
            ),
            timestamp = timestamp
        )
    }
}
