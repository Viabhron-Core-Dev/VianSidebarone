# Receipts Log — Part 101

---

### Receipt 112
* Timestamp: 2026-11-12 23:14 UTC
* One-line summary of what was requested: Implement Sidebar Note improvements, shared floating-window 16dp rounded corners, Reader refinements + SAF folder explorer + OPF literal `+` path fix, and a separate Floating PWA Loader mini-app (`floating_pwa`) without altering full-screen `PwaPlayerActivity`.
* Exact files touched:
  - Phase 1 (Note improvements — blank new notes, multi-instance staggered launch, Copy action, per-Note accent color):
    - `app/src/main/java/com/example/feature/floating/NotePersistenceCoordinator.kt`
    - `app/src/main/java/com/example/feature/floating/TestNotesMiniApp.kt`
    - `app/src/main/java/com/example/feature/floating/FloatingMiniAppBridge.kt`
    - `app/src/main/java/com/example/feature/floating/FloatingMiniAppLauncherBubble.kt`
    - `app/src/main/java/com/example/feature/floating/FloatingMiniAppWindow.kt`
  - Phase 2 (Shared floating shell 16dp rounded corners floating / 0f maximized):
    - `app/src/main/java/com/example/feature/floating/FloatingMiniAppWindow.kt`
    - `app/src/main/java/com/example/core/FloatingWindowManager.kt`
  - Phase 3 (Reader fixes, generic EmbeddedImeBridge, SAF folder browser, OPF literal `+` preservation):
    - `app/src/main/java/com/example/data/EpubParser.kt`
    - `app/src/main/java/com/example/data/LibraryRepository.kt`
    - `app/src/main/java/com/example/feature/floating/EmbeddedImeBridge.kt`
    - `app/src/main/java/com/example/feature/reader/ReaderModels.kt`
    - `app/src/main/java/com/example/feature/reader/ReaderFolderTreeActivity.kt`
    - `app/src/main/java/com/example/feature/reader/ReaderFolderBrowserView.kt`
    - `app/src/main/java/com/example/feature/reader/ReaderLibraryView.kt`
    - `app/src/main/java/com/example/feature/reader/ReaderReadingView.kt`
    - `app/src/main/java/com/example/feature/reader/ReaderMiniAppInstance.kt`
    - `app/src/main/java/com/example/feature/floating/HeavyFloatingHost.kt`
    - `app/src/main/java/com/example/feature/floating/HeavyFloatingHostService.kt`
    - `app/src/main/AndroidManifest.xml`
  - Phase 4 (Separate Floating PWA Loader `floating_pwa`):
    - `app/src/main/java/com/example/feature/pwa/floating/FloatingPwaMiniAppFactory.kt` (new)
    - `app/src/main/java/com/example/feature/pwa/floating/FloatingPwaMiniAppInstance.kt` (new)
    - `app/src/main/java/com/example/feature/pwa/floating/FloatingPwaWindowController.kt` (new)
    - `app/src/main/java/com/example/feature/pwa/floating/FloatingPwaRelayActivity.kt` (new)
    - `app/src/main/java/com/example/feature/floating/HeavyFloatingHost.kt`
    - `app/src/main/java/com/example/feature/floating/HeavyFloatingHostService.kt`
    - `app/src/main/java/com/example/feature/heavy/page/HeavyPageHostManager.kt`
    - `app/src/main/java/com/example/feature/floating/FloatingMiniAppWindow.kt`
    - `app/src/main/java/com/example/feature/element/ElementActionDispatcher.kt`
    - `app/src/main/java/com/example/feature/sidebar/SidebarItem.kt`
    - `app/src/main/java/com/example/feature/settings/AddElementActivity.kt`
    - `app/src/main/java/com/example/feature/floating/FloatingMiniAppLauncherBubble.kt`
    - `app/src/main/java/com/example/core/FloatingMiniAppLogFilter.kt`
    - `app/src/main/AndroidManifest.xml`
  - Tests & Documentation:
    - `app/src/test/java/com/example/data/EpubParserTest.kt`
    - `app/src/test/java/com/example/feature/reader/ReaderMiniAppTest.kt`
    - `app/src/test/java/com/example/feature/floating/FloatingMiniAppFrameworkTest.kt`
    - `app/src/test/java/com/example/feature/pwa/floating/FloatingPwaMiniAppTest.kt` (new)
    - `blueprint/BLUEPRINT.md`
    - `receipts/RECEIPTS_101.md` (new)
* What was actually done — factual, not persuasive:
  1. Note Improvements (Main-only multi-instance preserved):
     - Blank new Notes by default; added `TestNotesMiniAppInstance.isLegacyDefaultTemplate` sanitization to drop legacy boilerplate on load.
     - Added `FloatingMiniAppBridge.openNewNoteInstance` with staggered initial bounds so users can open multiple independent Note windows directly.
     - Added in-editor Copy action and per-Note `accent_color` persisted via `NotePersistenceCoordinator` into `DurableMiniAppStore`, tinting the floating Note top bar dot, Edit badge, folded launcher bubble stroke, and popover highlight.
  2. Shared Floating-Window 16dp Rounded Corners:
     - Updated `FloatingMiniAppWindow` root card, bottom `contentContainer`, `contentPlaceholderView`, and non-interactive `rootBorderOverlay` so all four outer corners clip cleanly at `16dp` when floating and switch dynamically to `0f` when maximized, preserving the surface cutout geometry and `[ ×  —  ↘ ]` sticker.
     - Updated `FloatingWindowManager.bringToFront` to skip redundant `updateViewLayout` refreshes when only hidden or detached windows sit above the target window.
  3. Reader Fixes, Generic Embedded IME Bridge, and SAF Folder Browser:
     - Replaced `URLDecoder` usage in `EpubParser.normalizeZipPath` with a percent-only UTF-8 decoder so literal `+` characters in OPF/ZIP paths remain intact.
     - Extracted generic `EmbeddedImeBridge` (`EmbeddedImeEditText`, focus/blur Binder coordination) so `:heavy`-hosted inputs (`ReaderFolderBrowserView`, Reader search, Floating PWA `EmbeddedPwaWebView`) can request Main window focusability without Note-specific hacks.
     - Added SAF directory picking (`ReaderFolderTreeActivity`), persistent `ReaderFolderPrefs` synced via `OverlaySyncManager`, and `ReaderFolderBrowserView` supporting type filtering and natural sorting; kept Reader `CoroutineScope`s resilient across detach during screen switches and cancelled cleanly on release.
     - Preserved Reader scope discipline (excluded Quick Notes, Book Tracker Edit/details panel, legacy backup migration, and Dictionary).
  4. Separate Floating PWA Loader (`appType = "floating_pwa"`):
     - Left `PwaPlayerActivity.kt` completely untouched.
     - Implemented `FloatingPwaMiniAppFactory` and `FloatingPwaMiniAppInstance` in `:heavy`, hosting an `EmbeddedPwaWebView` inside `SurfaceControlViewHost`, serving offline `PwaEntity` bundles over `https://pwa-<id>.local/`, loading external web URLs, enforcing per-PWA `PwaPermissionPolicy` (internet, files, camera, mic, location, sound with audio suppression script), and persisting `pwa_id`, `current_url`, and `current_title` across sleep/restore.
     - Implemented `FloatingPwaWindowController` (Main-process toolbar: Back, Forward, Reload/Stop, editable URL bar managing `FLAG_NOT_FOCUSABLE`, linear progress bar, installed PWA picker modal, and command dispatch) and `FloatingPwaRelayActivity` (Main-process SAF document picker & runtime camera/mic/location permission relay).
     - Wired `floating_pwa` across `HeavyFloatingHost`, `HeavyFloatingHostService`, `HeavyPageHostManager`, `FloatingMiniAppWindow`, `ElementActionDispatcher`, `SidebarItem`, `AddElementActivity`, `FloatingMiniAppLauncherBubble`, `FloatingMiniAppLogFilter`, and `AndroidManifest.xml`.
  5. Verification:
     - Targeted JVM unit tests executed cleanly (`111/111` passed across 5 suites):
       - `FloatingMiniAppFrameworkTest`: 82/82 passed
       - `EpubParserTest`: 10/10 passed
       - `ReaderMiniAppTest`: 6/6 passed
       - `FloatingMiniAppLogFilterTest`: 7/7 passed
       - `FloatingPwaMiniAppTest`: 6/6 passed
* How it was verified: Local JVM unit test execution (`gradle :app:testDebugUnitTest` on the 5 targeted suites listed above: 111/111 passing) and full Android build via `compile_applet` — on-device runtime behavior (overlay IME, WebView rendering, SAF pickers, SurfaceControlViewHost framing) not tested on physical device/emulator in this environment.
* Any deviation from what was requested, and why: None; excluded Quick Notes, Book Tracker Edit/details panel, legacy backup migration, Dictionary, and kept `PwaPlayerActivity.kt` unchanged as instructed.
* Any known issue or follow-up needed: Physical device verification required for multi-process overlay WebView rendering, SAF tree/file chooser relays, IME focus transitions, and physical runtime memory teardown. Pre-existing unrelated test `CallSensorIpcTest.testHeavyUnavailableAndQueueingBehavior` was not modified per isolation discipline.
