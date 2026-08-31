package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.feature.settings.PermissionManagerScreen
import com.example.feature.settings.SettingsActivity
import com.example.core.HandleService
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        startSidebarService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission immediately on launch so foreground speed notification is visible
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val firstLaunch = prefs.getBoolean("first_launch", true)
        
        if (!firstLaunch) {
            startSidebarService()
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
            return
        }
        
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PermissionManagerScreen(
                        onContinue = {
                            prefs.edit().putBoolean("first_launch", false).apply()
                            startSidebarService()
                            startActivity(Intent(this@MainActivity, SettingsActivity::class.java).apply {
                                putExtra("start_route", "handles")
                            })
                            finish()
                        },
                        isFirstLaunch = true
                    )
                }
            }
        }
    }

    private fun startSidebarService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            val svcIntent = Intent(this, com.example.core.HandleService::class.java).apply {
                putExtra("OPEN_FROM_LAUNCHER", true)
            }
            ContextCompat.startForegroundService(this, svcIntent)
        }
    }
}
