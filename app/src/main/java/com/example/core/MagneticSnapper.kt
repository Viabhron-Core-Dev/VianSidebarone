package com.example.core

import kotlin.math.abs

/**
 * MagneticSnapper: Helper responsible for collision detection and magnetic edge snapping.
 * Snaps moving window bounds to screen boundaries and adjacent visible windows within snapThresholdPx.
 */
object MagneticSnapper {

    const val DEFAULT_SNAP_THRESHOLD_PX = 28

    fun checkCollisions(
        movingWindowId: String,
        candidateBounds: WindowBounds,
        allWindows: Collection<FloatingWindow>,
        screenWidth: Int,
        screenHeight: Int,
        snapThresholdPx: Int = DEFAULT_SNAP_THRESHOLD_PX
    ): WindowBounds {
        var snappedX = candidateBounds.x
        var snappedY = candidateBounds.y
        val width = candidateBounds.width
        val height = candidateBounds.height

        // 1. Magnetic snap to Screen boundaries
        if (abs(snappedX) <= snapThresholdPx) {
            snappedX = 0
        }
        if (abs((snappedX + width) - screenWidth) <= snapThresholdPx) {
            snappedX = screenWidth - width
        }
        if (abs(snappedY) <= snapThresholdPx) {
            snappedY = 0
        }
        if (abs((snappedY + height) - screenHeight) <= snapThresholdPx) {
            snappedY = screenHeight - height
        }

        // 2. Magnetic snap to Other Visible Windows
        for (other in allWindows) {
            if (other.windowId == movingWindowId || !other.isVisible || other.isFolded) continue

            val oBounds = other.bounds

            // Horizontal magnetic snapping (moving left to other right, or moving right to other left)
            if (abs(snappedX - oBounds.right) <= snapThresholdPx) {
                snappedX = oBounds.right
            } else if (abs((snappedX + width) - oBounds.x) <= snapThresholdPx) {
                snappedX = oBounds.x - width
            } else if (abs(snappedX - oBounds.x) <= snapThresholdPx) {
                snappedX = oBounds.x
            }

            // Vertical magnetic snapping (moving top to other bottom, or moving bottom to other top)
            if (abs(snappedY - oBounds.bottom) <= snapThresholdPx) {
                snappedY = oBounds.bottom
            } else if (abs((snappedY + height) - oBounds.y) <= snapThresholdPx) {
                snappedY = oBounds.y - height
            } else if (abs(snappedY - oBounds.y) <= snapThresholdPx) {
                snappedY = oBounds.y
            }
        }

        return WindowBounds(snappedX, snappedY, width, height)
    }
}
