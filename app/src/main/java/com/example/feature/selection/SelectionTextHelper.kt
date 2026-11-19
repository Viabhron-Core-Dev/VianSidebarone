package com.example.feature.selection

import android.content.Intent
import android.os.Build

/**
 * Safely extracts and sanitizes text passed to Android text-selection (`ACTION_PROCESS_TEXT`)
 * popup activities.
 */
object SelectionTextHelper {
    const val MAX_SELECTION_LENGTH = 4000

    fun extractSelectedText(intent: Intent?): String? {
        if (intent == null) return null
        val raw: CharSequence? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                ?: intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT)
                ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
                ?: intent.getStringExtra(Intent.EXTRA_TEXT)
        } else {
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
                ?: intent.getStringExtra(Intent.EXTRA_TEXT)
        }
        return normalize(raw?.toString())
    }

    fun normalize(raw: String?): String? {
        if (raw == null) return null
        val collapsed = raw.trim()
        if (collapsed.isEmpty()) return null
        return if (collapsed.length > MAX_SELECTION_LENGTH) {
            collapsed.substring(0, MAX_SELECTION_LENGTH).trim()
        } else {
            collapsed
        }
    }

    fun formatPrimaryTerm(selectedText: String): String {
        val singleLine = selectedText.replace(Regex("\\s+"), " ").trim()
        return if (singleLine.length > 80) {
            singleLine.substring(0, 77).trimEnd() + "..."
        } else {
            singleLine
        }
    }
}
