package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import com.example.core.HandleService
import com.example.feature.settings.handle.HandleSettingsScreen

/**
 * SettingsActivity: Dedicated Settings management activity running in the Heavy process (:heavy).
 * Manages floating Handles, multi-gesture bindings, and independent Container identities.
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure Main lightweight resident runtime is started if overlay permission is granted
        HandleService.startIfConfigured(this)

        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HandleSettingsScreen(
                        onNavigateBack = { finish() }
                    )
                }
            }
        }
    }
}

