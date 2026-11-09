# Receipts Log — 100

Permanent audit trail for code changes, fixes, builds, and test verification.
Capped at 500 lines per file.

---

### Receipt 101 — Heavy Note Editor Typing Fix (IME Insets Focus Race & IMM Binding)
* Timestamp: 2026-11-07T11:03:00-08:00
* One-line summary of what was requested: Fix the inability to type into the actual floating Test Notes mini-app by repairing the WindowInsets focus race and IMM target binding on the Main IME bridge, while preserving Test A diagnostic mode and Phase 13.6 rendering updates.
* Exact files touched:
  - `app/src/main/java/com/example/feature/floating/FloatingMiniAppWindow.kt`
  - `app/src/test/java/com/example/feature/floating/FloatingMiniAppFrameworkTest.kt`
  - `blueprint/BLUEPRINT.md`
  - `receipts/RECEIPTS_100.md`
* What was actually done — factual, not persuasive:
  - Diagnosed root cause 1: In `ViewCompat.setOnApplyWindowInsetsListener`, when `activateEmbeddedEditorIme()` cleared `FLAG_NOT_FOCUSABLE`, the resulting window layout update triggered an insets dispatch while `imeVisible` was false. Because the window was already in focus, `isAwaitingWindowFocusForIme` was set to false, causing the insets listener to immediately re-arm `FLAG_NOT_FOCUSABLE` before Android's InputMethodManager could connect.
  - Repaired root cause 1: Added `isNoteEditorImeActive`, `isImeVisibleInInsets`, and `isAwaitingImeVisibility` state tracking. While `isNoteEditorImeActive` or `isAwaitingImeVisibility` is true, the insets listener preserves window focusability. Added `deactivateEmbeddedEditorIme()` which cleanly restores `FLAG_NOT_FOCUSABLE`, clears bridge focus, and hides soft input when the keyboard is confirmed dismissed (`!imeVisible` after being visible), or when the window hides (`onHide()`), or is destroyed (`onDestroy()`).
  - Diagnosed root cause 2: Android's `InputMethodManager` does not bind to off-screen / zero-alpha views unless verified attached with a valid window token and declared as the active served view.
  - Repaired root cause 2: In `triggerImeFocusOnBridge()`, verified `bridge.isAttachedToWindow` and `bridge.windowToken != null` before triggering IMM, deferring via `bridge.post` if not yet ready. Invoked `imm.viewClicked(bridge)` and `imm.restartInput(bridge)` before `imm.showSoftInput()`, and added `windowInsetsController?.show(WindowInsets.Type.ime())` on API 30+.
  - Preserved Heavy authoritative data model: Kept live text synchronization via `syncLiveTextToHeavy` and native `TextWatcher` without feedback loops.
  - Preserved Test A diagnostic mode and all Phase 13.6 timing/placeholder logic completely.
  - In `FloatingMiniAppFrameworkTest.kt`: Added `testNoteEditorImeFocusLockLifecycleAndSync()` and `testNoteEditorImeDeactivationOnHideAndDestroy()` validating focus-lock lifecycle, race prevention, bridge text sync, keyboard dismissal deactivation, and lifecycle clean-up on hide/destroy.
* How it was verified: local build only (`compile_applet` passed, 40/40 tests in `FloatingMiniAppFrameworkTest` passed, `FloatingMiniAppLogFilterTest` passed) — on-device claim only / not tested on physical hardware. Physical keyboard typing and character commit must be verified on a physical Android device.
* Any deviation from what was requested, and why: None.
* Any known issue or follow-up needed: Physical device verification required to confirm that soft keyboard opens and characters appear in the embedded Heavy Note editor upon tapping note content.

---

### Receipt 102 — Diagnose and Repair Note Launch Routing & Heavy Editor Touch Delivery
* Timestamp: 2026-11-07T12:41:40-08:00
* One-line summary of what was requested: Diagnose and repair unexpected Note mini-app launch routing (Problem A) and trace/repair complete 6-boundary touch delivery and IME handoff for the Heavy Note editor (Problem B) with evidence-first zero-PII logging, single IME authority, and automated tests.
* Exact files touched:
  - `app/src/main/java/com/example/core/FloatingMiniAppLogFilter.kt`
  - `app/src/main/java/com/example/feature/sidebar/SidebarView.kt`
  - `app/src/main/java/com/example/feature/sidebar/SidebarEditNavigator.kt`
  - `app/src/main/java/com/example/feature/settings/AddElementActivity.kt`
  - `app/src/main/java/com/example/SidebarEditActivity.kt`
  - `app/src/main/java/com/example/HybridGridEditActivity.kt`
  - `app/src/main/java/com/example/feature/element/ElementActionDispatcher.kt`
  - `app/src/main/java/com/example/feature/miniapps/MiniAppManager.kt`
  - `app/src/main/java/com/example/feature/floating/FloatingMiniAppBridge.kt`
  - `app/src/main/java/com/example/feature/floating/FloatingMiniAppWindow.kt`
  - `app/src/main/java/com/example/feature/heavy/page/HeavyPageHostManager.kt`
  - `app/src/main/java/com/example/feature/floating/TestNotesMiniApp.kt`
  - `app/src/test/java/com/example/feature/floating/FloatingMiniAppFrameworkTest.kt`
  - `blueprint/BLUEPRINT.md`
  - `receipts/RECEIPTS_100.md`
* What was actually done — factual, not persuasive:
  - Problem A Routing Diagnostics: Added zero-PII logging tagged `MiniAppLaunchRouting` and `ROUTING_DIAG` across the entire action and navigation chain:
    1. `SidebarView.kt`: `EDIT_BUTTON_CLICKED` with session, containerId, pageIndex, pageType.
    2. `SidebarEditNavigator.kt`: `NAVIGATE_TO_EDIT_SCREEN` logging target edit activity.
    3. `AddElementActivity.kt`: `ADD_ELEMENT_ACTIVITY_CREATED`, `ELEMENT_PICKER_ITEM_CLICKED` (distinguishing Note), `HANDLE_ELEMENT_SELECTED`, and `FINISH_WITH_ELEMENT_ID`.
    4. `SidebarEditActivity.kt` & `HybridGridEditActivity.kt`: `ON_ACTIVITY_RESULT` logging request, result, and added element ID.
    5. `ElementActionDispatcher.kt`: `ELEMENT_ACTION_PAGE_WINDOW` and `ELEMENT_ACTION_SYSTEM_ACTION` logging element clicks.
    6. `MiniAppManager.kt` & `FloatingMiniAppBridge.kt`: `MINI_APP_MANAGER_TOGGLE`, `BRIDGE_OPEN_MINI_APP`, and `BRIDGE_TOGGLE_MINI_APP` capturing caller stack traces (`class.method:line`).
    7. Updated `FloatingMiniAppLogFilter.kt` so `MiniAppLaunchRouting` is included in the "Floating Mini-apps" filter chip.
  - Problem B Touch Delivery & IME Architecture:
    1. Traced and logged all 6 touch boundaries:
       - Boundary 1 (`FloatingMiniAppWindow.kt` `rootCardView`): `TOUCH_BOUNDARY_1_MAIN_OVERLAY` on `dispatchTouchEvent`.
       - Boundary 2 (`FloatingMiniAppWindow.kt` `embeddedSurfaceView`): `TOUCH_BOUNDARY_2_MAIN_SURFACE_VIEW_DISPATCH` & `TOUCH_BOUNDARY_2_MAIN_SURFACE_VIEW_TOUCH`.
       - Boundary 3 (`HeavyPageHostManager.kt`): Added `"note"` to mini-app pageType list and logged `TOUCH_BOUNDARY_3_HEAVY_HOST_LIFECYCLE` when `host.setView()` completes.
       - Boundary 4 (`TestNotesMiniApp.kt` root layout): `TOUCH_BOUNDARY_4_HEAVY_ROOT_DISPATCH` & `TOUCH_BOUNDARY_4_HEAVY_ROOT_TOUCH`.
       - Boundary 5 (`TestNotesMiniApp.kt` `EmbeddedNoteEditText`): `TOUCH_BOUNDARY_5_HEAVY_EDITOR_DISPATCH`.
       - Boundary 6 (`TestNotesMiniApp.kt` `EmbeddedNoteEditText`): `TOUCH_BOUNDARY_6_HEAVY_EDITOR_TOUCH` on `ACTION_DOWN` and `ACTION_UP`.
    2. Focus Priming on `ACTION_DOWN`: Added early dispatch of `onEditorFocusedOrTouched()` on `ACTION_DOWN` so Main clears `FLAG_NOT_FOCUSABLE` before `ACTION_UP`, preventing touch-up timing drops.
    3. Architectural Cleanup: Removed competing `imm.showSoftInput(this)` and `windowInsetsController?.show` in Heavy inside windowless `SurfaceControlViewHost`. Main alone owns window focus and soft input activation.
    4. Preserved Test A diagnostic mode, Phase 13.6 rendering delay/placeholder code, and Phase 13.7 focus race fixes.
    5. Added unit tests in `FloatingMiniAppFrameworkTest.kt` verifying touch boundaries, `ACTION_DOWN` focus priming, and routing diagnostics filter tags.
* How it was verified: local build only (`compile_applet` passed, 42/42 tests in `FloatingMiniAppFrameworkTest` passed) — on-device claim only / not tested on physical hardware. Physical on-device execution required to observe log traces and test keyboard typing.
* Any deviation from what was requested, and why: None.
* Any known issue or follow-up needed: On-device testing required to collect `ROUTING_DIAG` trace upon tapping Sidebar Edit and `TOUCH_BOUNDARY` trace upon tapping the Note editor.

---

### Receipt 103 — Phase 14.1 Controlled Heavy-Native IME Experiment
* Timestamp: 2026-11-07T14:34:00-08:00
* One-line summary of what was requested: Configure a controlled, reversible experiment to determine whether the Heavy process can directly own the active Android InputConnection through SurfaceControlViewHost by having Main act strictly as the Focus Carrier while Heavy requests focus and triggers soft input.
* Exact files touched:
  - `app/src/main/java/com/example/feature/floating/FloatingMiniAppWindow.kt`
  - `app/src/main/java/com/example/feature/floating/TestNotesMiniApp.kt`
  - `app/src/test/java/com/example/feature/floating/FloatingMiniAppFrameworkTest.kt`
  - `receipts/RECEIPTS_100.md`
* What was actually done — factual, not persuasive:
  - Added `FloatingMiniAppWindow.EXPERIMENT_HEAVY_NATIVE_IME` toggle (default `true` for live physical-device experiment).
  - In `FloatingMiniAppWindow.kt`: When `EXPERIMENT_HEAVY_NATIVE_IME` is `true`, `activateEmbeddedEditorIme` clears `FLAG_NOT_FOCUSABLE` and `FLAG_ALT_FOCUSABLE_IM` to arm window focus, but intentionally skips requesting focus on `imeBridgeEditText` and skips calling `triggerImeFocusOnBridge()`. Main acts strictly as the Focus Carrier window.
  - In `TestNotesMiniApp.kt`:
    1. On `ACTION_UP`, `EmbeddedNoteEditText` requests focus via `requestFocus()`, invokes `imm.viewClicked(this)` and `imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)`, and calls `windowInsetsController?.show(WindowInsets.Type.ime())` on API 30+.
    2. Emits structured diagnostic log `PHASE_14_1_HEAVY_SHOW_IME_ATTEMPT` logging `immClicked`, `showSoftResult`, `insetsResult`, `hasWinFocus`, and `tokenNull`.
    3. Wrapped `onCreateInputConnection()` in `InputConnectionWrapper` to log typing events (`commitText`, `setComposingText`, `finishComposingText`, `deleteSurroundingText`, `setSelection`) without capturing PII.
  - In `FloatingMiniAppFrameworkTest.kt`: Added `testPhase14HeavyNativeImeExperimentToggle` verifying the toggle state and window focus clearing. Maintained 100% test compatibility for existing bridge tests by scoping toggle overrides.
  - Reversibility: The experiment is 100% reversible by setting `FloatingMiniAppWindow.EXPERIMENT_HEAVY_NATIVE_IME = false` or reverting the 2 touched source files.
* How it was verified: local build only (`compile_applet` passed, 43/43 tests in `FloatingMiniAppFrameworkTest` passed) — on-device claim only / not tested on physical hardware. Physical on-device execution required to observe whether Gboard opens and whether Heavy's `InputConnection` is served.
* Any deviation from what was requested, and why: None.
* Known issue or follow-up needed: Physical on-device test required to check Log Keeper for `PHASE_14_1_HEAVY_SHOW_IME_ATTEMPT` and `INPUT_CONNECTION_CREATED` and observe keyboard typing.

---

### Receipt 104 — Complete Note Mini-App Rebuild (Main-Owned Native Editor)
* Timestamp: 2026-11-08T10:41:00-08:00
* One-line summary of what was requested: Complete rebuild of Note mini-app with Main-owned native EditText architecture, single authoritative editor, clean top bar, formatting popover, and preserved shared floating framework.
* Exact files touched:
  - `app/src/main/java/com/example/feature/floating/FloatingMiniAppWindow.kt`
  - `blueprint/BLUEPRINT.md`
  - `receipts/RECEIPTS_100.md`
* What was actually done — factual, not persuasive:
  - Rebuilt Note mini-app content container in `FloatingMiniAppWindow.kt`:
    1. Instantiated single authoritative multiline `EditText` (`noteEditText`) directly in Main's `contentContainer` for `"note"` and `"test_notes"`.
    2. Attached native IME and Gboard bindings directly to `noteEditText` under `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`.
    3. Added `ACTION_DOWN` window focus clearance (`FLAG_NOT_FOCUSABLE` and `FLAG_ALT_FOCUSABLE_IM` cleared via `applyLayoutUpdate()`) with automatic soft input invocation.
    4. Enforced 16dp rounded card clipping, top-bar drag with touch-slop detection, double-tap maximize/restore, persistent normal bounds, 36dp launcher bubble, and bottom-right sticker controls `[ ×  —  ↘ ]`.
    5. Provided `48dp` bottom clearance padding in the editor to prevent any overlap with `[ ×  —  ↘ ]`.
    6. Cleaned top bar: Note icon, "Note" title, and single "Edit" button. Removed obsolete "Test A" button.
    7. Wired formatting popover (Font Size, Style, Wrap toggle, Line spacing) and actions (Undo, Redo, Clear) directly to the native editor and Main undo/redo stack (`undoStack`, `redoStack`), while continuing to sync with Heavy via IPC.
    8. Preserved `SurfaceView` and `SurfaceControlViewHost` attachment exclusively for non-Note embedded mini-apps (Reader, Speed Monitor, future PWAs).
    9. Pointed `imeBridgeEditText` to `noteEditText` so all existing test hooks and getters (`getImeBridgeEditTextForTesting()`) remain backward-compatible and functional.
  - Backups preserved in workspace: `TestNotesMiniApp.kt.phase14_bak`, `FloatingMiniAppWindow.kt.phase14_bak`.
* How it was verified: local build only (`compile_applet` passed, 43/43 tests in `FloatingMiniAppFrameworkTest` passed) — on-device claim only / not tested on physical hardware. Physical on-device verification required to observe native Gboard typing, word suggestions, and formatting updates.
* Any deviation from what was requested, and why: None.
* Any known issue or follow-up needed: Physical on-device verification of Note editor typing, word suggestions, formatting popover, sleep/wake persistence, and discard on close.

---

### Receipt 105 — Main-Only Multi-Instance Note Mini-App Architecture
* Timestamp: 2026-11-09T03:17:00-08:00
* One-line summary of what was requested: Complete decoupling of Note mini-app from Heavy process and implementation of placement-identity driven multi-instance Note support.
* Exact files touched:
  - `app/src/main/java/com/example/feature/element/ElementPlacementHelper.kt`
  - `app/src/main/java/com/example/feature/element/ElementIdParser.kt`
  - `app/src/main/java/com/example/feature/settings/AddElementActivity.kt`
  - `app/src/main/java/com/example/feature/settings/ActionPickerActivity.kt`
  - `app/src/main/java/com/example/feature/element/ElementActionDispatcher.kt`
  - `app/src/main/java/com/example/feature/miniapps/MiniAppManager.kt`
  - `app/src/main/java/com/example/feature/floating/FloatingMiniAppBridge.kt`
  - `app/src/main/java/com/example/feature/floating/FloatingMiniAppWindow.kt`
  - `app/src/main/java/com/example/core/ipc/HeavyProcessConnectionManager.kt`
  - `app/src/test/java/com/example/feature/floating/FloatingMiniAppFrameworkTest.kt`
  - `blueprint/BLUEPRINT.md`
  - `receipts/RECEIPTS_100.md`
* What was actually done — factual, not persuasive:
  - Placement-Identity Driven Multi-Instance Note Architecture:
    1. In `ElementPlacementHelper.kt`: Added `assignInstanceIfNote(id)` generating a unique, stable instance token (`system:test_notes:note_${timestamp}_${uuid}`) whenever a Note element is placed on the hybrid grid or handle layout.
    2. In `ElementIdParser.kt`: Updated `parseSystemAction` to match on `baseAction` (`test_notes`) while preserving the full action ID with instance token on `SidebarItem.SystemAction`.
    3. In `AddElementActivity.kt` and `ActionPickerActivity.kt`: Applied `assignInstanceIfNote` to return unique instance IDs on selection.
    4. In `ElementActionDispatcher.kt`: Intercepted Note actions (`test_notes` or `test_notes:<instanceId>`) and `PageWindow` actions to extract the placement `instanceId` and forward it to `MiniAppManager.toggleApp`.
    5. In `MiniAppManager.kt`: Added `instanceId: String? = null` parameter to `toggleApp`, delegating directly to `FloatingMiniAppBridge.toggleMiniApp(pageType, instanceId)`.
  - Zero Heavy Process Dependency for Note Mini-App:
    1. In `FloatingMiniAppBridge.kt`: Added `isMainOnlyMiniApp(appType)` returning true for `"note"` and `"test_notes"`.
    2. In `openMiniApp`: Bypasses `connectionManager.acquireConsumer` completely when `isMainOnlyMiniApp(appType)` is true.
    3. In `sleepMiniApp`: Skips Heavy snapshot request, IPC commands, and Heavy consumer release. Gathers snapshot from Main window (`getCurrentPayload()`), commits snapshot to `DurableMiniAppStore`, unregisters window, and displays launcher bubble.
    4. In `closeMiniApp`: Skips Heavy stop command and consumer release. Unregisters window and deletes/updates snapshot in Main.
    5. In `toggleMiniApp`: Supports `targetInstanceId`. When passed, finds the specific instance window/bubble/snapshot. Brings to front if already open but obscured; sleeps if focused; wakes if sleeping; opens if closed. Falls back to default `miniapp_test_notes` if no ID specified, preserving 100% backward compatibility for legacy Note placements.
    6. In `FloatingMiniAppWindow.kt`: Added `isMainOnlyApp()` helper. In `syncLiveTextToHeavy`, `sendNoteCommand`, `onShow`, and `onHide`, returns immediately without contacting Heavy. In `onAttach` and `onDetach`, avoids registering Heavy connection listener. In `onDestroy`, skips SurfaceView/package release and saves authoritative text directly to `DurableMiniAppStore`. Added `setNoteTextForTesting` helper.
    7. In `HeavyProcessConnectionManager.kt`: Added `getActiveConsumers(): Set<String>` method.
  - Test Suite & Verification:
    1. In `FloatingMiniAppFrameworkTest.kt`: Added `testMultiInstanceNoteIndependentWindowsAndPersistence`, `testMultiInstanceToggleRoutingAndFocusManagement`, `testZeroHeavyDependencyForNoteOperations`, and `testLegacyNoteRecoveryWithoutInstanceId`.
    2. Updated existing Heavy lifecycle tests (`testCloseReleasesInstanceAndSurfaceBeforeFinalConsumerDisconnect`, `testFoldPersistsSuccessfullyBeforeReleasingHeavy`) to use `appType = "reader"` to continue verifying the Heavy process fold/close contract for Heavy-hosted mini-apps.
    3. Executed `./gradlew :app:testDebugUnitTest --tests com.example.feature.floating.FloatingMiniAppFrameworkTest`: 47/47 tests passed (0 failures).
    4. Executed `compile_applet`: Succeeded cleanly.
* How it was verified: local build and JVM test suite only (`compile_applet` passed, 47/47 tests in `FloatingMiniAppFrameworkTest` passed) — on-device claim only / not tested on physical hardware. Physical on-device execution required to verify multiple simultaneous Note windows on screen, typing in both, folding one to a bubble, and tapping grid elements.
* Any deviation from what was requested, and why: None.
* Any known issue or follow-up needed: Physical on-device testing following the On-Device Verification Guide below.



