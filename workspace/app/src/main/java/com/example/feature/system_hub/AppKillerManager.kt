package com.example.feature.system_hub

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityService
import android.content.Intent

class AppKillerManager(private val service: AccessibilityService) {
    var isForceStopping = false

    fun handleAccessibilityEvent(event: AccessibilityEvent, rootInActiveWindow: AccessibilityNodeInfo?) {
        if (!isForceStopping) return
        
        val rootNode = rootInActiveWindow ?: return
        
        // We just watch for the "Force stop" button to be disabled.
        // When it becomes disabled (meaning the user clicked it and confirmed it),
        // or if it was already disabled, we perform the BACK action to go to the next app.
        val forceStopNodes = rootNode.findAccessibilityNodeInfosByText("Force stop")
        var buttonIsDisabled = false
        for (node in forceStopNodes) {
            if (node.isClickable && !node.isEnabled) {
                buttonIsDisabled = true
                break
            } else if (node.parent?.isClickable == true && node.parent?.isEnabled == false) {
                buttonIsDisabled = true
                break
            }
        }
        
        if (buttonIsDisabled) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            return
        }

        // Auto click "Force stop" if it's enabled
        for (node in forceStopNodes) {
            if (node.isClickable && node.isEnabled) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            } else if (node.parent?.isClickable == true && node.parent?.isEnabled == true) {
                node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
        }

        // Handle the confirmation dialog ("OK" button)
        val okNodes = rootNode.findAccessibilityNodeInfosByText("OK")
        for (node in okNodes) {
            if (node.isClickable && node.isEnabled) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
        }
    }
}
