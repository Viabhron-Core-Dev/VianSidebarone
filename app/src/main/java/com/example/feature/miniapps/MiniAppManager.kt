package com.example.feature.miniapps

import android.content.Context
import android.widget.Toast

object MiniAppManager {
    fun toggleApp(context: Context, pageType: String) {
        Toast.makeText(context, "Opening $pageType window", Toast.LENGTH_SHORT).show()
    }
}
