package com.example

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings

/**
 * AppTrackerOpenerActivity: Lightweight, on-demand sequential loop presenter for force-stopping apps.
 *
 * Strictly adheres to user directive:
 * "No clicking. Only loop. User will click."
 *
 * Sequentially brings up each app's App Info settings page. When the user finishes and returns,
 * onResume() advances to the next app in the queue until the list is exhausted.
 */
class AppTrackerOpenerActivity : Activity() {
    private var packageNames = arrayListOf<String>()
    private var currentIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        packageNames = intent.getStringArrayListExtra("packages") ?: arrayListOf()
        if (packageNames.isEmpty()) {
            finish()
            return
        }
    }

    override fun onResume() {
        super.onResume()
        if (packageNames.isEmpty() || isFinishing || isDestroyed) {
            return
        }
        // Advance to next app whenever we resume (initial start or return from previous Settings page)
        openNext()
    }

    private fun openNext() {
        if (currentIndex < packageNames.size) {
            val pkg = packageNames[currentIndex]
            currentIndex++
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$pkg")
                }
                startActivity(intent)
            } catch (e: Exception) {
                // If this package fails to open, immediately proceed to the next
                openNext()
            }
        } else {
            finish()
        }
    }
}
