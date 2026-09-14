package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
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

        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                WelcomeScreen()
            }
        }
    }
}

