package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.example.core.HandleService
import com.example.core.LogKeeper
import com.example.feature.settings.PermissionManagerScreen
import com.example.feature.settings.handle.HandleSettingsScreen

/**
 * SettingsActivity: Dedicated Settings management activity running in the Heavy process (:heavy).
 * Manages floating Handles, multi-gesture bindings, permissions, and diagnostic logging.
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogKeeper.log(this, "SettingsActivity", "onCreate entered")

        try {
            // Ensure Main lightweight resident runtime is started if overlay permission is granted
            HandleService.startIfConfigured(this)

            val startRoute = intent.getStringExtra("start_route") ?: "main"

            setContent {
                MaterialTheme(colorScheme = lightColorScheme()) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        SettingsNavigationApp(
                            initialRoute = startRoute,
                            onFinish = { finish() }
                        )
                    }
                }
            }
            LogKeeper.log(this, "SettingsActivity", "onCreate setContent executed successfully")
        } catch (t: Throwable) {
            LogKeeper.logError(this, "SettingsActivity", "Exception during onCreate", t)
            throw t
        }
    }
}

@Composable
fun SettingsNavigationApp(initialRoute: String, onFinish: () -> Unit) {
    val backStack = remember {
        val stack = mutableStateListOf("main")
        if (initialRoute != "main") {
            stack.add(initialRoute)
        }
        stack
    }
    val currentRoute = backStack.lastOrNull() ?: "main"

    fun navigateTo(route: String) {
        if (backStack.lastOrNull() != route) {
            backStack.add(route)
        }
    }

    fun navigateBack() {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.size - 1)
        } else {
            onFinish()
        }
    }

    BackHandler {
        navigateBack()
    }

    when {
        currentRoute == "handles" || currentRoute.startsWith("pages_") || currentRoute.startsWith("handle_") -> {
            HandleSettingsScreen(
                onNavigateBack = { navigateBack() }
            )
        }
        currentRoute == "permissions" -> {
            PermissionManagerScreen(
                onContinue = { navigateBack() },
                isFirstLaunch = false
            )
        }
        else -> {
            MainSettingsScreen(
                onNavigateToHandles = { navigateTo("handles") },
                onNavigateToPermissions = { navigateTo("permissions") },
                onBack = onFinish
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainSettingsScreen(
    onNavigateToHandles: () -> Unit,
    onNavigateToPermissions: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("settings_main_list")
        ) {
            item {
                ListItem(
                    headlineContent = { Text("Handles & Sidebar") },
                    supportingContent = { Text("Customize trigger handles, edge gestures, and sidebar placement") },
                    modifier = Modifier
                        .clickable { onNavigateToHandles() }
                        .testTag("settings_item_handles")
                )
                Divider()
            }
            item {
                ListItem(
                    headlineContent = { Text("Vian Permissions Manager") },
                    supportingContent = { Text("Review and manage necessary system permissions") },
                    modifier = Modifier
                        .clickable { onNavigateToPermissions() }
                        .testTag("settings_item_permissions")
                )
                Divider()
            }
            item {
                ListItem(
                    headlineContent = { Text("Log Keeper") },
                    supportingContent = { Text("View diagnostic running events and crash reports") },
                    modifier = Modifier
                        .clickable {
                            val intent = Intent(context, LogKeeperActivity::class.java)
                            context.startActivity(intent)
                        }
                        .testTag("settings_item_log_keeper")
                )
                Divider()
            }
        }
    }
}
