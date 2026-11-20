package com.example.feature.quickcall

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.example.core.LogKeeper

/**
 * Single phone entry belonging to a device contact.
 */
data class ContactPhoneEntry(
    val number: String,
    val label: String
)

/**
 * Grouped device contact containing one or more valid phone numbers.
 */
data class DeviceContactGroup(
    val lookupKey: String,
    val displayName: String,
    val numbers: List<ContactPhoneEntry>
)

/**
 * Raw projection row read from [ContactsContract.CommonDataKinds.Phone].
 * Exposed internally so grouping, deduplication, and filtering can be tested deterministically in JVM.
 */
internal data class RawContactPhoneRow(
    val lookupKey: String,
    val displayName: String,
    val rawNumber: String,
    val typeLabel: String
)

/**
 * On-demand contacts loader for Quick Calls.
 *
 * Strictly queries [ContactsContract.CommonDataKinds.Phone.CONTENT_URI] (never CallLog),
 * only when explicitly invoked by the Quick Calls picker on open or explicit user Refresh.
 * Registers zero ContentObservers, zero background workers, and zero polling.
 */
object QuickCallContactsLoader {

    private const val TAG = "QuickCallContactsLoader"

    fun hasContactsPermission(context: Context): Boolean {
        return try {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Queries saved contacts on-demand. Returns an empty list if [Manifest.permission.READ_CONTACTS]
     * is not granted or if no contacts with valid phone numbers exist.
     */
    fun loadDeviceContacts(context: Context): List<DeviceContactGroup> {
        if (!hasContactsPermission(context)) {
            LogKeeper.log(context, TAG, "LOAD_SKIPPED_NO_PERMISSION")
            return emptyList()
        }

        val rows = mutableListOf<RawContactPhoneRow>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL
        )

        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                "${ContactsContract.CommonDataKinds.Phone.HAS_PHONE_NUMBER} > 0",
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC"
            )?.use { cursor ->
                val idxLookup = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY)
                val idxContactId = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val idxName = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
                val idxNumber = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val idxType = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                val idxLabel = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LABEL)

                while (cursor.moveToNext()) {
                    val rawNumber = if (idxNumber >= 0) cursor.getString(idxNumber) ?: "" else ""
                    if (!QuickCallNumberValidator.isValidPhoneNumber(rawNumber)) continue

                    val contactId = if (idxContactId >= 0) cursor.getString(idxContactId) ?: "" else ""
                    val lookupKey = (if (idxLookup >= 0) cursor.getString(idxLookup) else null)
                        ?.takeIf { it.isNotBlank() }
                        ?: "id_$contactId"
                    val displayName = (if (idxName >= 0) cursor.getString(idxName) else null)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: "Unnamed Contact"

                    val phoneType = if (idxType >= 0) cursor.getInt(idxType) else ContactsContract.CommonDataKinds.Phone.TYPE_OTHER
                    val customLabel = if (idxLabel >= 0) cursor.getString(idxLabel) else null
                    val typeLabel = try {
                        ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                            context.resources,
                            phoneType,
                            customLabel
                        ).toString()
                    } catch (_: Throwable) {
                        customLabel?.takeIf { it.isNotBlank() } ?: "Phone"
                    }

                    rows.add(
                        RawContactPhoneRow(
                            lookupKey = lookupKey,
                            displayName = displayName,
                            rawNumber = rawNumber.trim(),
                            typeLabel = typeLabel
                        )
                    )
                }
            }
        } catch (e: Throwable) {
            LogKeeper.logError(context, TAG, "Failed to query saved contacts", e)
            return emptyList()
        }

        val grouped = groupAndFilterRawRows(rows)
        LogKeeper.log(context, TAG, "CONTACTS_LOADED count=${grouped.size}")
        return grouped
    }

    /**
     * Groups raw phone rows by contact lookup key, validates every phone number,
     * deduplicates normalized numbers per contact, drops contacts with zero valid numbers,
     * and sorts alphabetically by display name.
     */
    internal fun groupAndFilterRawRows(rows: List<RawContactPhoneRow>): List<DeviceContactGroup> {
        val groupedByKey = LinkedHashMap<String, MutableList<RawContactPhoneRow>>()
        for (row in rows) {
            if (!QuickCallNumberValidator.isValidPhoneNumber(row.rawNumber)) continue
            val key = row.lookupKey.trim().ifEmpty { row.displayName.trim().lowercase() }
            groupedByKey.getOrPut(key) { mutableListOf() }.add(row)
        }

        val groups = ArrayList<DeviceContactGroup>(groupedByKey.size)
        for ((lookupKey, contactRows) in groupedByKey) {
            val displayName = contactRows.firstOrNull { it.displayName.isNotBlank() }?.displayName?.trim()
                ?: "Unnamed Contact"
            val seenNormKeys = LinkedHashSet<String>()
            val entries = mutableListOf<ContactPhoneEntry>()
            for (row in contactRows) {
                val normKey = QuickCallNumberValidator.normalizationKey(row.rawNumber)
                if (normKey.isEmpty() || !seenNormKeys.add(normKey)) continue
                entries.add(
                    ContactPhoneEntry(
                        number = row.rawNumber.trim(),
                        label = row.typeLabel.trim().ifEmpty { "Phone" }
                    )
                )
            }
            if (entries.isNotEmpty()) {
                groups.add(
                    DeviceContactGroup(
                        lookupKey = lookupKey,
                        displayName = displayName,
                        numbers = entries
                    )
                )
            }
        }

        return groups.sortedBy { it.displayName.lowercase() }
    }

    /**
     * Filters grouped contacts by display name or phone number (supports digit-only matching
     * regardless of formatting punctuation).
     */
    fun filterContacts(
        contacts: List<DeviceContactGroup>,
        query: String
    ): List<DeviceContactGroup> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return contacts
        val queryDigits = trimmed.filter { it in '0'..'9' || it == '+' }

        return contacts.filter { contact ->
            if (contact.displayName.contains(trimmed, ignoreCase = true)) {
                return@filter true
            }
            contact.numbers.any { entry ->
                entry.number.contains(trimmed, ignoreCase = true) ||
                    (queryDigits.isNotEmpty() && QuickCallNumberValidator.normalizationKey(entry.number).contains(queryDigits))
            }
        }
    }
}
