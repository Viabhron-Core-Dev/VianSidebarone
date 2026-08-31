* Timestamp: 2026-08-14T05:13:00-07:00
* One-line summary: Implemented Phase 12 Z-Window Manager (Excluding Popups)
* Exact files touched: 
    * `app/src/main/java/com/example/core/FloatingWindowManager.kt`
    * `app/src/main/java/com/example/core/FloatingWindow.kt`
* What was actually done: 
    * Enhanced `FloatingWindowManager` to track `focusedWindow`.
    * Implemented `bringToFront` to automatically update focus.
    * Added `foldAllExceptActive` and `foldAll` for dormant folding and memory management.
    * Upgraded `checkCollisions` to perform magnetic snapping when windows are dragged near each other.
    * Injected a custom `FrameLayout` wrapper in `FloatingWindow.kt`'s `createContainerView` that overrides `onInterceptTouchEvent` to instantly trigger `bringToFront` upon tapping anywhere inside a dormant window.
    * Intentionally bypassed OS Popups logic as mandated by the user ("Is popup later").
* How it was verified: local build only (Gradle compilation successful)
* Any deviation from what was requested: None. Popups skipped intentionally.
* Known issues: None.
