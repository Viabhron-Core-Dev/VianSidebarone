package com.example

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.LogKeeper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogKeeperActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                LogKeeperScreen(onBack = { finish() })
            }
        }
    }
}

// Data models for the on-demand Heavy process Running tab
data class ActiveProcessItem(
    val name: String,
    val isMain: Boolean,
    val isHeavy: Boolean,
    val pid: Int,
    val importance: String,
    val isRunning: Boolean
)

data class ActiveServiceItem(
    val shortName: String,
    val fullName: String,
    val processName: String,
    val isMainProcess: Boolean,
    val isForeground: Boolean,
    val started: Boolean,
    val activeSinceMs: Long,
    val startTimeFormatted: String,
    val durationFormatted: String
)

data class LifecycleEventItem(
    val timestamp: String,
    val process: String,
    val isMainProcess: Boolean,
    val thread: String,
    val component: String,
    val eventType: String,
    val message: String,
    val durationText: String? = null
)

data class RunningRuntimeState(
    val processes: List<ActiveProcessItem> = emptyList(),
    val activeServices: List<ActiveServiceItem> = emptyList(),
    val lifecycleEvents: List<LifecycleEventItem> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogKeeperScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Diagnostic, 1: Crash, 2: Running
    var diagnosticLogText by remember { mutableStateOf("Loading diagnostic logs...") }
    var crashLogText by remember { mutableStateOf("Loading crash logs...") }
    var runningState by remember { mutableStateOf(RunningRuntimeState()) }
    var showClearDialog by remember { mutableStateOf(false) }

    fun loadLogs() {
        scope.launch {
            val diag = withContext(Dispatchers.IO) { LogKeeper.getDiagnosticLogs(context) }
            val crash = withContext(Dispatchers.IO) { LogKeeper.getCrashLogs(context) }
            val running = withContext(Dispatchers.IO) { loadRunningState(context, diag) }
            diagnosticLogText = diag
            crashLogText = crash
            runningState = running
        }
    }

    LaunchedEffect(Unit) {
        loadLogs()
    }

    val currentLogContent = when (selectedTab) {
        0 -> diagnosticLogText
        1 -> crashLogText
        else -> formatRunningSummary(runningState)
    }

    val currentSubTitle = when (selectedTab) {
        0 -> LogKeeper.FILE_DIAGNOSTIC_LOG
        1 -> LogKeeper.FILE_CRASH_LOG
        else -> "Runtime & Lifecycle Diagnostics"
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Log Keeper", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Text(
                            text = currentSubTitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("log_keeper_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { loadLogs() }, modifier = Modifier.testTag("log_keeper_refresh_button")) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Vian-Sidebar $currentSubTitle")
                                putExtra(Intent.EXTRA_TEXT, currentLogContent)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share Logs"))
                        },
                        modifier = Modifier.testTag("log_keeper_share_button")
                    ) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = "Share")
                    }
                    IconButton(
                        onClick = { showClearDialog = true },
                        modifier = Modifier.testTag("log_keeper_clear_button")
                    ) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Clear Logs")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("log_keeper_screen")
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Diagnostic", fontWeight = FontWeight.Medium) },
                    modifier = Modifier.testTag("tab_diagnostic_logs")
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Crash Reports", fontWeight = FontWeight.Medium) },
                    modifier = Modifier.testTag("tab_crash_logs")
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Running", fontWeight = FontWeight.Medium) },
                    modifier = Modifier.testTag("tab_running")
                )
            }

            if (selectedTab == 2) {
                // Running Tab UI (Processes, Active Services, Lifecycle Events)
                RunningTabContent(
                    state = runningState,
                    onCopySummary = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("LogKeeper_Running", formatRunningSummary(runningState))
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Copied runtime summary to clipboard", Toast.LENGTH_SHORT).show()
                    }
                )
            } else {
                // Diagnostic & Crash Log Text View
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Zero-PII Local Logging",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("LogKeeper", currentLogContent)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Copied logs to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.testTag("copy_logs_button")
                    ) {
                        Text("Copy Logs", fontSize = 12.sp)
                    }
                }

                Card(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    val verticalScroll = rememberScrollState()
                    val horizontalScroll = rememberScrollState()

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                    ) {
                        SelectionContainer {
                            Text(
                                text = currentLogContent,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(verticalScroll)
                                    .horizontalScroll(horizontalScroll)
                                    .testTag("log_text_content")
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    if (showClearDialog) {
        val clearTitle = if (selectedTab == 1) "Clear Crash Reports?" else "Clear Diagnostic Logs?"
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(clearTitle) },
            text = { Text("This will permanently delete the recorded entries in this log file.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                if (selectedTab == 1) {
                                    LogKeeper.clearCrashLogs(context)
                                } else {
                                    LogKeeper.clearDiagnosticLogs(context)
                                }
                            }
                            loadLogs()
                            Toast.makeText(context, "Cleared logs", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.testTag("confirm_clear_logs_button")
                ) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun RunningTabContent(
    state: RunningRuntimeState,
    onCopySummary: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("running_tab_content")
    ) {
        // Sub-toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Runtime Processes & Services",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            OutlinedButton(
                onClick = onCopySummary,
                modifier = Modifier.testTag("copy_running_summary_button")
            ) {
                Text("Copy Summary", fontSize = 12.sp)
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Section 1: Process Status
            item {
                Text(
                    text = "App Processes",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                )
            }

            items(state.processes) { proc ->
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (proc.isRunning) Color(0xFF2E7D32) else Color(0xFF757575),
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .height(10.dp)
                                        .width(10.dp)
                                ) {}
                                Text(
                                    text = proc.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (proc.isMain) "Resident Daemon (com.example)" else "On-Demand UI (com.example:heavy)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            StatusPill(
                                text = if (proc.isRunning) "RUNNING" else "IDLE",
                                containerColor = if (proc.isRunning) Color(0xFFE8F5E9) else Color(0xFFF5F5F5),
                                contentColor = if (proc.isRunning) Color(0xFF2E7D32) else Color(0xFF757575)
                            )
                            if (proc.isRunning) {
                                Text(
                                    text = "PID ${proc.pid} • ${proc.importance}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }

            // Section 2: Active Services
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Active Services (${state.activeServices.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }

            if (state.activeServices.isEmpty()) {
                item {
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "No active background services running.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            } else {
                items(state.activeServices) { service ->
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = service.shortName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                StatusPill(
                                    text = if (service.isMainProcess) "Main Process" else "Heavy Process",
                                    containerColor = if (service.isMainProcess) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = if (service.isMainProcess) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                StatusPill(
                                    text = if (service.isForeground) "Foreground" else "Background",
                                    containerColor = if (service.isForeground) Color(0xFFE3F2FD) else Color(0xFFF5F5F5),
                                    contentColor = if (service.isForeground) Color(0xFF1565C0) else Color(0xFF616161)
                                )
                                TimePill(text = "Started: ${service.startTimeFormatted}")
                                TimePill(text = "Uptime: ${service.durationFormatted}")
                            }
                        }
                    }
                }
            }

            // Section 3: Lifecycle History
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Lifecycle & Runtime History (${state.lifecycleEvents.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }

            if (state.lifecycleEvents.isEmpty()) {
                item {
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "No lifecycle events recorded yet in diagnostic logs.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            } else {
                items(state.lifecycleEvents) { event ->
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = event.component,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    StatusPill(
                                        text = event.process,
                                        containerColor = if (event.isMainProcess) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
                                        contentColor = if (event.isMainProcess) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                    val (bg, fg) = getEventTypeColors(event.eventType)
                                    StatusPill(
                                        text = event.eventType,
                                        containerColor = bg,
                                        contentColor = fg
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TimePill(text = event.timestamp)
                                if (event.durationText != null) {
                                    StatusPill(
                                        text = "Duration: ${event.durationText}",
                                        containerColor = Color(0xFFFFF8E1),
                                        contentColor = Color(0xFFF57F17)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = event.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                softWrap = true,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun StatusPill(
    text: String,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = containerColor
    ) {
        Text(
            text = text,
            color = contentColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun TimePill(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

fun getEventTypeColors(eventType: String): Pair<Color, Color> {
    return when (eventType) {
        "STARTED" -> Pair(Color(0xFFE8F5E9), Color(0xFF2E7D32))
        "STOPPED" -> Pair(Color(0xFFFFF3E0), Color(0xFFE65100))
        "DESTROYED" -> Pair(Color(0xFFFFEBEE), Color(0xFFC62828))
        "CONNECTED" -> Pair(Color(0xFFE3F2FD), Color(0xFF1565C0))
        "DISCONNECTED" -> Pair(Color(0xFFF5F5F5), Color(0xFF616161))
        "RESTARTED" -> Pair(Color(0xFFEDE7F6), Color(0xFF512DA8))
        "CRASH", "ERROR" -> Pair(Color(0xFFFFEBEE), Color(0xFFC62828))
        else -> Pair(Color(0xFFF5F5F5), Color(0xFF424242))
    }
}

fun loadRunningState(context: Context, diagnosticLogs: String): RunningRuntimeState {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    // 1. Check Processes
    val runningProcesses = am?.runningAppProcesses ?: emptyList()
    val mainProc = runningProcesses.firstOrNull { it.processName == context.packageName }
    val heavyProc = runningProcesses.firstOrNull { it.processName == "${context.packageName}:heavy" }

    val processesList = listOf(
        ActiveProcessItem(
            name = "Main Process",
            isMain = true,
            isHeavy = false,
            pid = mainProc?.pid ?: 0,
            importance = mainProc?.let { formatImportance(it.importance) } ?: "Not Running",
            isRunning = mainProc != null
        ),
        ActiveProcessItem(
            name = "Heavy Process",
            isMain = false,
            isHeavy = true,
            pid = heavyProc?.pid ?: 0,
            importance = heavyProc?.let { formatImportance(it.importance) } ?: "Stopped / Idle",
            isRunning = heavyProc != null
        )
    )

    // 2. Check Active Services
    val activeServicesList = mutableListOf<ActiveServiceItem>()
    try {
        val services = am?.getRunningServices(Int.MAX_VALUE) ?: emptyList()
        val appServices = services.filter { it.service.packageName == context.packageName }
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

        for (s in appServices) {
            val shortName = s.service.shortClassName.removePrefix(".")
            val isMain = !s.process.endsWith(":heavy")
            val uptimeMs = if (s.activeSince > 0) (nowElapsed - s.activeSince).coerceAtLeast(0L) else 0L
            val startWallMs = if (uptimeMs > 0) nowWall - uptimeMs else 0L
            val startTimeStr = if (startWallMs > 0) timeFormat.format(Date(startWallMs)) else "Active"
            val durationStr = if (uptimeMs > 0) formatDuration(uptimeMs) else "< 1s"

            activeServicesList.add(
                ActiveServiceItem(
                    shortName = shortName,
                    fullName = s.service.className,
                    processName = s.process,
                    isMainProcess = isMain,
                    isForeground = s.foreground,
                    started = s.started,
                    activeSinceMs = s.activeSince,
                    startTimeFormatted = startTimeStr,
                    durationFormatted = durationStr
                )
            )
        }
    } catch (_: Exception) {}

    // 3. Parse Lifecycle Events from diagnostic log
    val eventsList = mutableListOf<LifecycleEventItem>()
    val lineRegex = Regex("""^\[(.*?)\]\s+\[(.*?)\]\s+\[(.*?)\]\s+\[(.*?)\]\s+(.*)$""")
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    val lastStartTimes = mutableMapOf<String, Long>()

    val lines = diagnosticLogs.lines()
    for (line in lines) {
        if (line.isBlank() || line.startsWith("[... logs truncated ...]")) continue
        val match = lineRegex.find(line.trim()) ?: continue
        val timestamp = match.groupValues[1]
        val process = match.groupValues[2]
        val thread = match.groupValues[3]
        val rawComponent = match.groupValues[4]
        val message = match.groupValues[5]

        val isMain = !process.endsWith(":heavy")
        val isCrash = rawComponent == "CRASH" || message.contains("[CRASH]")
        val isError = rawComponent.startsWith("ERROR") || message.contains("[ERROR")

        var component = rawComponent
        if (component.startsWith("ERROR:")) {
            component = component.removePrefix("ERROR:")
        }

        val eventType: String
        var durationText: String? = null
        val eventTime = try { sdf.parse(timestamp)?.time } catch (_: Exception) { null }

        when {
            isCrash -> eventType = "CRASH"
            isError -> eventType = "ERROR"
            component == "Lifecycle" -> {
                when {
                    message.contains("started", ignoreCase = true) -> {
                        eventType = "STARTED"
                        val compName = message.substringAfter("started:").trim()
                        if (eventTime != null && compName.isNotEmpty()) {
                            lastStartTimes[compName] = eventTime
                        }
                    }
                    message.contains("stopped", ignoreCase = true) -> {
                        eventType = "STOPPED"
                        val compName = message.substringAfter("stopped:").trim()
                        if (eventTime != null && compName.isNotEmpty()) {
                            val start = lastStartTimes[compName]
                            if (start != null && eventTime >= start) {
                                durationText = formatDuration(eventTime - start)
                            }
                        }
                    }
                    message.contains("destroyed", ignoreCase = true) -> {
                        eventType = "DESTROYED"
                        val compName = message.substringAfter("destroyed:").trim()
                        if (eventTime != null && compName.isNotEmpty()) {
                            val start = lastStartTimes[compName]
                            if (start != null && eventTime >= start) {
                                durationText = formatDuration(eventTime - start)
                            }
                            lastStartTimes.remove(compName)
                        }
                    }
                    else -> eventType = "LIFECYCLE"
                }
            }
            component == "Notification" -> {
                when {
                    message.contains("connected", ignoreCase = true) -> {
                        eventType = "CONNECTED"
                        if (eventTime != null) lastStartTimes["NotificationListener"] = eventTime
                    }
                    message.contains("disconnected", ignoreCase = true) -> {
                        eventType = "DISCONNECTED"
                        if (eventTime != null) {
                            val start = lastStartTimes["NotificationListener"]
                            if (start != null && eventTime >= start) {
                                durationText = formatDuration(eventTime - start)
                            }
                        }
                    }
                    else -> eventType = "NOTIFICATION"
                }
            }
            component == "LogKeeper" && message.contains("Initialized logger", ignoreCase = true) -> {
                eventType = "STARTED"
            }
            component == "StartupDiagnostics" -> {
                eventType = "STARTED"
            }
            message.contains("restarted", ignoreCase = true) || message.contains("restart", ignoreCase = true) -> {
                eventType = "RESTARTED"
            }
            message.contains("start", ignoreCase = true) -> {
                eventType = "STARTED"
            }
            message.contains("stop", ignoreCase = true) -> {
                eventType = "STOPPED"
            }
            else -> continue
        }

        eventsList.add(
            LifecycleEventItem(
                timestamp = timestamp.substringAfter(" ").substringBeforeLast("."),
                process = if (isMain) "Main" else "Heavy",
                isMainProcess = isMain,
                thread = thread,
                component = component,
                eventType = eventType,
                message = message,
                durationText = durationText
            )
        )
    }

    return RunningRuntimeState(
        processes = processesList,
        activeServices = activeServicesList,
        lifecycleEvents = eventsList.asReversed()
    )
}

fun formatRunningSummary(state: RunningRuntimeState): String {
    val sb = StringBuilder()
    sb.append("=== RUNTIME PROCESSES ===\n")
    for (p in state.processes) {
        val status = if (p.isRunning) "RUNNING (PID: ${p.pid}, ${p.importance})" else "STOPPED / IDLE"
        sb.append("- ${p.name}: $status\n")
    }
    sb.append("\n=== ACTIVE SERVICES (${state.activeServices.size}) ===\n")
    if (state.activeServices.isEmpty()) {
        sb.append("No active background services.\n")
    } else {
        for (s in state.activeServices) {
            val fg = if (s.isForeground) "Foreground" else "Background"
            val proc = if (s.isMainProcess) "Main" else "Heavy"
            sb.append("- ${s.shortName} [$proc] [$fg] | Started: ${s.startTimeFormatted} | Uptime: ${s.durationFormatted}\n")
        }
    }
    sb.append("\n=== RECENT LIFECYCLE EVENTS (${state.lifecycleEvents.size}) ===\n")
    if (state.lifecycleEvents.isEmpty()) {
        sb.append("No lifecycle events recorded.\n")
    } else {
        for (e in state.lifecycleEvents.take(50)) {
            val dur = if (e.durationText != null) " (Duration: ${e.durationText})" else ""
            sb.append("[${e.timestamp}] [${e.process}] [${e.eventType}] [${e.component}] ${e.message}$dur\n")
        }
    }
    return sb.toString()
}

fun formatImportance(importance: Int): String {
    return when (importance) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "Foreground"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "Foreground Service"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "Visible"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "Perceptible"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CANT_SAVE_STATE -> "Cant Save State"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "Service"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "Cached"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "Terminated"
        else -> "Priority: $importance"
    }
}

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0s"
    val seconds = (ms / 1000) % 60
    val minutes = (ms / (1000 * 60)) % 60
    val hours = (ms / (1000 * 60 * 60))
    val days = (ms / (1000 * 60 * 60 * 24))

    return when {
        days > 0 -> "${days}d ${hours % 24}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

