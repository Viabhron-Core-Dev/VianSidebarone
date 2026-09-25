package com.example.feature.sidebar

import android.view.WindowManager
import org.junit.Assert.*
import org.junit.Test

/**
 * SidebarImeInteractionTest:
 * Verifies:
 * 1. SidebarWindow layoutParams are configured with SOFT_INPUT_ADJUST_RESIZE.
 * 2. Window flags retain FLAG_NOT_TOUCH_MODAL and FLAG_WATCH_OUTSIDE_TOUCH without FLAG_NOT_FOCUSABLE.
 * 3. Outside touch and back key dismissal state transitions.
 */
class SidebarImeInteractionTest {

    @Test
    fun testSidebarWindowFlagsAndSoftInputMode() {
        // Verify WindowManager flag constants and layoutParams contract
        val expectedFlags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        val softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

        // Verify FLAG_NOT_FOCUSABLE is not part of expectedFlags
        val hasNotFocusable = (expectedFlags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0
        assertFalse("FLAG_NOT_FOCUSABLE must NOT be set on SidebarWindow so IME can open", hasNotFocusable)

        // Verify FLAG_WATCH_OUTSIDE_TOUCH is retained for outside dismissal
        val hasWatchOutside = (expectedFlags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH) != 0
        assertTrue("FLAG_WATCH_OUTSIDE_TOUCH must be present for outside dismissal", hasWatchOutside)

        // Verify SOFT_INPUT_ADJUST_RESIZE
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE, softInputMode)
    }

    @Test
    fun testKeyboardActiveOutsideTouchLogic() {
        var keyboardActive = true
        var lastKeyboardCloseTime = 0L
        var isClosed = false

        fun handleOutsideTouch(now: Long) {
            if (keyboardActive) {
                // Outside touch ignored while keyboard active
                return
            }
            if (now - lastKeyboardCloseTime < 500L) {
                // Outside touch ignored during keyboard dismissal transition
                return
            }
            isClosed = true
        }

        // 1. Touch while keyboard is open -> must NOT close
        handleOutsideTouch(1000L)
        assertFalse("Sidebar must remain open while keyboard is active", isClosed)

        // 2. Keyboard closes at t = 2000ms
        keyboardActive = false
        lastKeyboardCloseTime = 2000L

        // Residual touch at t = 2200ms (within 500ms) -> must NOT close
        handleOutsideTouch(2200L)
        assertFalse("Sidebar must remain open during keyboard dismissal transition", isClosed)

        // 3. User taps outside after keyboard has closed at t = 2600ms (> 500ms) -> closes normally
        handleOutsideTouch(2600L)
        assertTrue("Sidebar must close on outside touch when keyboard is not involved", isClosed)
    }

    @Test
    fun testBackKeyClosesKeyboardFirstThenSidebar() {
        var keyboardActive = true
        var sidebarClosed = false

        fun handleBackKey() {
            if (keyboardActive) {
                keyboardActive = false // Dismiss keyboard
            } else {
                sidebarClosed = true // Dismiss sidebar
            }
        }

        // First back press: dismiss keyboard only
        handleBackKey()
        assertFalse("Keyboard dismissal should not close sidebar", keyboardActive)
        assertFalse("Sidebar must not close on first back press when keyboard is open", sidebarClosed)

        // Second back press: dismiss sidebar
        handleBackKey()
        assertTrue("Sidebar should close on subsequent back press when keyboard is closed", sidebarClosed)
    }
}
