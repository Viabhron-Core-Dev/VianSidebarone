package com.example.feature.sidebar

import android.app.Notification
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.text.format.DateUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
import com.example.core.ipc.HeavyProcessHost
import com.example.core.ipc.MainActionType
import com.example.core.ipc.MainCommandRequest
import com.example.feature.notification.NotificationItem
import com.example.service.AppNotificationListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class NotificationPageView(
    context: Context,
    private val onCloseSidebar: () -> Unit,
    private val onHideApp: (String) -> Unit = {},
    private val onHeightChanged: ((Int) -> Unit)? = null
) : FrameLayout(context), SidebarPageControllable {

    var themeBackgroundDrawable: Drawable? = null
        private set

    // Height guard to prevent redundant WindowManager updates and viewport jumps
    private var lastDispatchedHeightPx = 0

    companion object {
        // Static in-memory cache to prevent constant icon reloading and flickering
        private val appIconCache = ConcurrentHashMap<String, Drawable>()
        private val appNameCache = ConcurrentHashMap<String, String>()

        fun getNotificationItemKey(sbn: StatusBarNotification): String {
            return sbn.key ?: "${sbn.packageName}_${sbn.id}_${sbn.tag ?: ""}"
        }

        fun getNotificationItemKey(item: NotificationItem): String {
            return item.key
        }

        fun areNotificationContentsTheSame(oldItem: StatusBarNotification, newItem: StatusBarNotification): Boolean {
            return areNotificationContentsTheSame(
                NotificationItem.fromStatusBarNotification(oldItem),
                NotificationItem.fromStatusBarNotification(newItem)
            )
        }

        fun areNotificationContentsTheSame(oldItem: NotificationItem, newItem: NotificationItem): Boolean {
            if (oldItem.postTime != newItem.postTime) return false
            if (oldItem.isClearable != newItem.isClearable) return false
            if (oldItem.isOngoing != newItem.isOngoing) return false
            if (oldItem.title != newItem.title) return false
            if (oldItem.text != newItem.text) return false
            if (oldItem.subText != newItem.subText) return false
            if (oldItem.bigText != newItem.bigText) return false
            if (oldItem.textLines != newItem.textLines) return false
            if (oldItem.actions.size != newItem.actions.size) return false
            return true
        }
    }

    class NotificationDiffCallback(
        oldInput: List<*>,
        newInput: List<*>
    ) : androidx.recyclerview.widget.DiffUtil.Callback() {

        private val oldList: List<NotificationItem> = oldInput.map { toNotificationItem(it) }
        private val newList: List<NotificationItem> = newInput.map { toNotificationItem(it) }

        companion object {
            private fun toNotificationItem(item: Any?): NotificationItem {
                return when (item) {
                    is NotificationItem -> item
                    is StatusBarNotification -> NotificationItem.fromStatusBarNotification(item)
                    else -> throw IllegalArgumentException("Unsupported notification item: $item")
                }
            }
        }

        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldItem = oldList[oldItemPosition]
            val newItem = newList[newItemPosition]
            return getNotificationItemKey(oldItem) == getNotificationItemKey(newItem)
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldItem = oldList[oldItemPosition]
            val newItem = newList[newItemPosition]
            return areNotificationContentsTheSame(oldItem, newItem)
        }
    }

    private val recyclerView: RecyclerView?
    private val tvEmpty: TextView?
    private val llPermissionBanner: View?
    private val btnClearAll: ImageButton?

    private val adapter = NotificationAdapter()
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var activeNotifications = listOf<NotificationItem>()
    private val expandedItemKeys = mutableSetOf<String>()

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadNotifications()
        }
    }

    init {
        LogKeeper.writeLog("Notification", "Opened Notification mirror page")
        try {
            LayoutInflater.from(context).inflate(R.layout.page_notification, this, true)
        } catch (_: Throwable) {
            // Fallback for mock/test contexts
        }

        recyclerView = findViewById(R.id.recycler_view)
        tvEmpty = findViewById(R.id.tv_empty)
        llPermissionBanner = findViewById(R.id.ll_permission_banner)
        btnClearAll = findViewById(R.id.btn_clear_all)

        recyclerView?.layoutManager = LinearLayoutManager(context)
        recyclerView?.adapter = adapter
        
        findViewById<View>(R.id.btn_grant)?.setOnClickListener {
            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (_: Throwable) {}
            onCloseSidebar()
        }

        btnClearAll?.setOnClickListener {
            clearAllNotifications()
        }

        val hasPermission = checkNotificationPermission()
        llPermissionBanner?.visibility = if (hasPermission) View.GONE else View.VISIBLE
        
        val filter = IntentFilter().apply {
            addAction(AppNotificationListener.ACTION_NOTIFICATION_POSTED)
            addAction(AppNotificationListener.ACTION_NOTIFICATION_REMOVED)
        }
        try {
            context.registerReceiver(notificationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } catch (_: Throwable) {
            try {
                context.registerReceiver(notificationReceiver, filter)
            } catch (_: Throwable) {}
        }

        loadNotifications()
    }

    fun applyThemeBackground(bgColor: Int = 0, isRight: Boolean = true) {
        val finalColor = if (bgColor != 0) {
            bgColor
        } else {
            val prefs = try { context?.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE) } catch (_: Throwable) { null }
            val opacity = prefs?.getFloat("sidebar_transparency", 0.9f) ?: 0.9f
            val alphaInt = (opacity * 255).toInt().coerceIn(0, 255)
            val colorHex = prefs?.getString("sidebar_color", "#1E1E2E") ?: "#1E1E2E"
            val baseColor = try { Color.parseColor(colorHex) } catch (_: Throwable) { 0xFF1E1E2E.toInt() }
            val r = try { Color.red(baseColor) } catch (_: Throwable) { (baseColor shr 16) and 0xFF }
            val g = try { Color.green(baseColor) } catch (_: Throwable) { (baseColor shr 8) and 0xFF }
            val b = try { Color.blue(baseColor) } catch (_: Throwable) { baseColor and 0xFF }
            try { Color.argb(alphaInt, r, g, b) } catch (_: Throwable) { (alphaInt shl 24) or (r shl 16) or (g shl 8) or b }
        }

        val density = try { resources?.displayMetrics?.density ?: 1f } catch (_: Throwable) { 1f }
        val radius = 16f * density
        val bgDrawable = try {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                try { setColor(finalColor) } catch (_: Throwable) {}
                try {
                    cornerRadii = if (isRight) {
                        floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, radius, radius)
                    } else {
                        floatArrayOf(0f, 0f, 0f, 0f, radius, radius, 0f, 0f)
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {
            null
        }
        themeBackgroundDrawable = bgDrawable
        try {
            background = bgDrawable
        } catch (_: Throwable) {}
    }

    override fun onPageSelected() {
        loadNotifications()
    }

    override fun onPageUnselected() {
        adapter.submitList(emptyList())
        activeNotifications = emptyList()
        lastDispatchedHeightPx = 0
    }

    public fun cleanup() {
        try {
            context.unregisterReceiver(notificationReceiver)
        } catch (_: Exception) {}
        adapter.submitList(emptyList())
        activeNotifications = emptyList()
        lastDispatchedHeightPx = 0
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cleanup()
    }

    private fun checkNotificationPermission(): Boolean {
        return try {
            val listeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            listeners != null && listeners.contains(context.packageName)
        } catch (_: Throwable) {
            false
        }
    }

    private fun loadNotifications() {
        if (!checkNotificationPermission()) {
            llPermissionBanner?.visibility = View.VISIBLE
            tvEmpty?.visibility = View.GONE
            btnClearAll?.visibility = View.GONE
            adapter.submitList(emptyList())
            post { updateHeight() }
            return
        }

        llPermissionBanner?.visibility = View.GONE

        // 1. Direct local fallback if running in Main/test process where AppNotificationListener is instantiated
        val localListener = AppNotificationListener.instance
        if (localListener != null) {
            try {
                val prefs = context.getSharedPreferences("NotificationPrefs", Context.MODE_PRIVATE)
                val sidebarHidden = prefs.getStringSet("sidebar_hidden_packages", emptySet()) ?: emptySet()
                val sbns = localListener.activeNotifications
                    .filter { 
                        it.packageName != context.packageName && 
                        !sidebarHidden.contains(it.packageName)
                    }
                    .sortedByDescending { it.postTime }
                activeNotifications = sbns.map { NotificationItem.fromStatusBarNotification(it) }
                adapter.submitList(activeNotifications)
                tvEmpty?.visibility = if (activeNotifications.isEmpty()) View.VISIBLE else View.GONE
                btnClearAll?.visibility = if (activeNotifications.any { it.isClearable }) View.VISIBLE else View.GONE
                post { updateHeight() }
                return
            } catch (e: Exception) {
                LogKeeper.writeLog("Notification", "Error loading active notifications locally: ${e.message}")
            }
        }

        // 2. Heavy-process IPC path to Main
        scope.launch(Dispatchers.IO) {
            try {
                val result = HeavyProcessHost.getInstance(context).requestMainAction(
                    MainCommandRequest(
                        action = MainActionType.CUSTOM,
                        targetId = "NOTIFICATION_ACTION",
                        parameters = mapOf("subAction" to "GET_ACTIVE")
                    )
                )

                if (result.success) {
                    val jsonStr = result.data["notifications"] ?: "[]"
                    val jsonArr = JSONArray(jsonStr)
                    val items = mutableListOf<NotificationItem>()
                    for (i in 0 until jsonArr.length()) {
                        items.add(NotificationItem.fromJson(jsonArr.getJSONObject(i)))
                    }
                    withContext(Dispatchers.Main) {
                        activeNotifications = items
                        adapter.submitList(activeNotifications)
                        tvEmpty?.visibility = if (activeNotifications.isEmpty()) View.VISIBLE else View.GONE
                        btnClearAll?.visibility = if (activeNotifications.any { it.isClearable }) View.VISIBLE else View.GONE
                        post { updateHeight() }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        tvEmpty?.visibility = View.VISIBLE
                        tvEmpty?.text = "Waiting for notification service..."
                        adapter.submitList(emptyList())
                        post { updateHeight() }
                    }
                }
            } catch (e: Exception) {
                LogKeeper.writeLog("Notification", "IPC loadNotifications error: ${e.message}")
            }
        }
    }

    private fun updateHeight() {
        val density = try { resources.displayMetrics.density } catch (_: Throwable) { 1f }
        measure(
            MeasureSpec.makeMeasureSpec(width.takeIf { it > 0 } ?: (330 * density).toInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        val minH = if (activeNotifications.isEmpty()) (100 * density).toInt() else (120 * density).toInt()
        val maxH = (520 * density).toInt()
        val targetH = measuredHeight.coerceIn(minH, maxH)
        if (targetH != lastDispatchedHeightPx) {
            lastDispatchedHeightPx = targetH
            onHeightChanged?.invoke(targetH)
        }
    }

    private fun clearAllNotifications() {
        val localListener = AppNotificationListener.instance
        if (localListener != null) {
            try {
                localListener.cancelAllNotifications()
                loadNotifications()
            } catch (_: Exception) {}
        } else {
            scope.launch(Dispatchers.IO) {
                HeavyProcessHost.getInstance(context).requestMainAction(
                    MainCommandRequest(
                        action = MainActionType.CUSTOM,
                        targetId = "NOTIFICATION_ACTION",
                        parameters = mapOf("subAction" to "CLEAR_ALL")
                    )
                )
                withContext(Dispatchers.Main) {
                    loadNotifications()
                }
            }
        }
    }

    private fun dismissNotification(item: NotificationItem) {
        val localListener = AppNotificationListener.instance
        if (localListener != null) {
            try {
                localListener.cancelNotification(item.key)
            } catch (_: Exception) {
                try {
                    localListener.cancelNotification(item.packageName, item.tag, item.id)
                } catch (_: Exception) {}
            }
        } else {
            scope.launch(Dispatchers.IO) {
                HeavyProcessHost.getInstance(context).requestMainAction(
                    MainCommandRequest(
                        action = MainActionType.CUSTOM,
                        targetId = "NOTIFICATION_ACTION",
                        parameters = mapOf(
                            "subAction" to "DISMISS",
                            "key" to item.key,
                            "package" to item.packageName,
                            "tag" to (item.tag ?: ""),
                            "id" to item.id.toString()
                        )
                    )
                )
            }
        }
    }

    private fun openNotification(item: NotificationItem) {
        val localListener = AppNotificationListener.instance
        if (localListener != null) {
            val sbn = localListener.activeNotifications.find {
                (it.key ?: "${it.packageName}_${it.id}_${it.tag ?: ""}") == item.key
            }
            try {
                if (sbn?.notification?.contentIntent != null) {
                    sbn.notification.contentIntent.send()
                } else {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(item.packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                    }
                }
            } catch (_: Exception) {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(item.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                }
            }
            onCloseSidebar()
        } else {
            scope.launch(Dispatchers.IO) {
                HeavyProcessHost.getInstance(context).requestMainAction(
                    MainCommandRequest(
                        action = MainActionType.CUSTOM,
                        targetId = "NOTIFICATION_ACTION",
                        parameters = mapOf(
                            "subAction" to "OPEN",
                            "key" to item.key,
                            "package" to item.packageName
                        )
                    )
                )
            }
        }
    }

    private fun triggerNotificationAction(item: NotificationItem, actionIdx: Int, replyText: String? = null) {
        val localListener = AppNotificationListener.instance
        if (localListener != null) {
            val sbn = localListener.activeNotifications.find {
                (it.key ?: "${it.packageName}_${it.id}_${it.tag ?: ""}") == item.key
            }
            val action = sbn?.notification?.actions?.getOrNull(actionIdx)
            if (action != null) {
                try {
                    if (!replyText.isNullOrBlank() && !action.remoteInputs.isNullOrEmpty()) {
                        val replyIntent = Intent()
                        val bundle = Bundle()
                        for (ri in action.remoteInputs) {
                            bundle.putCharSequence(ri.resultKey, replyText)
                        }
                        RemoteInput.addResultsToIntent(action.remoteInputs, replyIntent, bundle)
                        action.actionIntent.send(context, 0, replyIntent)
                    } else {
                        action.actionIntent.send()
                    }
                } catch (e: Exception) {
                    LogKeeper.writeLog("Notification", "Local action failed: ${e.message}")
                }
            }
        } else {
            scope.launch(Dispatchers.IO) {
                val params = mutableMapOf(
                    "subAction" to "TRIGGER_ACTION",
                    "key" to item.key,
                    "actionIndex" to actionIdx.toString()
                )
                if (!replyText.isNullOrBlank()) {
                    params["replyText"] = replyText
                }
                HeavyProcessHost.getInstance(context).requestMainAction(
                    MainCommandRequest(
                        action = MainActionType.CUSTOM,
                        targetId = "NOTIFICATION_ACTION",
                        parameters = params
                    )
                )
            }
        }
    }

    // Touch handling to support horizontal Sidebar page swiping out of this Heavy page
    private var downX = 0f
    private var downY = 0f
    private var isHorizontalSwipeConsumed = false
    private val touchSlop by lazy {
        try { ViewConfiguration.get(context).scaledTouchSlop } catch (_: Throwable) { 16 }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                isHorizontalSwipeConsumed = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (!isHorizontalSwipeConsumed && abs(dx) > touchSlop * 2 && abs(dx) > abs(dy) * 1.5f) {
                    isHorizontalSwipeConsumed = true
                    val delta = if (dx < 0) 1 else -1
                    HeavyProcessHost.getInstance(context).requestMainAction(
                        MainCommandRequest(
                            action = MainActionType.CUSTOM,
                            targetId = "NAVIGATE_PAGE",
                            parameters = mapOf("delta" to delta.toString())
                        )
                    )
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isHorizontalSwipeConsumed = false
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private inner class NotificationAdapter : RecyclerView.Adapter<NotificationAdapter.ViewHolder>() {
        private var list = emptyList<NotificationItem>()

        fun submitList(newList: List<NotificationItem>) {
            val diffResult = androidx.recyclerview.widget.DiffUtil.calculateDiff(
                NotificationDiffCallback(list, newList)
            )
            list = newList
            diffResult.dispatchUpdatesTo(this)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_notification_row, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            val itemKey = item.key

            val title = item.title
            val text = item.text
            val subText = item.subText
            val bigText = item.bigText
            val textLines = item.textLines

            val detailedLines = if (textLines.isNotEmpty()) {
                textLines.joinToString("\n")
            } else if (bigText.isNotBlank() && bigText != text) {
                bigText
            } else if (subText.isNotBlank() && subText != text) {
                subText
            } else {
                ""
            }

            val actions = item.actions
            val hasActions = actions.isNotEmpty()
            val isExpandable = detailedLines.isNotBlank() || hasActions || text.length > 50

            val isExpanded = expandedItemKeys.contains(itemKey)

            holder.tvTitle.text = if (title.isNotBlank()) title else item.packageName
            holder.tvText.text = if (text.isNotBlank()) text else (if (detailedLines.isNotBlank()) detailedLines else subText)
            holder.tvText.visibility = if (holder.tvText.text.isNotBlank()) View.VISIBLE else View.GONE
            
            val timeString = DateUtils.getRelativeTimeSpanString(
                item.postTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
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

                    actions.forEach { action ->
                        val actionTitle = action.title
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
                                if (action.hasRemoteInputs) {
                                    // Direct Reply Action (e.g. WhatsApp Reply)
                                    holder.llInlineReply.visibility = View.VISIBLE
                                    holder.etReply.hint = "Reply to $title..."
                                    holder.etReply.requestFocus()
                                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                                    imm?.showSoftInput(holder.etReply, InputMethodManager.SHOW_IMPLICIT)

                                    val sendAction = {
                                        val replyContent = holder.etReply.text.toString().trim()
                                        if (replyContent.isNotBlank()) {
                                            triggerNotificationAction(item, action.actionIndex, replyContent)
                                            Toast.makeText(context, "Replied: $replyContent", Toast.LENGTH_SHORT).show()
                                            holder.etReply.setText("")
                                            holder.llInlineReply.visibility = View.GONE
                                            imm?.hideSoftInputFromWindow(holder.etReply.windowToken, 0)
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
                                    triggerNotificationAction(item, action.actionIndex)
                                    Toast.makeText(context, actionTitle, Toast.LENGTH_SHORT).show()
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
            if (item.isClearable) {
                holder.btnDismiss.visibility = View.VISIBLE
                holder.btnDismiss.setOnClickListener {
                    dismissNotification(item)
                }
            } else {
                holder.btnDismiss.visibility = View.GONE
            }

            // Static App Icon & Name Cache Check (avoids continuous icon fetching and flickering)
            val cachedName = appNameCache[item.packageName]
            val cachedIcon = appIconCache[item.packageName]

            if (cachedName != null && cachedIcon != null) {
                holder.tvAppName.text = cachedName
                holder.ivIcon.setImageDrawable(cachedIcon)
            } else {
                holder.tvAppName.text = cachedName ?: item.packageName
                
                scope.launch(Dispatchers.IO) {
                    try {
                        val appInfo = context.packageManager.getApplicationInfo(item.packageName, 0)
                        val appName = context.packageManager.getApplicationLabel(appInfo).toString()
                        val icon = context.packageManager.getApplicationIcon(appInfo)
                        appNameCache[item.packageName] = appName
                        appIconCache[item.packageName] = icon
                        
                        withContext(Dispatchers.Main) {
                            if (holder.bindingAdapterPosition == position) {
                                holder.tvAppName.text = appName
                                holder.ivIcon.setImageDrawable(icon)
                            }
                        }
                    } catch (_: Exception) {
                        appNameCache[item.packageName] = item.packageName
                        withContext(Dispatchers.Main) {
                            if (holder.bindingAdapterPosition == position) {
                                holder.tvAppName.text = item.packageName
                            }
                        }
                    }
                }
            }

            // Tap row -> Take directly to chat / target activity & dismiss sidebar
            holder.itemView.setOnClickListener {
                openNotification(item)
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
