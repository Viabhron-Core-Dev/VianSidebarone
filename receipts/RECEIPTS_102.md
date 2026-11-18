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
