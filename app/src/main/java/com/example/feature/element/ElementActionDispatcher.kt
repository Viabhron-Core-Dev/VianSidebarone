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
                    val instanceId = if (item.pageType.contains(":")) item.pageType.substringAfter(":") else if (item.pageType == "test_notes") "miniapp_test_notes" else null
                    val baseType = item.pageType.substringBefore(":")
                    MiniAppManager.toggleApp(context, baseType, instanceId)
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
            val instanceId = if (action.contains(":")) action.substringAfter(":") else "miniapp_test_notes"
            LogKeeper.log(
                context,
                "MiniAppLaunchRouting",
                "ROUTING_DIAG: ELEMENT_ACTION_SYSTEM_ACTION action=$action instanceId=$instanceId containerId=$containerId"
            )
            MiniAppManager.toggleApp(context, "test_notes", instanceId)
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
            "ebook_reader" -> {
                MiniAppManager.toggleApp(context, "reader")
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

    private fun handleSettingsShortcut(context: Context, action: String): Boolean {
        val settingsIntent = when (action) {
            "call_recorder" -> Intent(context, com.example.SettingsActivity::class.java).apply {
                putExtra("start_route", "call_recorder")
            }
            "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS)
            "apps" -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
            "battery" -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
            "storage" -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "date" -> Intent(Settings.ACTION_DATE_SETTINGS)
            "security" -> Intent(Settings.ACTION_SECURITY_SETTINGS)
            "privacy" -> Intent(Settings.ACTION_PRIVACY_SETTINGS)
            "device_info" -> Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)

            // Extended Network & Wireless
            "network_provider" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS)
                } else {
                    Intent(Settings.ACTION_WIRELESS_SETTINGS)
                }
            }
            "data_usage" -> Intent(Settings.ACTION_DATA_USAGE_SETTINGS)
            "hotspot" -> {
                Intent("android.settings.TETHER_SETTINGS").apply {
                    if (resolveActivity(context.packageManager) == null) {
                        this.action = Settings.ACTION_WIRELESS_SETTINGS
                    }
                }
            }
            "airplane" -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            "vpn" -> Intent(Settings.ACTION_VPN_SETTINGS)
            "nfc" -> Intent(Settings.ACTION_NFC_SETTINGS)

            // Connected Devices
            "cast" -> Intent(Settings.ACTION_CAST_SETTINGS)

            // Display & Themes
            "night_display" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Intent(Settings.ACTION_NIGHT_DISPLAY_SETTINGS)
                } else {
                    Intent(Settings.ACTION_DISPLAY_SETTINGS)
                }
            }
            "wallpaper" -> {
                Intent(Intent.ACTION_SET_WALLPAPER).apply {
                    if (resolveActivity(context.packageManager) == null) {
                        this.action = Settings.ACTION_DISPLAY_SETTINGS
                    }
                }
            }

            // Sound & DND
            "do_not_disturb" -> Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS)

            // Notifications
            "notifications" -> {
                Intent("android.settings.ALL_APPS_NOTIFICATION_SETTINGS").apply {
                    if (resolveActivity(context.packageManager) == null) {
                        this.action = Settings.ACTION_APPLICATION_SETTINGS
                    }
                }
            }
            "notification_history" -> {
                Intent("android.settings.NOTIFICATION_HISTORY").apply {
                    if (resolveActivity(context.packageManager) == null) {
                        this.action = "android.settings.ALL_APPS_NOTIFICATION_SETTINGS"
                        if (resolveActivity(context.packageManager) == null) {
                            this.action = Settings.ACTION_APPLICATION_SETTINGS
                        }
                    }
                }
            }

            // Apps & Permissions
            "all_apps" -> Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS)
            "default_apps" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                } else {
                    Intent(Settings.ACTION_APPLICATION_SETTINGS)
                }
            }
            "permissions" -> {
                Intent("android.intent.action.MANAGE_PERMISSIONS").apply {
                    if (resolveActivity(context.packageManager) == null) {
                        this.action = Settings.ACTION_APPLICATION_SETTINGS
                    }
                }
            }

            // Battery Saver
            "battery_saver" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)

            // Biometrics & Screen Lock
            "biometrics" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Intent(Settings.ACTION_BIOMETRIC_ENROLL)
                } else {
                    Intent(Settings.ACTION_SECURITY_SETTINGS)
                }
            }

            // Accounts & Sync
            "accounts" -> Intent(Settings.ACTION_SYNC_SETTINGS)

            // Languages & Keyboard
            "language" -> Intent(Settings.ACTION_LOCALE_SETTINGS)
            "keyboard" -> Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            "backup" -> {
                Intent(Settings.ACTION_PRIVACY_SETTINGS).apply {
                    if (resolveActivity(context.packageManager) == null) {
                        this.action = Settings.ACTION_SETTINGS
                    }
                }
            }

            // Accessibility & Captions
            "captioning" -> Intent(Settings.ACTION_CAPTIONING_SETTINGS)

            // Developer options & System update
            "developer_options" -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            "system_update" -> {
                Intent("android.settings.SYSTEM_UPDATE_SETTINGS").apply {
                    if (resolveActivity(context.packageManager) == null) {
                        this.action = Settings.ACTION_DEVICE_INFO_SETTINGS
                    }
                }
            }

            else -> Intent(Settings.ACTION_SETTINGS)
        }
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
