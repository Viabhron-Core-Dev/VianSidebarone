package com.example.service

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.core.LogKeeper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AppNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.d("AppNotificationListener", "Listener connected")
        LogKeeper.writeLog("Notification", "NotificationListenerService connected")
        sendBroadcast(Intent(ACTION_NOTIFICATION_POSTED))
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        Log.d("AppNotificationListener", "Listener disconnected")
        LogKeeper.writeLog("Notification", "NotificationListenerService disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.let {
            val intent = Intent(ACTION_NOTIFICATION_POSTED).apply {
                putExtra("package", it.packageName)
                setPackage(packageName)
            }
            sendBroadcast(intent)
            
            val extras = it.notification.extras
            val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
            val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
            val packageName = it.packageName
            val isOngoing = it.isOngoing || (it.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0

            // Skip logging internal self-notifications to avoid infinite loop or duplicate clutter
            if (packageName == applicationContext.packageName) return@let

            if (title.isNotBlank() || text.isNotBlank()) {
                val pm = applicationContext.packageManager
                val appName = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
                } catch (e: Exception) {
                    packageName
                }
                
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val dao = com.example.data.AppDatabase.getDatabase(applicationContext).notificationHistoryDao()
                        
                        // Prevent repetitive spam in history for ongoing notifications (e.g. NetSpeed indicator, downloads, music timestamps)
                        // If same package & same title exists and was recorded recently, update in-place instead of creating hundreds of duplicate records
                        val existing = dao.findLatestByPackageAndTitle(packageName, title)
                        val now = System.currentTimeMillis()
                        
                        if (existing != null && (isOngoing || (now - existing.timestamp < 15_000L && existing.title == title))) {
                            // Update existing record in-place
                            dao.update(existing.copy(text = text, timestamp = now))
                        } else {
                            val history = com.example.data.NotificationHistory(
                                packageName = packageName,
                                appName = appName,
                                title = title,
                                text = text,
                                timestamp = now
                            )
                            dao.insert(history)
                        }
                    } catch (e: Exception) {
                        Log.e("AppNotificationListener", "Error inserting/updating notification", e)
                    }
                }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        sbn?.let {
            val intent = Intent(ACTION_NOTIFICATION_REMOVED).apply {
                putExtra("package", it.packageName)
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLEAR_ALL) {
            cancelAllNotifications()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    companion object {
        var instance: AppNotificationListener? = null
            private set
            
        const val ACTION_NOTIFICATION_POSTED = "com.example.ACTION_NOTIFICATION_POSTED"
        const val ACTION_NOTIFICATION_REMOVED = "com.example.ACTION_NOTIFICATION_REMOVED"
        const val ACTION_CLEAR_ALL = "com.example.ACTION_CLEAR_ALL"
    }
}
