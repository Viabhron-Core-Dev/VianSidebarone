* Timestamp: 2026-08-14T06:20:00-07:00
* One-line summary: Patched lifecycle management for mini-apps, grid pages, and E-Reader true fold logic.
* Exact files touched:
    * `app/src/main/java/com/example/core/FloatingWindow.kt`
    * `app/src/main/java/com/example/service/PageWindowService.kt`
    * `app/src/main/java/com/example/feature/sidebar/SidebarView.kt`
    * `app/src/main/java/com/example/feature/sidebar/HybridGridPageView.kt`
    * `app/src/main/java/com/example/feature/sidebar/WidgetsGridPageView.kt`
    * `app/src/main/java/com/example/feature/miniapps/reader/FloatingReaderService.kt`
* What was actually done:
    * PageWindowService Gap: Added `var onClose: (() -> Unit)?` to `FloatingWindow`, invoked on `hide()`. Registered `PageWindowService` to this hook to remove dead references from its tracker map when windows are closed from their own X button.
    * Grid Unstructured Coroutines: Refactored `HybridGridPageView` and `WidgetsGridPageView` constructors to accept the parent `SidebarView`'s `viewScope`. Replaced raw `CoroutineScope(Dispatchers.Main)` and `CoroutineScope(Dispatchers.IO)` with this managed scope, ensuring icon loading stops cleanly when the sidebar detaches.
    * Reader Teardown: Built `closeReader()` in `FloatingReaderService` which saves position, forcefully removes all layouts, and executes `stopSelf()`. Hooked this to the bottom control exit button (`btn_exit_bottom`).
    * Reader Fold Logic (True Fold): Re-engineered `setFolded(true)` in `FloatingReaderService` to mirror `FloatingWindow`. It now unhooks the massive `floatingView` from `WindowManager` (saving GPU/composition memory without destroying book state) and spawns an independent, lightweight `bubbleView`. When tapped, the bubble removes itself and reattaches the `floatingView`.
* How it was verified: local build only (gradle compileDebugKotlin successful)
* Any deviation from what was requested: None.
* Known issues: None.
