package com.example.feature.notification

import android.app.Notification
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

/**
 * Typed notification data contract for crossing the Main (:main) <-> Heavy (:heavy) process boundary.
 * Allows :heavy to render notification cards and send action requests without holding references to Main Views
 * or system NotificationListenerService instances.
 */
data class NotificationActionItem(
    val actionIndex: Int,
    val title: String,
    val hasRemoteInputs: Boolean = false,
    val remoteInputResultKey: String? = null,
    val remoteInputLabel: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("actionIndex", actionIndex)
        put("title", title)
        put("hasRemoteInputs", hasRemoteInputs)
        if (remoteInputResultKey != null) put("remoteInputResultKey", remoteInputResultKey)
        if (remoteInputLabel != null) put("remoteInputLabel", remoteInputLabel)
    }

    companion object {
        fun fromJson(obj: JSONObject): NotificationActionItem {
            return NotificationActionItem(
                actionIndex = obj.getInt("actionIndex"),
                title = obj.getString("title"),
                hasRemoteInputs = obj.optBoolean("hasRemoteInputs", false),
                remoteInputResultKey = if (obj.has("remoteInputResultKey")) obj.getString("remoteInputResultKey") else null,
                remoteInputLabel = if (obj.has("remoteInputLabel")) obj.getString("remoteInputLabel") else null
            )
        }
    }
}

data class NotificationItem(
    val key: String,
    val packageName: String,
    val id: Int,
    val tag: String?,
    val postTime: Long,
    val isClearable: Boolean,
    val isOngoing: Boolean,
    val title: String,
    val text: String,
    val subText: String = "",
    val bigText: String = "",
    val textLines: List<String> = emptyList(),
    val hasContentIntent: Boolean = false,
    val actions: List<NotificationActionItem> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("packageName", packageName)
        put("id", id)
        if (tag != null) put("tag", tag)
        put("postTime", postTime)
        put("isClearable", isClearable)
        put("isOngoing", isOngoing)
        put("title", title)
        put("text", text)
        put("subText", subText)
        put("bigText", bigText)
        if (textLines.isNotEmpty()) {
            val linesArr = JSONArray()
            textLines.forEach { linesArr.put(it) }
            put("textLines", linesArr)
        }
        put("hasContentIntent", hasContentIntent)
        if (actions.isNotEmpty()) {
            val actionsArr = JSONArray()
            actions.forEach { actionsArr.put(it.toJson()) }
            put("actions", actionsArr)
        }
    }

    companion object {
        fun fromStatusBarNotification(sbn: StatusBarNotification): NotificationItem {
            val notification = sbn.notification
            val extras = notification?.extras

            val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
            val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
            val subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim() ?: ""
            val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim() ?: ""
            val rawLines = extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            val textLines = rawLines?.filterNotNull()?.map { it.toString().trim() }?.filter { it.isNotBlank() } ?: emptyList()

            val actionItems = mutableListOf<NotificationActionItem>()
            notification?.actions?.forEachIndexed { idx, action ->
                val actionTitle = action.title?.toString()?.trim() ?: "Action"
                val remoteInputs = action.remoteInputs
                val hasRemoteInputs = !remoteInputs.isNullOrEmpty()
                val resultKey = if (hasRemoteInputs) remoteInputs?.get(0)?.resultKey else null
                val label = if (hasRemoteInputs) remoteInputs?.get(0)?.label?.toString() else null
                actionItems.add(NotificationActionItem(idx, actionTitle, hasRemoteInputs, resultKey, label))
            }

            val itemKey = sbn.key ?: "${sbn.packageName}_${sbn.id}_${sbn.tag ?: ""}"

            return NotificationItem(
                key = itemKey,
                packageName = sbn.packageName,
                id = sbn.id,
                tag = sbn.tag,
                postTime = sbn.postTime,
                isClearable = sbn.isClearable,
                isOngoing = sbn.isOngoing || ((notification?.flags ?: 0) and Notification.FLAG_ONGOING_EVENT) != 0,
                title = title,
                text = text,
                subText = subText,
                bigText = bigText,
                textLines = textLines,
                hasContentIntent = notification?.contentIntent != null,
                actions = actionItems
            )
        }

        fun fromJson(obj: JSONObject): NotificationItem {
            val textLinesList = mutableListOf<String>()
            val linesArr = obj.optJSONArray("textLines")
            if (linesArr != null) {
                for (i in 0 until linesArr.length()) {
                    textLinesList.add(linesArr.getString(i))
                }
            }

            val actionsList = mutableListOf<NotificationActionItem>()
            val actionsArr = obj.optJSONArray("actions")
            if (actionsArr != null) {
                for (i in 0 until actionsArr.length()) {
                    actionsList.add(NotificationActionItem.fromJson(actionsArr.getJSONObject(i)))
                }
            }

            return NotificationItem(
                key = obj.getString("key"),
                packageName = obj.getString("packageName"),
                id = obj.getInt("id"),
                tag = if (obj.has("tag")) obj.getString("tag") else null,
                postTime = obj.getLong("postTime"),
                isClearable = obj.getBoolean("isClearable"),
                isOngoing = obj.getBoolean("isOngoing"),
                title = obj.optString("title", ""),
                text = obj.optString("text", ""),
                subText = obj.optString("subText", ""),
                bigText = obj.optString("bigText", ""),
                textLines = textLinesList,
                hasContentIntent = obj.optBoolean("hasContentIntent", false),
                actions = actionsList
            )
        }
    }
}
