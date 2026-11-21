package com.example.feature.language

import android.content.Context
import android.graphics.Color
import com.example.core.WindowBounds

/**
 * Deferred Translation-specific visual tokens and bounds calculation removed from active Sidebar.
 * Merge into Vian World's UI helper during Vian World integration.
 */
object LanguageMiniAppUiHelperTranslationAdditions {
    val COLOR_ACCENT_TRANSLATION: Int = Color.parseColor("#A6E3A1")
    const val ACCENT_TRANSLATION_HEX = "#A6E3A1"

    fun computeCompactTranslationBounds(
        context: Context,
        screenWidth: Int,
        screenHeight: Int
    ): WindowBounds {
        val density = context.resources.displayMetrics.density
        val safeW = screenWidth.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels.coerceAtLeast(720)
        val safeH = screenHeight.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels.coerceAtLeast(1280)
        val maxW = (safeW * 0.90f).toInt().coerceAtLeast((250 * density).toInt())
        val maxH = (safeH * 0.65f).toInt().coerceAtLeast((280 * density).toInt())
        val width = (312 * density).toInt().coerceAtMost(maxW)
        val height = (380 * density).toInt().coerceAtMost(maxH)
        val x = ((safeW - width) / 2 + (14 * density).toInt()).coerceIn(0, maxOf(0, safeW - width))
        val y = (128 * density).toInt().coerceAtMost(maxOf(0, safeH - height - (48 * density).toInt()))
        return WindowBounds(x, y, width, height)
    }
}
