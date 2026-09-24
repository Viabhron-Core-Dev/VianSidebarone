# Receipts Ledger - Part 095

* Timestamp: 2026-09-13T21:58:00Z
* One-line summary: Implemented Heavy-process Welcome page with singleTop launch, IPC routing, launch deduplication, and safe process-death isolation.
* Exact files touched:
  - `app/src/main/java/com/example/core/ipc/IpcModels.kt`
  - `app/src/main/java/com/example/core/ipc/WelcomeIpcContract.kt`
  - `app/src/main/java/com/example/feature/welcome/WelcomeActivity.kt`
  - `app/src/main/java/com/example/feature/welcome/HeavyWelcomeHostExtension.kt`
  - `app/src/main/java/com/example/feature/welcome/MainWelcomeController.kt`
  - `app/src/main/java/com/example/core/ipc/HeavyProcessHost.kt`
  - `app/src/main/java/com/example/MainActivity.kt`
  - `app/src/main/AndroidManifest.xml`
  - `app/src/test/java/com/example/feature/welcome/WelcomeIpcTest.kt`
  - `receipts/RECEIPTS_095.md`
* What was actually done:
  - Added `SHOW_WELCOME` to `HeavyCommandType` enum in `IpcModels.kt`.
  - Created `WelcomeIpcContract.kt` defining minimal command generation (`createShowWelcomeCommand`), target `welcome`, and reason payload (`setup`, `overlay_required`, `user_request`) without passing Main hierarchy or internal managers.
  - Created `WelcomeActivity.kt` in package `com.example.feature.welcome` under `:heavy` process, housing `WelcomeScreen` and preserving the exact existing layout, wording, test tags (`grant_overlay_button`, `net_speed_switch`, `start_core_button`, `stop_core_button`, `open_log_keeper_button`), and permission flows (overlay permission check, notification permission launcher).
  - Maintained `MainActivity` as a clean subclass of `WelcomeActivity` in `:heavy` with `singleTop` launchMode.
  - Declared `WelcomeActivity` in `AndroidManifest.xml` assigned strictly to `android:process=":heavy"` and `android:launchMode="singleTop"`.
  - Implemented `HeavyWelcomeHostExtension` and `DefaultHeavyWelcomeHostExtension` in `:heavy` to handle `SHOW_WELCOME` commands, launch `WelcomeActivity` with `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_SINGLE_TOP`, and deduplicate rapid repeated launches using active foreground tracking and a debounce threshold.
  - Wired `HeavyProcessHost` in `:heavy` to route `SHOW_WELCOME` commands directly to `HeavyWelcomeHostExtension`.
  - Implemented `MainWelcomeController` in the Main process (`com.example`) allowing Main components to request the Welcome UI on demand via `HeavyProcessConnectionManager` without holding any heavy UI or Compose dependencies.
  - Ensured process-death resilience: If the `:heavy` process is terminated or killed while Welcome is displayed, Main process runtime (`HandleService`, handles, gestures, internet speed monitor) continues unaffected without crashes or ANRs.
  - Wrote comprehensive unit test suite in `WelcomeIpcTest.kt` covering contract integrity and JSON serialization, Main request → Heavy Welcome routing, duplicate launch debouncing and active state suppression, and Main process resilience when Heavy is disconnected or dead.
* How it was verified: local build only (`gradle :app:testDebugUnitTest` - all unit tests passed; `compile_applet` passed cleanly).
* Deviations: None.
* Known issues: None.

* Timestamp: 2026-09-16T02:58:00Z
* One-line summary: Completed migration of AppsPageView, HybridGridPageView, and WidgetsGridPageView to SidebarManager and MiniAppManager, removed obsolete legacy services, and restored calculator styles to fix compilation.
* Exact files touched:
  - `app/src/main/java/com/example/feature/sidebar/SidebarManager.kt`
  - `app/src/main/java/com/example/feature/sidebar/AppsPageView.kt`
  - `app/src/main/java/com/example/feature/sidebar/HybridGridPageView.kt`
  - `app/src/main/java/com/example/feature/sidebar/WidgetsGridPageView.kt`
  - `app/src/main/java/com/example/SidebarEditActivity.kt`
  - `app/src/main/java/com/example/WidgetPickerActivity.kt`
  - `app/src/main/java/com/example/HybridGridEditActivity.kt`
  - `app/src/main/res/values/styles_calc.xml`
  - `app/src/main/AndroidManifest.xml`
  - `app/src/main/java/com/example/service/SidebarService.kt` (deleted)
  - `app/src/main/java/com/example/service/FloatingTriggerService.kt` (deleted)
  - `app/src/main/java/com/example/feature/miniapps/reader/FloatingReaderService.kt` (deleted)
* What was actually done:
  - Added `openContainerById(containerId: String)` and `closeSidebar()` alias to `SidebarManager.kt` to allow clean resolution of containers via canonical handle/gesture format without passing raw intent triggers.
  - Updated `AppsPageView.kt` to use `SidebarManager.getInstance(context).openContainerById(item.targetId)` and eliminated obsolete service import.
  - Refactored `HybridGridPageView.kt` to route `FloatingTrigger` to `SidebarManager.getInstance(context).openContainerById(parsed.targetId)`, close actions to `SidebarManager.closeSidebar()`, and all floating app toggles (`dictionary`, `translation`, `hybrid_grid`, `work_notes`, `reader`) directly to `MiniAppManager.toggleApp(...)`.
  - Refactored `WidgetsGridPageView.kt` to route `FloatingTrigger` to `SidebarManager.openContainerById(parsed.targetId)` and removed obsolete service imports.
  - Cleaned up obsolete service references in `SidebarEditActivity.kt`, `WidgetPickerActivity.kt`, and `HybridGridEditActivity.kt`.
  - Removed deprecated service declarations (`SidebarService`, `FloatingTriggerService`, `FloatingReaderService`) from `AndroidManifest.xml` and deleted their obsolete stub files.
  - Restored `styles_calc.xml` defining `CalcBtn` and `CalcBtnSmall` styles required by `page_calculator.xml`.
  - Verified that all compilation errors are resolved and the applet builds cleanly.
* How it was verified: local build only (`compile_applet` passed cleanly; `gradle :app:testDebugUnitTest` executed and passed all unit tests).
* Any deviation from what was requested, and why: None.
* Any known issue or follow-up needed: None.

* Timestamp: 2026-09-16T13:28:00Z
* One-line summary: Added previous sidebar repo snapshot directory (Vian-Sidebar-main/) to .gitignore.
* Exact files touched:
  - `.gitignore`
  - `receipts/RECEIPTS_095.md`
* What was actually done:
  - Added `Vian-Sidebar-main/` under the Reference Folders & Scripts section in `.gitignore` to prevent exporting deprecated monolithic files and reference code into version control.
* How it was verified: local build only (file syntax verified; project remains compiling).
* Any deviation from what was requested, and why: None.
* Any known issue or follow-up needed: None.

* Timestamp: 2026-09-17T22:25:00Z
* One-line summary: Registered GhostCameraActivity and CameraMeasureActivity in manifest, declared mediaProjection foreground service attributes, and synchronized folder popup system action handling.
* Exact files touched:
  - `app/src/main/AndroidManifest.xml`
  - `app/src/main/java/com/example/feature/sidebar/HybridGridPageView.kt`
  - `receipts/RECEIPTS_095.md`
* What was actually done:
  - Inspected reference implementation in `Vian-Sidebar-main/app/src/main/AndroidManifest.xml` and identified missing `<activity>` declarations for `GhostCameraActivity` and `CameraMeasureActivity`.
  - Added declarations for `.feature.system_hub.GhostCameraActivity` and `.feature.system_hub.CameraMeasureActivity` to `app/src/main/AndroidManifest.xml` with `android:exported="false"` and `android:theme="@style/Theme.LiteReader"`.
  - Added `android:foregroundServiceType="mediaProjection"` to `.service.ScreenRecordService` and added `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />` to satisfy Android 14 foreground service requirements for screen recording.
  - Updated `showFolderPopup` in `HybridGridPageView.kt` to handle all system actions (`audio_record`, `camera_measure`, `arrangement_checker` / `ghost_camera`, `settings`, and accessibility service fallback), ensuring folder items function identically to root grid items without silent drops.
* How it was verified: local build only (`compile_applet` passed cleanly; `gradle :app:testDebugUnitTest` executed and passed all unit tests).
* Any deviation from what was requested, and why: None.
* Any known issue or follow-up needed: Screen capture sub-actions (`redact_screenshot`, `qr_scan`, `barcode_scanner`) are defined as items in `SidebarAppsManager.kt` but do not have local activity implementations in this repository; they route gracefully to the accessibility service or display an enablement toast without crashing.

* Timestamp: 2026-09-17T22:33:00Z
* One-line summary: Restored Handle appearance (slanted block shape, #242962ff default color, right edge default) and robust gesture touch handling aligned with OG reference.
* Exact files touched:
  - `app/src/main/java/com/example/util/HandleShapeDrawable.kt`
  - `app/src/main/java/com/example/core/HandleManager.kt`
  - `app/src/main/java/com/example/core/TriggerHandleView.kt`
  - `receipts/RECEIPTS_095.md`
* What was actually done:
  - Inspected OG reference in `Vian-Sidebar-main/app/src/main/java/com/example/util/HandleShapeDrawable.kt` and restored `SLANTED_BLOCK` geometry, robust `HandleShape.fromString()` and `HandleEdge.fromString()` factory methods, and precise edge slant path construction.
  - Aligned default handle configuration in `HandleManager.kt` to match the OG reference: `HandleShape.SLANTED_BLOCK`, `HandleEdge.RIGHT`, default color `#242962ff` (14% alpha), width 12dp, height 120dp, `onSwipeLeftAction = "open_sidebar"`, and self-healed stale template placeholder defaults.
  - Enhanced `TriggerHandleView.kt` with window layout parameter flags (`FLAG_NOT_TOUCH_MODAL`), `onDown: true` in `GestureDetector`, and inward horizontal/vertical gesture detection on `ACTION_MOVE` to ensure responsive, tactile swipe-to-open sidebar triggering with haptic feedback.
  - Preserved the Main process runtime ownership of handle detection and the Handle → Gesture → Container → Page independent architecture.
* How it was verified: local build only (`compile_applet` passed cleanly; `gradle :app:testDebugUnitTest` executed and passed all unit tests).
* Any deviation from what was requested, and why: None.
* Any known issue or follow-up needed: None.

* Timestamp: 2026-09-18T13:38:30-07:00
* One-line summary: Decoupled AppNotificationListener from Room/AppDatabase in resident Main using lightweight durable file buffer.
* Exact files touched:
  - `app/src/main/java/com/example/data/NotificationHistoryBuffer.kt`
  - `app/src/main/java/com/example/service/AppNotificationListener.kt`
  - `app/src/main/java/com/example/NotificationHistoryActivity.kt`
  - `app/src/test/java/com/example/data/NotificationHistoryBufferTest.kt`
  - `receipts/RECEIPTS_095.md`
* What was actually done:
  - Removed `com.example.data.AppDatabase.getDatabase(...)` call and all Room database initialization from `AppNotificationListener.kt`.
  - Created `NotificationHistoryBuffer.kt` in `com.example.data` providing lightweight, durable append-only file persistence (`notification_history_buffer.log`) in internal storage with OS `FileChannel` file locks. Utilizes an in-memory debounce map to filter rapid duplicate events within a 15s window without loading any SQLite/Room dependencies into the resident Main process.
  - Connected `NotificationHistoryActivity.kt` to call `NotificationHistoryBuffer.ingestPending(context, dao)` on screen composition, seamlessly draining the durable buffer and inserting/updating entries into Room (`NotificationHistoryDao`) on-demand when the UI activity is opened.
  - Updated "Clear All" action in `NotificationHistoryActivity` to clear both the Room table and any pending entries in `NotificationHistoryBuffer`.
  - Created comprehensive unit test suite `NotificationHistoryBufferTest.kt` verifying buffer appending, debouncing, draining, and DAO ingestion.
* How it was verified: local build only (`compile_applet` passed cleanly; `gradle :app:testDebugUnitTest` passed all 34 unit tests including `NotificationHistoryBufferTest`).
* Any deviation from what was requested, and why: None.
* Any known issue or follow-up needed: None. Existing Notification History behavior, filtering, and export are fully preserved without resident Main Room overhead.

* Timestamp: 2026-09-18T14:39:40-07:00
* One-line summary: Completed architectural audit of Home Grid and Sidebar grid app/link loading and icon caching without code changes.
* Exact files touched:
  - `receipts/RECEIPTS_095.md`
* What was actually done:
  - Audited `SidebarAppsManager.kt`, `HybridGridPageView.kt`, `AppsPageView.kt`, `SidebarView.kt`, `SidebarEditActivity.kt`, `AddElementActivity.kt`, `HybridGridEditActivity.kt`, and `IconCacheManager.kt`.
  - Analyzed resident memory footprint: discovered `SidebarAppsManager` registers an active `BroadcastReceiver` for package changes and queries all installed activities via `LauncherApps.getActivityList()` on init/ensureLoaded, keeping `allInstalledApps` in memory in the resident Main process.
  - Analyzed item storage model: identified that grid items are stored as raw ID strings (`app:packageName`, `link:uuid:{...}`) in SharedPreferences (`FloatingReaderPrefs`), without persistent labels or pre-compressed icons attached to the element payload.
  - Analyzed icon lifecycle: discovered icons are loaded on-the-fly when pages open via `IconCacheManager` and `pm.getApplicationIcon()`, cached in multiple `LruCache` instances in Main memory.
  - Prepared design roadmap for persistent element data model without modifying any application code.
* How it was verified: local audit only; no application code files altered.
* Any deviation from what was requested, and why: None (strictly audit only).
* Any known issue or follow-up needed: Ready for user review before implementing persistent element data model.

* Timestamp: 2026-09-19T13:36:30-07:00
* One-line summary: Implemented persistent app/link element-data model, eradicated resident allInstalledApps & package BroadcastReceiver.
* Exact files touched:
  - `app/src/main/java/com/example/core/IconCacheManager.kt`
  - `app/src/main/java/com/example/feature/sidebar/ElementMetadataStore.kt`
  - `app/src/main/java/com/example/feature/sidebar/SidebarAppsManager.kt`
  - `app/src/main/java/com/example/feature/settings/AppPickerActivity.kt`
  - `app/src/main/java/com/example/feature/settings/AddElementActivity.kt`
  - `app/src/main/java/com/example/feature/sidebar/AppsPageView.kt`
  - `receipts/RECEIPTS_095.md`
* What was actually done:
  - Created `ElementMetadataStore.kt` (`data class ElementMetadata` with id, type, target, label, iconPath) persisting element data to SharedPreferences (`FloatingReaderPrefs`) with automatic canonical lookups and one-time legacy migration.
  - Enhanced `IconCacheManager.kt` with `captureAndSavePackageIcon` and `captureAndSaveLinkIcon` methods, capturing icons once and compressing/downsampling them to compact WebP files on disk (`filesDir/icons_webp_cache`).
  - Updated `SidebarItem.App` and `SidebarItem.Link` to include `iconPath` and `isLaunchable` fields with backwards-compatible constructors.
  - Eradicated `packageReceiver` (`BroadcastReceiver` for package changes) and resident `allInstalledApps` caching from `SidebarAppsManager.kt`, replacing `allInstalledApps` with an empty read-only getter to drop resident Main memory footprint.
  - Removed full-app scan (`loadAllAppsFromPackageManager`) from `ensureLoaded()` in `SidebarAppsManager.kt`, replacing runtime app resolution with direct `ElementMetadataStore` reads and targeted `PackageManager.getLaunchIntentForPackage()` checks for single elements.
  - Updated `AppPickerActivity.kt` to query `LauncherApps` only during picker lifecycle in its own coroutine, capture the selected app's label and compact WebP icon via `ElementMetadataStore.saveAppElement` once upon selection, and terminate without creating a resident `SidebarAppsManager`.
  - Updated `AddElementActivity.kt` to capture and persist link element metadata and WebP icon to disk via `ElementMetadataStore.saveLinkElement` upon link creation.
  - Updated `AppsPageView.kt` to render `SidebarItem.Link` with cached disk icons from `manager.getIconBitmap`.
* How it was verified: local build only (`compile_applet` passed cleanly; `gradle :app:testDebugUnitTest` passed all 34 unit tests).
* Any deviation from what was requested, and why: None.
* Any known issue or follow-up needed: None. Existing grid/page behavior, UI appearance, and custom icon overrides are fully preserved.

* Timestamp: 2026-09-24T20:34:00Z
* One-line summary: Fixed notification BadForegroundServiceNotificationException crash by restoring 33 corrupted PNG assets, added active runtime drawable validation, and hardened AlarmManager recovery triggers.
* Exact files touched:
  - `tools/generate_speed_icons.py`
  - `app/src/main/res/drawable-xhdpi/ic_stat_speed_25_k.png` (and 32 other corrupted speed icons)
  - `app/src/main/java/com/example/core/HandleService.kt`
  - `app/src/main/java/com/example/core/MainProcessRecovery.kt`
  - `app/src/main/java/com/example/service/BootReceiver.kt`
  - `app/src/test/java/com/example/core/MainProcessRecoveryTest.kt`
  - `receipts/RECEIPTS_095.md`
* What was actually done:
  - Discovered root cause of BadForegroundServiceNotificationException: 33 pre-rendered speed icon assets (including `ic_stat_speed_25_k.png`) were corrupted with UTF-8 replacement characters (`\xEF\xBF\xBD`) instead of the PNG magic header (`\x89PNG\r\n\x1a\n`). While AAPT2 indexed them in `resources.arsc` under ID `0x7f0601fe`, SystemUI failed to decode them into Drawables, throwing BadForegroundServiceNotificationException.
  - Identified why `resolveSafeIconResId()` previously failed: `resources.getResourceName(candidateResId)` only queried the symbol table and never verified that the underlying resource could be decoded into a Drawable.
  - Enhanced `tools/generate_speed_icons.py` with `is_valid_png()` check verifying the 8-byte PNG header, and regenerated all 33 corrupted icon files so that all 1,421 PNG assets on disk are genuine 96x96 PNG images.
  - Updated `isValidDrawableResource()` in `HandleService.kt` to actively test decoding using `ContextCompat.getDrawable(this, resId)` backed by a concurrent cache (`validatedIconCache`), preventing any undecodable resource from ever being passed to `setSmallIcon()`.
  - Added safe fallback substitution logging in `HandleService.buildNotification()` if an unusable icon candidate is ever resolved.
  - Enhanced `MainProcessRecovery.kt` to use `setExactAndAllowWhileIdle()` / `setAndAllowWhileIdle()` on Android 6.0+ / 12+ instead of inexact `set()`, ensuring the recovery alarm fires even under background battery optimizations.
  - Added comprehensive diagnostic lifecycle logs across `HandleService.kt`, `MainProcessRecovery.kt`, and `BootReceiver.kt` to distinguish sticky restarts, alarm triggers, receiver dispatches, and startup states.
  - Added unit tests in `MainProcessRecoveryTest.kt` verifying `SpeedIconProvider` resolution and validating the 8-byte PNG signatures across all 1,421 assets.
* How it was verified: local build only (`compile_applet` passed cleanly; `gradle :app:testDebugUnitTest` executed and passed all unit test suites).
* Any deviation from what was requested, and why: None.
* Any known issue or follow-up needed: None. All 1,421 pre-rendered speed icons, sampling, TrafficStats, and Handle behaviors are fully preserved.

* Timestamp: 2026-09-24T21:09:00Z
* One-line summary: Slightly widened Sidebar from 198dp to 220dp and enabled wrap_content by default across SidebarWindow, ViewPager frame, and updateWindowForPage.
* Exact files touched:
  - `app/src/main/java/com/example/feature/sidebar/SidebarView.kt`
  - `app/src/main/java/com/example/feature/sidebar/SidebarWindow.kt`
  - `receipts/RECEIPTS_095.md`
* What was actually done:
  - Adjusted default 3-column / baseline sidebar width from 198dp to 220dp in `SidebarView.kt` and `SidebarWindow.kt` (with <=2 cols scaled from 140dp to 155dp, and >=4 cols scaled from 240dp to 265dp) for a modest, comfortable increase while preserving the existing rounded shape, edge handling, and handle relationship.
  - Eliminated hardcoded `MATCH_PARENT` height in `SidebarWindow.kt`, setting initial window height to `WindowManager.LayoutParams.WRAP_CONTENT` and synchronizing layout parameters from `SidebarView.sidebarLayoutParams`.
  - Updated `onCreateViewHolder` in `SidebarView.kt` so the child page container FrameLayout uses `WRAP_CONTENT` height instead of `MATCH_PARENT` when wrap-content is active.
  - Refactored `updateWindowForPage` in `SidebarView.kt` so that default wrap-content pages set `WRAP_CONTENT` for both `viewPager.layoutParams` and `layoutParams.height`, allowing pages (apps, hybrid grid, widgets grid, tools) to hug their content naturally rather than stretching to a fixed 280dp box.
  - Preserved element sizes, item spacing, scrolling, and widget measurement behavior.
* How it was verified: local build only (`compile_applet` passed cleanly; `gradle :app:testDebugUnitTest` executed and passed all unit tests).
* Any deviation from what was requested, and why: None.
* Any known issue or follow-up needed: None. Ready for combined on-device testing.






