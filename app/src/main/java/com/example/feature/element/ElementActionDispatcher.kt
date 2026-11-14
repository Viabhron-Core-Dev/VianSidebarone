package com.example.feature.element

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.example.core.LogKeeper
import com.example.feature.miniapps.MiniAppManager
import com.example.feature.sidebar.SidebarItem
import com.example.feature.sidebar.SidebarManager
import com.example.feature.system_hub.DisplayHandler
import com.example.feature.system_hub.MediaVolumeHandler
import com.example.feature.system_hub.QuickTileHandler
import com.example.utils.AppTrackerHelper

/**
 * ElementActionDispatcher: Dedicated, independent executor for all Element actions.
 *
 * Encapsulates the execution logic for modular Element types (Apps, Links, QuickTiles,
 * SystemActions, Media, Volume, Display, Settings Shortcuts, Intents, and Floating Triggers)
 * completely outside of SidebarView and HybridGridPageView.
 */
object ElementActionDispatcher {

    private const val TAG = "ElementActionDispatcher"

    /**
     * Executes the action defined by a [SidebarItem].
     *
     * @param context Active context
     * @param item The element item to execute
     * @param onShowFolder Callback to display folder popup if item is a Folder
     * @param onShowWidget Callback to display widget popup if item is a PopupWidget
     * @return true if the action was handled, false otherwise
     */
    fun execute(
        context: Context,
        item: SidebarItem,
        onShowFolder: ((SidebarItem.Folder) -> Unit)? = null,
        onShowWidget: ((SidebarItem.PopupWidget) -> Unit)? = null
    ): Boolean {
        return try {
            when (item) {
                is SidebarItem.App -> {
                    val intent = context.packageManager.getLaunchIntentForPackage(item.packageName)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        LogKeeper.log(context, TAG, "Launching app element: ${item.packageName}")
                        context.startActivity(intent)
                        true
                    } else {
                        LogKeeper.log(context, TAG, "Launch intent not found for app: ${item.packageName}")
                        false
                    }
                }

                is SidebarItem.Link -> {
                    LogKeeper.log(context, TAG, "Opening link element via Custom Tab: ${item.url} (browser: ${item.browserPackage})")
                    CustomTabLauncher.openLink(context, item.url, item.browserPackage)
                }

                is SidebarItem.Pwa -> {
                    LogKeeper.log(context, TAG, "Launching PWA element: ${item.pwaId}")
                    val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
                    val orientation = prefs.getString("pwa_pref_${item.pwaId}_orientation", "manifest") ?: "manifest"
                    val closeButton = prefs.getString("pwa_pref_${item.pwaId}_close_button", "show") ?: "show"
                    val policy = com.example.feature.pwa.PwaPermissionManager.getPolicy(context, item.pwaId)
                    val pwaIntent = Intent(context, com.example.feature.pwa.PwaPlayerActivity::class.java).apply {
                        putExtra("PWA_ID", item.pwaId)
                        putExtra("PREF_ORIENTATION", orientation)
                        putExtra("PREF_CLOSE_BUTTON", closeButton)
                        putExtra("PERM_INTERNET", policy.internetAllowed)
                        putExtra("PERM_FILES", policy.filesAllowed)
                        putExtra("PERM_CAMERA", policy.cameraAllowed)
                        putExtra("PERM_MICROPHONE", policy.microphoneAllowed)
                        putExtra("PERM_LOCATION", policy.locationAllowed)
                        putExtra("PERM_SOUND", policy.soundAllowed)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(pwaIntent)
                    true
                }

                is SidebarItem.QuickTile -> {
                    LogKeeper.log(context, TAG, "Triggering QuickTile element: ${item.action}")
                    QuickTileHandler.handleQuickTileAction(context, item.action)
                    true
                }

                is SidebarItem.VolumeAction -> {
                    LogKeeper.log(context, TAG, "Triggering VolumeAction element: ${item.stream}_${item.action}")
                    MediaVolumeHandler.handleVolumeAction(context, item.stream, item.action)
                    true
                }

                is SidebarItem.MediaAction -> {
                    LogKeeper.log(context, TAG, "Triggering MediaAction element: ${item.action}")
                    handleMediaAction(context, item.action)
                    true
                }

                is SidebarItem.DisplayAction -> {
                    LogKeeper.log(context, TAG, "Triggering DisplayAction element: ${item.action}")
                    DisplayHandler.handleDisplayAction(context, item.action)
                    true
                }

                is SidebarItem.SystemAction -> {
                    handleSystemAction(context, item.action)
                }

                is SidebarItem.SettingsShortcut -> {
                    handleSettingsShortcut(context, item.action)
                }

                is SidebarItem.IntentAction -> {
                    LogKeeper.log(context, TAG, "Launching IntentAction element: ${item.label}")
                    val intent = Intent.parseUri(item.uri, Intent.URI_INTENT_SCHEME)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    true
                }

                is SidebarItem.PageWindow -> {
                    LogKeeper.log(context, TAG, "Toggling PageWindow element: ${item.pageType}")
                    LogKeeper.log(
                        context,
                        "MiniAppLaunchRouting",
                        "ROUTING_DIAG: ELEMENT_ACTION_PAGE_WINDOW item=${item.id} pageType=${item.pageType}"
                    )
                    val baseType = item.pageType.substringBefore(":")
                    if (baseType == "test_notes" || baseType == "note") {
                        com.example.feature.floating.FloatingMiniAppBridge.getInstance(context)
                            .openNewNoteInstance(baseType)
                    } else {
                        val instanceId = if (item.pageType.contains(":")) item.pageType.substringAfter(":") else null
                        MiniAppManager.toggleApp(context, baseType, instanceId)
                    }
                    true
                }

                is SidebarItem.FloatingTrigger -> {
                    LogKeeper.log(context, TAG, "Triggering container element: ${item.targetId}")
                    SidebarManager.getInstance(context).openContainerById(item.targetId)
                    true
                }

                is SidebarItem.Folder -> {
                    onShowFolder?.invoke(item)
                    true
                }

                is SidebarItem.PopupWidget -> {
                    onShowWidget?.invoke(item)
                    true
                }

                is SidebarItem.Spacer, is SidebarItem.Widget -> {
                    // Non-clickable or handled by widget host view
                    false
                }
            }
        } catch (e: Throwable) {
            LogKeeper.logError(context, TAG, "Error executing element action: ${item.id}", e)
            false
        }
    }

    internal fun handleSystemAction(context: Context, action: String, containerId: String = "sidebar"): Boolean {
        LogKeeper.log(context, TAG, "Handling system action: $action (container=$containerId)")
        if (action == "test_notes" || action.startsWith("test_notes:") || action == "note" || action.startsWith("note:")) {
            val baseType = action.substringBefore(":")
            LogKeeper.log(
                context,
                "MiniAppLaunchRouting",
                "ROUTING_DIAG: ELEMENT_ACTION_SYSTEM_ACTION action=$action containerId=$containerId (spawning new note instance)"
            )
            com.example.feature.floating.FloatingMiniAppBridge.getInstance(context)
                .openNewNoteInstance(baseType)
            return true
        }
        return when (action) {
            "force_stop_running_apps" -> {
                if (LogKeeper.getProcessName(context).endsWith(":heavy")) {
                    LogKeeper.log(context, TAG, "Routing force_stop_running_apps from :heavy to Main via IPC")
                    val res = com.example.core.ipc.HeavyProcessHost.getInstance(context).requestTriggerAction(action, containerId)
                    return res.success
                }
                AppTrackerHelper.startForceStopSequence(context, containerId)
                true
            }
            "log_keeper" -> {
                val intent = Intent(context, com.example.LogKeeperActivity::class.java).apply {
                    putExtra(com.example.LogKeeperActivity.EXTRA_LAUNCH_SOURCE, com.example.LogKeeperActivity.SOURCE_SIDEBAR)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                }
                context.startActivity(intent)
                true
            }
            "screen_record" -> {
                val intent = Intent(context, com.example.feature.sidebar.ScreenRecordActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            }
            "dictionary_floating", "dictionary_full" -> {
                MiniAppManager.toggleApp(context, "dictionary")
                true
            }
            "translation_floating" -> {
                MiniAppManager.toggleApp(context, "translation")
                true
            }
            "hybrid_grid_floating" -> {
                MiniAppManager.toggleApp(context, "hybrid_grid")
                true
            }
            "work_notes" -> {
                MiniAppManager.toggleApp(context, "work_notes")
                true
            }
            "ebook_reader", "reader" -> {
                MiniAppManager.toggleApp(context, "reader")
                true
            }
            "floating_pwa" -> {
                MiniAppManager.toggleApp(context, "floating_pwa")
                true
            }
            "audio_record" -> {
                com.example.feature.system_hub.RecordingActionHelper.startOrToggleAudioRecord(context)
                true
            }
            "call_recorder" -> {
                com.example.feature.system_hub.RecordingActionHelper.openCallRecorderSettings(context)
                true
            }
            "recordings" -> {
                com.example.feature.system_hub.RecordingActionHelper.openRecordings(context)
                true
            }
            "camera_measure" -> {
                val intent = Intent(context, com.example.feature.system_hub.CameraMeasureActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            }
            "arrangement_checker", "ghost_camera" -> {
                val intent = Intent(context, com.example.feature.system_hub.GhostCameraActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            }
            "settings" -> {
                val intent = Intent(context, com.example.SettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            }
            else -> {
                val service = com.example.feature.system_hub.VianSideAccessibilityService.instance
                if (service == null) {
                    if (LogKeeper.getProcessName(context).endsWith(":heavy")) {
                        LogKeeper.log(context, TAG, "Routing accessibility action '$action' from :heavy to Main via IPC")
                        val res = com.example.core.ipc.HeavyProcessHost.getInstance(context).requestTriggerAction(action, containerId)
                        return res.success
                    }
                    LogKeeper.log(context, TAG, "Accessibility service not running/enabled for action: $action")
                    try {
                        android.widget.Toast.makeText(context, "Please enable VianSide Accessibility Service", android.widget.Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {}
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try { context.startActivity(intent) } catch (_: Exception) {}
                    false
                } else {
                    when (val result = service.executeAction(action)) {
                        is com.example.feature.system_hub.accessibility.AccessibilityActionResult.Success -> {
                            LogKeeper.log(context, TAG, "System action performed by accessibility service: $action")
                            true
                        }
                        is com.example.feature.system_hub.accessibility.AccessibilityActionResult.Unavailable -> {
                            LogKeeper.log(context, TAG, "Accessibility action '$action' unavailable: ${result.reason}")
                            try {
                                android.widget.Toast.makeText(context, result.reason, android.widget.Toast.LENGTH_SHORT).show()
                            } catch (_: Exception) {}
                            false
                        }
                        is com.example.feature.system_hub.accessibility.AccessibilityActionResult.Failed -> {
                            LogKeeper.log(context, TAG, "Accessibility action '$action' failed: ${result.reason}")
                            try {
                                android.widget.Toast.makeText(context, "Action failed: ${result.reason}", android.widget.Toast.LENGTH_SHORT).show()
                            } catch (_: Exception) {}
                            false
                        }
                        is com.example.feature.system_hub.accessibility.AccessibilityActionResult.ServiceUnavailable -> {
                            LogKeeper.log(context, TAG, "Accessibility service disconnected during action: $action")
                            try {
                                android.widget.Toast.makeText(context, "Please enable VianSide Accessibility Service", android.widget.Toast.LENGTH_SHORT).show()
                            } catch (_: Exception) {}
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            try { context.startActivity(intent) } catch (_: Exception) {}
                            false
                        }
                    }
                }
            }
        }
    }

    private fun resolveWithFallbacks(context: Context, vararg actions: String): Intent {
        val pm = context.packageManager
        for (act in actions) {
            val candidate = Intent(act)
            if (pm != null && candidate.resolveActivity(pm) != null) {
                return candidate
            }
        }
        return Intent(actions.lastOrNull() ?: Settings.ACTION_SETTINGS)
    }

    internal fun resolveSettingsActionCandidates(
        action: String,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Array<String> {
        return when (action) {
            "wifi" -> arrayOf(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> arrayOf(Settings.ACTION_BLUETOOTH_SETTINGS)
            "display" -> arrayOf(Settings.ACTION_DISPLAY_SETTINGS)
            "sound" -> arrayOf(Settings.ACTION_SOUND_SETTINGS)
            "apps" -> arrayOf(Settings.ACTION_APPLICATION_SETTINGS)
            "battery" -> arrayOf(Intent.ACTION_POWER_USAGE_SUMMARY, Settings.ACTION_SETTINGS)
            "storage" -> arrayOf(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            "location" -> arrayOf(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "accessibility" -> arrayOf(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "date" -> arrayOf(Settings.ACTION_DATE_SETTINGS)
            "security" -> arrayOf(Settings.ACTION_SECURITY_SETTINGS)
            "privacy" -> arrayOf(Settings.ACTION_PRIVACY_SETTINGS)
            "device_info" -> arrayOf(Settings.ACTION_DEVICE_INFO_SETTINGS)

            // Extended Network & Wireless
            "network_provider" -> {
                if (sdkInt >= Build.VERSION_CODES.Q) {
                    arrayOf(Settings.ACTION_NETWORK_OPERATOR_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS)
                } else {
                    arrayOf(Settings.ACTION_WIRELESS_SETTINGS)
                }
            }
            "wireless_network" -> arrayOf(Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS)
            "data_usage" -> arrayOf(Settings.ACTION_DATA_USAGE_SETTINGS)
            "hotspot" -> arrayOf("android.settings.TETHER_SETTINGS", Settings.ACTION_WIRELESS_SETTINGS)
            "airplane" -> arrayOf(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            "vpn" -> arrayOf(Settings.ACTION_VPN_SETTINGS)
            "nfc" -> arrayOf(Settings.ACTION_NFC_SETTINGS)

            // Connected Devices
            "cast" -> arrayOf(Settings.ACTION_CAST_SETTINGS)

            // Display & Themes
            "night_display" -> {
                if (sdkInt >= Build.VERSION_CODES.Q) {
                    arrayOf(Settings.ACTION_NIGHT_DISPLAY_SETTINGS, Settings.ACTION_DISPLAY_SETTINGS)
                } else {
                    arrayOf(Settings.ACTION_DISPLAY_SETTINGS)
                }
            }
            "wallpaper" -> arrayOf(Intent.ACTION_SET_WALLPAPER, Settings.ACTION_DISPLAY_SETTINGS)
            "screen_saver" -> arrayOf(Settings.ACTION_DREAM_SETTINGS, Settings.ACTION_DISPLAY_SETTINGS)

            // Sound & DND
            "do_not_disturb" -> arrayOf(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS)

            // Notifications
            "notifications" -> arrayOf(
                "android.settings.ALL_APPS_NOTIFICATION_SETTINGS",
                Settings.ACTION_APPLICATION_SETTINGS
            )
            "notification_history" -> arrayOf(
                "android.settings.NOTIFICATION_HISTORY",
                "android.settings.ALL_APPS_NOTIFICATION_SETTINGS",
                Settings.ACTION_APPLICATION_SETTINGS
            )

            // Apps & Permissions / Special App Access
            "all_apps" -> arrayOf(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS)
            "default_apps" -> {
                if (sdkInt >= Build.VERSION_CODES.N) {
                    arrayOf(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_APPLICATION_SETTINGS)
                } else {
                    arrayOf(Settings.ACTION_APPLICATION_SETTINGS)
                }
            }
            "default_home" -> arrayOf(
                Settings.ACTION_HOME_SETTINGS,
                Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
                Settings.ACTION_APPLICATION_SETTINGS
            )
            "permissions" -> arrayOf(
                "android.intent.action.MANAGE_PERMISSIONS",
                Settings.ACTION_PRIVACY_SETTINGS,
                Settings.ACTION_APPLICATION_SETTINGS
            )
            "overlay_permission" -> arrayOf(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS
            )
            "modify_system_settings" -> arrayOf(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS
            )
            "usage_access" -> arrayOf(
                Settings.ACTION_USAGE_ACCESS_SETTINGS,
                Settings.ACTION_SECURITY_SETTINGS,
                Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS
            )
            "app_usage" -> {
                if (sdkInt >= Build.VERSION_CODES.P) {
                    arrayOf(
                        Settings.ACTION_APP_USAGE_SETTINGS,
                        Settings.ACTION_USAGE_ACCESS_SETTINGS,
                        Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS
                    )
                } else {
                    arrayOf(
                        Settings.ACTION_USAGE_ACCESS_SETTINGS,
                        Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS
                    )
                }
            }
            "notification_access" -> arrayOf(
                Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
                Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS
            )
            "dnd_access" -> arrayOf(
                Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
                Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS,
                Settings.ACTION_SOUND_SETTINGS
            )
            "all_files_access" -> {
                if (sdkInt >= Build.VERSION_CODES.R) {
                    arrayOf(
                        Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
                        Settings.ACTION_INTERNAL_STORAGE_SETTINGS
                    )
                } else {
                    arrayOf(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
                }
            }
            "install_unknown_apps" -> {
                if (sdkInt >= Build.VERSION_CODES.O) {
                    arrayOf(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Settings.ACTION_SECURITY_SETTINGS,
                        Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS
                    )
                } else {
                    arrayOf(Settings.ACTION_SECURITY_SETTINGS)
                }
            }
            "alarms_reminders" -> {
                if (sdkInt >= Build.VERSION_CODES.S) {
                    arrayOf(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS
                    )
                } else {
                    arrayOf(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS)
                }
            }
            "picture_in_picture" -> arrayOf(
                "android.settings.PICTURE_IN_PICTURE_SETTINGS",
                Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS
            )

            // Battery Saver & Optimization
            "battery_saver" -> arrayOf(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            "battery_optimization" -> arrayOf(
                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                Intent.ACTION_POWER_USAGE_SUMMARY,
                Settings.ACTION_SETTINGS
            )

            // Biometrics & Screen Lock
            "biometrics" -> {
                if (sdkInt >= Build.VERSION_CODES.R) {
                    arrayOf(Settings.ACTION_BIOMETRIC_ENROLL, Settings.ACTION_SECURITY_SETTINGS)
                } else {
                    arrayOf(Settings.ACTION_SECURITY_SETTINGS)
                }
            }

            // Accounts & Sync
            "accounts" -> arrayOf(Settings.ACTION_SYNC_SETTINGS)

            // Languages, Keyboard & Input
            "language" -> arrayOf(Settings.ACTION_LOCALE_SETTINGS)
            "keyboard" -> arrayOf(Settings.ACTION_INPUT_METHOD_SETTINGS)
            "physical_keyboard" -> arrayOf(
                Settings.ACTION_HARD_KEYBOARD_SETTINGS,
                Settings.ACTION_INPUT_METHOD_SETTINGS
            )
            "voice_input" -> arrayOf(
                Settings.ACTION_VOICE_INPUT_SETTINGS,
                Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
                Settings.ACTION_INPUT_METHOD_SETTINGS
            )
            "printing" -> arrayOf(Settings.ACTION_PRINT_SETTINGS, Settings.ACTION_SETTINGS)
            "backup" -> arrayOf(Settings.ACTION_PRIVACY_SETTINGS, Settings.ACTION_SETTINGS)

            // Accessibility & Captions
            "captioning" -> arrayOf(Settings.ACTION_CAPTIONING_SETTINGS)

            // Developer options, WebView & System update
            "developer_options" -> arrayOf(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            "webview_impl" -> arrayOf(
                Settings.ACTION_WEBVIEW_SETTINGS,
                Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
                Settings.ACTION_SETTINGS
            )
            "system_update" -> arrayOf(
                "android.settings.SYSTEM_UPDATE_SETTINGS",
                Settings.ACTION_DEVICE_INFO_SETTINGS
            )

            else -> arrayOf(Settings.ACTION_SETTINGS)
        }
    }

    internal fun resolveSettingsShortcutIntent(context: Context, action: String): Intent {
        if (action == "call_recorder") {
            return Intent(context, com.example.SettingsActivity::class.java).apply {
                putExtra("start_route", "call_recorder")
            }
        }
        val candidates = resolveSettingsActionCandidates(action)
        return resolveWithFallbacks(context, *candidates)
    }

    fun handleSettingsShortcut(context: Context, action: String): Boolean {
        val settingsIntent = resolveSettingsShortcutIntent(context, action)
        settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(settingsIntent)
            true
        } catch (e: Exception) {
            LogKeeper.logError(context, TAG, "Error opening settings shortcut: $action, attempting general settings fallback", e)
            try {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (fallbackError: Exception) {
                LogKeeper.logError(context, TAG, "Fallback to general settings also failed", fallbackError)
                false
            }
        }
    }

    private fun handleMediaAction(context: Context, action: String) {
        val keyEvent = when (action) {
            "play_pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
            "prev" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> android.view.KeyEvent.KEYCODE_MEDIA_STOP
            else -> -1
        }
        if (keyEvent != -1) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            audioManager?.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyEvent))
            audioManager?.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyEvent))
        }
    }
}
