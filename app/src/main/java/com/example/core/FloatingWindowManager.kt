package com.example.core

import android.graphics.Rect
import com.example.util.AppLogger
import kotlin.math.abs

object FloatingWindowManager {
    private val windows = mutableListOf<FloatingWindow>()
    val activeWindows: List<FloatingWindow> get() = windows.toList()
    var focusedWindow: FloatingWindow? = null
        private set

    fun addWindow(window: FloatingWindow) {
        if (!windows.contains(window)) {
            windows.add(window)
        }
        window.show()
    }

    fun removeWindow(window: FloatingWindow) {
        window.hide()
        windows.remove(window)
        if (focusedWindow == window) {
            focusedWindow = windows.lastOrNull()
        }
    }

    fun bringToFront(window: FloatingWindow) {
        if (windows.remove(window)) {
            windows.add(window)
            focusedWindow = window
            
            // Bring to front by removing and re-adding
            try {
                window.view?.let {
                    window.windowManager.removeView(it)
                    window.windowManager.addView(it, window.layoutParams)
                }
            } catch (e: Exception) {
                android.util.Log.e("FloatingWindowManager", "Error bringing window to front: ${e.message}")
            }
        }
    }
    
    fun foldAllExceptActive(activeWindow: FloatingWindow? = focusedWindow) {
        windows.forEach { window ->
            if (window != activeWindow && !window.isFolded) {
                window.fold()
            }
        }
    }

    fun foldAll() {
        windows.forEach { window ->
            if (!window.isFolded) {
                window.fold()
            }
        }
    }

    fun checkCollisions(draggedWindow: FloatingWindow) {
        // Magnetic grouping: snap dragged window to target window if close enough
        val draggedView = draggedWindow.view ?: draggedWindow.bubbleView ?: return
        val draggedParams = draggedWindow.layoutParams ?: draggedWindow.bubbleLayoutParams ?: return
        
        val draggedRect = Rect()
        draggedView.getGlobalVisibleRect(draggedRect)
        
        for (window in windows.toList()) {
            if (window == draggedWindow) continue
            
            val view = window.view ?: window.bubbleView ?: continue
            val params = window.layoutParams ?: window.bubbleLayoutParams ?: continue
            
            val rect = Rect()
            view.getGlobalVisibleRect(rect)
            
            // Magnetic snap threshold (e.g. 50 pixels)
            val threshold = 50
            
            // Check if edges are close
            val snapLeft = abs(draggedRect.left - rect.right) < threshold
            val snapRight = abs(draggedRect.right - rect.left) < threshold
            val snapTop = abs(draggedRect.top - rect.bottom) < threshold
            val snapBottom = abs(draggedRect.bottom - rect.top) < threshold
            val snapAlignLeft = abs(draggedRect.left - rect.left) < threshold
            val snapAlignTop = abs(draggedRect.top - rect.top) < threshold
            
            var snapped = false
            
            if (snapLeft) {
                draggedParams.x = params.x + rect.width()
                snapped = true
            } else if (snapRight) {
                draggedParams.x = params.x - draggedRect.width()
                snapped = true
            } else if (snapAlignLeft) {
                draggedParams.x = params.x
                snapped = true
            }
            
            if (snapTop) {
                draggedParams.y = params.y + rect.height()
                snapped = true
            } else if (snapBottom) {
                draggedParams.y = params.y - draggedRect.height()
                snapped = true
            } else if (snapAlignTop) {
                draggedParams.y = params.y
                snapped = true
            }
            
            if (snapped) {
                AppLogger.d("FloatingWindowManager", "Magnetic snap: ${draggedWindow.title} to ${window.title}")
                try {
                    draggedWindow.windowManager.updateViewLayout(draggedView, draggedParams)
                } catch (e: Exception) {
                    android.util.Log.e("FloatingWindowManager", "Error snapping window: ${e.message}")
                }
                break // Only snap to one window at a time
            }
        }
    }

    fun onTrimMemory(level: Int) {
        windows.forEach { it.onTrimMemory(level) }
        foldAllExceptActive()
    }
}
