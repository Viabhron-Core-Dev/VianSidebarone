package com.example.feature.element

import android.content.Context
import android.content.Intent
import android.net.Uri
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
                    val url = item.url
                    LogKeeper.log(context, TAG, "Opening link element: $url")
                    val intent = if (url.startsWith("intent:")) {
                        Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    } else {
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    }
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
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
                    MiniAppManager.toggleApp(context, item.pageType)
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

    internal fun handleSystemAction(context: Context, action: String): Boolean {
        LogKeeper.log(context, TAG, "Handling system action: $action")
        return when (action) {
            "force_stop_running_apps" -> {
                AppTrackerHelper.startForceStopSequence(context)
                true
            }
            "log_keeper" -> {
                val intent = Intent(context, com.example.LogKeeperActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
            else -> Intent(Settings.ACTION_SETTINGS)
        }
        settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(settingsIntent)
            true
        } catch (e: Exception) {
            LogKeeper.logError(context, TAG, "Error opening settings shortcut: $action", e)
            false
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
