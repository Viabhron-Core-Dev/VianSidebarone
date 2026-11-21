# Receipts Log (Continuation from RECEIPTS_101.md)

### Receipt 125
* Timestamp: 2026-11-18 22:35 UTC
* One-line summary of what was requested: Remove the unwanted full-width bottom strip and obsolete Sort / + File / Search bottom controls from the floating Reader Library while keeping `[ × | — | ↘ ]` and the circular `▶` Continue button, and fix cold-start / process-kill restoration of the last-opened book (book identity, chapter, scrollY, and font size) without breaking Book Tracker or single-active-binary separation.
* Exact files touched:
  - `app/src/main/java/com/example/feature/reader/ReaderLibraryView.kt`
  - `app/src/main/java/com/example/feature/reader/ReaderFolderBrowserView.kt`
  - `app/src/main/java/com/example/feature/reader/ReaderModels.kt`
  - `app/src/main/java/com/example/data/LibraryRepository.kt`
  - `app/src/main/java/com/example/feature/reader/ReaderReadingView.kt`
  - `app/src/main/java/com/example/feature/reader/ReaderMiniAppInstance.kt`
  - `app/src/main/java/com/example/feature/floating/FloatingMiniAppBridge.kt`
  - `app/src/test/java/com/example/feature/reader/ReaderMiniAppTest.kt`
  - `app/src/test/java/com/example/feature/reader/ReaderArchitectureAndBackupTest.kt`
  - `blueprint/BLUEPRINT.md`
  - `receipts/RECEIPTS_102.md`
* What was actually done — factual, not persuasive:
  1. **Library Bottom Strip & Utility Bar Removal (`ReaderLibraryView.kt`, `ReaderFolderBrowserView.kt`)**:
     - Changed `ReaderLibraryView.rootLayout` padding from `setPadding(dp(5), dp(2), dp(5), dp(36))` to `setPadding(dp(5), dp(2), dp(5), 0)`, removing the full-width bottom strip across both `Recent` and `File Explorer` tabs.
     - Removed the obsolete bottom `utilityBar` (`Sort`, `+ File`, `Search`) from `ReaderLibraryView` without replacing it with another bottom bar; added `getRootLayoutBottomPaddingPxForTesting()` and `hasBottomUtilityBarForTesting()`.
     - Increased inner `ScrollView` bottom padding (`clipToPadding = false`) on `ReaderLibraryView.bookListScrollView` and embedded `ReaderFolderBrowserView.listScrollView` from `dp(52)` to `dp(86)` so the bottom-most item scrolls above the circular `"▶"` Continue button (`82dp` from bottom) and untouched `[ × | — | ↘ ]` pill (`42dp` from bottom).
     - Kept `ReaderLibraryView.getLastReadBookForContinue()` from substituting another book when `last_active_reader_book_id` points to a deleted book, while keeping the circular `"▶"` Continue button visible across both `Recent` and `File Explorer` tabs.
  2. **Cold-Start & Process-Kill Book Restoration (`ReaderModels.kt`, `LibraryRepository.kt`, `ReaderReadingView.kt`, `ReaderMiniAppInstance.kt`, `FloatingMiniAppBridge.kt`)**:
     - Updated `ReaderPreferences` setters to commit preferences to local `FloatingReaderPrefs` before `OverlaySyncManager` IPC sync so font size and reading preferences persist deterministically in single-process and multi-process runs.
     - Updated `LibraryRepository` to `.commit()` `active_binary_book_id` and `last_active_reader_book_id`, update `last_active_reader_book_id` during `reimportFromInputStream` and duplicate `importFromInputStream(..., markOpenedOnImport = true)`, add `SyncResumeCheckpoint` (written on scroll/progress saves, committed in `saveCurrentProgressImmediately()`, cleared in `deleteBook`), and harden `getLastActiveReaderBook()` (clearing stale deleted IDs without falling back to `Recent[0]`, reconciling newer `SyncResumeCheckpoint`, and falling back only to `active_binary_book_id` if unset and valid).
     - Guarded `ReaderReadingView.readingScrollView` `setOnScrollChangeListener` during `isProgrammaticScrollOrLoad`, added one-shot `OnLayoutChangeListener` deferred `scrollTo` in `openChapter` when `readingContentTextView` height is not yet measured, applied saved `ReaderPreferences.KEY_DEFAULT_FONT_SIZE` on `applyReaderPreferences()` and `loadBook()`, and wrote committed `SyncResumeCheckpoint` in `saveCurrentProgressImmediately()`.
     - Updated `ReaderMiniAppInstance` to preserve `ColdStartResumeHint` from cold-start reading snapshots (never resurrecting deleted books or unrelated `Recent[0]`), apply saved `ReaderPreferences` font size on cold start, clear `currentBookId`/`currentChapterIndex`/`currentScrollY` in `onBackToLibrary`, preserve `KEY_COLD_START = "true"` in `onSaveState()` while `pendingAutoOpenLastReadBook` is pending, and updated `FloatingMiniAppBridge.toggleMiniApp` to match `isReaderMiniApp(it.appType)`.
  3. **Tests (`ReaderMiniAppTest.kt`, `ReaderArchitectureAndBackupTest.kt`)**:
     - Updated `testLibraryRemovesHeaderBarAndShowsContinueFabAcrossTabs` to verify zero `rootLayout` bottom padding on both tabs, absence of `Sort`/`+ File`/`Search` bottom controls, `>= 80` scroll bottom padding, Continue FAB behavior across tabs + missing-binary guard, and unchanged shared `[ × | — | ↘ ]` pill.
     - Added `testColdStartRestoresBookChapterScrollAndSavedFontSizeAcrossProcessDeath` and `testProcessKillDuringReadingRecoversSyncCheckpointAndWarmFoldUnfoldPreservesSession`.
* How it was verified: Local build (`compile_applet` -> `Build succeeded - the applet is compiled`) and local JVM unit tests:
  - `gradle :app:testDebugUnitTest --tests "com.example.feature.reader.*" --tests "com.example.feature.floating.*" --rerun-tasks --console=plain` -> `BUILD SUCCESSFUL` (`ReaderArchitectureAndBackupTest` 13/13, `ReaderMiniAppTest` 18/18, `FloatingMiniAppFrameworkTest` 98/98; 0 failures/errors/skipped).
  - `gradle :app:testDebugUnitTest --tests "com.example.data.EpubParserTest" --tests "com.example.core.MainProcessRecoveryTest" --tests "com.example.core.MainRuntimeRestorationTest" --console=plain` -> `BUILD SUCCESSFUL` (`EpubParserTest` 14/14, `MainProcessRecoveryTest` 8/8, `MainRuntimeRestorationTest` 6/6; 0 failures/errors/skipped).
  - Total: **157/157** JVM tests passed. Not tested on a physical Android device or emulator.
* Any deviation from what was requested, and why: None in behavior. Created `RECEIPTS_102.md` for Receipt 125 because `RECEIPTS_101.md` is at 448 lines and appending there would risk exceeding the 500-line cap.
* Any known issue or follow-up needed: Physical-device testing required to visually verify the bottom edge of the Reader Library window across `Recent` and `File Explorer` tabs (including scroll clearance above `▶` and `[ × | — | ↘ ]`), real `ScrollView` pixel scroll restoration timing on large multi-chapter EPUBs, and OS force-stop / process-kill recovery end-to-end.

### Receipt 126
* Timestamp: 2026-11-19 21:03 UTC
* One-line summary of what was requested: Implement standalone Floating Trigger buttons (with backward compatibility for legacy container-navigation IDs), separate Dictionary & Translation floating mini-app UI shells, shared Dictionary & Translation Settings, three exported `ACTION_PROCESS_TEXT` text-selection popup actions (`Read aloud`, `Meaning`, `Translate`), and built-in Android `TextToSpeech` Read Aloud with focused unit tests.
* Exact files touched:
  - Created:
    - `app/src/main/java/com/example/feature/floating/trigger/FloatingTriggerStore.kt`
    - `app/src/main/java/com/example/feature/floating/trigger/FloatingTriggerButtonWindow.kt`
    - `app/src/main/java/com/example/feature/floating/trigger/FloatingTriggerManager.kt`
    - `app/src/main/java/com/example/feature/language/LanguageMiniAppUiHelper.kt`
    - `app/src/main/java/com/example/feature/language/DictionaryMiniAppInstance.kt`
    - `app/src/main/java/com/example/feature/language/TranslationMiniAppInstance.kt`
    - `app/src/main/java/com/example/feature/settings/DictionaryTranslationSettingsScreen.kt`
    - `app/src/main/java/com/example/feature/selection/SelectionTextHelper.kt`
    - `app/src/main/java/com/example/feature/selection/ReadAloudController.kt`
    - `app/src/main/java/com/example/feature/selection/SelectionPopupShell.kt`
    - `app/src/main/java/com/example/feature/selection/ReadAloudSelectionActivity.kt`
    - `app/src/main/java/com/example/feature/selection/MeaningSelectionActivity.kt`
    - `app/src/main/java/com/example/feature/selection/TranslateSelectionActivity.kt`
    - `app/src/test/java/com/example/feature/floating/trigger/FloatingTriggerTest.kt`
    - `app/src/test/java/com/example/feature/language/DictionaryAndTranslationMiniAppTest.kt`
    - `app/src/test/java/com/example/feature/selection/SelectionActionsAndReadAloudTest.kt`
  - Modified:
    - `app/src/main/AndroidManifest.xml`
    - `app/src/main/res/values/themes.xml`
    - `app/src/main/java/com/example/SettingsActivity.kt`
    - `app/src/main/java/com/example/core/HandleService.kt`
    - `app/src/main/java/com/example/feature/settings/AddElementActivity.kt`
    - `app/src/main/java/com/example/feature/element/ElementActionDispatcher.kt`
    - `app/src/main/java/com/example/feature/sidebar/HybridGridPageView.kt`
    - `app/src/main/java/com/example/feature/sidebar/AppsPageView.kt`
    - `app/src/main/java/com/example/feature/sidebar/WidgetsGridPageView.kt`
    - `app/src/main/java/com/example/feature/floating/FloatingMiniAppBridge.kt`
    - `app/src/main/java/com/example/feature/floating/FloatingMiniAppWindow.kt`
    - `blueprint/BLUEPRINT.md`
    - `receipts/RECEIPTS_102.md`
* What was actually done — factual, not persuasive:
  1. **Floating Trigger**: Added `FloatingTriggerStore`, `FloatingTriggerButtonWindow`, and `FloatingTriggerManager` for standalone 48dp circular floating buttons with tap-to-dispatch via `ElementActionDispatcher.execute`, long-press-to-drag repositioning with display-bounds clamping and synchronous `.commit()` persistence, startup restoration in `HandleService.attachHandles()`, and `FloatingTriggerCompat` backward-compatible routing so legacy `floating_trigger:<containerId>` IDs still open `SidebarManager.openContainerById(...)`. Updated `AddElementActivity`, `ElementActionDispatcher`, `HybridGridPageView`, `AppsPageView`, and `WidgetsGridPageView`.
  2. **Separate Dictionary & Translation Mini-Apps**: Added `DictionaryMiniAppInstance` (`appType = "dictionary"`), `TranslationMiniAppInstance` (`appType = "translation"`), and shared styling/bounds helper `LanguageMiniAppUiHelper`. Hosted both directly in Main via `FloatingMiniAppBridge` and `FloatingMiniAppWindow` with compact initial bounds, separate durable sleep/wake/close snapshots, top-bar `Edit` buttons opening shared `dictionary_translation_settings`, untouched `[ × | — | ↘ ]` controls, honest unconfigured placeholders (no fake lookups/translations), and separate entries under `Add Element -> Floating Windows`.
  3. **Shared Dictionary & Translation Settings**: Added Compose M3 `DictionaryTranslationSettingsScreen` with clearly separated Dictionary (StarDict planned, 0 imported status) and Translation (models/packs 0 installed, language preferences placeholder) sections; wired route `dictionary_translation_settings` and `MainSettingsScreen` item in `SettingsActivity`.
  4. **Text-Selection Popup Actions & Built-In `TextToSpeech` Read Aloud**: Added `SelectionTextHelper`, `ReadAloudController` (built-in Android `TextToSpeech`, slower/faster `0.5x–2.0x` controls, init/language/speak error handling, cross-popup `ReadAloudSessionCoordinator` preemption, clean `release()`/`shutdown()`), `SelectionPopupShell`, translucent `Theme.VianSide.SelectionPopup` in `themes.xml`, and three exported `ACTION_PROCESS_TEXT` (`text/plain`) activities (`ReadAloudSelectionActivity`, `MeaningSelectionActivity`, `TranslateSelectionActivity`) registered in `AndroidManifest.xml`.
  5. **Tests**: Added `FloatingTriggerTest` (6 tests), `DictionaryAndTranslationMiniAppTest` (5 tests), and `SelectionActionsAndReadAloudTest` (6 tests) using the project's pure JVM JUnit 4 + `FakeSharedPreferences` harness.
* How it was verified: Local build (`compile_applet` -> `Build succeeded - the applet is compiled`) and local JVM unit tests:
  - `gradle :app:testDebugUnitTest --tests "com.example.feature.floating.trigger.FloatingTriggerTest" --tests "com.example.feature.language.DictionaryAndTranslationMiniAppTest" --tests "com.example.feature.selection.SelectionActionsAndReadAloudTest" --tests "com.example.feature.settings.SettingsNavigationStructureTest" --tests "com.example.feature.element.ElementIdParserTest" --tests "com.example.feature.element.ElementSystemTest" --tests "com.example.feature.floating.FloatingMiniAppFrameworkTest" --tests "com.example.feature.reader.ReaderMiniAppTest" --console=plain` -> `BUILD SUCCESSFUL` (**151/151** tests passed; 0 failures, 0 errors, 0 skipped).
  - Not tested on a physical Android device or emulator.
* Any deviation from what was requested, and why: Unit tests use the project's existing pure JVM JUnit 4 + `FakeSharedPreferences` harness (with a `TtsEngineAdapter` fake for `ReadAloudController`) rather than Robolectric, because Robolectric is not in `app/build.gradle.kts` and adding new dependencies was explicitly out of scope.
* Any known issue or follow-up needed: Dictionary lookup (StarDict parsing/import/DB) and Translation engines/language packs are intentionally unconfigured UI placeholders pending future phases. Physical-device QA is required to verify overlay long-press drag ergonomics, IME focus in floating Dictionary/Translation windows, `ACTION_PROCESS_TEXT` menu entries across third-party host apps (which vary in support), and real Android `TextToSpeech` voice playback.

### Receipt 127
* Timestamp: 2026-11-19 23:06 UTC
* One-line summary of what was requested: Audit and fix Floating Trigger target eligibility so only genuinely executable Sidebar elements (per `ElementActionDispatcher`) can be associated or restored, and clean up the unexpected `[Test worker]` modification to `app/LiteReader_Log.txt`.
* Exact files touched:
  - `app/src/main/java/com/example/feature/floating/trigger/FloatingTriggerStore.kt`
  - `app/src/main/java/com/example/feature/floating/trigger/FloatingTriggerManager.kt`
  - `app/src/main/java/com/example/feature/settings/AddElementActivity.kt`
  - `app/src/main/java/com/example/core/LogKeeper.kt`
  - `app/LiteReader_Log.txt`
  - `app/src/test/java/com/example/feature/floating/trigger/FloatingTriggerTest.kt`
  - `blueprint/BLUEPRINT.md`
  - `receipts/RECEIPTS_102.md`
* What was actually done — factual, not persuasive:
  1. **Floating Trigger Target Eligibility (`FloatingTriggerStore.kt`, `FloatingTriggerManager.kt`)**:
     - Added `FloatingTriggerTargetValidation` (`ValidStandalone`, `LegacyContainer`, `Unsupported`) and `FloatingTriggerCompat.validateItem` / `validateTarget` / `resolveExecutableItem` / `unsupportedReason` aligned directly with standalone `ElementActionDispatcher.execute` branches (`onShowFolder = null`, `onShowWidget = null`).
     - Allowed executable types (`App`, `Link`, `Pwa`, `QuickTile`, `VolumeAction`, `MediaAction`, `DisplayAction`, `SystemAction`, `SettingsShortcut`, `IntentAction`, non-widget `PageWindow`) with non-empty target field validation, while explicitly rejecting `Folder`, `PopupWidget`, `Widget`, `Spacer`, recursive `FloatingTrigger`, `page_window:widget`, and malformed known-prefix IDs (preventing `popup_widget:` or invalid known-prefix IDs from falling back to `LegacyContainer`), while preserving backward compatibility for legacy container IDs (e.g. `left_0_swipe_right`, `notes`).
     - Updated `FloatingTriggerStore.discoverExistingSidebarGridElements()` to filter exclusively via `FloatingTriggerCompat.resolveExecutableItem`.
     - Updated `FloatingTriggerManager` (`handleSidebarTriggerItem`, `toggleTriggerForElement`, `ensureActiveTrigger`, `restoreActiveTriggers`, `executeAssociatedElement`) to enforce `FloatingTriggerCompat.validateTarget`, surface user-facing rejection explanations via `userMessageNotifier`, skip and deactivate unsupported persisted records on restore without crashing, and refuse execution of unsupported targets.
  2. **Picker & Fallback Parity (`AddElementActivity.kt`)**:
     - When `IS_FLOATING_TRIGGER_SELECTION == true`, changed title to `"Select trigger action"`, hid `Widget`, `Popup Widget`, and `Folder` rows alongside `Floating Trigger`, forced `IS_HYBRID_GRID = false` in `launchFullFloatingTriggerPicker` (fixing direct-to-grid bypass when invoked from a Hybrid Grid page), guarded `finishWithId` gesture configuration, and validated `handleElementSelected` and `onActivityResult(700)` via `FloatingTriggerCompat.validateTarget` with `showUnsupportedFloatingTriggerExplanation(...)` (`AlertDialog`/`Toast`).
  3. **Log Cleanup & Prevention (`app/LiteReader_Log.txt`, `LogKeeper.kt`)**:
     - Removed the 24 `[2026-11-19 21:02:...]` `[pid-0] [Test worker]` lines (lines 1198–1221) appended to `app/LiteReader_Log.txt` during the Phase 14.22 test run, preserving lines 1–1197 intact.
     - Added `context.filesDir ?: return` null guards in `LogKeeper.kt` (`appendToFile`, `readLogFile`, `deleteLogFile`) so JVM unit tests using mock contexts without `filesDir` do not write into `app/LiteReader_Log.txt`.
  4. **Tests (`FloatingTriggerTest.kt`)**:
     - Added 5 unit tests verifying all 11 valid dispatcher-executable types, rejection of Folder/Widget/PopupWidget/Spacer/FloatingTrigger/widget-window/malformed IDs with reasons and zero execution, `discoverExistingSidebarGridElements()` filtering, `restoreActiveTriggers()` safe skipping/deactivation of unsupported persisted records, and `AddElementActivity` full-picker parity.
* How it was verified: Local build (`compile_applet` -> `Build succeeded - the applet is compiled`) and local JVM unit tests:
  - `gradle :app:testDebugUnitTest --tests "com.example.feature.floating.trigger.FloatingTriggerTest" --tests "com.example.feature.element.ElementIdParserTest" --tests "com.example.feature.element.ElementSystemTest" --tests "com.example.feature.floating.FloatingMiniAppFrameworkTest" --tests "com.example.feature.reader.ReaderMiniAppTest" --tests "com.example.feature.settings.SettingsNavigationStructureTest" --tests "com.example.feature.language.DictionaryAndTranslationMiniAppTest" --tests "com.example.feature.selection.SelectionActionsAndReadAloudTest" --rerun-tasks --console=plain` -> `BUILD SUCCESSFUL` (**156/156** tests passed: `FloatingTriggerTest` 11/11, `ElementIdParserTest` 12/12, `ElementSystemTest` 4/4, `FloatingMiniAppFrameworkTest` 98/98, `ReaderMiniAppTest` 18/18, `SettingsNavigationStructureTest` 2/2, `DictionaryAndTranslationMiniAppTest` 5/5, `SelectionActionsAndReadAloudTest` 6/6; 0 failures, 0 errors, 0 skipped).
  - Verified `app/LiteReader_Log.txt` remained unchanged at 1197 lines after running the unit tests. Not tested on a physical Android device or emulator.
* Any deviation from what was requested, and why: None.
* Any known issue or follow-up needed: Physical-device testing is required to confirm live overlay alert dialogs, Toast notifications, and end-to-end Floating Trigger selection inside Hybrid Grid edit mode on Android.

### Receipt 128
* Timestamp: 2026-11-20 12:39 UTC
* One-line summary of what was requested: Finalize Floating Trigger `PageWindow` eligibility against the real mini-app/embedded-page registry with regression tests (Part A) and implement end-to-end Quick Calls Sidebar elements with on-demand Contacts picker, `READ_CONTACTS`/`CALL_PHONE` runtime flows, emergency `ACTION_DIAL` routing, durable UUID storage, dedicated editing/icon reset, and unit tests (Part B).
* Exact files touched:
  - Created:
    - `app/src/main/java/com/example/feature/quickcall/QuickCallStore.kt`
    - `app/src/main/java/com/example/feature/quickcall/QuickCallContactsLoader.kt`
    - `app/src/main/java/com/example/feature/quickcall/QuickCallActionHandler.kt`
    - `app/src/main/java/com/example/feature/quickcall/QuickCallPermissionActivity.kt`
    - `app/src/main/java/com/example/feature/quickcall/QuickCallPickerActivity.kt`
    - `app/src/main/java/com/example/feature/quickcall/QuickCallEditActivity.kt`
    - `app/src/test/java/com/example/feature/quickcall/QuickCallTest.kt`
  - Modified:
    - `app/src/main/java/com/example/feature/floating/HeavyFloatingHost.kt`
    - `app/src/main/java/com/example/feature/floating/trigger/FloatingTriggerStore.kt`
    - `app/src/main/java/com/example/feature/floating/trigger/FloatingTriggerManager.kt`
    - `app/src/main/java/com/example/feature/sidebar/SidebarItem.kt`
    - `app/src/main/java/com/example/feature/element/ElementIdParser.kt`
    - `app/src/main/java/com/example/feature/element/ElementActionDispatcher.kt`
    - `app/src/main/java/com/example/feature/element/ElementViewRenderer.kt`
    - `app/src/main/java/com/example/feature/settings/AddElementActivity.kt`
    - `app/src/main/java/com/example/feature/sidebar/SidebarAppsManager.kt`
    - `app/src/main/java/com/example/feature/sidebar/HybridGridPageView.kt`
    - `app/src/main/java/com/example/feature/sidebar/AppsPageView.kt`
    - `app/src/main/java/com/example/feature/sidebar/WidgetsGridPageView.kt`
    - `app/src/main/java/com/example/IconPickerActivity.kt`
    - `app/src/main/AndroidManifest.xml`
    - `app/src/test/java/com/example/feature/floating/trigger/FloatingTriggerTest.kt`
    - `blueprint/BLUEPRINT.md`
    - `receipts/RECEIPTS_102.md`
* What was actually done — factual, not persuasive:
  1. **Part A — Floating Trigger `PageWindow` Eligibility (`HeavyFloatingHost.kt`, `FloatingTriggerStore.kt`, `FloatingTriggerTest.kt`)**:
     - Added `HeavyFloatingHost.isFactoryRegistered(appType)` and `getRegisteredAppTypes()` to expose live registered factory types (including dynamic `registerFactory(...)` registrations).
     - Tightened `FloatingTriggerCompat.validateItem(context, item)` / `validatePageWindow` / `isSupportedPageWindowType` to allow Main-only mini-apps, Heavy embedded non-widget page views (matching `HeavyPageHostManager.createEmbeddedPage`), and live registered `HeavyFloatingHost` factories, while rejecting `page_window:widget`, `page_window:single_widget`, malformed colon IDs, and unregistered types (`page_window:not_a_real_app`). Preserved legacy container target compatibility.
     - Added 4 regression tests in `FloatingTriggerTest` (bringing suite to 15/15) covering all supported registered types, dynamic factory registration/unregistration, unknown/malformed ID rejection with zero execution, and safe deactivation on `restoreActiveTriggers()`.
  2. **Part B — Quick Calls (`feature/quickcall/*`, Sidebar/Element/AddElement/Manifest integration, `QuickCallTest.kt`)**:
     - Added `SidebarItem.QuickCall` with UUID-based element IDs (`quick_call:<uuid>`, never containing phone numbers) and `QuickCallStore` (`SharedPreferences` + `.commit()`) storing durable snapshots (`displayName`, `phoneNumber`, `contactLookupKey`, `customIconPath`, `manuallyEditedNumber`) with zero phone-number PII logged to `LogKeeper`.
     - Added `QuickCallContactsLoader` (on-demand `ContactsContract.CommonDataKinds.Phone` query, grouping/deduplication, invalid-number filtering, search; zero `READ_CALL_LOG`, ContentObservers, workers, or polling) and Compose M3 `QuickCallPickerActivity` launched from a new **Quick Calls** category in `AddElementActivity` (`requestCode = 303`) with on-demand `READ_CONTACTS` request and multi-number selection dialog.
     - Added `QuickCallActionHandler` and translucent `QuickCallPermissionActivity`: routes emergency numbers strictly to `ACTION_DIAL`, requests `CALL_PHONE` only on first direct-call attempt with an `ACTION_DIAL` fallback dialog on denial, dispatches `ACTION_CALL` when permitted, and handles missing call/dialer handlers gracefully. Declared `CALL_PHONE`, `tel:` `<queries>`, and optional telephony `<uses-feature>` in `AndroidManifest.xml`; left Call Recorder untouched.
     - Wired `ElementIdParser`, `ElementActionDispatcher`, `SidebarAppsManager`, `ElementViewRenderer`, `HybridGridPageView`, `AppsPageView`, `WidgetsGridPageView`, `FloatingTriggerCompat`, `FloatingTriggerManager`, `IconPickerActivity`, and Compose M3 `QuickCallEditActivity` (display name edit, validated phone number edit setting `manuallyEditedNumber = true`, zero-permission `PickVisualMedia` custom icon, and Reset Icon to default Android caller icon).
     - Added `QuickCallTest` with 9 JVM unit tests covering UUID IDs, persistence across restart/contact deletion, multi-number grouping/dedup/search, invalid numbers, emergency `ACTION_DIAL` routing, `CALL_PHONE` request + dialer fallback + no-handler path, authoritative manual edits + icon reset, zero-PII logging, and Floating Trigger compatibility.
* How it was verified: Local build (`compile_applet` -> `Build succeeded - the applet is compiled`) and targeted local JVM unit tests:
  - `gradle :app:testDebugUnitTest --tests "com.example.feature.quickcall.QuickCallTest" --tests "com.example.feature.floating.trigger.FloatingTriggerTest" --tests "com.example.feature.element.ElementIdParserTest" --tests "com.example.feature.element.ElementSystemTest" --tests "com.example.feature.floating.FloatingMiniAppFrameworkTest" --tests "com.example.feature.reader.ReaderMiniAppTest" --tests "com.example.feature.settings.SettingsNavigationStructureTest" --tests "com.example.feature.language.DictionaryAndTranslationMiniAppTest" --tests "com.example.feature.selection.SelectionActionsAndReadAloudTest" --rerun-tasks --console=plain` -> `BUILD SUCCESSFUL` (**169/169** tests passed: `QuickCallTest` 9/9, `FloatingTriggerTest` 15/15, `ElementIdParserTest` 12/12, `ElementSystemTest` 4/4, `FloatingMiniAppFrameworkTest` 98/98, `ReaderMiniAppTest` 18/18, `SettingsNavigationStructureTest` 2/2, `DictionaryAndTranslationMiniAppTest` 5/5, `SelectionActionsAndReadAloudTest` 6/6; 0 failures, 0 errors, 0 skipped).
  - Verified `app/LiteReader_Log.txt` remained unchanged at 1197 lines after running the unit tests. Not tested on a physical Android device or emulator (unavailable in this environment).
* Any deviation from what was requested, and why: None in product behavior. All tests run on the project's existing pure JVM JUnit 4 + `FakeSharedPreferences` harness without adding new Gradle dependencies; `QuickCallNumberValidator.buildTelUri` returns `Uri?` so JVM `Uri.fromParts` stubs do not throw a platform-null NPE while real Android builds the exact `Uri.fromParts("tel", dialable, null)` intent URI.
* Any known issue or follow-up needed: Physical-device verification is outstanding for both Part A (Floating Trigger overlay drag/tap/rejection dialogs) and Part B (real `ContactsContract` picker, `READ_CONTACTS`/`CALL_PHONE` system dialogs, `ACTION_CALL`/`ACTION_DIAL` handoff, `PickVisualMedia` custom icon selection, and long-press edit flows).

### Receipt 129
* Timestamp: 2026-11-20 22:15 UTC
* One-line summary of what was requested: Phase 14.25 — Implement a real shared offline StarDict Dictionary engine, real shared Google ML Kit on-device Translation engine, functional Dictionary & Translation Settings, connect both floating windows and selection popups (`Meaning` / `Translate`), remove `ReadAloudSelectionActivity` from `ACTION_PROCESS_TEXT` and custom Read Aloud from `MeaningSelectionActivity`, and verify with unit/integration tests and APK size reporting.
* Exact files touched:
  - Created:
    - `app/src/main/java/com/example/feature/language/dictionary/DictionaryModels.kt`
    - `app/src/main/java/com/example/feature/language/dictionary/StarDictFormatParser.kt`
    - `app/src/main/java/com/example/feature/language/dictionary/StarDictDiskIndex.kt`
    - `app/src/main/java/com/example/feature/language/dictionary/StarDictImporter.kt`
    - `app/src/main/java/com/example/feature/language/dictionary/DictionaryService.kt`
    - `app/src/main/java/com/example/feature/language/translation/TranslationModels.kt`
    - `app/src/main/java/com/example/feature/language/translation/TranslationEngineBackend.kt`
    - `app/src/main/java/com/example/feature/language/translation/TranslationService.kt`
    - `app/src/main/java/com/example/feature/settings/DictionaryTranslationSettingsDialogs.kt`
    - `app/src/test/java/com/example/feature/language/StarDictDictionaryEngineTest.kt`
    - `app/src/test/java/com/example/feature/language/TranslationServiceTest.kt`
  - Modified:
    - `app/build.gradle.kts`
    - `app/src/main/AndroidManifest.xml`
    - `app/src/main/java/com/example/feature/language/DictionaryMiniAppInstance.kt`
    - `app/src/main/java/com/example/feature/language/TranslationMiniAppInstance.kt`
    - `app/src/main/java/com/example/feature/selection/MeaningSelectionActivity.kt`
    - `app/src/main/java/com/example/feature/selection/TranslateSelectionActivity.kt`
    - `app/src/main/java/com/example/feature/settings/DictionaryTranslationSettingsScreen.kt`
    - `app/src/test/java/com/example/feature/language/DictionaryAndTranslationMiniAppTest.kt`
    - `app/src/test/java/com/example/feature/selection/SelectionActionsAndReadAloudTest.kt`
    - `blueprint/BLUEPRINT.md`
    - `receipts/RECEIPTS_102.md`
* What was actually done — factual, not persuasive:
  1. **Part A — Offline StarDict Dictionary Engine (`feature/language/dictionary/*`)**:
     - Implemented `StarDictFormatParser` (`.ifo` v2.4.2/3.0.0 32/64-bit, `sametypesequence` + self-tagged `.dict` decoding for `m`, `h`, `x`, `g`, `t`, `w`, graceful binary fallback), `StarDictDiskIndex` (bounded-RAM external merge-sort disk index `sorted_keys.bin` + `key_offsets.bin` with `O(log N)` `RandomAccessFile` exact/case-insensitive/`.syn` lookup), `StarDictImporter` (multi-dict ZIP import, path traversal rejection, streaming `.dict.dz`/`.idx.gz` via `GZIPInputStream`, disk-space guard, `.staging_<uuid>` cleanup, duplicate fingerprint check), and shared `DictionaryService` persisted atomically in `filesDir/stardict_dictionaries/catalog.json` for cross-process consistency (`:heavy` Settings <-> Main).
  2. **Part D — Google ML Kit On-Device Translation Engine (`app/build.gradle.kts`, `feature/language/translation/*`)**:
     - Added `com.google.mlkit:translate:17.0.3` and `com.google.mlkit:language-id:17.0.6`.
     - Implemented `TranslationEngineBackend`, `MlKitTranslationBackend` (`TranslateLanguage.getAllLanguages()`, `RemoteModelManager`, `DownloadConditions`, `LanguageIdentification`, `Translation`, guaranteed `close()` in `finally`), and shared `TranslationService` persisted atomically in `filesDir/mlkit_translation/prefs.json` (defaults: `auto` -> `en`, `wifiOnlyDownloads = true`) with missing-model detection before translation, Wi-Fi-only enforcement + mobile opt-in, and duplicate concurrent download prevention.
  3. **Parts B, C, E, F — UI Wiring, Functional Settings & Manifest Correction**:
     - Connected `DictionaryMiniAppInstance` and `MeaningSelectionActivity` to `DictionaryService` (off-main-thread lookup, dictionary attribution, loading/empty/no-result/disabled/error states) and removed custom Read Aloud action & `ReadAloudController` from `MeaningSelectionActivity` (Reader TTS untouched).
     - Connected `TranslationMiniAppInstance` (inline overlay-safe searchable picker across all ML Kit languages with offline badges) and `TranslateSelectionActivity` to `TranslationService` (real output, working Copy on real output only, in-place missing-model download + Settings shortcut).
     - Replaced all placeholder rows in `DictionaryTranslationSettingsScreen` (with companion `DictionaryTranslationSettingsDialogs.kt`) with real SAF `.zip` import (`ActivityResultContracts.OpenDocument`), live progress/feedback banners, installed dictionary list with metadata/storage/enable/priority/delete confirmation, default source/target pickers, Wi-Fi-only switch, downloaded models list + delete confirmation, and searchable ML Kit model browser dialog.
     - Removed `ReadAloudSelectionActivity` `ACTION_PROCESS_TEXT` registration from `AndroidManifest.xml`, keeping ONLY `MeaningSelectionActivity` (`Meaning`) and `TranslateSelectionActivity` (`Translate`).
  4. **Tests**:
     - Added `StarDictDictionaryEngineTest` (6 tests), `TranslationServiceTest` (5 tests), and updated `DictionaryAndTranslationMiniAppTest` (5 tests) and `SelectionActionsAndReadAloudTest` (6 tests).
* How it was verified: Local build (`compile_applet` -> `Build succeeded - the applet is compiled`) and targeted local JVM unit tests:
  - `gradle :app:testDebugUnitTest --tests "com.example.feature.language.*" --tests "com.example.feature.selection.*" --tests "com.example.feature.settings.SettingsNavigationStructureTest" --tests "com.example.feature.quickcall.*" --tests "com.example.feature.floating.trigger.*" --tests "com.example.feature.floating.FloatingMiniAppFrameworkTest" --tests "com.example.feature.reader.ReaderMiniAppTest" --tests "com.example.feature.reader.ReaderArchitectureAndBackupTest" --tests "com.example.feature.element.*" --rerun-tasks --console=plain` -> `BUILD SUCCESSFUL` (**211/211** tests passed across 16 suites: `StarDictDictionaryEngineTest` 6/6, `TranslationServiceTest` 5/5, `DictionaryAndTranslationMiniAppTest` 5/5, `SelectionActionsAndReadAloudTest` 6/6, `SettingsNavigationStructureTest` 2/2, `QuickCallTest` 9/9, `FloatingTriggerTest` 15/15, `FloatingMiniAppFrameworkTest` 98/98, `ReaderMiniAppTest` 18/18, `ReaderArchitectureAndBackupTest` 13/13, `ElementIdParserTest` 12/12, `ElementSystemTest` 4/4, `FolderElementLifecycleTest` 7/7, `HybridGridStorageScopeTest` 4/4, `LinkCustomTabIntegrationTest` 6/6, `VectorDrawablePathTest` 1/1; 0 failures, 0 errors, 0 skipped).
  - Verified `app/LiteReader_Log.txt` remained unchanged at 1197 lines.
  - Measured `app/build/outputs/apk/debug/app-debug.apk`: **80.87 MB** total universal debug APK, with **64.12 MB** from ML Kit native `.so` libraries + TFLite language-ID assets across 4 ABIs (~16.9 MB for `arm64-v8a` alone; translation models are downloaded on-demand at runtime).
  - Not tested on a physical Android device or emulator (unavailable in this environment).
* Any deviation from what was requested, and why: None in product behavior. Left `ReadAloudController.kt` and `ReadAloudSelectionActivity.kt` source files in place (unregistered in `AndroidManifest.xml` and removed from `MeaningSelectionActivity`) so no unrelated references break while ensuring zero custom "Read aloud" selection menu entry appears on Android.
* Any known issue or follow-up needed: Physical-device QA is required to verify live SAF ZIP import on large StarDict archives, real Google ML Kit model download over Wi-Fi/mobile networks, on-device language identification and translation accuracy, cross-process sync between `:heavy` Settings and Main floating windows/popups, and host-app `ACTION_PROCESS_TEXT` menu entries (`Meaning` and `Translate` only).

### Receipt 130
* Timestamp: 2026-11-21 13:45 UTC
* One-line summary of what was requested: Remove Google ML Kit Translation and Language Identification from the active Sidebar app while preserving the complete Translation implementation in `deferred/vian-world/translation/` for future Vian World integration, keeping the offline StarDict Dictionary and all unrelated Sidebar features fully functional.
* Exact files touched:
  - Created (preserved archive outside Gradle source sets):
    - `deferred/vian-world/translation/README.md`
    - `deferred/vian-world/translation/src/main/java/com/example/feature/language/translation/TranslationModels.kt`
    - `deferred/vian-world/translation/src/main/java/com/example/feature/language/translation/TranslationEngineBackend.kt`
    - `deferred/vian-world/translation/src/main/java/com/example/feature/language/translation/TranslationService.kt`
    - `deferred/vian-world/translation/src/main/java/com/example/feature/language/TranslationMiniAppInstance.kt`
    - `deferred/vian-world/translation/src/main/java/com/example/feature/language/LanguageMiniAppUiHelperTranslationAdditions.kt`
    - `deferred/vian-world/translation/src/main/java/com/example/feature/selection/TranslateSelectionActivity.kt`
    - `deferred/vian-world/translation/src/main/java/com/example/feature/settings/DictionaryTranslationSettingsDialogs.kt`
    - `deferred/vian-world/translation/src/main/java/com/example/feature/settings/TranslationSettingsSectionReference.kt`
    - `deferred/vian-world/translation/src/test/java/com/example/feature/language/TranslationServiceTest.kt`
    - `deferred/vian-world/translation/src/test/java/com/example/feature/language/TranslationUiAndSelectionTest.kt`
  - Created (active app & tests):
    - `app/src/main/java/com/example/feature/settings/DictionarySettingsScreen.kt`
    - `app/src/test/java/com/example/feature/language/DictionaryMiniAppTest.kt`
  - Removed from active app/test (moved/archived to `deferred/vian-world/translation/` or replaced):
    - `app/src/main/java/com/example/feature/language/translation/TranslationModels.kt`
    - `app/src/main/java/com/example/feature/language/translation/TranslationEngineBackend.kt`
    - `app/src/main/java/com/example/feature/language/translation/TranslationService.kt`
    - `app/src/main/java/com/example/feature/language/TranslationMiniAppInstance.kt`
    - `app/src/main/java/com/example/feature/selection/TranslateSelectionActivity.kt`
    - `app/src/main/java/com/example/feature/settings/DictionaryTranslationSettingsDialogs.kt`
    - `app/src/main/java/com/example/feature/settings/DictionaryTranslationSettingsScreen.kt`
    - `app/src/test/java/com/example/feature/language/TranslationServiceTest.kt`
    - `app/src/test/java/com/example/feature/language/DictionaryAndTranslationMiniAppTest.kt`
  - Modified:
    - `app/build.gradle.kts`
    - `app/src/main/AndroidManifest.xml`
    - `app/src/main/java/com/example/SettingsActivity.kt`
    - `app/src/main/java/com/example/feature/floating/FloatingMiniAppBridge.kt`
    - `app/src/main/java/com/example/feature/floating/FloatingMiniAppWindow.kt`
    - `app/src/main/java/com/example/feature/floating/FloatingMiniAppLauncherBubble.kt`
    - `app/src/main/java/com/example/feature/floating/HeavyFloatingHost.kt`
    - `app/src/main/java/com/example/feature/heavy/page/HeavyPageHostManager.kt`
    - `app/src/main/java/com/example/feature/floating/trigger/FloatingTriggerStore.kt`
    - `app/src/main/java/com/example/feature/settings/AddElementActivity.kt`
    - `app/src/main/java/com/example/feature/sidebar/SidebarItem.kt`
    - `app/src/main/java/com/example/feature/element/ElementActionDispatcher.kt`
    - `app/src/main/java/com/example/feature/language/LanguageMiniAppUiHelper.kt`
    - `app/src/main/java/com/example/feature/language/DictionaryMiniAppInstance.kt`
    - `app/src/main/java/com/example/feature/language/dictionary/DictionaryService.kt`
    - `app/src/main/java/com/example/feature/selection/SelectionPopupShell.kt`
    - `app/src/main/java/com/example/feature/selection/MeaningSelectionActivity.kt`
    - `app/src/test/java/com/example/feature/selection/SelectionActionsAndReadAloudTest.kt`
    - `blueprint/BLUEPRINT.md`
    - `receipts/RECEIPTS_102.md`
* What was actually done — factual, not persuasive:
  1. **Deferred Vian World Handoff (`deferred/vian-world/translation/`)**: Archived all ML Kit Translation engine files, `TranslationMiniAppInstance`, `TranslateSelectionActivity`, settings dialog/section references, unit/integration tests, and a complete `README.md` (exact `17.0.3`/`17.0.6` Gradle deps, 64.12 MiB APK size analysis, original Sidebar hooks, cross-process `prefs.json`/inline-picker patterns, `:heavy` `MlKitContext has not been initialized` root cause, and Vian World checklist). Left any existing on-device `filesDir/mlkit_translation/` storage intact.
  2. **ML Kit & Translation Entry Point Removal**: Removed `com.google.mlkit:translate:17.0.3` and `com.google.mlkit:language-id:17.0.6` from `app/build.gradle.kts`; removed `TranslateSelectionActivity` from `AndroidManifest.xml`; removed `"translation"` / `system:translation_floating` from `AddElementActivity`, `SidebarItem`, `ElementActionDispatcher`, `FloatingTriggerStore` (including `FloatingTriggerCompat.validateItem` rejection of `SystemAction("translation_floating")` and `PageWindow("translation")`), `FloatingMiniAppBridge` (including safe skip guards for stale `"translation"` snapshots), `FloatingMiniAppWindow`, `FloatingMiniAppLauncherBubble`, `HeavyFloatingHost`, `HeavyPageHostManager`, and `LanguageMiniAppUiHelper`.
  3. **Dictionary-Only Settings & Preserved StarDict**: Created `DictionarySettingsScreen.kt` (keeping all SAF ZIP import, progress/feedback banners, enable switch, priority reorder, and delete confirmation intact under `"Dictionary Settings"` / `ROUTE_DICTIONARY_SETTINGS`, aliasing `dictionary_translation_settings` to `DictionarySettingsScreen`, and routing legacy `translation_settings` to non-ML-Kit `DeferredTranslationNoticeScreen`). Updated `DictionaryMiniAppInstance`, `DictionaryService`, `SelectionPopupShell`, and `MeaningSelectionActivity` references accordingly.
  4. **Tests**: Replaced `DictionaryAndTranslationMiniAppTest.kt` with `DictionaryMiniAppTest.kt` (4 tests) and updated `SelectionActionsAndReadAloudTest.kt` (6 tests) to assert only `MeaningSelectionActivity` is registered for `ACTION_PROCESS_TEXT`, zero ML Kit deps/imports in active source, safe handling of stale `"translation"` snapshots/triggers, and full StarDict Dictionary & TTS ReadAloud functionality.
* How it was verified: Local build (`compile_applet` -> `Build succeeded - the applet is compiled`), dependency graph checks (`gradle :app:dependencies --configuration debugRuntimeClasspath --console=plain` and `debugCompileClasspath` -> zero `mlkit`/`translate`/`language-id`/`tflite` hits), clean `gradle :app:assembleDebug` (`app-debug.apk` = **16,267,127 bytes / 15.51 MiB / 16.27 MB** vs **80.87 MiB** baseline; only 4 CameraX `libimage_processing_util_jni.so` native libs totaling 132,208 bytes remain, 0 `.tflite`/ML Kit entries), `wc -l app/LiteReader_Log.txt` = 1197 lines, and targeted local JVM unit tests (`--rerun-tasks`): **263/263** tests passed across 21 suites (`DictionaryMiniAppTest` 4/4, `StarDictDictionaryEngineTest` 6/6, `SelectionActionsAndReadAloudTest` 6/6, `SettingsNavigationStructureTest` 2/2, `FloatingTriggerTest` 15/15, `FloatingMiniAppFrameworkTest` 98/98, `ReaderMiniAppTest` 18/18, `ReaderArchitectureAndBackupTest` 13/13, `EpubParserTest` 14/14, `QuickCallTest` 9/9, `OfflinePwaFoundationTest` 20/20, `FloatingPwaMiniAppTest` 6/6, `ElementIdParserTest` 12/12, `ElementSystemTest` 4/4, `FolderElementLifecycleTest` 7/7, `HybridGridStorageScopeTest` 4/4, `LinkCustomTabIntegrationTest` 6/6, `VectorDrawablePathTest` 1/1, `NotificationHistoryBufferTest` 4/4, `MainProcessRecoveryTest` 8/8, `MainRuntimeRestorationTest` 6/6; 0 failures/errors/skipped). An earlier full `:app:testDebugUnitTest` run (521 tests) had 1 unrelated pre-existing failure in `CallSensorIpcTest.testHeavyUnavailableAndQueueingBehavior` (omits `callManager.testEnabledOverride = true`) plus 1 assertion failure in `DictionaryMiniAppTest` that was subsequently fixed and re-verified. Not tested on a physical Android device or emulator (unavailable in this environment).
* Any deviation from what was requested, and why: None in product scope. `deferred/vian-world/translation/` is explicitly a preserved reference archive outside Gradle source sets (requires Vian World module integration and `MlKitContext` multi-process fix before compiling).
* Any known issue or follow-up needed: Physical-device QA is required to confirm live StarDict SAF ZIP import and multi-dictionary priority lookup across `:heavy` Settings and Main floating/popup views, `Meaning` `ACTION_PROCESS_TEXT` popup in host apps, absence of any Translation/Translate entry in Add Element, Settings, and text-selection menus, and clean handling of any pre-existing persisted `"translation"` snapshots or Floating Triggers on upgrade.
