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

---

### Entry 110
* Timestamp: 2026-10-31T13:32:00Z
* One-line summary: Corrected floating mini-app framework Fold transactional ordering, folded launcher dimension stability, 3-control rounded sticker with ↘ resize, true safe maximize, geometry separation, and Heavy SurfacePackage embedding.
* Exact files touched:
  - app/src/main/java/com/example/feature/floating/FloatingMiniAppBridge.kt
  - app/src/main/java/com/example/feature/floating/FloatingMiniAppLauncherBubble.kt
  - app/src/main/java/com/example/feature/floating/FloatingMiniAppWindow.kt
  - app/src/main/java/com/example/feature/heavy/page/HeavyPageHostManager.kt
  - app/src/main/java/com/example/feature/floating/HeavyFloatingHostService.kt
  - app/src/test/java/com/example/feature/floating/FloatingMiniAppFrameworkTest.kt
  - receipts/RECEIPTS_099.md
* What was actually done:
  - Reordered Fold sequence into a safe transaction (`FloatingMiniAppBridge.kt`):
    * Consumer release deferred until AFTER snapshot is gathered and durably saved in Main.
    * If snapshot retrieval fails or durable save fails: transaction is aborted, runtime remains active, and error is logged.
    * Consumer released only after durable save confirmation, allowing Heavy to cleanly idle/teardown.
  - Eliminated duplicate `START_OPERATION` on wake/start:
    * Coordinated connection acquisition in `openMiniApp` before show.
    * Prevented dual-dispatch of queued Binder commands and fallback Intents in `FloatingMiniAppWindow.onShow()`.
    * Added explicit `CMD_START_OPERATION` and `INTENT_START_MINIAPP` logging in `HeavyFloatingHostService`.
  - Enabled API 30+ Heavy embedded UI (`HeavyPageHostManager.kt` & `FloatingMiniAppWindow.kt`):
    * Supported mini-app instances in `HeavyPageHostManager.createSurfacePackageApi30`.
    * Attached `SurfaceView` with `setChildSurfacePackage` in `FloatingMiniAppWindow`, logging `CONTENT_MODE_SELECTED mode=surface_package reason=...` and explicit fallback when unavailable.
  - Fixed folded launcher bubble dimensions & stability (`FloatingMiniAppLauncherBubble.kt`):
    * Explicitly set `bubbleSizePx` via `setWindowBounds()` during `init`, fixing the initial 600x800 LayoutParams bug that caused shrinking on drag.
    * Dragging strictly updates x/y while preserving fixed 52dp width and height.
    * Logged `LAUNCHER_ATTACH`, `LAUNCHER_MOVE_START`, and `LAUNCHER_MOVE_END`.
  - Implemented 3-control rounded sticker & card shell (`FloatingMiniAppWindow.kt`):
    * Applied 16dp rounded corners with `clipToOutline = true` at the common shell level.
    * Replaced previous bottom cluster with rounded pill container containing exactly `[ ×  —  ↘ ]`.
    * The `↘` region in the sticker is the direct resize touch target; removed separate corner resize handle.
  - True safe maximize & strict geometry separation (`FloatingMiniAppWindow.kt`):
    * Authoritative safe display bounds determined from system insets, navigation bar, and display cutout without arbitrary margins.
    * Maximize does not overwrite `normalFloatingBounds` or persist maximized bounds to durable storage.
    * Double-tap top-bar restores exact previous normal floating bounds and rounded corners.
    * Logged `SAFE_DISPLAY_BOUNDS`, `MAXIMIZE_BOUNDS`, and `RESTORE_BOUNDS`.
  - Added unit tests in `FloatingMiniAppFrameworkTest.kt`:
    * `testFoldSafeTransactionalOrderingAndPreservation`
    * `testLauncherDimensionsRemainStableBeforeDuringAndAfterDrag`
    * `testMaximizePreservesNormalFloatingBoundsWithoutOverwrite`
* How it was verified: local build and unit tests (`gradle :app:testDebugUnitTest` passed all 326 tests; `compile_applet` compiled cleanly).
* Any deviation from what was requested, and why: None. No new mini-apps added, eReader/PWA avoided, and all unrelated features preserved untouched.
* Any known issue or follow-up needed: Ready for physical device acceptance validation.

---

### Entry 111
* Timestamp: 2026-11-01T11:32:00Z
* One-line summary: Repaired floating mini-app framework Heavy lifecycle and disconnects, fixed SurfacePackage full-height measurement and rendering, replaced folded launcher bubble with 36dp circular icon, and cleaned up Add Element floating items.
* Exact files touched:
  - app/src/main/java/com/example/feature/floating/FloatingMiniAppLauncherBubble.kt
  - app/src/main/java/com/example/feature/settings/AddElementActivity.kt
  - app/src/main/java/com/example/feature/heavy/page/HeavyPageHostManager.kt
  - app/src/main/java/com/example/feature/floating/FloatingMiniAppWindow.kt
  - app/src/main/java/com/example/feature/floating/HeavyFloatingHostService.kt
  - app/src/main/java/com/example/core/ipc/HeavyProcessConnectionManager.kt
  - app/src/test/java/com/example/feature/floating/FloatingMiniAppFrameworkTest.kt
  - receipts/RECEIPTS_099.md
* What was actually done:
  - Identified and eliminated root cause of immediate Heavy disconnect/reconnect on mini-app launch:
    * In `HeavyFloatingHostService.kt`, `checkDisposableTeardown()` was previously invoked on every `onStartCommand()`. Because the start command arrives before the asynchronous Binder command `START_OPERATION` registers the active instance, `getActiveInstanceCount()` was 0, triggering an immediate `stopSelf()` and causing Main's Binder DeathRecipient to trip `DISCONNECTED`.
    * Removed premature teardown calls on startup/`ACTION_START_MINIAPP`; teardown check is now restricted to explicit sleep, stop, destroy, and unmount triggers.
  - Repaired SurfaceControlViewHost measurement and touch routing in Heavy process:
    * In `HeavyPageHostManager.kt` (`createSurfacePackageApi30`), mini-app root views were previously measured with `MeasureSpec.UNSPECIFIED` height. Weighted views (such as `EditText` with `height=0dp, weight=1f` in `TestNotesMiniAppInstance`) collapsed to 0, resulting in a host height of only ~120px (the title header) inside an 800px window. Content below was truncated and unreceptive to touch.
    * Measured with `MeasureSpec.EXACTLY` for both width and height, establishing full-window dimensions (`finalHostHeight = height`).
  - Corrected surface visibility and compositing in Main floating window:
    * In `FloatingMiniAppWindow.kt`, set `holder.setFormat(PixelFormat.TRANSLUCENT)` and `setZOrderMediaOverlay(true)` on `embeddedSurfaceView`.
    * Cleared `container.background = null` upon attaching `SurfaceControlViewHost` so the card container's opaque background does not occlude the embedded view.
    * Added structured `CMD_START_RESULT` diagnostic logging.
  - Fixed explicit Component Intent in `HeavyProcessConnectionManager.kt`:
    * Replaced string package lookup with explicit `Intent(ctx, HeavyFloatingHostService::class.java)` to prevent `NullPointerException` during `bindService`.
  - Replaced folded launcher bubble with compact 36dp circular icon bubble matching standalone Reader:
    * Resized `FloatingMiniAppLauncherBubble.kt` to fixed 36dp × 36dp (matching `36dp` in `layout_floating_reader.xml`).
    * Removed text label `TextView`.
    * Centered app icon via `ImageView` inside circular background with 36dp drag clamping and tap-to-wake.
  - Cleaned up Add Element floating windows section:
    * In `AddElementActivity.kt`, updated `floatingItems` to show only "Floating Trigger", "eBook Reader", and "Test Notes".
    * Removed obsolete items (Hybrid Grid, Dictionary, Cursor Trackpad, Work Notes, Page Window) from the UI picker while preserving catalog definitions (`ALL_FLOATING_WINDOWS`), legacy dispatchers, and PWAs untouched.
* How it was verified: local build and unit tests (`gradle :app:testDebugUnitTest --tests "com.example.feature.floating.FloatingMiniAppFrameworkTest"` passed all tests; `compile_applet` passed cleanly).
* Any deviation from what was requested, and why: None. No new mini-apps added, eReader/PWA avoided, and all unrelated features preserved untouched.
* Any known issue or follow-up needed: Ready for manual physical device verification.

---

### Entry 112
* Timestamp: 2026-11-01T20:10:00Z
* One-line summary: Repaired floating mini-app cross-process SurfacePackage lifecycle, queued command delivery, and fold transaction ordering
* Exact files touched:
  - app/src/main/java/com/example/feature/floating/HeavyFloatingHost.kt
  - app/src/main/java/com/example/feature/floating/HeavyFloatingHostService.kt
  - app/src/main/java/com/example/feature/floating/FloatingMiniAppBridge.kt
  - app/src/main/java/com/example/core/ipc/HeavyProcessConnectionManager.kt
  - app/src/main/java/com/example/core/ipc/HeavyHostBinder.kt
  - app/src/main/java/com/example/feature/heavy/page/HeavyPageHostManager.kt
  - app/src/main/java/com/example/feature/floating/FloatingMiniAppWindow.kt
  - app/src/main/java/com/example/feature/floating/TestNotesMiniApp.kt
  - app/src/test/java/com/example/feature/floating/FloatingMiniAppFrameworkTest.kt
  - receipts/RECEIPTS_099.md
* What was actually done:
  - Fixed cross-process SurfacePackage null & premature fallback:
    * In `FloatingMiniAppWindow.kt`, implemented `HeavyConnectionListener` to track `:heavy` process connection state. When `surfaceCreated` fires while connection is `CONNECTING`, window enters `SURFACE_WAITING_FOR_CONNECTION` and defers creation instead of dropping immediately to `in_process_fallback`. When `onConnected()` fires, `tryAttachSurfacePackage()` mounts the cross-process host cleanly.
    * In `HeavyHostBinder.kt` (`HeavyHostProxy`), explicitly set `bundle?.classLoader = SurfaceControlViewHost.SurfacePackage::class.java.classLoader` to eliminate cross-process parceling failures.
    * In `HeavyPageHostManager.kt`, released any previous stale `SurfaceControlViewHost` tied to dead host tokens before re-mounting on wake, and resolved display IDs safely.
  - Fixed Heavy connection and command ordering:
    * In `HeavyProcessConnectionManager.kt`, commands queued during `CONNECTING` state now succeed immediately with `IpcResult.success("Queued for delivery upon connection")` rather than logging false `IPC_FAILURE` errors. Queued commands are drained exactly once when `onServiceConnected` fires.
    * In `HeavyFloatingHost.kt`, `startInstance` was made idempotent: when an instance already exists, incoming snapshot payloads and bounds are restored without duplicating instances or failing the wake transaction.
  - Enforced strict 6-step Fold transaction ordering:
    * Added non-destructive `getSnapshot(instanceId, additionalPayload)` in `HeavyFloatingHost` and `PAUSE_OPERATION` (`action = "get_snapshot"`) without premature teardown.
    * Added explicit `releaseInstance(instanceId, reason)` in `HeavyFloatingHost` and `STOP_OPERATION` (`action = "release_sleeping_instance"`).
    * Reordered `FloatingMiniAppBridge.sleepMiniApp`: (1) request non-destructive snapshot, (2) verify durable write in `DurableMiniAppStore`, (3) release Heavy instance and surface host only after write confirmation, (4) unregister window and release consumer. If snapshot or durable save fails, live instance in `:heavy` remains intact.
  - Added 5 new unit tests to `FloatingMiniAppFrameworkTest.kt`: verified fold-safe snapshot preservation on persistence failure, idempotent wake, command queuing during `CONNECTING`, dormant record restoration without waking Heavy, and surface host cleanup with teardown checks.
* How it was verified: local build and unit tests (`gradle :app:testDebugUnitTest --tests "com.example.feature.floating.FloatingMiniAppFrameworkTest"` passed all 17 tests; `compile_applet` passed cleanly).
* Any deviation from what was requested, and why: None. No new mini-apps added, `test_notes` preserved as validation harness, no unrelated features touched.
* Any known issue or follow-up needed: All framework contracts verified via unit tests; physical on-device verification recommended for hardware SurfaceControlViewHost compositing.

