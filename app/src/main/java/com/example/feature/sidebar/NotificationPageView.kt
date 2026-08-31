package com.example.feature.sidebar

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.text.format.DateUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.core.LogKeeper
import com.example.service.AppNotificationListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class NotificationPageView(
    context: Context,
    private val onCloseSidebar: () -> Unit,
    private val onHideApp: (String) -> Unit,
    private val onHeightChanged: ((Int) -> Unit)? = null
) : FrameLayout(context), SidebarPageControllable {

    companion object {
        // Static in-memory cache to prevent constant icon reloading and flickering
        private val appIconCache = ConcurrentHashMap<String, Drawable>()
        private val appNameCache = ConcurrentHashMap<String, String>()
    }

    private val recyclerView: RecyclerView
    private val tvEmpty: TextView
    private val llPermissionBanner: View
    private val btnClearAll: ImageButton

    private val adapter = NotificationAdapter()
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var activeNotifications = listOf<StatusBarNotification>()
    private val expandedItemKeys = mutableSetOf<String>()

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadNotifications()
        }
    }

    init {
        LogKeeper.writeLog("Notification", "Opened Notification mirror page")
        LayoutInflater.from(context).inflate(R.layout.page_notification, this, true)

        recyclerView = findViewById(R.id.recycler_view)
        tvEmpty = findViewById(R.id.tv_empty)
        llPermissionBanner = findViewById(R.id.ll_permission_banner)
        btnClearAll = findViewById(R.id.btn_clear_all)

        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter
        
        findViewById<View>(R.id.btn_grant).setOnClickListener {
            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            onCloseSidebar()
        }

        btnClearAll.setOnClickListener {
            AppNotificationListener.instance?.let { listener ->
                try {
                    listener.cancelAllNotifications()
                    LogKeeper.writeLog("Notification", "Cleared all dismissible notifications")
                    loadNotifications()
                } catch (e: Exception) {
                    LogKeeper.writeLog("Notification", "Failed to clear all notifications: ${e.message}")
                }
            }
        }

        val hasPermission = checkNotificationPermission()
        llPermissionBanner.visibility = if (hasPermission) View.GONE else View.VISIBLE
        
        val filter = IntentFilter().apply {
            addAction(AppNotificationListener.ACTION_NOTIFICATION_POSTED)
            addAction(AppNotificationListener.ACTION_NOTIFICATION_REMOVED)
        }
        context.registerReceiver(notificationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        loadNotifications()
    }

    override fun onPageSelected() {
        loadNotifications()
    }

    override fun onPageUnselected() {
        adapter.submitList(emptyList())
        activeNotifications = emptyList()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        try {
            context.unregisterReceiver(notificationReceiver)
        } catch (e: Exception) {}
        adapter.submitList(emptyList())
        activeNotifications = emptyList()
    }

    private fun checkNotificationPermission(): Boolean {
        val listeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        return listeners != null && listeners.contains(context.packageName)
    }

    private fun loadNotifications() {
        if (!checkNotificationPermission()) {
            llPermissionBanner.visibility = View.VISIBLE
            tvEmpty.visibility = View.GONE
            btnClearAll.visibility = View.GONE
            adapter.submitList(emptyList())
            post {
                measure(
                    MeasureSpec.makeMeasureSpec(width.takeIf { it > 0 } ?: (330 * resources.displayMetrics.density).toInt(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                )
                val density = resources.displayMetrics.density
                val targetH = if (measuredHeight > 0) measuredHeight else (160 * density).toInt()
                onHeightChanged?.invoke(targetH)
            }
            return
        }

        llPermissionBanner.visibility = View.GONE
        val listener = AppNotificationListener.instance

        if (listener != null) {
            try {
                val prefs = context.getSharedPreferences("NotificationPrefs", Context.MODE_PRIVATE)
                val sidebarHidden = prefs.getStringSet("sidebar_hidden_packages", emptySet()) ?: emptySet()

                // Mirror all live notifications in Android notification bar, excluding self app and user-blocked packages
                val sbns = listener.activeNotifications
                    .filter { 
                        it.packageName != context.packageName && 
                        !sidebarHidden.contains(it.packageName)
                    }
                    .sortedByDescending { it.postTime }

                activeNotifications = sbns
                adapter.submitList(activeNotifications)
                tvEmpty.visibility = if (activeNotifications.isEmpty()) View.VISIBLE else View.GONE
                btnClearAll.visibility = if (activeNotifications.any { it.isClearable }) View.VISIBLE else View.GONE

                post {
                    measure(
                        MeasureSpec.makeMeasureSpec(width.takeIf { it > 0 } ?: (330 * resources.displayMetrics.density).toInt(), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                    )
                    val density = resources.displayMetrics.density
                    val minH = if (activeNotifications.isEmpty()) (100 * density).toInt() else (120 * density).toInt()
                    val maxH = (520 * density).toInt()
                    val targetH = measuredHeight.coerceIn(minH, maxH)
                    onHeightChanged?.invoke(targetH)
                }
            } catch (e: Exception) {
                LogKeeper.writeLog("Notification", "Error loading active notifications: ${e.message}")
            }
        } else {
            // Listener instance might be initializing; prompt permission / show empty
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = "Waiting for notification service..."
            adapter.submitList(emptyList())
            post {
                measure(
                    MeasureSpec.makeMeasureSpec(width.takeIf { it > 0 } ?: (330 * resources.displayMetrics.density).toInt(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                )
                val density = resources.displayMetrics.density
                val targetH = measuredHeight.coerceIn((100 * density).toInt(), (520 * density).toInt())
                onHeightChanged?.invoke(targetH)
            }
        }
    }

    private inner class NotificationAdapter : RecyclerView.Adapter<NotificationAdapter.ViewHolder>() {
        private var list = emptyList<StatusBarNotification>()

        fun submitList(newList: List<StatusBarNotification>) {
            list = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_notification_row, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val sbn = list[position]
            val notification = sbn.notification
            val extras = notification.extras
            val itemKey = sbn.key ?: "${sbn.packageName}_${sbn.id}"

            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim() ?: ""
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim() ?: ""
            val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)

            val detailedLines = if (!textLines.isNullOrEmpty()) {
                textLines.filterNotNull().map { it.toString().trim() }.filter { it.isNotBlank() }.joinToString("\n")
            } else if (bigText.isNotBlank() && bigText != text) {
                bigText
            } else if (subText.isNotBlank() && subText != text) {
                subText
            } else {
                ""
            }

            val actions = notification.actions
            val hasActions = !actions.isNullOrEmpty()
            val isExpandable = detailedLines.isNotBlank() || hasActions || text.length > 50

            val isExpanded = expandedItemKeys.contains(itemKey)

            holder.tvTitle.text = if (title.isNotBlank()) title else sbn.packageName
            holder.tvText.text = if (text.isNotBlank()) text else (if (detailedLines.isNotBlank()) detailedLines else subText)
            holder.tvText.visibility = if (holder.tvText.text.isNotBlank()) View.VISIBLE else View.GONE
            
            val timeString = DateUtils.getRelativeTimeSpanString(
                sbn.postTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            ).toString()
            holder.tvTime.text = timeString

            // Fold / Unfold configuration
            if (isExpandable) {
                holder.btnExpand.visibility = View.VISIBLE
                holder.btnExpand.text = if (isExpanded) "▲" else "▼"
                holder.btnExpand.setOnClickListener {
                    if (expandedItemKeys.contains(itemKey)) {
                        expandedItemKeys.remove(itemKey)
                    } else {
                        expandedItemKeys.add(itemKey)
                    }
                    notifyItemChanged(holder.bindingAdapterPosition)
                }
            } else {
                holder.btnExpand.visibility = View.GONE
            }

            if (isExpanded) {
                holder.tvTitle.maxLines = 10
                holder.tvText.maxLines = 15
                if (detailedLines.isNotBlank() && detailedLines != holder.tvText.text) {
                    holder.tvExpandedDetails.text = detailedLines
                    holder.tvExpandedDetails.visibility = View.VISIBLE
                } else {
                    holder.tvExpandedDetails.visibility = View.GONE
                }

                // Render Notification Actions
                if (hasActions) {
                    holder.hsvActions.visibility = View.VISIBLE
                    holder.llActions.removeAllViews()
                    val density = context.resources.displayMetrics.density

                    actions?.forEach { action ->
                        val actionTitle = action.title?.toString()?.trim() ?: "Action"
                        val btnAction = TextView(context).apply {
                            this.text = actionTitle
                            textSize = 11f
                            setTextColor(Color.WHITE)
                            gravity = Gravity.CENTER
                            background = ContextCompat.getDrawable(context, R.drawable.bg_notification_action_chip)
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                (30 * density).toInt()
                            ).apply {
                                marginEnd = (6 * density).toInt()
                            }
                            isClickable = true
                            isFocusable = true

                            setOnClickListener {
                                val remoteInputs = action.remoteInputs
                                if (!remoteInputs.isNullOrEmpty()) {
                                    // Direct Reply Action (e.g. WhatsApp Reply)
                                    holder.llInlineReply.visibility = View.VISIBLE
                                    holder.etReply.hint = "Reply to $title..."
                                    holder.etReply.requestFocus()
                                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                                    imm?.showSoftInput(holder.etReply, InputMethodManager.SHOW_IMPLICIT)

                                    val sendAction = {
                                        val replyContent = holder.etReply.text.toString().trim()
                                        if (replyContent.isNotBlank()) {
                                            try {
                                                val replyIntent = Intent()
                                                val bundle = Bundle()
                                                for (ri in remoteInputs) {
                                                    bundle.putCharSequence(ri.resultKey, replyContent)
                                                }
                                                RemoteInput.addResultsToIntent(remoteInputs, replyIntent, bundle)
                                                action.actionIntent.send(context, 0, replyIntent)
                                                Toast.makeText(context, "Replied: $replyContent", Toast.LENGTH_SHORT).show()
                                                LogKeeper.writeLog("Notification", "Sent reply to ${sbn.packageName}")
                                                holder.etReply.setText("")
                                                holder.llInlineReply.visibility = View.GONE
                                                imm?.hideSoftInputFromWindow(holder.etReply.windowToken, 0)
                                            } catch (e: Exception) {
                                                LogKeeper.writeLog("Notification", "Failed to send reply: ${e.message}")
                                                Toast.makeText(context, "Reply failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }

                                    holder.btnSendReply.setOnClickListener { sendAction() }
                                    holder.etReply.setOnEditorActionListener { _, actionId, _ ->
                                        if (actionId == EditorInfo.IME_ACTION_SEND) {
                                            sendAction()
                                            true
                                        } else false
                                    }
                                    holder.btnCancelReply.setOnClickListener {
                                        holder.llInlineReply.visibility = View.GONE
                                        imm?.hideSoftInputFromWindow(holder.etReply.windowToken, 0)
                                    }
                                } else {
                                    // Tap Action (e.g. "Mark as read", "Archive", "Delete", "Mute", etc.)
                                    try {
                                        action.actionIntent.send()
                                        LogKeeper.writeLog("Notification", "Triggered action '$actionTitle' on ${sbn.packageName}")
                                        Toast.makeText(context, "$actionTitle", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        LogKeeper.writeLog("Notification", "Failed action '$actionTitle': ${e.message}")
                                    }
                                }
                            }
                        }
                        holder.llActions.addView(btnAction)
                    }
                } else {
                    holder.hsvActions.visibility = View.GONE
                    holder.llInlineReply.visibility = View.GONE
                }
            } else {
                holder.tvTitle.maxLines = 1
                holder.tvText.maxLines = 2
                holder.tvExpandedDetails.visibility = View.GONE
                holder.hsvActions.visibility = View.GONE
                holder.llInlineReply.visibility = View.GONE
            }

            // Dismiss button (only shown if notification is clearable)
            if (sbn.isClearable) {
                holder.btnDismiss.visibility = View.VISIBLE
                holder.btnDismiss.setOnClickListener {
                    try {
                        AppNotificationListener.instance?.cancelNotification(sbn.key)
                        LogKeeper.writeLog("Notification", "Dismissed notification: ${sbn.packageName}")
                    } catch (e: Exception) {
                        try {
                            AppNotificationListener.instance?.cancelNotification(sbn.packageName, sbn.tag, sbn.id)
                        } catch (e2: Exception) {}
                    }
                }
            } else {
                holder.btnDismiss.visibility = View.GONE
            }

            // Static App Icon & Name Cache Check (avoids continuous icon fetching and flickering)
            val cachedName = appNameCache[sbn.packageName]
            val cachedIcon = appIconCache[sbn.packageName]

            if (cachedName != null && cachedIcon != null) {
                holder.tvAppName.text = cachedName
                holder.ivIcon.setImageDrawable(cachedIcon)
            } else {
                holder.tvAppName.text = cachedName ?: sbn.packageName
                
                scope.launch(Dispatchers.IO) {
                    try {
                        val appInfo = context.packageManager.getApplicationInfo(sbn.packageName, 0)
                        val appName = context.packageManager.getApplicationLabel(appInfo).toString()
                        val icon = context.packageManager.getApplicationIcon(appInfo)
                        appNameCache[sbn.packageName] = appName
                        appIconCache[sbn.packageName] = icon
                        
                        withContext(Dispatchers.Main) {
                            if (holder.bindingAdapterPosition == position) {
                                holder.tvAppName.text = appName
                                holder.ivIcon.setImageDrawable(icon)
                            }
                        }
                    } catch (e: Exception) {
                        appNameCache[sbn.packageName] = sbn.packageName
                        withContext(Dispatchers.Main) {
                            if (holder.bindingAdapterPosition == position) {
                                holder.tvAppName.text = sbn.packageName
                            }
                        }
                    }
                }
            }

            // Tap row -> Take directly to chat / target activity & dismiss sidebar
            holder.itemView.setOnClickListener {
                try {
                    if (notification.contentIntent != null) {
                        notification.contentIntent.send()
                    } else {
                        val launchIntent = context.packageManager.getLaunchIntentForPackage(sbn.packageName)
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(launchIntent)
                        }
                    }
                    LogKeeper.writeLog("Notification", "Opened notification chat/activity for: ${sbn.packageName}")
                    onCloseSidebar()
                } catch (e: Exception) {
                    LogKeeper.writeLog("Notification", "Failed to launch notification intent: ${e.message}")
                    try {
                        val launchIntent = context.packageManager.getLaunchIntentForPackage(sbn.packageName)
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(launchIntent)
                            onCloseSidebar()
                        }
                    } catch (e2: Exception) {}
                }
            }

            // Long press row -> toggle fold / unfold
            holder.itemView.setOnLongClickListener {
                if (isExpandable) {
                    if (expandedItemKeys.contains(itemKey)) {
                        expandedItemKeys.remove(itemKey)
                    } else {
                        expandedItemKeys.add(itemKey)
                    }
                    notifyItemChanged(holder.bindingAdapterPosition)
                    true
                } else false
            }
        }

        override fun getItemCount() = list.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val ivIcon: ImageView = itemView.findViewById(R.id.iv_icon)
            val tvAppName: TextView = itemView.findViewById(R.id.tv_app_name)
            val tvTime: TextView = itemView.findViewById(R.id.tv_time)
            val btnExpand: TextView = itemView.findViewById(R.id.btn_expand)
            val btnDismiss: ImageButton = itemView.findViewById(R.id.btn_dismiss)
            val tvTitle: TextView = itemView.findViewById(R.id.tv_title)
            val tvText: TextView = itemView.findViewById(R.id.tv_text)
            val tvExpandedDetails: TextView = itemView.findViewById(R.id.tv_expanded_details)
            val hsvActions: HorizontalScrollView = itemView.findViewById(R.id.hsv_actions)
            val llActions: LinearLayout = itemView.findViewById(R.id.ll_actions)
            val llInlineReply: LinearLayout = itemView.findViewById(R.id.ll_inline_reply)
            val etReply: EditText = itemView.findViewById(R.id.et_reply)
            val btnSendReply: TextView = itemView.findViewById(R.id.btn_send_reply)
            val btnCancelReply: ImageButton = itemView.findViewById(R.id.btn_cancel_reply)
        }
    }
}
