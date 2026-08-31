# Session Receipts - Sidebar Outside Tap Fix

## Actions Taken
- Checked `SidebarView.kt` in `feature/sidebar`.
- Found that `FLAG_WATCH_OUTSIDE_TOUCH` was set, but `onTouchEvent` handling `MotionEvent.ACTION_OUTSIDE` was missing.
- Injected `onTouchEvent` to call the `onClose` callback when tapping outside.
- Added `dispatchKeyEvent` to handle hardware back button presses as well, matching the behavior of the old `SidebarView`.

## Verification
- Waiting for compilation.
