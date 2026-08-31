package com.example.core

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.R

/**
 * Centralized IconManager.
 * Uses lightweight XML drawables from res/drawable and built-in core icons.
 */
object IconManager {
    val AppyworkDrawable: Int = R.drawable.ic_code
    val DictionaryDrawable: Int = R.drawable.ic_book
    val TranslationDrawable: Int = R.drawable.ic_translate
    val ReadAloudDrawable: Int = R.drawable.ic_volume_up
    val BrowserDrawable: Int = R.drawable.ic_language
    val CallRecorderDrawable: Int = R.drawable.ic_mic
    val NetSpeedDrawable: Int = R.drawable.ic_speed
    val ScreenRecordDrawable: Int = R.drawable.ic_videocam
    val SettingsIcon: ImageVector = Icons.Default.Settings
    val CloseIcon: ImageVector = Icons.Default.Close
    val FoldDrawable: Int = R.drawable.ic_minimize
    val ResizeDrawable: Int = R.drawable.ic_open_with
    val DragHandleDrawable: Int = R.drawable.ic_drag_handle
}
