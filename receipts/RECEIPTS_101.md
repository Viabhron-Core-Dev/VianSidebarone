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

---

### Receipt 113
* Timestamp: 2026-11-13 22:32 UTC
* One-line summary of what was requested: Implement the complete Sidebar Reader architecture and Reader system (Main-process Reader runtime, Room v10→v11 schema & identity migration, lazy metadata-first EPUB/TXT parser + 21-chapter rolling cache + virtualized RecyclerView TOC, Recent vs All Books landing tabs, high-water vs resume separation + Undo banner, Heavy-process Book Tracker & Reader Settings, fold/unfold recovery, and LiteReader/Moon+ backup import/export).
* Exact files touched:
  - Process & Floating Host Routing:
    - `app/src/main/java/com/example/feature/floating/FloatingMiniAppBridge.kt`
    - `app/src/main/java/com/example/feature/floating/FloatingMiniAppWindow.kt`
    - `app/src/main/java/com/example/feature/floating/HeavyFloatingHost.kt`
    - `app/src/main/AndroidManifest.xml`
  - Room v10→v11 Data & Lazy Parser Layer:
    - `app/src/main/java/com/example/data/EpubBook.kt`
    - `app/src/main/java/com/example/data/TrackerBook.kt`
    - `app/src/main/java/com/example/data/AppDatabase.kt`
    - `app/src/main/java/com/example/data/EpubParser.kt`
    - `app/src/main/java/com/example/data/LibraryRepository.kt`
  - Main-Process Reader Runtime & UI:
    - `app/src/main/java/com/example/feature/reader/ReaderModels.kt`
    - `app/src/main/java/com/example/feature/reader/ReaderMiniAppInstance.kt`
    - `app/src/main/java/com/example/feature/reader/ReaderMiniAppFactory.kt`
    - `app/src/main/java/com/example/feature/reader/ReaderLibraryView.kt`
    - `app/src/main/java/com/example/feature/reader/ReaderReadingView.kt`
    - `app/src/main/java/com/example/feature/reader/ReaderFolderBrowserView.kt`
  - Heavy-Process Book Tracker, Reader Settings & Backup System:
    - `app/src/main/java/com/example/feature/reader/BookTrackerActivity.kt` (new)
    - `app/src/main/java/com/example/feature/reader/LiteReaderBackupManager.kt` (new)
    - `app/src/main/java/com/example/feature/settings/ReaderSettingsScreen.kt` (new)
    - `app/src/main/java/com/example/SettingsActivity.kt`
  - Tests & Documentation:
    - `app/src/test/java/com/example/data/EpubParserTest.kt`
    - `app/src/test/java/com/example/feature/reader/ReaderMiniAppTest.kt`
    - `app/src/test/java/com/example/feature/reader/ReaderArchitectureAndBackupTest.kt` (new)
    - `app/src/test/java/com/example/feature/floating/FloatingMiniAppFrameworkTest.kt`
    - `blueprint/BLUEPRINT.md`
    - `receipts/RECEIPTS_101.md`
* What was actually done — factual, not persuasive:
  1. Main-Process Reader Runtime & Dispatcher Safety:
     - Routed `"reader"` and `"ebook_reader"` to run exclusively in the Main process (`com.example`) in `FloatingMiniAppBridge` and `FloatingMiniAppWindow` (removed Reader from `HeavyFloatingHost`).
     - Configured `ReaderDispatchers.main` to execute directly on `Looper.getMainLooper()` in Android runtime rather than silently falling back to `Dispatchers.Default`.
  2. Room Schema v10→v11 & Strict Book Identity:
     - Added `MIGRATION_10_11` and `.enableMultiInstanceInvalidation()` on `AppDatabase` (`litereader_db`).
     - Added `author` and `lastActivityTimestamp` to `EpubBook`; added `linkedEpubBookId` (nullable FK to `epubs(id)` with `ON DELETE SET NULL` and index), `lastReadingActivityTimestamp`, `lastMetadataEditTimestamp`, and `backupOriginKey` to `TrackerBook`.
     - Kept duplicate titles distinct; deleting an `EpubBook` unlinks (`linkedEpubBookId = null`) without deleting Tracker progress.
  3. Lazy Metadata-First Parser, 21-Chapter Rolling Cache & Virtualized TOC:
     - Updated `EpubParser` and `LibraryRepository` to parse metadata/manifest/spine/TXT ranges into `toc.json` and `cache_manifest.json` at import without upfront full-book extraction, maintain a rolling 21-chapter cache window (`[current - 10 .. current + 10]`) for books > 21 chapters, and stream outside-window chapters transiently during full-book search via `readChapterTextTransient`.
     - Replaced `ReaderReadingView` TOC rendering with a virtualized `RecyclerView` + `TocChapterAdapter` displaying 1 ellipsized line per chapter (`"${index + 1}. ${title}"`) with active-chapter highlight.
     - Fixed `ReaderReadingView`, `ReaderLibraryView`, and `ReaderFolderBrowserView` to hold `appContext` directly instead of calling final `View.getContext()` and guarded `RecyclerView(context)` creation for headless JUnit mock contexts.
  4. Reader Home Landing, Resume vs High-Water Progress & Undo Banner:
     - Configured `ReaderLibraryView` to open on **Recent** (max 10 opened Reader books ordered by `lastOpenedTimestamp DESC`, cold-start default) alongside a separate uncapped **All Books** tab.
     - Separated `EpubBook.lastReadChapter`/`lastReadScrollY` (resume) from `TrackerBook.readChapters` (high-water); TOC/search jumps and initial/warm restores update resume only, while genuine reading advances `readChapters` and presents an in-Reader **Undo** banner.
  5. Heavy Book Tracker, Reader Settings, Fold/Recovery & Backups:
     - Implemented `BookTrackerActivity` and `ReaderSettingsScreen` in `:heavy`, linked top-bar `Edit` in Main's floating Reader window to `:heavy`'s Tracker editor for the exact `EpubBook.id`, and added **Open in Reader** dispatch by exact `linkedEpubBookId`.
     - Implemented `LiteReaderBackupManager` in `:heavy` supporting Full ZIP, NoBooks ZIP, Tracker TSV (import/export), and Moon+ `.mrstd` import with non-destructive temp-dir SQLite/manifest merging, zip-slip protection, `linkedEpubBookId` remapping, and `quick_notes`/window-state exclusion.
  6. Verification:
     - Targeted unit test execution: `111/111` passed (`EpubParserTest`: 12, `ReaderMiniAppTest`: 6, `ReaderArchitectureAndBackupTest`: 5, `FloatingMiniAppFrameworkTest`: 82, `FloatingPwaMiniAppTest`: 6).
     - Full debug Android build via `compile_applet` succeeded.
* How it was verified: Local JVM unit tests (`gradle :app:testDebugUnitTest` across the 5 suites listed above: 111/111 passed) and `compile_applet` full APK compilation. Multi-process IPC, SAF pickers, TTS/Auto-Scroll playback, and SQLite backup round-trips on physical storage were not tested on a physical Android device or emulator in this environment.
* Any deviation from what was requested, and why: None requested. In `LiteReaderBackupManager`, added a JSON manifest snapshot alongside `litereader_db` SQLite tables inside exported backup archives so archive round-trips execute deterministically in both pure JVM unit tests (without SQLite native libs) and Android runtime.
* Any known issue or follow-up needed: Physical device verification required for Main↔`:heavy` multi-process Intent/IPC round-trips (`Open in Reader`, top-bar `Edit`), Android SAF ZIP/TSV/MRSTD import/export, and Room v10→v11 on-device migration.

---

### Receipt 114
* Timestamp: 2026-11-14 06:34 UTC
* One-line summary of what was requested: Apply targeted quick fixes to Sidebar Reader: genuine streaming/byte-indexed loading for large TXT files without full-file materialization, and preventing `Next` button navigation from falsely advancing `TrackerBook.readChapters` high-water progress while keeping short chapters fairly countable.
* Exact files touched:
  - `app/src/main/java/com/example/data/EpubParser.kt`
  - `app/src/main/java/com/example/feature/reader/ReaderModels.kt`
  - `app/src/main/java/com/example/feature/reader/ReaderReadingView.kt`
  - `app/src/test/java/com/example/data/EpubParserTest.kt`
  - `app/src/test/java/com/example/feature/reader/ReaderArchitectureAndBackupTest.kt`
  - `blueprint/BLUEPRINT.md`
  - `receipts/RECEIPTS_101.md`
* What was actually done — factual, not persuasive:
  1. Large TXT Genuine Streaming & Byte-Range Indexing (`EpubParser.kt`):
     - Added `charsetName` to `BookCacheManifest` and replaced whole-file `readLines()` / whole-string slicing with `detectTxtEncoding` (BOM detection for UTF-32BE/LE, UTF-8, UTF-16BE/LE plus streaming UTF-8 validation falling back to `windows-1252` / `ISO-8859-1`) and `streamTxtLinesWithByteOffsets` (chunked `FileInputStream` single-pass scanner tracking exact byte offsets and line endings `\n`, `\r\n`, `\r`, plus safe UTF-8/multi-byte character-boundary soft-wrapping for >32KB/64KB newline-free lines).
     - Recorded `"txt_bytes:<startByte>:<endByte>:<charsetName>"` in `toc.json` during import without extracting `chapter_*.txt` files upfront.
     - Updated `materializeChaptersFromSource` to load only requested chapters' byte ranges via `readTxtByteRange` (`RandomAccessFile` + `FileChannel` + `CharsetDecoder` with `inBuf.compact()` across chunk boundaries) so rolling 21-chapter window shifts and transient searches read only missing chapters and never rebuild the full file. Preserved `readTxtCharRangeStreaming` fallback for legacy `txt_chars:` entries.
  2. Prevent False Progress on `Next` (`ReaderModels.kt`, `ReaderReadingView.kt`):
     - Added `ReaderProgressPolicy` defining normal vs short-chapter thresholds (`SHORT_CHAPTER_MAX_WORDS = 220`, `SHORT_CHAPTER_MAX_CHARS = 1400`, `MIN_SHORT_CHAPTER_DWELL_MS = 2500L`, `MAX_SHORT_CHAPTER_DWELL_MS = 10000L`, proportional bottom scroll detection for small scrollable ranges `< dp(80)`).
     - Removed unconditional `recordGenuineReadingActivityOnCurrentChapter()` from `nextChapterButton` in `ReaderReadingView.kt`, routing through `advanceToNextChapterByUser()` which checks `ReaderProgressPolicy.shouldAdvanceProgressOnNextClick(...)` before recording progress.
     - Captured `targetChapter` before launching `viewScope.launch` in `recordGenuineReadingActivityOnCurrentChapter()`, added `openChapterJob`/`highWaterJob`/`undoJob` tracking to cancel stale chapter loads on rapid navigation, handled `readingScrollView.post` returning `false` in headless tests, and guarded `Toast.makeText(...)?.show()` in Undo feedback.
  3. Regression Tests (`EpubParserTest.kt`, `ReaderArchitectureAndBackupTest.kt`):
     - Added Cases 13 & 14 to `EpubParserTest`: `testLargeTxtStreamingAndUtf8BoundarySafetyWithoutFullFileMaterialization` (multi-MB UTF-8 + CRLF with prime 37-byte / 19-byte buffer cuts and UTF-16LE BOM) and `testRepeatedTxtWindowChangesDoNotReReadWholeFile` (verifying 1-chapter / 2-chapter window shifts and transient out-of-window search read strictly the missing chapters).
     - Added `testPreventFalseNextProgressShortChapterCompletionAndResumeSeparation` to `ReaderArchitectureAndBackupTest`: verifying immediate `Next` after TOC jump, waiting 15s on an unscrolled normal chapter + `Next`, immediate `Next` on a short chapter, dwell-completed `Next` on a short chapter, Undo rollback, and meaningful scroll updates.
* How it was verified: Local JVM unit tests (`gradle :app:testDebugUnitTest` across 5 targeted suites: `EpubParserTest` 14/14, `ReaderArchitectureAndBackupTest` 6/6, `ReaderMiniAppTest` 6/6, `FloatingMiniAppFrameworkTest` 82/82, `FloatingPwaMiniAppTest` 6/6 — `114/114` passed, 0 failures/errors) and full debug build via `compile_applet` (`Build succeeded`). On-device Android behavior (physical large TXT reading scroll performance, IME focus, TTS/auto-scroll timing) was not tested on a physical device or emulator.
* Any deviation from what was requested, and why: Added `Toast.makeText(...)?.show()` `try/catch` guard and coroutine `Job` tracking (`openChapterJob`, `highWaterJob`, `undoJob`) in `ReaderReadingView.kt` after initial test execution exposed a Room `Dispatchers.IO` race condition and null `Toast` return in headless JVM tests.
* Any known issue or follow-up needed: Physical device verification recommended for multi-MB TXT scroll responsiveness, short-chapter dwell feel, and Android 12+ Room/SAF integration.

