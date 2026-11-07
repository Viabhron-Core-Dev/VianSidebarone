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
