package com.example.feature.miniapps.reader

import com.example.service.SidebarService


import android.app.Activity
import android.content.Intent
import android.os.Bundle

class ReadAloudActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() ?: ""
        if (text.isNotBlank()) {
            val i = Intent(this, SidebarService::class.java)
            i.action = "READ_ALOUD"
            i.putExtra("TEXT", text)
            startService(i)
        }
        finish()
    }
}
