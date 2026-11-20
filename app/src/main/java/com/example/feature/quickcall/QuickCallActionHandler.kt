package com.example.feature.quickcall

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.core.LogKeeper
import com.example.feature.sidebar.SidebarItem

/**
 * Explicit outcome of executing a Quick Call Sidebar element.
 * Never claims a call connected or completed — only reports whether the call/dialer/permission
 * intent was launched or why execution could not proceed.
 */
enum class QuickCallDispatchOutcome {
    CALL_INTENT_LAUNCHED,
    DIALER_OPENED_EMERGENCY,
    PERMISSION_REQUESTED,
    DIALER_FALLBACK_OPENED,
    RECORD_NOT_FOUND,
    INVALID_NUMBER,
    NO_HANDLER
}

/**
 * Central execution handler for [SidebarItem.QuickCall] elements invoked by
 * [com.example.feature.element.ElementActionDispatcher].
 *
 * Security & Privacy guarantees:
 * - Never writes phone numbers into logs.
 * - Routes emergency numbers strictly through system dialler (`ACTION_DIAL`), never `ACTION_CALL`.
 * - Requests `CALL_PHONE` only on first direct-call attempt when missing.
 * - Falls back cleanly to `ACTION_DIAL` when `CALL_PHONE` is denied or unavailable.
 * - Uses no background calling service.
 */
object QuickCallActionHandler {

    private const val TAG = "QuickCallActionHandler"
    const val EXTRA_ELEMENT_ID = "QUICK_CALL_ELEMENT_ID"

    internal var hasCallPhonePermission: (Context) -> Boolean = { ctx ->
        try {
            ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    internal var isEmergencyNumberCheck: (Context, String) -> Boolean = { ctx, number ->
        QuickCallNumberValidator.isEmergencyNumber(ctx, number)
    }

    internal var canResolveIntent: (Context, Intent) -> Boolean = { ctx, intent ->
        try {
            val pm = ctx.packageManager
            pm != null && intent.resolveActivity(pm) != null
        } catch (_: Throwable) {
            false
        }
    }

    internal var activityLauncher: (Context, String, Intent) -> Boolean = { ctx, _, intent ->
        try {
            ctx.startActivity(intent)
            true
        } catch (e: Throwable) {
            LogKeeper.logError(ctx, TAG, "Failed to start call/dialer activity", e)
            false
        }
    }

    internal var permissionPromptLauncher: (Context, String) -> Boolean = { ctx, elementId ->
        try {
            val intent = Intent(ctx, QuickCallPermissionActivity::class.java).apply {
                putExtra(EXTRA_ELEMENT_ID, elementId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            true
        } catch (e: Throwable) {
            LogKeeper.logError(ctx, TAG, "Failed to launch QuickCallPermissionActivity", e)
            false
        }
    }

    internal var userMessageNotifier: (Context, String) -> Unit = { ctx, message ->
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
            } else {
                Handler(Looper.getMainLooper()).post {
                    try {
                        Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
    }

    internal fun resetHooksForTesting() {
        hasCallPhonePermission = { ctx ->
            try {
                ContextCompat.checkSelfPermission(
                    ctx,
                    Manifest.permission.CALL_PHONE
                ) == PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) {
                false
            }
        }
        isEmergencyNumberCheck = { ctx, number ->
            QuickCallNumberValidator.isEmergencyNumber(ctx, number)
        }
        canResolveIntent = { ctx, intent ->
            try {
                val pm = ctx.packageManager
                pm != null && intent.resolveActivity(pm) != null
            } catch (_: Throwable) {
                false
            }
        }
        activityLauncher = { ctx, _, intent ->
            try {
                ctx.startActivity(intent)
                true
            } catch (e: Throwable) {
                LogKeeper.logError(ctx, TAG, "Failed to start call/dialer activity", e)
                false
            }
        }
        permissionPromptLauncher = { ctx, elementId ->
            try {
                val intent = Intent(ctx, QuickCallPermissionActivity::class.java).apply {
                    putExtra(EXTRA_ELEMENT_ID, elementId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
                true
            } catch (e: Throwable) {
                LogKeeper.logError(ctx, TAG, "Failed to launch QuickCallPermissionActivity", e)
                false
            }
        }
        userMessageNotifier = { ctx, message ->
            try {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
                } else {
                    Handler(Looper.getMainLooper()).post {
                        try {
                            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
                        } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    fun execute(context: Context, item: SidebarItem.QuickCall): QuickCallDispatchOutcome {
        return executeByUuid(context, item.uuid)
    }

    fun executeByUuid(context: Context, uuid: String): QuickCallDispatchOutcome {
        val cleanUuid = uuid.trim()
        if (cleanUuid.isEmpty()) {
            LogKeeper.log(context, TAG, "EXECUTE_FAILED reason=blank_uuid")
            userMessageNotifier(context, "Quick Call target is missing.")
            return QuickCallDispatchOutcome.RECORD_NOT_FOUND
        }

        val record = QuickCallStore.getInstance(context).getRecord(cleanUuid)
        if (record == null) {
            LogKeeper.log(context, TAG, "EXECUTE_FAILED reason=record_not_found uuid=$cleanUuid")
            userMessageNotifier(context, "Quick Call contact not found. Remove or recreate it.")
            return QuickCallDispatchOutcome.RECORD_NOT_FOUND
        }

        if (!QuickCallNumberValidator.isValidPhoneNumber(record.phoneNumber)) {
            LogKeeper.log(context, TAG, "EXECUTE_FAILED reason=invalid_number uuid=$cleanUuid")
            userMessageNotifier(context, "Invalid phone number. Edit this Quick Call to fix it.")
            return QuickCallDispatchOutcome.INVALID_NUMBER
        }

        // Emergency numbers must always open in the system dialer (ACTION_DIAL), never ACTION_CALL.
        if (isEmergencyNumberCheck(context, record.phoneNumber)) {
            val dialIntent = buildDialIntent(record.phoneNumber)
            return if (canResolveIntent(context, dialIntent) && activityLauncher(context, Intent.ACTION_DIAL, dialIntent)) {
                LogKeeper.log(context, TAG, "DIALER_OPENED_EMERGENCY uuid=$cleanUuid")
                QuickCallDispatchOutcome.DIALER_OPENED_EMERGENCY
            } else {
                LogKeeper.log(context, TAG, "NO_DIALER_HANDLER_EMERGENCY uuid=$cleanUuid")
                userMessageNotifier(context, "No dialer app available to handle emergency calls.")
                QuickCallDispatchOutcome.NO_HANDLER
            }
        }

        if (!hasCallPhonePermission(context)) {
            val launched = permissionPromptLauncher(context, record.elementId)
            return if (launched) {
                LogKeeper.log(context, TAG, "CALL_PERMISSION_REQUESTED uuid=$cleanUuid")
                QuickCallDispatchOutcome.PERMISSION_REQUESTED
            } else {
                // If permission prompt could not start, offer/launch dialer fallback cleanly
                launchDialerFallback(context, cleanUuid)
            }
        }

        val callIntent = buildCallIntent(record.phoneNumber)
        if (canResolveIntent(context, callIntent)) {
            if (activityLauncher(context, Intent.ACTION_CALL, callIntent)) {
                LogKeeper.log(context, TAG, "CALL_INTENT_LAUNCHED uuid=$cleanUuid")
                return QuickCallDispatchOutcome.CALL_INTENT_LAUNCHED
            }
        }

        // Direct call handler unavailable on this device — fall back cleanly to system dialer
        return launchDialerFallback(context, cleanUuid)
    }

    /**
     * Opens the Android system dialer (`ACTION_DIAL`) pre-populated with the saved Quick Call
     * number (e.g., when `CALL_PHONE` permission is denied or no direct call handler exists).
     */
    fun launchDialerFallback(context: Context, uuid: String): QuickCallDispatchOutcome {
        val cleanUuid = uuid.trim()
        val record = QuickCallStore.getInstance(context).getRecord(cleanUuid)
        if (record == null) {
            LogKeeper.log(context, TAG, "DIALER_FALLBACK_FAILED reason=record_not_found uuid=$cleanUuid")
            userMessageNotifier(context, "Quick Call contact not found.")
            return QuickCallDispatchOutcome.RECORD_NOT_FOUND
        }
        if (!QuickCallNumberValidator.isValidPhoneNumber(record.phoneNumber)) {
            LogKeeper.log(context, TAG, "DIALER_FALLBACK_FAILED reason=invalid_number uuid=$cleanUuid")
            userMessageNotifier(context, "Invalid phone number.")
            return QuickCallDispatchOutcome.INVALID_NUMBER
        }

        val dialIntent = buildDialIntent(record.phoneNumber)
        return if (canResolveIntent(context, dialIntent) && activityLauncher(context, Intent.ACTION_DIAL, dialIntent)) {
            LogKeeper.log(context, TAG, "DIALER_FALLBACK_OPENED uuid=$cleanUuid")
            QuickCallDispatchOutcome.DIALER_FALLBACK_OPENED
        } else {
            LogKeeper.log(context, TAG, "NO_CALL_OR_DIALER_HANDLER uuid=$cleanUuid")
            userMessageNotifier(context, "No phone or dialer app is available on this device.")
            QuickCallDispatchOutcome.NO_HANDLER
        }
    }

    internal fun buildCallIntent(phoneNumber: String): Intent {
        val uri = QuickCallNumberValidator.buildTelUri(phoneNumber)
        return (if (uri != null) Intent(Intent.ACTION_CALL, uri) else Intent(Intent.ACTION_CALL)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    internal fun buildDialIntent(phoneNumber: String): Intent {
        val uri = QuickCallNumberValidator.buildTelUri(phoneNumber)
        return (if (uri != null) Intent(Intent.ACTION_DIAL, uri) else Intent(Intent.ACTION_DIAL)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun isHandledOutcome(outcome: QuickCallDispatchOutcome): Boolean {
        return outcome == QuickCallDispatchOutcome.CALL_INTENT_LAUNCHED ||
            outcome == QuickCallDispatchOutcome.DIALER_OPENED_EMERGENCY ||
            outcome == QuickCallDispatchOutcome.PERMISSION_REQUESTED ||
            outcome == QuickCallDispatchOutcome.DIALER_FALLBACK_OPENED
    }
}
