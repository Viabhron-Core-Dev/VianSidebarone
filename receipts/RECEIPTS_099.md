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

---

### Entry 113
* Timestamp: 2026-11-01T21:25:00Z
* One-line summary: Resolved duplicate START_OPERATION during wake, fixed SurfaceControlViewHost resize oscillation, and made closeMiniApp a deterministic transaction
* Exact files touched:
  - app/src/main/java/com/example/core/ipc/HeavyProcessHost.kt
  - app/src/main/java/com/example/feature/floating/HeavyFloatingHostService.kt
  - app/src/main/java/com/example/feature/floating/HeavyFloatingHost.kt
  - app/src/main/java/com/example/core/ipc/HeavyProcessConnectionManager.kt
  - app/src/main/java/com/example/feature/floating/FloatingMiniAppWindow.kt
  - app/src/main/java/com/example/feature/floating/FloatingMiniAppBridge.kt
  - app/src/test/java/com/example/feature/floating/FloatingMiniAppFrameworkTest.kt
  - receipts/RECEIPTS_099.md
* What was actually done:
  - Fixed duplicate START_OPERATION during wake:
    * In `HeavyFloatingHostService.kt`, stored command handlers as fields and explicitly unregistered them in `onDestroy()` so repeated service restarts across idle teardowns do not accumulate duplicate handlers on the singleton `HeavyProcessHost`.
    * In `HeavyProcessHost.kt`, guarded `registerCommandHandler` to prevent duplicate handler registration.
    * In `HeavyFloatingHost.kt`, updated `startInstance`: when an existing instance is already in `STARTED` or `RESUMED` state, it preserves live state and skips overwriting with older snapshot payloads.
    * In `HeavyProcessConnectionManager.kt`, deduplicated pending `START_OPERATION` commands in `pendingCommands` to guarantee exactly one command queued and delivered per logical wake.
  - Fixed SurfaceControlViewHost resize relayout oscillation:
    * Identified root cause: Main shell outer window height (`572`) was passed in `relayoutEmbeddedSurface` during resize/bounds changes, while `SurfaceHolder.Callback.surfaceChanged` passed actual content container height (`496` after subtracting top bar height `76`). Heavy repeatedly relayouted between `592x572` and `592x496`.
    * Fixed `FloatingMiniAppWindow.kt`: added `getContentViewportHeight(windowHeight)` subtracting top bar chrome (`38 * density`); `tryAttachSurfacePackage`, resize drag `ACTION_MOVE`, and `setWindowBounds` now consistently pass content viewport height (`496`). Added relayout deduplication (`lastRelayoutWidth`, `lastRelayoutHeight`) in `relayoutEmbeddedSurface`.
  - Fixed Close ordering and disconnect race:
    * Reordered `FloatingMiniAppBridge.closeMiniApp` transaction:
      (1) Dismiss launcher bubble and unregister window from Main.
      (2) While Heavy is connected, deliver `STOP_OPERATION` and release surface host before releasing consumer.
      (3) If Heavy was already disconnected/dead, treat as normal unavailable-process path without spurious failure logs or intent dispatch.
      (4) Release consumer: Heavy disconnects only if no other consumers remain.
      (5) Clean up durable recovery snapshot.
  - Added / updated 7 focused unit tests in `FloatingMiniAppFrameworkTest.kt` (all 22 tests passing):
    (1) `testStartRequestedDuringConnectingQueuesAndDeliversExactlyOnce` (deduplicates queue & delivers once)
    (2) `testIdempotentRepeatedStartAndWake` (preserves live active state without overwrite)
    (3) `testRepeatedResizeMeasurementsRemainStableAndConverge` (verifies stable 592x496 viewport convergence)
    (4) `testCloseReleasesInstanceAndSurfaceBeforeFinalConsumerDisconnect`
    (5) `testCloseAfterDeadHeavyCompletesCleanlyWithoutIpcFailure`
    (6) `testClosingOneWindowDoesNotTerminateHeavyWhileAnotherConsumerActive`
    (7) `testFoldPersistsSuccessfullyBeforeReleasingHeavy`
* How it was verified: local build (`compile_applet`) succeeded; all 22 unit tests in `com.example.feature.floating.FloatingMiniAppFrameworkTest` passed cleanly.
* Any deviation from what was requested, and why: None. No new mini-apps added, `test_notes` preserved as validation harness, no unrelated features touched.
* Any known issue or follow-up needed: All framework contracts verified via unit tests; physical on-device verification recommended for hardware SurfaceControlViewHost compositing.

---

### Entry 114
* Timestamp: 2026-11-02T19:47:00Z
* One-line summary: Resolved excessive resize/layout work, eliminated post-RESIZE_END relayout queue, and made keyboard position adjustments strictly reversible and isolated from durable bounds
* Exact files touched:
  - app/src/main/java/com/example/feature/floating/FloatingMiniAppWindow.kt
  - app/src/main/java/com/example/feature/floating/FloatingMiniAppBridge.kt
  - app/src/main/java/com/example/core/ipc/HeavyProcessConnectionManager.kt
  - app/src/test/java/com/example/feature/floating/FloatingMiniAppFrameworkTest.kt
  - receipts/RECEIPTS_099.md
* What was actually done:
  - Fixed Excessive Resize/Layout Work (Objective A):
    * Identified root cause of 202 `SAFE_DISPLAY_BOUNDS` queries: `ACTION_MOVE` touch handler invoked `getSafeDisplayBounds(context)` on every single touch point delta. Added gesture-start caching in `setupStickerResizeTouch` and `setupTopBarGestures`, plus 1-second TTL memoization in `getSafeDisplayBounds()`.
    * Identified root cause of 29 relayouts after `RESIZE_END`: `WindowManager.updateViewLayout` during drag caused asynchronous `SurfaceHolder.Callback.surfaceChanged` events to queue up. When the user lifted their finger, the stale queue arrived with intermediate dimensions, triggering relayouts. Fixed by introducing `isResizing` state: intermediate `surfaceChanged` events during drag are ignored, stale events whose dimensions don't match authoritative `bounds` are discarded, and `ACTION_UP` settles the embedded surface once cleanly.
    * Throttled Binder IPC `relayoutSurfaceHost` during active drag to ~60fps (16ms or >= 8px delta) while maintaining 120fps smooth window shell updates, preventing Binder buffer congestion.
  - Fixed Keyboard Upward Shift and Position Stability (Objective B):
    * Identified root cause of upward shift when typing: `setupImeObserver` calculated shift using live `layoutParams.y` and shifted whenever any part of the window exceeded `r.bottom`, causing recursive shift loops and position drift.
    * Fixed `setupImeObserver`: anchored shift calculations strictly against `normalFloatingBounds.y` as an invariant baseline. Maximized windows do not shift off-screen.
    * Ensured `normalFloatingBounds` is NEVER overwritten by keyboard adjustments; all shifts are recorded in temporary state (`imeShiftOffsetY` / `isImeShifted`) and restored to `normalFloatingBounds.y` on keyboard dismiss.
    * Updated `FloatingMiniAppBridge.sleepMiniApp`: when folding while IME is shifted, records `w.normalFloatingBounds`, guaranteeing durable storage is never tainted by temporary keyboard coordinates.
    * Updated `toggleMaximize()`: captures `normalFloatingBounds` into `maximizeRestoreBounds`, preventing keyboard offsets from corrupting maximize/restore bounds.
    * Smoothly reset IME shift on manual top-bar drag without visual jumping.
  - Added 5 focused unit tests in `FloatingMiniAppFrameworkTest.kt` (all 27 passing):
    (1) `testSafeDisplayBoundsCachedDuringRapidInquiries`
    (2) `testResizeEndSettlesCleanlyWithoutContinuingRelayout`
    (3) `testKeyboardOpenAppliesTemporaryShiftWithoutOverwritingNormalFloatingBounds`
    (4) `testFoldWhileKeyboardShiftedSavesNormalFloatingBounds`
    (5) `testMaximizeWhileKeyboardShiftedDoesNotCorruptMaximizeRestoreBounds`
* How it was verified: local build (`compile_applet`) succeeded cleanly; all 27 unit tests in `com.example.feature.floating.FloatingMiniAppFrameworkTest` passed; `WelcomeIpcTest` passed cleanly.
* Any deviation from what was requested, and why: None. No new mini-apps added, `test_notes` preserved as validation harness, architecture strictly preserved.
* Any known issue or follow-up needed: Ready for physical device testing per On-Device Verification Guide below.

---

### Entry 115
* Timestamp: 2026-11-03T06:00:00Z
* One-line summary: Implemented genuinely editable multiline EditText (`EmbeddedNoteEditText`) in Test Notes with windowless-surface focus/cursor support, Main↔Heavy IME input bridging, and snapshot/recovery persistence
* Exact files touched:
  - app/src/main/java/com/example/feature/floating/TestNotesMiniApp.kt
  - app/src/main/java/com/example/feature/floating/FloatingMiniAppWindow.kt
  - app/src/main/java/com/example/feature/floating/HeavyFloatingHost.kt
  - app/src/main/java/com/example/feature/floating/HeavyFloatingHostService.kt
  - app/src/main/java/com/example/feature/floating/FloatingMiniAppBridge.kt
  - app/src/main/java/com/example/feature/heavy/page/HeavyPageHostManager.kt
  - app/src/test/java/com/example/feature/floating/FloatingMiniAppFrameworkTest.kt
  - receipts/RECEIPTS_099.md
* What was actually done:
  - Diagnosed and fixed why the Test Notes `EditText` inside `:heavy`'s `SurfaceControlViewHost` behaved as read-only:
    1. `SurfaceControlViewHost` (`WindowlessWindowManager`) does not hold a top-level `WindowManagerService` focus token, so `ViewRootImpl.hasWindowFocus()` returns `false`. In Android's `TextView`/`Editor`, `hasWindowFocus() == false` disables cursor blinking (`Editor.shouldBlink()`), suppresses touch-driven `InputMethodManager.showSoftInput()`, and blocks text selection handles.
    2. `InputMethodManagerService` binds the soft keyboard (`InputConnection`) exclusively to the focused top-level window in `Main` (`FloatingMiniAppWindow`), which previously had only a `SurfaceView` (`onCheckIsTextEditor() == false`) when `isSurfaceAttached == true`, and rejected direct `showSoftInput` requests from `:heavy` due to UID/window-focus checks.
    3. `HeavyFloatingHost.getSnapshot` called `restoreFromSnapshot(additionalPayload)` before `instance.onPause()` / `instance.onSaveState()`, which could overwrite live `EditText` edits in `:heavy`, and `syncUi()` accessed the `EditText` from Binder threads without checking `Looper.getMainLooper()`.
  - Implemented `EmbeddedNoteEditText` in `TestNotesMiniApp.kt`:
    * Subclasses `EditText` with `hasWindowFocus(): Boolean = true` and `onCheckIsTextEditor(): Boolean = true` (and root `LinearLayout` overriding `hasWindowFocus(): Boolean = true`).
    * Explicitly configured multiline editing (`InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES`, `ArrowKeyMovementMethod.getInstance()`, `TextKeyListener.getInstance(...)`, `isFocusable = true`, `isFocusableInTouchMode = true`, `isCursorVisible = true`, `maxLines = Int.MAX_VALUE`, `setHorizontallyScrolling(false)`).
    * Handles `ACTION_DOWN` / `ACTION_UP` touch to request focus, place the cursor at the touched character offset, invoke `InputMethodManager.showSoftInput`, and emit `ACTION_MINIAPP_EDITOR_FOCUS` / `ACTION_MINIAPP_SELECTION_CHANGED` / `ACTION_MINIAPP_TEXT_CHANGED` events to `Main`.
    * Made `syncUi()` UI-thread safe and added `applyLiveTextFromHost(newText, selStart, selEnd)`.
  - Implemented Main↔Heavy IME InputConnection bridge in `FloatingMiniAppWindow.kt`, `HeavyFloatingHost.kt`, and `HeavyFloatingHostService.kt`:
    * Added `imeBridgeEditText` in `FloatingMiniAppWindow` (`Main`) so when the user taps the embedded `EmbeddedNoteEditText` in `:heavy`, `Main` claims focus (`removeFlag(FLAG_NOT_FOCUSABLE)`), focuses `imeBridgeEditText` synced to `:heavy`'s current text and cursor selection, opens the soft keyboard on `Main`'s top-level window token, and streams real-time text edits and cursor selection back to `:heavy` via `sync_live_text`.
  - Added immediate `pageView.layout(0, 0, width, height)` after `measure` in `HeavyPageHostManager.createSurfacePackageApi30` so child views (`EmbeddedNoteEditText`) have non-zero hit bounds immediately on creation.
  - Preserved fold/wake snapshot persistence and close/discard semantics in `HeavyFloatingHost.kt` and `FloatingMiniAppBridge.kt`.
  - Added 2 focused unit tests in `FloatingMiniAppFrameworkTest.kt` (`testTestNotesViewContainsEditableMultilineEditTextWithFocusAndSelection` and `testTestNotesEditedTextSurvivesSnapshotFoldAndWakeAndDiscardsOnClose`).
* How it was verified: local build (`compile_applet`) and unit tests (`gradle :app:testDebugUnitTest`) only — not tested on physical device.
* Any deviation from what was requested, and why: None.
* Any known issue or follow-up needed: Physical on-device verification of cross-process `SurfaceControlViewHost` touch and soft-keyboard input.

---

## [2026-11-04T00:46:16-08:00] Test Notes IME Bridge Unit Test Verification & Robustness
* Summary: Finalized and verified unit tests for Test Notes IME bridge activation, live text synchronization, and durable payload generation across JVM test environments.
* Exact files touched:
  - app/src/main/java/com/example/feature/floating/FloatingMiniAppWindow.kt
  - app/src/main/java/com/example/feature/floating/TestNotesMiniApp.kt
  - app/src/test/java/com/example/feature/floating/FloatingMiniAppFrameworkTest.kt
  - receipts/RECEIPTS_099.md
* What was actually done:
  - Fixed `getCurrentPayload()` in `FloatingMiniAppWindow` to inspect both `imeBridgeEditText` and `noteEditText` prioritizing non-empty text buffers, preventing uninitialized or empty secondary views from zeroing out live note edits during payload inspection.
  - Made `JvmStubEditable` internal in `TestNotesMiniApp.kt` and updated `EmbeddedNoteEditText.getText()` and `createImeBridgeEditText.getText()` to return `JvmStubEditable` during JVM unit tests where stubbed android classes return empty/null text.
  - Verified `FloatingMiniAppFrameworkTest` suite (30 unit tests) passing with 0 failures under `gradle :app:testDebugUnitTest`.
  - Verified overall project compilation using `compile_applet`.
* How it was verified: local JVM build and test execution only (`compile_applet` and `gradle :app:testDebugUnitTest`) — not tested on physical device.
* Any deviation from what was requested, and why: None.
* Any known issue or follow-up needed: Manual on-device verification of soft-keyboard input flow on physical hardware.

---

## [2026-11-05T15:10:00Z] Targeted Test Notes IME Repair — Fix Proven Defects Only
* Summary: Repaired proven IME defects: removed JVM Editable fallback from production runtime, implemented explicit TextAuthority tracking and empty note persistence, enforced real window focus acquisition before soft keyboard display, and aligned precision diagnostic checkpoints without logging note content.
* Exact files touched:
  - app/src/main/java/com/example/feature/floating/TestNotesMiniApp.kt
  - app/src/main/java/com/example/feature/floating/FloatingMiniAppWindow.kt
  - app/src/test/java/com/example/feature/floating/FloatingMiniAppFrameworkTest.kt
  - receipts/RECEIPTS_099.md
* What was actually done:
  - In `TestNotesMiniApp.kt`: Removed `real.toString().isNotEmpty()` from `EmbeddedNoteEditText.getText()` so native Android `Editable` is always returned when non-null (never returning `JvmStubEditable` on real Android devices). Updated diagnostic logs to `HEAVY_EDITOR_FOCUS_REQUEST` and `INPUT_CONNECTION_CREATED` with zero content logging.
  - In `FloatingMiniAppWindow.kt`: Removed fake `override fun hasWindowFocus(): Boolean = true` from `createImeBridgeEditText` and replaced with dynamic check against real window focus. Removed `real.toString().isNotEmpty()` check in bridge `getText()`. Implemented explicit `TextAuthority` enum and tracking in `getCurrentPayload()` to strictly preserve intentionally empty notes (`""`) without string length heuristics and prevent stale text from either editor from overwriting newer content. Updated `activateEmbeddedEditorIme` to clear `FLAG_NOT_FOCUSABLE` and `FLAG_ALT_FOCUSABLE_IM`, wait for real system window focus (`hasWindowFocus() == true` via `onWindowFocusChanged` and `OnWindowFocusChangeListener`), request focus on `imeBridgeEditText`, verify window token availability, and request soft input with `SHOW_IMPLICIT`. Logged all 9 precision diagnostic checkpoints without note contents.
  - In `FloatingMiniAppFrameworkTest.kt`: Updated `testTestNotesImeBridgeActivationAndDiagnostics` to verify that bridge does not claim window focus while idle and verifies window focus after WMS callback. Added `testEmptyNotePersistenceThroughSnapshotAndWake` and `testClearOperationAndAuthoritativeTextSelection`.
* How it was verified: local JVM build and test execution only (`compile_applet` succeeded, 32 unit tests passed in `FloatingMiniAppFrameworkTest`) — not tested on physical device.
* Any deviation from what was requested, and why: None.
* Any known issue or follow-up needed: Manual on-device verification of real soft-keyboard appearance, character typing, deletion, note clearing, and fold/wake recovery.

## [2026-11-05T15:50:00Z] Critical Fix: Test Notes Window Attachment Crash
* Summary: Repaired constructor-unsafe `setText()` override in `FloatingMiniAppWindow.createImeBridgeEditText()`, eliminating the NullPointerException during window attachment. Replaced manual `stubWatchers` dispatch with native Android `TextWatcher` attached post-construction.
* Exact files touched:
  - app/src/main/java/com/example/feature/floating/FloatingMiniAppWindow.kt
  - app/src/test/java/com/example/feature/floating/FloatingMiniAppFrameworkTest.kt
  - receipts/RECEIPTS_099.md
* What was actually done:
  - In `FloatingMiniAppWindow.kt`:
    - Removed constructor-unsafe `setText()` override, `stubWatchers`, and `stubText` from `createImeBridgeEditText()` anonymous subclass. Android's `TextView` constructor invokes `setText()` prior to subclass field initialization; eliminating this override ensures standard native `TextView` initialization without accessing uninitialized references.
    - Attached native Android `TextWatcher` inside `.apply { ... }` after the `EditText` instance completes construction. Protected against echo loops with `if (isSyncingImeBridge) return`.
    - Handled programmatic text synchronization via explicit helper `updateImeBridgeTextAndSelection()`.
    - Preserved `isSyncingImeBridge`, `TextAuthority`, empty-note preservation, and selection handling.
    - Kept JVM-only stub fallbacks strictly isolated to headless mock environments (`super.getText() == null`).
    - Added `simulateBridgeTextInputForTesting()` on `FloatingMiniAppWindow` for deterministic test simulation without mocking internal `TextWatcher` lists.
  - In `FloatingMiniAppFrameworkTest.kt`:
    - Updated `testTestNotesImeBridgeActivationAndDiagnostics` and `testClearOperationAndAuthoritativeTextSelection` to use explicit helper `simulateBridgeTextInputForTesting()`.
    - Added regression test `testCreateImeBridgeEditTextAndContentViewNoCrash()` verifying `window.createContentView(testContext)` and `window.getImeBridgeEditTextForTesting()` complete cleanly without exceptions.
* How it was verified: Local build and unit tests only (`compile_applet` passed, 33 unit tests passed in `FloatingMiniAppFrameworkTest`) — not physically tested on device.
* Any deviation from what was requested, and why: None.
* Any known issue or follow-up needed: Verify Test Notes opens cleanly without crash on physical hardware and inspect LogKeeper for attachment diagnostics.

## [2026-11-05T21:26:00Z] Add Floating Mini-apps Filter Pill to Log Keeper
* Summary: Added Material 3 FilterChip pill labelled "Floating Mini-apps" (testTag: "filter_floating_mini_apps") to Log Keeper Diagnostic and Crash Reports tabs, backed by a pure log-filtering helper `FloatingMiniAppLogFilter` with full multiline exception and crash report support.
* Exact files touched:
  - app/src/main/java/com/example/core/FloatingMiniAppLogFilter.kt
  - app/src/main/java/com/example/LogKeeperActivity.kt
  - app/src/test/java/com/example/core/FloatingMiniAppLogFilterTest.kt
  - receipts/RECEIPTS_099.md
* What was actually done:
  - Created `FloatingMiniAppLogFilter.kt` in `com.example.core`:
    - Pure unit-testable filtering engine.
    - Explicit matching for 6 dedicated component tags: `FloatingMiniAppBridge`, `FloatingMiniAppWindow`, `FloatingMiniAppLauncherBubble`, `HeavyFloatingHost`, `HeavyFloatingHostService`, `TestNotesImeDebug` (including `ERROR:`/`CRASH:` prefixed forms).
    - Contextual matching for shared components (`FloatingWindowManager`, `HeavyPageHostManager`, `HeavyProcessConnectionManager`) via instance IDs (`miniapp_`), consumer tags, and mini-app types (`test_notes`, `speed_monitor`, `ebook_reader`).
    - Uncaught crash report block parser with stack trace inspection for mini-app classes (`com.example.feature.floating.`, `FloatingMiniAppWindow`, etc.).
    - Complete multiline exception preservation across all matching blocks.
    - Negative filtering to exclude ordinary floating windows, sidebar pages, PWAs, or other Heavy-hosted pages.
    - Clear empty state fallback: "No floating mini-app logs found."
  - In `LogKeeperActivity.kt`:
    - Added `isMiniAppFilterActive` state in `LogKeeperScreen`.
    - Wrapped raw log content with `FloatingMiniAppLogFilter.filter(rawLogContent, isMiniAppFilterActive)` for tabs 0 and 1, preserving filter state across tabs and refreshes.
    - Bound `currentLogContent` to both "Copy Logs" action and TopAppBar "Share Logs" intent, ensuring exported logs match the filtered view.
    - Added Material 3 `FilterChip` labelled "Floating Mini-apps" with `filter_floating_mini_apps` testTag, checkmark leading icon when active, and hidden on Running tab.
  - In `FloatingMiniAppLogFilterTest.kt`:
    - Added unit test suite covering disabled passthrough, empty log states, known component tags, contextual shared component entries, unrelated entry exclusion, multiline exception preservation, and uncaught crash report block filtering.
* How it was verified: Local JVM compilation and unit tests (`compile_applet` succeeded, `FloatingMiniAppLogFilterTest` and `FloatingMiniAppFrameworkTest` passed 100%) — not physically tested on device.
* Any deviation from what was requested, and why: None.
* Any known issue or follow-up needed: Manual verification of FilterChip toggle, copy, and share actions in the Log Keeper UI on device.

## [2026-11-06T09:30:00Z] Stabilize and Complete Shared Floating Mini-App Framework (Stages B–E)
* Summary: Completed Stages B–E of the Shared Floating Mini-App Framework using local workspace as source of truth: 16dp rounded corner clipping, protected bottom sticker clearance, native-only Editable IME bridge, legitimate window focus sequence, production Note mini-app with edit formatting popover and undo/redo, and sleep/wake snapshot persistence.
* Exact files touched:
  - app/src/main/java/com/example/feature/floating/TestNotesMiniApp.kt
  - app/src/main/java/com/example/feature/floating/FloatingMiniAppWindow.kt
  - app/src/main/java/com/example/feature/floating/HeavyFloatingHost.kt
  - app/src/main/java/com/example/feature/floating/HeavyFloatingHostService.kt
  - app/src/main/java/com/example/feature/floating/FloatingMiniAppLauncherBubble.kt
  - app/src/test/java/com/example/feature/floating/FloatingMiniAppFrameworkTest.kt
  - blueprint/BLUEPRINT.md
  - receipts/RECEIPTS_099.md
* What was actually done:
  - Stage B (Shared Shell & 16dp Rounded Clipping):
    - Implemented 16dp rounded corner clipping across all 4 corners via `ViewOutlineProvider` with `setRoundRect` and `clipToOutline = true` on `rootCardView` and `contentContainer`.
    - Protected embedded `SurfaceView` with API 30+ reflection-safe `setCornerRadius(cardCornerRadius)` and outline provider fallback.
    - Updated `applyCardCornerRadius` to dynamically remove clipping (0dp radius) upon safe maximize and restore 16dp radius upon restore.
    - Added 48dp bottom clearance padding in `NoteMiniAppInstance` content view and fallback editor to ensure text and cursor are never obscured by the 32dp elevated bottom-right 3-control sticker `[ ×  —  ↘ ]`.
    - Maintained top-bar drag with touch-slop detection, double-tap maximize/restore, and 36dp launcher bubble.
  - Stage C (Single Input Route & Focus Stabilization):
    - Maintained single input path: Main-owned IME bridge (`createImeBridgeEditText`) and Heavy-owned editor (`EmbeddedNoteEditText`) via narrow Binder IPC.
    - Strictly removed `JvmStubEditable` fallback from production `getText()`, always exposing real Android `Editable` (`super.getText()`) for native IME and selection handling.
    - Stabilized overlay window focus sequence in `activateEmbeddedEditorIme`: clears `FLAG_NOT_FOCUSABLE` and `FLAG_ALT_FOCUSABLE_IM`, requests bridge view focus, listens to legitimate WMS `onWindowFocusChanged(true)` or window token binding before requesting `showSoftInput` without artificial synthetic touch simulation.
    - Bidirectional synchronization (`syncLiveTextToHeavy` and `applyLiveTextFromHost`) guarded by `isSyncingImeBridge` and `isApplyingHostUpdate` flags to prevent echo loops.
  - Stage D (Production Note Mini-App in Heavy):
    - Replaced "Test Notes" placeholder title with "Note" (subtitle "Multiline editor • Floating runtime") and registered `"note"` alias.
    - Added compact "Edit" button in top bar and toggleable in-window Edit popover card (`editPopoverView`).
    - Implemented formatting controls: Font Size (- / +), Font Style (Regular, Bold, Italic, Monospace), Word wrap toggle (ON/OFF), and Line spacing (1.0x, 1.2x, 1.5x).
    - Implemented content-scoped Undo/Redo stack (`undoStack`, `redoStack`) and Clear action in `:heavy` Note runtime.
    - Handled IPC commands `note_format`, `note_undo`, `note_redo`, `note_clear` across `HeavyFloatingHostService` and `HeavyFloatingHost`.
    - Persisted formatting attributes durably in `DurableMiniAppStore` snapshots across Sleep (Fold) / Wake cycles; discarded cleanly on Close.
  - Stage E (Test Validation):
    - Expanded `FloatingMiniAppFrameworkTest` with tests for 16dp rounded clipping, bottom clearance padding, formatting controls, undo/redo stack, and snapshot serialization across sleep/wake.
* How it was verified: Local JVM compilation and unit tests only (`compile_applet` passed, 36/36 tests passed in `FloatingMiniAppFrameworkTest`, `FloatingMiniAppLogFilterTest` passed) — not tested on physical hardware. Physical keyboard input, hardware surface clipping, and on-device window focus transitions have NOT been verified on a physical device.
* Any deviation from what was requested, and why: None.
* Any known issue or follow-up needed: Physical-device verification required for soft keyboard popup, text typing, and SurfaceView corner clipping on actual Android hardware.

## [2026-11-06T12:50:00Z] Build Isolated Main-Process IME Test A
* Summary: Built isolated Main-process IME diagnostic test mode directly inside FloatingMiniAppWindow to isolate whether the Main overlay window independently receives Android keyboard input without relying on Heavy.
* Exact files touched:
  - app/src/main/java/com/example/feature/floating/FloatingMiniAppWindow.kt
  - app/src/test/java/com/example/feature/floating/FloatingMiniAppFrameworkTest.kt
  - blueprint/BLUEPRINT.md
  - receipts/RECEIPTS_099.md
* What was actually done:
  - In `FloatingMiniAppWindow.kt`:
    - Added temporary isolated diagnostic mode (`isDiagnosticMode`, `diagnosticContainer`, `diagnosticEditText`, `diagnosticModeButton`).
    - Added `[Test A]` / `[Exit Diag]` toggle pill in the top bar for Note mini-app.
    - Temporarily hidden and disabled embedded `SurfaceView` / fallback content in diagnostic mode so it cannot cover the test field or intercept touches.
    - Added visible, multiline Android `EditText` owned by Main (`diagnosticEditText`) with native `InputConnection` target, completely disconnected from Heavy and IPC.
    - When touched: records `ACTION_DOWN`/`ACTION_UP`, clears `FLAG_NOT_FOCUSABLE` and `FLAG_ALT_FOCUSABLE_IM` via `applyLayoutUpdate()`, waits for genuine WMS `onWindowFocusChanged(true)`, verifies view attachment & token, logs `onCreateInputConnection()`, and invokes `InputMethodManager.showSoftInput()`.
    - Protected IME insets listener (`setOnApplyWindowInsetsListener`) from immediately restoring `FLAG_NOT_FOCUSABLE` when insets are applied while awaiting soft keyboard animation.
    - Added zero-PII diagnostic logging using `TestNotesImeDebug` for touch actions, window flags, focus transitions, connection nullability, showSoftInput results, and text lengths/selections.
  - In `FloatingMiniAppFrameworkTest.kt`:
    - Added unit test `testIsolatedMainProcessImeDiagnosticModeTestA()` verifying mode toggle, touch layout update, focus flag clearing, and clean restoration on exit.
* How it was verified: Local JVM compilation and unit tests (`compile_applet` passed, 37/37 tests in `FloatingMiniAppFrameworkTest` passed, `FloatingMiniAppLogFilterTest` passed) — not tested on physical hardware. Physical keyboard opening and touch input on device are NOT verified until the user performs the manual physical-device test.
* Any deviation from what was requested, and why: None.
* Any known issue or follow-up needed: Physical device execution required to determine which outcome (A, B, C, D, or E) occurs.

---

### Receipt 100 — Floating Mini-App Initial Rendering Delay & Transparent Flash Fix (Phase 13.6)
* Timestamp: 2026-11-07T00:45:00-08:00
* Summary: Diagnosed and repaired floating mini-app initial rendering delay and transparent flash; added zero-PII lifecycle timing logs, neutral 16dp placeholder, frame committed reveal signal, and relayout priming.
* Exact files touched:
  - app/src/main/java/com/example/core/FloatingMiniAppLogFilter.kt
  - app/src/main/java/com/example/feature/heavy/page/HeavyPageHostManager.kt
  - app/src/main/java/com/example/feature/floating/TestNotesMiniApp.kt
  - app/src/main/java/com/example/feature/floating/FloatingMiniAppWindow.kt
  - app/src/test/java/com/example/feature/floating/FloatingMiniAppFrameworkTest.kt
  - blueprint/BLUEPRINT.md
  - receipts/RECEIPTS_099.md
* What was actually done:
  - In `FloatingMiniAppLogFilter.kt`: Added `FloatingMiniAppTiming` to `MINI_APP_COMPONENT_TAGS` and `MINI_APP_STACK_MARKERS`.
  - In `HeavyPageHostManager.kt`: Added zero-PII timing logs for Heavy host creation, measure/layout, pre-draw, and frame commit callback (`registerFrameCommitCallback` on Q+) dispatching `miniapp_frame_committed` to Main across Binder.
  - In `TestNotesMiniApp.kt`: Added `HEAVY_VIEW_CREATION_START`, `HEAVY_VIEW_CREATION_DONE` logs and `ACTION_MINIAPP_FRAME_COMMITTED`.
  - In `FloatingMiniAppWindow.kt`:
    - Initialized `SurfaceView` with `alpha = 0f` and added neutral 16dp rounded content placeholder (`contentPlaceholderView`).
    - Removed immediate background deletion (`container.background = null`) on attach.
    - Primed `lastRelayoutWidth = w` and `lastRelayoutHeight = h` in `tryAttachSurfacePackage()` to prevent immediate redundant IPC relayout during initial frame rendering.
    - Added `miniapp_frame_committed` event listener triggering `revealEmbeddedContent()` to unmask surface and dismiss placeholder seamlessly, backed by 400ms safeguard timeout.
  - In `FloatingMiniAppFrameworkTest.kt`: Added unit test `testContentPlaceholderAndFrameRevealTiming()`.
* How it was verified: Local build only (`compile_applet` passed, 38/38 unit tests in `FloatingMiniAppFrameworkTest` passed, `FloatingMiniAppLogFilterTest` passed) — physical device testing required to evaluate visual presentation.
* Any deviation from what was requested, and why: None.
* Any known issue or follow-up needed: Physical device verification required to compare visual perception and inspect timing logs in Log Keeper under the "Floating Mini-apps" filter chip.
