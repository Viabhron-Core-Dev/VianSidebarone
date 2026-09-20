package com.example

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import com.example.core.HandleManager
import com.example.core.HandleService
import com.example.feature.welcome.WelcomeScreen

/**
 * MainActivity: Main launcher entry point running in the Heavy process (:heavy).
 * Does not inherit from WelcomeActivity, ensuring clean process separation and
 * preventing Main from depending on or subclassing Heavy UI activities.
 * Preserves the exact launcher behavior and Welcome UI.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure Main lightweight resident runtime is started if overlay permission is granted
        HandleService.startIfConfigured(this)

        val prefs = getSharedPreferences(HandleManager.PREFS_NAME, Context.MODE_PRIVATE)
        val setupCompleted = prefs.getBoolean("setup_completed", false)

        if (Settings.canDrawOverlays(this) || setupCompleted) {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
            finish()
            return
        }

        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                WelcomeScreen(
                    onContinue = {
                        prefs.edit().putBoolean("setup_completed", true).apply()
                        val intent = Intent(this@MainActivity, SettingsActivity::class.java)
                        startActivity(intent)
                        finish()
                    }
                )
            }
        }
    }
}


