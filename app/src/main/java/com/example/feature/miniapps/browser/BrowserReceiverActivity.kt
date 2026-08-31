package com.example.feature.miniapps.browser


import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.example.feature.miniapps.browser.FloatingBrowserService

class BrowserReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        var url: String? = null
        if (intent.action == Intent.ACTION_VIEW) {
            url = intent.dataString
        } else if (intent.action == Intent.ACTION_SEND) {
            url = intent.getStringExtra(Intent.EXTRA_TEXT)
        }
        
        if (url != null) {
            val serviceIntent = Intent(this, FloatingBrowserService::class.java)
            serviceIntent.action = "OPEN_URL"
            serviceIntent.putExtra("URL", url)
            startService(serviceIntent)
        }
        
        finish()
    }
}
