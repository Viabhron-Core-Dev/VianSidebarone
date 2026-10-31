# Receipts Ledger — Part 099

Permanent, sequential audit trail for codebase modifications, testing, and lifecycle actions.
Capped at 500 lines per file.

---

### Entry 108
* Timestamp: 2026-10-31T11:12:00Z
* One-line summary: Fixed floating mini-app interaction model, top-bar drag, bottom-right sticker controls, corner resize, and Heavy process consumer lifecycle.
* Exact files touched:
  - app/src/main/java/com/example/core/FloatingWindowManager.kt
  - app/src/main/java/com/example/core/FloatingWindow.kt
  - app/src/main/java/com/example/feature/floating/FloatingMiniAppBridge.kt
  - app/src/main/java/com/example/feature/floating/FloatingMiniAppWindow.kt
  - app/src/test/java/com/example/feature/floating/FloatingMiniAppFrameworkTest.kt
  - receipts/RECEIPTS_099.md
* What was actually done:
  - Fixed root cause of floating window flickering and touch cancellation on tap/drag:
    * In `FloatingWindowManager.bringToFront(windowId)`, prevented calling `refreshOverlayZOrder` (which calls `windowManager.removeViewImmediate` and `addView`) when the window is already at the top of the Z-order.
    * In `FloatingWindow.getOrCreateContainerView()`, updated `onInterceptTouchEvent` to set focus without tearing down and re-adding top-level overlay views on ACTION_DOWN.
  - Fixed root cause of Heavy disconnect/reconnect on launch:
    * In `FloatingMiniAppBridge.openMiniApp`, acquired active consumer (`"miniapp_$instanceId"`) on `HeavyProcessConnectionManager` so that closing the Sidebar does not drop consumer count to 0.
    * In `sleepMiniApp` and `closeMiniApp`, released the mini-app consumer so Heavy can cleanly teardown when idle.
  - Re-architected Floating Mini-App Shell UI & Touch Model (`FloatingMiniAppWindow.kt`):
    * Removed separate bottom bar.
    * Designed compact "sticker" control cluster attached to the bottom-right of the floating card (`marginEnd = 28dp, bottomMargin = 4dp`):
      - Close button (`✕`) with distinct red theme (`#F38BA8`).
      - Sleep/Minimize button (`—`).
      - Maximize/Restore button (`□` / `❐`) updating label dynamically on toggle.
    * Implemented dedicated bottom-right corner resize handle (`26dp × 26dp`, affordance "↘") with its own non-overlapping hit region.
    * Added 38dp bottom padding to content view so text never collides with bottom-right sticker or resize handle.
    * Top Bar: Dedicated exclusively to title, icon, and gestures (smooth drag using `touchSlop`, long-press move mode, and double-tap maximize/restore without conflict).
    * Enhanced IME observer: shifts `layoutParams.y` upward when keyboard occludes window, restoring exact unmaximized or maximized position on keyboard dismiss without corrupting persistent bounds.
  - Added unit test `testWindowGeometryMoveResizeMaximizeAndImeIndependence` in `FloatingMiniAppFrameworkTest.kt`.
* How it was verified: local build and unit tests only (`gradle :app:testDebugUnitTest` passed all 323 tests; `compile_applet` passed cleanly).
* Any deviation from what was requested, and why: None. No new mini-apps added, eReader/PWA avoided, and all unrelated features preserved untouched.
* Any known issue or follow-up needed: Ready for on-device physical testing.

---

### Entry 109
* Timestamp: 2026-10-31T12:49:00Z
* One-line summary: Added comprehensive structured LogKeeper diagnostic instrumentation across floating mini-app framework lifecycle, IPC, geometry, and runtime boundaries.
* Exact files touched:
  - app/src/main/java/com/example/core/FloatingWindowManager.kt
  - app/src/main/java/com/example/core/ipc/HeavyProcessConnectionManager.kt
  - app/src/main/java/com/example/feature/floating/HeavyFloatingHost.kt
  - app/src/main/java/com/example/feature/floating/HeavyFloatingHostService.kt
  - app/src/main/java/com/example/feature/floating/FloatingMiniAppBridge.kt
  - app/src/main/java/com/example/feature/floating/FloatingMiniAppWindow.kt
  - app/src/main/java/com/example/feature/floating/FloatingMiniAppLauncherBubble.kt
  - receipts/RECEIPTS_099.md
* What was actually done:
  - Instrumented `FloatingWindowManager.kt` with structured tags:
    * `WINDOW_REGISTER`, `WINDOW_UNREGISTER`, `WINDOW_ATTACH`, `WINDOW_DETACH`, `WINDOW_FOCUS`, `WINDOW_BRING_TO_FRONT`.
  - Instrumented `HeavyProcessConnectionManager.kt`:
    * `CONNECT_REQUEST`, `BIND_REQUEST`, `CONNECTED`, `DISCONNECT_REQUEST`, `DISCONNECTED`, `BINDER_DIED`.
    * `CONSUMER_ACQUIRE`, `CONSUMER_RELEASE`.
    * `IPC_FAILURE` logging on errors with cmdId, type, target, and message.
  - Instrumented `HeavyFloatingHost.kt`:
    * `INSTANCE_CREATE_REQUEST`, `INSTANCE_CREATED`, `INSTANCE_ACTIVE`, `INSTANCE_CREATE_FAILURE`.
    * `INSTANCE_RELEASE_REQUEST`, `STATE_SAVED` (zero-PII: metadata `keys`, `textLength`), `INSTANCE_RELEASED`, `INSTANCE_CLOSED`.
  - Instrumented `HeavyFloatingHostService.kt`:
    * `HEAVY_IDLE_CHECK` recording `activeMiniApps`, `activeHeavyPages`, `moduleSessions`, `callRecording`, and `willStop`.
    * `HEAVY_STOP_REQUEST`, `HEAVY_SERVICE_DESTROY`.
  - Instrumented `FloatingMiniAppBridge.kt`:
    * `FLOATING_SESSION_START`, `HEAVY_MOUNT_REQUEST`, `WINDOW_RESTORED`.
    * `SLEEP_REQUEST`, `RECOVERY_SNAPSHOT_REQUEST`, `RECOVERY_SNAPSHOT_READY` (`textLength=...`), `INSTANCE_RELEASE`, `CONTENT_RELEASE`, `WINDOW_HIDDEN`, `RECOVERY_SAVE_START`, `RECOVERY_SAVE_SUCCESS`/`FAILURE`, `FOLDED_LAUNCHER_SHOWN`, `FLOATING_SESSION_END reason=sleep`.
    * `WAKE_REQUEST`, `RECOVERY_LOAD_START`, `RECOVERY_LOAD_SUCCESS`/`FAILURE`, `WAKE_COMPLETE`.
    * `CLOSE_REQUEST`, `INSTANCE_CLOSED`, `RECOVERY_DELETED`/`RECOVERY_RETAINED`, `WINDOW_UNREGISTER`, `CONSUMER_RELEASE`, `FLOATING_SESSION_END reason=close`.
  - Instrumented `FloatingMiniAppWindow.kt`:
    * Top-bar drag gestures: `TOPBAR_DOWN`, `MOVE_START`, `MOVE_END`, `MOVE_CANCEL`, `DOUBLE_TAP`, `MAXIMIZE`, `RESTORE`, `WINDOW_BOUNDS_PERSISTED`.
    * Corner resize: `RESIZE_START`, `RESIZE_END`, `RESIZE_CANCEL`, `WINDOW_BOUNDS_PERSISTED source=resize`.
    * Sticker controls: `CONTROL_CLOSE`, `CONTROL_SLEEP`, `CONTROL_MAXIMIZE`, `CONTROL_RESTORE`.
    * Content & surface: `CONTENT_REQUEST`, `CONTENT_CREATED mode=...`, `SURFACE_ATTACHED`, `SURFACE_DETACHED`, `CONTENT_RELEASED`.
    * IME: `IME_VISIBLE`, `IME_SHIFT`, `IME_TEMP_SHIFT`, `IME_RESTORE`, `IME_TEMP_RESTORE`, `IME_HIDDEN`.
  - Instrumented `FloatingMiniAppLauncherBubble.kt`:
    * `LAUNCHER_POSITION_CHANGED instance=... buttonPosition=(x,y)`, `LAUNCHER_TAPPED instance=...`.
  - Strict PII compliance enforced: Zero user text logged anywhere. Only lengths (`textLength`) and payload key collections are recorded.
* How it was verified: local build and unit tests only (`gradle :app:testDebugUnitTest` passed all 323 tests; `compile_applet` verified successful compilation).
* Any deviation from what was requested, and why: None. Diagnostic logging pass only; no framework or visual alterations.
* Any known issue or follow-up needed: Ready for physical device test run.
