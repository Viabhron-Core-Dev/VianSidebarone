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

