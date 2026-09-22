package com.example.feature.welcome

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.example.MainActivity
import com.example.SettingsActivity
import com.example.core.HandleManager
import com.example.core.HandleService
import com.example.feature.settings.PermissionManagerScreen
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WelcomeActivity: Permission / Setup entry page of Settings running in the Heavy process (:heavy).
 *
 * Characteristics:
 * 1. Resident strictly in the :heavy process; keeps Main process lightweight.
 * 2. Provides active state tracking to prevent duplicate launches from repeated IPC requests.
 * 3. Preserves exact reference UI, wording, permission checks, and behavior.
 * 4. Terminates cleanly upon exit without leaving background services or leaking memory.
 */
class WelcomeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                WelcomeScreen(
                    onContinue = {
                        com.example.core.LogKeeper.log(this@WelcomeActivity, "WelcomeActivity", "onContinue triggered: saving setup_completed=true")
                        val prefs = getSharedPreferences(HandleManager.PREFS_NAME, Context.MODE_PRIVATE)
                        prefs.edit().putBoolean("setup_completed", true).commit()
                        com.example.core.LogKeeper.log(this@WelcomeActivity, "WelcomeActivity", "Starting HandleService and finishing cleanly")
                        HandleService.start(this@WelcomeActivity)
                        finish()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isActive.set(true)
    }

    override fun onPause() {
        super.onPause()
        isActive.set(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        isActive.set(false)
    }

    companion object {
        private val isActive = AtomicBoolean(false)

        val isWelcomeActive: Boolean
            get() = isActive.get()

        internal fun setActiveForTesting(active: Boolean) {
            isActive.set(active)
        }
    }
}

@Composable
fun WelcomeScreen(onContinue: (() -> Unit)? = null) {
    val context = LocalContext.current
    PermissionManagerScreen(
        onContinue = {
            HandleService.startIfConfigured(context)
            onContinue?.invoke()
        },
        isFirstLaunch = true
    )
}
