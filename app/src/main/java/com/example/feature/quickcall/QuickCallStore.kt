package com.example.feature.quickcall

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import com.example.core.IconCacheManager
import com.example.core.LogKeeper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Durable snapshot record for a Quick Call Sidebar element.
 *
 * The element ID is strictly UUID-based (`quick_call:<uuid>`) and NEVER contains the phone number.
 * Snapshots survive restarts, contact deletions, and `READ_CONTACTS` revocations.
 * When [manuallyEditedNumber] is true, the stored number remains authoritative and is never
 * silently overwritten by external contact changes.
 */
data class QuickCallRecord(
    val uuid: String,
    val displayName: String,
    val phoneNumber: String,
    val contactLookupKey: String? = null,
    val customIconPath: String? = null,
    val manuallyEditedNumber: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val elementId: String
        get() = "${QuickCallStore.ELEMENT_PREFIX}$uuid"
}

/**
 * Phone number validation, dialable normalization, URI building, and emergency-number checks.
 * Designed to run safely in both Android runtime and pure JVM unit tests without leaking PII.
 */
object QuickCallNumberValidator {

    private val KNOWN_EMERGENCY_NUMBERS = setOf(
        "911", "112", "999", "000", "110", "119", "118",
        "100", "101", "102", "108", "111", "08", "995", "997", "998"
    )

    /**
     * Extracts a clean dialable string preserving leading `+`, digits `0-9`, `*`, `#`, `,`, and `;`.
     * Strips formatting punctuation like spaces, dashes, dots, and parentheses.
     * Rejects strings containing alphabetic letters (except pause/wait syntax not used here).
     */
    fun extractDialableNumber(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val trimmed = raw.trim()
        // Reject strings with letters (e.g. "abc", "call-me")
        if (trimmed.any { it.isLetter() }) return ""

        val sb = StringBuilder(trimmed.length)
        for (i in trimmed.indices) {
            val ch = trimmed[i]
            when {
                ch == '+' && sb.isEmpty() -> sb.append(ch)
                ch in '0'..'9' -> sb.append(ch)
                ch == '*' || ch == '#' || ch == ',' || ch == ';' -> sb.append(ch)
                ch == ' ' || ch == '-' || ch == '(' || ch == ')' || ch == '.' || ch == '/' -> {
                    // Valid formatting separator; omit from raw dialable string
                }
                else -> return ""
            }
        }
        return sb.toString()
    }

    /**
     * Returns true when [raw] represents a plausible dialable phone number.
     * Requires at least 2 numeric digits (allows short/emergency codes like 911/112/08,
     * standard local numbers, and international numbers).
     */
    fun isValidPhoneNumber(raw: String?): Boolean {
        val dialable = extractDialableNumber(raw)
        if (dialable.isEmpty()) return false
        val digitCount = dialable.count { it in '0'..'9' }
        return digitCount >= 2 && digitCount <= 20
    }

    /**
     * Returns a normalized comparison key (leading `+` + digits only) for deduplicating
     * identical numbers formatted differently (e.g. "(555) 123-4567" vs "555-123-4567").
     */
    fun normalizationKey(raw: String?): String {
        val dialable = extractDialableNumber(raw)
        if (dialable.isEmpty()) return ""
        val sb = StringBuilder(dialable.length)
        for (i in dialable.indices) {
            val ch = dialable[i]
            if ((ch == '+' && sb.isEmpty()) || ch in '0'..'9' || ch == '*' || ch == '#') {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Determines whether [raw] is an emergency number that MUST be routed to the system
     * dialler (`ACTION_DIAL`) rather than direct `ACTION_CALL`.
     */
    fun isEmergencyNumber(context: Context?, raw: String?): Boolean {
        val dialable = extractDialableNumber(raw)
        if (dialable.isEmpty()) return false
        val digitsOnly = dialable.filter { it in '0'..'9' }
        if (digitsOnly in KNOWN_EMERGENCY_NUMBERS) {
            return true
        }
        if (context != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                    if (tm != null && tm.isEmergencyNumber(dialable)) {
                        return true
                    }
                }
            } catch (_: Throwable) {
                // Safe fallback in JVM tests or devices without Telephony service
            }
            try {
                @Suppress("DEPRECATION")
                if (PhoneNumberUtils.isEmergencyNumber(dialable)) {
                    return true
                }
            } catch (_: Throwable) {
                // Safe fallback in JVM tests
            }
        }
        return false
    }

    /**
     * Builds a safe `tel:` URI for Android call/dial intents using `Uri.fromParts`
     * so special characters like `#` and `+` are preserved properly.
     */
    fun buildTelUri(raw: String): Uri? {
        val dialable = extractDialableNumber(raw)
        if (dialable.isEmpty()) return null
        return Uri.fromParts("tel", dialable, null)
    }
}

/**
 * SharedPreferences-backed persistence store for Quick Call elements.
 * Uses synchronous `.commit()` so saved records survive restarts and process recreation.
 *
 * Zero-PII Policy: Phone numbers are NEVER written to diagnostic logs.
 */
class QuickCallStore private constructor(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    fun getAllRecords(): List<QuickCallRecord> = synchronized(lock) {
        val raw = prefs.getString(KEY_RECORDS_JSON, null) ?: return emptyList()
        parseRecords(raw)
    }

    fun getRecord(uuid: String): QuickCallRecord? {
        val clean = uuid.trim()
        if (clean.isEmpty()) return null
        return getAllRecords().firstOrNull { it.uuid == clean }
    }

    fun getRecordByElementId(elementId: String): QuickCallRecord? {
        val uuid = extractUuid(elementId) ?: return null
        return getRecord(uuid)
    }

    fun saveRecord(record: QuickCallRecord): Boolean = synchronized(lock) {
        if (record.uuid.isBlank()) return false
        val current = getAllRecords().toMutableList()
        val idx = current.indexOfFirst { it.uuid == record.uuid }
        if (idx >= 0) {
            current[idx] = record
        } else {
            current.add(record)
        }
        persistRecordsLocked(current)
    }

    fun createFromContact(
        displayName: String,
        phoneNumber: String,
        contactLookupKey: String? = null,
        uuid: String = UUID.randomUUID().toString()
    ): QuickCallRecord? {
        if (!QuickCallNumberValidator.isValidPhoneNumber(phoneNumber)) {
            LogKeeper.log(context, TAG, "CREATE_REJECTED_INVALID_NUMBER uuid=$uuid")
            return null
        }
        val cleanName = displayName.trim().ifEmpty { "Quick Call" }
        val cleanNumber = phoneNumber.trim()
        val now = System.currentTimeMillis()
        val record = QuickCallRecord(
            uuid = uuid.trim().ifEmpty { UUID.randomUUID().toString() },
            displayName = cleanName,
            phoneNumber = cleanNumber,
            contactLookupKey = contactLookupKey?.trim()?.takeIf { it.isNotEmpty() },
            customIconPath = null,
            manuallyEditedNumber = false,
            createdAt = now,
            updatedAt = now
        )
        return if (saveRecord(record)) {
            LogKeeper.log(context, TAG, "RECORD_CREATED uuid=${record.uuid}")
            record
        } else {
            null
        }
    }

    /**
     * Updates an existing Quick Call record's label and/or phone number.
     * Preserves [QuickCallRecord.manuallyEditedNumber] authoritatively whenever a number is edited.
     */
    fun updateRecord(
        uuid: String,
        displayName: String,
        phoneNumber: String,
        markManuallyEdited: Boolean = true
    ): QuickCallRecord? = synchronized(lock) {
        val existing = getRecord(uuid) ?: return null
        if (!QuickCallNumberValidator.isValidPhoneNumber(phoneNumber)) {
            LogKeeper.log(context, TAG, "UPDATE_REJECTED_INVALID_NUMBER uuid=$uuid")
            return null
        }
        val trimmedName = displayName.trim().ifEmpty { existing.displayName }
        val trimmedNumber = phoneNumber.trim()
        val numberChanged = QuickCallNumberValidator.normalizationKey(trimmedNumber) !=
            QuickCallNumberValidator.normalizationKey(existing.phoneNumber) ||
            trimmedNumber != existing.phoneNumber

        val updated = existing.copy(
            displayName = trimmedName,
            phoneNumber = trimmedNumber,
            manuallyEditedNumber = existing.manuallyEditedNumber || (numberChanged && markManuallyEdited),
            updatedAt = System.currentTimeMillis()
        )
        return if (saveRecord(updated)) {
            LogKeeper.log(context, TAG, "RECORD_UPDATED uuid=$uuid manuallyEdited=${updated.manuallyEditedNumber}")
            updated
        } else {
            null
        }
    }

    /**
     * Updates the custom icon path stored for a Quick Call element.
     */
    fun updateCustomIconPath(uuid: String, customIconPath: String?): Boolean = synchronized(lock) {
        val existing = getRecord(uuid) ?: return false
        val updated = existing.copy(
            customIconPath = customIconPath?.trim()?.takeIf { it.isNotEmpty() },
            updatedAt = System.currentTimeMillis()
        )
        saveRecord(updated)
    }

    /**
     * Clears any custom icon for the Quick Call record and evicts it from [IconCacheManager],
     * restoring the default Android caller icon.
     */
    fun resetCustomIcon(uuid: String): Boolean = synchronized(lock) {
        val existing = getRecord(uuid) ?: return false
        try {
            IconCacheManager.evictIcon(context, existing.elementId)
        } catch (_: Throwable) {
            // Safe when running in minimal JVM test environments
        }
        val updated = existing.copy(
            customIconPath = null,
            updatedAt = System.currentTimeMillis()
        )
        val ok = saveRecord(updated)
        if (ok) {
            LogKeeper.log(context, TAG, "ICON_RESET uuid=$uuid")
        }
        ok
    }

    fun deleteRecord(uuid: String): Boolean = synchronized(lock) {
        val current = getAllRecords().filterNot { it.uuid == uuid }
        try {
            IconCacheManager.evictIcon(context, "$ELEMENT_PREFIX$uuid")
        } catch (_: Throwable) {}
        persistRecordsLocked(current)
    }

    fun clearAll(): Boolean = synchronized(lock) {
        prefs.edit().remove(KEY_RECORDS_JSON).commit()
    }

    private fun parseRecords(raw: String): List<QuickCallRecord> {
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<QuickCallRecord>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val uuid = obj.optString("uuid").trim()
                val phoneNumber = obj.optString("phoneNumber").trim()
                if (uuid.isEmpty() || phoneNumber.isEmpty()) continue
                list.add(
                    QuickCallRecord(
                        uuid = uuid,
                        displayName = obj.optString("displayName", "Quick Call").ifBlank { "Quick Call" },
                        phoneNumber = phoneNumber,
                        contactLookupKey = obj.optString("contactLookupKey").takeIf { it.isNotBlank() },
                        customIconPath = obj.optString("customIconPath").takeIf { it.isNotBlank() },
                        manuallyEditedNumber = obj.optBoolean("manuallyEditedNumber", false),
                        createdAt = obj.optLong("createdAt", 0L),
                        updatedAt = obj.optLong("updatedAt", 0L)
                    )
                )
            }
            list
        } catch (e: Throwable) {
            LogKeeper.logError(context, TAG, "Failed to parse QuickCallStore records", e)
            emptyList()
        }
    }

    private fun persistRecordsLocked(records: List<QuickCallRecord>): Boolean {
        return try {
            val arr = JSONArray()
            for (rec in records) {
                val obj = JSONObject().apply {
                    put("uuid", rec.uuid)
                    put("displayName", rec.displayName)
                    put("phoneNumber", rec.phoneNumber)
                    if (!rec.contactLookupKey.isNullOrBlank()) {
                        put("contactLookupKey", rec.contactLookupKey)
                    }
                    if (!rec.customIconPath.isNullOrBlank()) {
                        put("customIconPath", rec.customIconPath)
                    }
                    put("manuallyEditedNumber", rec.manuallyEditedNumber)
                    put("createdAt", rec.createdAt)
                    put("updatedAt", rec.updatedAt)
                }
                arr.put(obj)
            }
            prefs.edit().putString(KEY_RECORDS_JSON, arr.toString()).commit()
        } catch (e: Throwable) {
            LogKeeper.logError(context, TAG, "Failed to persist QuickCallStore records", e)
            false
        }
    }

    companion object {
        private const val TAG = "QuickCallStore"
        const val PREFS_NAME = "QuickCallPrefs"
        const val KEY_RECORDS_JSON = "quick_call_records_json"
        const val ELEMENT_PREFIX = "quick_call:"

        fun extractUuid(elementId: String?): String? {
            if (elementId == null) return null
            val trimmed = elementId.trim()
            if (!trimmed.startsWith(ELEMENT_PREFIX)) return null
            val uuid = trimmed.removePrefix(ELEMENT_PREFIX).trim()
            if (uuid.isEmpty() || uuid.contains(":")) return null
            return uuid
        }

        @Volatile
        private var instance: QuickCallStore? = null

        fun getInstance(context: Context): QuickCallStore {
            return instance ?: synchronized(this) {
                instance ?: QuickCallStore(context.applicationContext).also { instance = it }
            }
        }

        internal fun resetForTesting() {
            synchronized(this) {
                instance = null
            }
        }
    }
}
