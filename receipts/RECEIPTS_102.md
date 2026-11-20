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
