# Receipts Ledger — Part 098

Permanent, sequential audit trail for codebase modifications, testing, and lifecycle actions.
Capped at 500 lines per file.

---

* Timestamp: 2026-10-24T01:13:00-07:00
* One-line summary of what was requested: Migrate Scheduler (Short Reminders) page UI/runtime to :heavy process via SurfaceControlViewHost while preserving persistent Room database data and task lifecycle.
* Exact files touched:
  - app/src/main/java/com/example/feature/sidebar/SidebarPageFactory.kt
  - app/src/main/java/com/example/feature/sidebar/SchedulerPageView.kt
  - app/src/main/java/com/example/feature/heavy/page/HeavyPageHostManager.kt
  - app/src/test/java/com/example/core/HandleGestureContainerTest.kt
  - app/src/test/java/com/example/feature/sidebar/HeavySchedulerSplitTest.kt
  - blueprint/BLUEPRINT.md
  - receipts/RECEIPTS_098.md
* What was actually done:
  - Comprehensive Pre-Migration Ownership Audit:
    * Categorized Scheduler components: UI/layout/adapters belong in `:heavy` (Category A); System broadcast receivers and services remain resident in Main (Category B); Shared Room database (`AppDatabase` / `SchedulerTaskDao` / `SchedulerTask`) operates across processes via SQLite locking with zero database duplication (Category C).
  - Main Process Delegation (`SidebarPageFactory.kt`):
    * Routed `"scheduler"` to `HeavyPagePlaceholderView(context, config, containerId) { onHeightChanged(it) }`.
    * Main process preserves exclusively the single resident Home Grid (`default_hybrid` on Handle 1 -> Swipe Left -> first/default container).
  - Heavy Host Runtime (`HeavyPageHostManager.kt`):
    * Extended `createSurfacePackageApi30` (API 30+) and `mountPage` (API 26-29 fallback) to instantiate `SchedulerPageView` in `:heavy`.
    * Wired dynamic height measurement (`getCurrentHeightPx()`) and Binder height reporting (`reportDesiredPageHeight`).
    * Implemented theme background synchronization (`applyThemeBackground`).
    * Added explicit coroutine collector cleanup (`cleanup()`) on `releaseSurfaceHost` and `unmountPageInternal`.
  - Lifecycle & UI Improvements (`SchedulerPageView.kt`):
    * Added `applyThemeBackground(bgColor, isRight)` and `themeBackgroundDrawable` matching Sidebar styling.
    * Added `getCurrentHeightPx(): Int` bounded between 100dp and 520dp for initial SurfaceControlViewHost layout.
    * Added `onAttachedToWindow()`, `onDetachedFromWindow()`, `onPageSelected()`, `onPageUnselected()`, and `cleanup()` to cancel background Flow collectors without touching persistent data.
    * Ensured overlay dialog safety with `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`.
  - Test Suite (`HeavySchedulerSplitTest.kt`):
    * Created 8 focused unit tests validating Scheduler routing to Heavy in Main; exact resident Home Grid retention in Main; Calculator, Apps Grid, Widgets Grid, Single Widget, and Compass remaining Heavy; secondary grid routing; lifecycle and task job cleanup; theme background application and height bounds; SchedulerTask entity integrity; and untouched Internet Speed Monitor files.
* How it was verified: local build only (`gradle :app:testDebugUnitTest --tests "com.example.feature.sidebar.HeavySchedulerSplitTest"`, `gradle :app:testDebugUnitTest --tests "com.example.feature.sidebar.HeavyCompassSplitTest"`, and `gradle assembleDebug`).
* Any deviation from what was requested, and why: None. Internet Speed Monitor, Calculator, Apps Grid, Widgets Grid, Single Widget, Compass, and other unmigrated pages untouched.
* Any known issue or follow-up needed: Ready for on-device manual QA verification.

---

* Timestamp: 2026-10-24T01:37:00-07:00
* One-line summary of what was requested: Fix ONLY the Scheduler FAB touch issue where tap events failed to reach the click listener in :heavy.
* Exact files touched:
  - app/src/main/res/layout/page_scheduler.xml
  - app/src/main/java/com/example/feature/sidebar/SchedulerPageView.kt
  - app/src/test/java/com/example/feature/sidebar/HeavySchedulerSplitTest.kt
  - blueprint/BLUEPRINT.md
  - receipts/RECEIPTS_098.md
* What was actually done:
  - Resolved Touch Shadowing in ViewGroup Hit-Testing:
    * In `page_scheduler.xml`, `ll_list` (`match_parent` x `match_parent`) shadowed `fab_add` during touch dispatch within `SurfaceControlViewHost`. Added `android:elevation="8dp"`, `android:translationZ="8dp"`, `android:clickable="true"`, and `android:focusable="true"` to `fab_add` to trigger `hasChildWithZ()` pre-ordering in `ViewGroup.dispatchTouchEvent`.
  - Child Index Ordering & State Tracking:
    * In `SchedulerPageView.kt`, called `fabAdd?.bringToFront()` on initialization and in `hideEditUi()` to place `fab_add` at the top of the child index order.
    * Added `isEditingState` tracking so state transitions between list and editor are deterministic and verifiable.
    * Logged FAB click and edit transition to LogKeeper (`"Scheduler", "FAB add clicked -> showEditUi"`).
    * Safely guarded `loadTags()` against mock contexts lacking full `LayoutInflater` services.
  - Regression Testing:
    * Added `testFabClickShowsEditUiAndCancelingRestoresList()` to `HeavySchedulerSplitTest.kt` verifying that clicking the FAB transitions the UI to the editor state, hides the list and FAB, and clicking cancel returns the view cleanly to the list state.
* How it was verified: local build only (`gradle :app:testDebugUnitTest --tests "com.example.feature.sidebar.HeavySchedulerSplitTest"` passed with 9/9 tests; `gradle assembleDebug` passed).
* Any deviation from what was requested, and why: None. Calculator, Internet Speed Monitor, and all other migrated pages untouched. No redesign, no new IPC, and no added overlays.
* Any known issue or follow-up needed: Ready for on-device touch validation of the Scheduler FAB in :heavy.
---

* Timestamp: 2026-10-26T01:38:00-07:00
* One-line summary of what was requested: Sequential migration of App Tracker (Unit 1) and Media Player (Unit 2) from Main process to Heavy process using SurfaceControlViewHost.
* Exact files touched:
  - app/src/main/java/com/example/feature/sidebar/SidebarPageFactory.kt
  - app/src/main/java/com/example/feature/heavy/page/HeavyPageHostManager.kt
  - app/src/main/java/com/example/feature/sidebar/AppTrackerPageView.kt
  - app/src/main/java/com/example/feature/sidebar/MediaPlayerPageView.kt
  - app/src/test/java/com/example/feature/sidebar/HeavyAppTrackerSplitTest.kt
  - app/src/test/java/com/example/feature/sidebar/HeavyMediaPlayerSplitTest.kt
* What was actually done:
  - Unit 1 (App Tracker -> Heavy):
    * In `SidebarPageFactory.kt`, delegated `"app_tracker"` to `HeavyPagePlaceholderView`.
    * In `HeavyPageHostManager.kt`, added `"app_tracker"` instantiation in `createSurfacePackageApi30` and `mountPage`, dynamic height measurement, theme background styling, and lifecycle cleanup.
    * In `AppTrackerPageView.kt`, added `getCurrentHeightPx()`, `applyThemeBackground()`, coroutine `cleanup()`, and safe adapter notification for headless/mock environments.
    * Verified with `HeavyAppTrackerSplitTest` (7/7 tests passed) and compilation.
  - Unit 2 (Media Player -> Heavy):
    * In `SidebarPageFactory.kt`, delegated `"media_player"` to `HeavyPagePlaceholderView`.
    * In `HeavyPageHostManager.kt`, added `"media_player"` instantiation in `createSurfacePackageApi30` and `mountPage`, dynamic height measurement, theme background synchronization, and lifecycle cleanup.
    * In `MediaPlayerPageView.kt`, added `getCurrentHeightPx()`, `applyThemeBackground()`, safe controller/session error handling, and `cleanup()`.
    * Verified with `HeavyMediaPlayerSplitTest` (7/7 tests passed) and full applet compilation.
* How it was verified: local build only (`gradle :app:testDebugUnitTest --tests "com.example.feature.sidebar.HeavyAppTrackerSplitTest"`, `gradle :app:testDebugUnitTest --tests "com.example.feature.sidebar.HeavyMediaPlayerSplitTest"`, and `compile_applet`).
* Any deviation from what was requested, and why: None. Home Grid default_hybrid on Handle 1 swipe left remains the single resident page in Main. Internet Speed Monitor, Scheduler, Calculator, Apps, Widgets, Compass, Notifications, and Resources Tracker untouched.
* Any known issue or follow-up needed: None.

---

* Timestamp: 2026-10-26T14:56:00-07:00
* One-line summary of what was requested: Fix horizontal Sidebar page swiping regression on App Tracker and Media Player; fix App Tracker initial height bottom-navigation overlap and empty results after usage access permission grant.
* Exact files touched:
  - app/src/main/res/layout/item_app_tracker_running_icon.xml
  - app/src/main/res/layout/item_app_tracker_grid.xml
  - app/src/main/res/layout/page_app_tracker.xml
  - app/src/main/java/com/example/utils/AppTrackerHelper.kt
  - app/src/main/java/com/example/feature/sidebar/AppTrackerPageView.kt
  - app/src/main/java/com/example/feature/sidebar/MediaPlayerPageView.kt
  - app/src/main/java/com/example/feature/sidebar/SidebarView.kt
  - app/src/main/java/com/example/feature/heavy/page/HeavyPageHostManager.kt
  - app/src/test/java/com/example/feature/sidebar/HeavyAppTrackerSplitTest.kt
  - receipts/RECEIPTS_098.md
* What was actually done:
  - Task 1 (Swipe Regression Root Cause & Resolution):
    * Discovered that `item_app_tracker_running_icon.xml` and `item_app_tracker_grid.xml` contained explicit `android:clickable="true"` and `android:focusable="true"` on their root containers, and `page_app_tracker.xml` had `match_parent` RecyclerView with `wrap_content` tv_empty, consuming `ACTION_DOWN` across the entire screen inside the embedded SurfaceControlViewHost window.
    * In `MediaPlayerPageView`, `updateState()` and `updateUI()` were never called on initialization or attach, leaving default visible child buttons and an unmeasured height (falling back to 360dp) that captured touches.
    * Removed `android:clickable="true"` and `android:focusable="true"` from item layouts. Made `tv_empty` match_parent with centered gravity in `page_app_tracker.xml`, and dynamically hid `RecyclerView` when empty (matching `AppsPageView` and `SchedulerPageView`) so empty-state touches pass through to host ViewPager2.
    * Implemented proper layout measurement in `MediaPlayerPageView.getCurrentHeightPx()` (80dp-120dp), called `updateState()` and `updateUI()` in `init` and `onAttachedToWindow()`, showing unclickable "Nothing playing" when no active media is present.
    * Driven lifecycle (`onPageSelected()` / `onPageUnselected()`) from `HeavyPageHostManager` on attach and detach.
  - Task 2 (App Tracker Physical Bugs):
    * Resolved initial height/bottom-navigation overlap: aligned `SidebarView.kt` initial fallback heights (`"app_tracker" -> 180`, `"media_player" -> 100`) and clamped `AppTrackerPageView` to 180dp-360dp, eliminating 560dp navigation-bar overflow.
    * Resolved empty data on permission return: added initial `loadData()` call in `AppTrackerPageView.init`, added `onWindowFocusChanged` and `onWindowVisibilityChanged` to immediately recheck permission and refresh data when user returns from system Settings.
    * Enhanced `AppTrackerHelper.getRecentApps()`: added `MOVE_TO_FOREGROUND` event checking, `queryAndAggregateUsageStats`, and 7-day fallback window when 24h query returns empty.
  - Task 3 (Media Player):
    * Preserved existing Media Player session querying without redesign. Reported current capabilities and limitations.
* How it was verified: local build only (`gradle :app:testDebugUnitTest --tests "com.example.feature.sidebar.HeavyAppTrackerSplitTest" --tests "com.example.feature.sidebar.HeavyMediaPlayerSplitTest"` passed; `gradle :app:testDebugUnitTest --tests "com.example.feature.sidebar.HeavySchedulerSplitTest" --tests "com.example.feature.sidebar.HeavyCompassSplitTest"` passed; `compile_applet` passed; confirmed `setZOrderOnTop(true)` remains intact).
* Any deviation from what was requested, and why: None. No global touch workarounds or generic SurfaceView modifications introduced. All existing working pages untouched.
* Any known issue or follow-up needed: Ready for on-device manual QA validation of swiping from App Tracker and Media Player.

---

* Timestamp: 2026-10-26T15:56:00-07:00
* One-line summary of what was requested: Focused fix restoring App Tracker visible UI (tab pills and FAB), stabilizing mount churn, and cleanly removing Media Player as an available Sidebar page.
* Exact files touched:
  - app/src/main/res/layout/page_app_tracker.xml
  - app/src/main/java/com/example/feature/sidebar/AppTrackerPageView.kt
  - app/src/main/java/com/example/feature/sidebar/SidebarView.kt
  - app/src/main/java/com/example/feature/sidebar/SidebarPageFactory.kt
  - app/src/main/java/com/example/feature/heavy/page/HeavyPageHostManager.kt
  - app/src/main/java/com/example/feature/settings/handle/HandleSettingsScreen.kt
  - app/src/test/java/com/example/feature/sidebar/HeavyAppTrackerSplitTest.kt
  - app/src/test/java/com/example/feature/sidebar/HeavyMediaPlayerSplitTest.kt
  - receipts/RECEIPTS_098.md
* What was actually done:
  - Task 1 (App Tracker UI Restoration & Mount Churn Elimination):
    * Root cause analysis: In the previous turn, `onWindowFocusChanged` and `onWindowVisibilityChanged` were added to `AppTrackerPageView`, which repeatedly fired on every WindowManager layout change, causing rapid coroutine queries, unconditional `onHeightChanged` dispatches, ViewPager rebinds, and detachment/unbinding where `embeddedSurfaceView?.alpha` was set to 0f, making the surface blank. Furthermore, `onPageUnselected()` was aggressively clearing all app data, and `setTab(0)` was hiding the FAB when `recentApps` was empty.
    * Removed `onWindowFocusChanged` and `onWindowVisibilityChanged` listeners to permanently eliminate the oscillation feedback loop.
    * Restored Tab 0 FAB visibility (`fabStopAll?.visibility = View.VISIBLE`), brought FAB to front (`bringToFront()`), and added `android:translationZ="6dp"` to `page_app_tracker.xml`.
    * Retained loaded app lists in `onPageUnselected()` so page does not clear state on temporary unselect/rebind passes.
    * Added change-deduplication to `updateList` (`if (targetH != lastCalculatedHeightPx)`), preventing spurious height reporting.
    * Bounded measured height to a comfortable compact range (`minH = 240dp`, `maxH = 420dp`, fallback `320dp`), preventing tab pills from being pushed off-screen while staying safely above the bottom navigation bar.
    * Updated `SidebarView.kt` initial fallback height for `app_tracker` to 320dp.
  - Task 2 (Clean Removal of Media Player Sidebar Page):
    * Removed Media Player from user-facing Add Page dialog in `HandleSettingsScreen.kt`.
    * In `SidebarView.kt`, sanitized incoming `pageConfigs` by filtering out any legacy or saved Media Player page entries (`it.type != "media_player"`), ensuring existing configs cannot break ViewPager, crash, or attempt Heavy connection.
    * In `SidebarPageFactory.kt`, updated `"media_player"` routing to return an inert zero-sized GONE view fallback.
    * In `HeavyPageHostManager.kt`, removed obsolete Media Player hosting, theme application, and cleanup blocks.
    * Updated `HeavyMediaPlayerSplitTest.kt` to prove safe decommission without creating Heavy placeholders.
* How it was verified: local build only (`gradle :app:testDebugUnitTest --tests "com.example.feature.sidebar.HeavyAppTrackerSplitTest" --tests "com.example.feature.sidebar.HeavyMediaPlayerSplitTest" --tests "com.example.feature.sidebar.HeavySchedulerSplitTest" --tests "com.example.feature.sidebar.HeavyCompassSplitTest"` passed with 26/26 tests; `compile_applet` passed cleanly; transient keystore files purged per Credential Immunity Rule).
* Any deviation from what was requested, and why: None. Retained `setZOrderOnTop(true)` and Main/Heavy process split. Internet Speed Monitor, Scheduler, Calculator, Apps, Widgets, Compass, and Notifications untouched.
* Any known issue or follow-up needed: Ready for physical device verification of restored App Tracker UI, smooth horizontal swiping, and clean omission of Media Player.

---

* Timestamp: 2026-10-28T15:30:00-07:00
* One-line summary of what was requested: Fix Notification Sidebar page and Edit screen app filters to include System Apps with All Apps, User Apps, and System Apps category filtering.
* Exact files touched:
  - app/src/main/java/com/example/feature/notification/NotificationAppFilterHelper.kt
  - app/src/main/java/com/example/NotificationHistoryActivity.kt
  - app/src/test/java/com/example/feature/notification/NotificationAppFilterHelperTest.kt
  - receipts/RECEIPTS_098.md
* What was actually done:
  - Created `NotificationAppFilterHelper.kt` declaring `NotificationAppItem`, `NotificationAppFilterCategory`, system app detection via `(appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0`, and filtering logic.
  - Refactored `NotificationHistoryActivity.kt` filter dialogs into a unified, reusable `NotificationAppFilterDialog` component providing FilterChips for "All Apps", "User Apps", and "System Apps".
  - Updated both Notification Sidebar page filter (`sidebar_hidden_packages`) and Notification Edit screen filter (`history_hidden_packages`) to load all installed packages and support category switching.
  - Created unit tests in `NotificationAppFilterHelperTest.kt` verifying category filtering and system app metadata classification.
* How it was verified: local build only (`gradle :app:testDebugUnitTest --tests "com.example.feature.notification.NotificationAppFilterHelperTest"` passed with 5/5 tests; full unit test suite passed; `compile_applet` passed cleanly).
* Any deviation from what was requested, and why: None. Notification data/IPC, listener behavior, Heavy/Main architecture, Notification UI/layout, Notification history, and Internet Speed Monitor untouched.


## Entry 099 - Offline PWA Foundation Implementation
* Timestamp: 2026-10-29T10:51:00Z
* One-line summary: Implemented real Offline PWA foundation with Room storage, zip import pipeline, Add Element picker integration, and full-screen Heavy runtime player.
* Exact files touched:
  - app/src/main/java/com/example/data/PwaEntity.kt
  - app/src/main/java/com/example/data/PwaDao.kt
  - app/src/main/java/com/example/data/AppDatabase.kt
  - app/src/main/java/com/example/feature/pwa/PwaManifestParser.kt
  - app/src/main/java/com/example/feature/pwa/PwaZipImporter.kt
  - app/src/main/java/com/example/feature/pwa/PwaStorageManager.kt
  - app/src/main/java/com/example/feature/pwa/PwaPlayerActivity.kt
  - app/src/main/java/com/example/feature/pwa/PwaEditActivity.kt
  - app/src/main/java/com/example/feature/pwa/PwaListActivity.kt
  - app/src/main/java/com/example/feature/sidebar/SidebarItem.kt
  - app/src/main/java/com/example/feature/element/ElementIdParser.kt
  - app/src/main/java/com/example/feature/element/ElementActionDispatcher.kt
  - app/src/main/java/com/example/feature/sidebar/HybridGridPageView.kt
  - app/src/main/java/com/example/feature/settings/AddElementActivity.kt
  - app/src/main/java/com/example/feature/sidebar/SidebarAppsManager.kt
  - app/src/main/AndroidManifest.xml
  - app/src/test/java/com/example/feature/pwa/OfflinePwaFoundationTest.kt
* What was actually done:
  - Added Room schema (`PwaEntity`, `PwaDao`, AppDatabase version bump to 10 with MIGRATION_9_10) to persist PWA metadata and physical paths.
  - Implemented secure zip importer (`PwaZipImporter`) retaining original `dist.zip` package permanently, safely unpacking assets, and extracting manifest metadata (`PwaManifestParser`).
  - Implemented storage management (`PwaStorageManager`) with path isolation and recursive cleanup on deletion.
  - Built PWA library UI (`PwaListActivity` & `PwaEditActivity`) with search, sort, multi-delete, editing, zip import launcher, and element picker result propagation.
  - Replaced "Empty item" in `AddElementActivity` with "Offline PWA", connecting directly into grid placement via `ElementPlacementHelper.addElementToHybridGrid(...)` using container-scoped hierarchy.
  - Added `SidebarItem.Pwa`, parser resolution in `ElementIdParser`, launcher in `ElementActionDispatcher`, click execution in `HybridGridPageView`, and icon binding in `SidebarAppsManager`.
  - Registered full-screen runtime `PwaPlayerActivity` in `:heavy` with offline asset interception (`shouldInterceptRequest`), isolated storage paths, floating close pill, and back-press handling.
  - Created and ran unit tests in `OfflinePwaFoundationTest.kt` verifying manifest parsing, zip retention, unpack integrity, directory cleanup, and element ID parsing.
* How it was verified: local build only (`gradle :app:testDebugUnitTest --tests "com.example.feature.pwa.OfflinePwaFoundationTest"` passed; all project unit tests passed in 7s; `compile_applet` passed cleanly).
* Any deviation from what was requested, and why: None. No floating PWA mini-windows created yet; AI app integration kept on hold; Internet Speed Monitor, Notifications, Media, Calculator, Compass, Scheduler, and other modules left untouched.
* Any known issue or follow-up needed: Ready for on-device manual verification.

## Entry 100 - Offline PWA Startup Optimization and LogKeeper Diagnostics
* Timestamp: 2026-10-29T21:46:00Z
* One-line summary: Audited startup paths, offloaded PWA metadata resolution to ElementMetadataStore to prevent Room/SQLite access from Main and UI threads, made startIfConfigured process-aware, and connected full PWA runtime diagnostics to LogKeeper.
* Exact files touched:
  - app/src/main/java/com/example/feature/sidebar/ElementMetadataStore.kt
  - app/src/main/java/com/example/feature/element/ElementIdParser.kt
  - app/src/main/java/com/example/feature/sidebar/SidebarAppsManager.kt
  - app/src/main/java/com/example/feature/pwa/PwaZipImporter.kt
  - app/src/main/java/com/example/feature/pwa/PwaManifestParser.kt
  - app/src/main/java/com/example/feature/pwa/PwaListActivity.kt
  - app/src/main/java/com/example/feature/pwa/PwaEditActivity.kt
  - app/src/main/java/com/example/feature/pwa/PwaPlayerActivity.kt
  - app/src/main/java/com/example/core/HandleService.kt
  - app/src/test/java/com/example/feature/pwa/OfflinePwaFoundationTest.kt
  - receipts/RECEIPTS_098.md
* What was actually done:
  - Identified startup slowdown root cause: `ElementIdParser.parsePwa` and `SidebarAppsManager.bindIcon` were performing synchronous queries to `AppDatabase.getDatabase()`, triggering SQLite database builder, schema verification, and `MIGRATION_9_10` execution directly on the UI thread and across process boundaries.
  - Added PWA caching support to `ElementMetadataStore` (`savePwaElement`, canonical prefix lookup in `FloatingReaderPrefs`).
  - Offloaded `ElementIdParser.parsePwa` and `SidebarAppsManager.bindIcon` to read from `ElementMetadataStore`, completely removing Room database queries from element ID parsing and sidebar icon binding.
  - Updated `PwaZipImporter`, `PwaListActivity`, and `PwaEditActivity` to synchronize PWA metadata directly with `ElementMetadataStore`.
  - Updated `HandleService.startIfConfigured` to be process-aware, using tag `"LauncherBridge"` when forwarding the start intent from `:heavy` to prevent misleading `"HandleService"` lifecycle logs in `:heavy`.
  - Added comprehensive PWA LogKeeper diagnostics: import start/finish with duration, zip unpack failures, manifest parse failures, PWA player launch with PWA ID, page started, page finished, WebView load errors (onReceivedError & onReceivedHttpError), missing local assets, blocked path traversal requests, JS console warnings/errors via `WebChromeClient.onConsoleMessage`, and PWA runtime exit/cleanup.
* How it was verified: local build only (`gradle :app:testDebugUnitTest --tests "com.example.feature.pwa.OfflinePwaFoundationTest"` passed; all project unit tests passed in 4s; `compile_applet` passed cleanly).
* Any deviation from what was requested, and why: None. Internet Speed Monitor, Notifications, Media, Calculator, Compass, Scheduler, and other modules left untouched.
* Any known issue or follow-up needed: Ready for on-device manual testing.

## Entry 101 - PWA Presentation Caching, Squircle Layer, Virtual Origin Runtime, and Diagnostics
* Timestamp: 2026-10-29T22:08:00Z
* One-line summary: Resolved PWA icon reload flash via process-wide IconCacheManager presentation caching, added continuous squircle clipping for web/custom elements, migrated PWA runtime to isolated virtual origin with SPA routing fallback, added orientation customization, and instrumented 10s watchdog diagnostics.
* Exact files touched:
  - app/src/main/java/com/example/core/SquircleHelper.kt
  - app/src/main/java/com/example/core/IconCacheManager.kt
  - app/src/main/java/com/example/feature/sidebar/SidebarAppsManager.kt
  - app/src/main/java/com/example/feature/sidebar/AppsPageView.kt
  - app/src/main/java/com/example/feature/pwa/PwaZipImporter.kt
  - app/src/main/java/com/example/feature/pwa/PwaEditActivity.kt
  - app/src/main/java/com/example/feature/pwa/PwaPlayerActivity.kt
  - app/src/test/java/com/example/feature/pwa/OfflinePwaFoundationTest.kt
  - receipts/RECEIPTS_098.md
* What was actually done:
  - Created `SquircleHelper` with standard Android M3 superellipse curvature (`PorterDuff.Mode.SRC_IN`) for presentation-time clipping of PWA, Link, and custom icons without modifying raw stored bitmaps or altering system adaptive app icons.
  - Upgraded `IconCacheManager` to store and serve squircle-clipped bitmaps for `pwa:$pwaId`, `link:$uuid`, and `custom_$targetId` synchronously from the process-wide `memoryCache`.
  - Fixed PWA and Link icon reload flashing in `SidebarAppsManager` and `AppsPageView`: eliminated the unconditional premature reset to `ic_language` when an image is already loaded or available in `IconCacheManager`.
  - Migrated `PwaPlayerActivity` from raw `file://` loading to dedicated virtual domain origin `https://pwa-${pwaId}.local/`, eliminating ES module/fetch CORS restrictions and establishing 100% origin isolation for DOM storage, IndexedDB, and Cache Storage.
  - Implemented SPA client-side routing fallback in `PwaPlayerActivity.shouldInterceptRequest`: automatically maps non-static subroutes to `index.html`.
  - Added orientation customization in `PwaEditActivity` (Follow Manifest, Auto/Unspecified, Portrait, Landscape) with persistence in `FloatingReaderPrefs` and dynamic resolution on launch.
  - Locked down Android Back behavior in `PwaPlayerActivity`: navigates WebView history when `canGoBack()` is true with `PWA_BACK webHistory=true -> goBack` logging, and cleanly exits at root with `PWA_BACK root -> finish`.
  - Added 10-second `PWA_LOAD_STALLED` loading watchdog and deep asset/console diagnostics to LogKeeper.
  - Added unit tests in `OfflinePwaFoundationTest` verifying PWA metadata persistence, orientation resolution logic, and SPA route identification.
* How it was verified: local build only (`gradle :app:testDebugUnitTest --tests "com.example.feature.pwa.OfflinePwaFoundationTest"` passed; all 307 project unit tests passed in 8s; `compile_applet` passed cleanly).
* Any deviation from what was requested, and why: None. Internet Speed Monitor, Notifications, Media, Calculator, Compass, Scheduler, and other modules left untouched.
* Any known issue or follow-up needed: Ready for on-device manual testing.

## Entry 102 - PWA Close Button Setting, Continuous Fast-Adding, PWA/Link Long-Press Edit, and Unified Action Popups
* Timestamp: 2026-10-30T00:50:00Z
* One-line summary: Added PWA Close Button show/hide option with non-blocking Android Back exit, enabled continuous fast-adding in PwaListActivity, added direct Edit actions to PWA and Link long-press menus, and unified all popup menus across Sidebar grids with a dedicated white pill/black icon/text helper.
* Exact files touched:
  - app/src/main/java/com/example/feature/sidebar/SidebarActionMenuHelper.kt
  - app/src/main/java/com/example/feature/pwa/PwaEditActivity.kt
  - app/src/main/java/com/example/feature/pwa/PwaPlayerActivity.kt
  - app/src/main/java/com/example/feature/pwa/PwaListActivity.kt
  - app/src/main/java/com/example/feature/settings/LinkPickerActivity.kt
  - app/src/main/java/com/example/feature/sidebar/HybridGridPageView.kt
  - app/src/main/java/com/example/feature/sidebar/AppsPageView.kt
  - app/src/main/java/com/example/feature/sidebar/WidgetsGridPageView.kt
  - app/src/test/java/com/example/feature/pwa/OfflinePwaFoundationTest.kt
  - receipts/RECEIPTS_098.md
* What was actually done:
  - Created `SidebarActionMenuHelper`: Encapsulated white pill background (`#FFFFFF`), black icons (`#000000`), black text (`#000000`), 38dp pill height, 140dp width, 19dp corner radius, 14dp horizontal padding, 18dp icons, 14sp text, and 6dp vertical margins. Replaced legacy inconsistent dark textviews across `HybridGridPageView` (both main grid and folder popups), `AppsPageView`, and `WidgetsGridPageView`.
  - Added "Close Button" setting card in `PwaEditActivity` with "Show" and "Hide" options, persisting per-PWA in `FloatingReaderPrefs` under key `"pwa_pref_${pwaId}_close_button"` (default "show").
  - Updated `PwaPlayerActivity` to check `"pwa_pref_${pwaId}_close_button"`: when set to "hide", hides the visual floating exit button (`View.GONE`) while preserving 100% of Android Back exit capability (navigates WebView history, exits runtime at root).
  - Updated `PwaListActivity` onSelectForGrid: when picking for Hybrid Grid (`isHybridGrid && !pageId.isNullOrEmpty()`), adds the element via `ElementPlacementHelper.addElementToHybridGrid`, shows feedback Toast, and remains open on the list so users can add multiple PWAs in one session before pressing Back.
  - Updated `LinkPickerActivity`: added support for `EDIT_ELEMENT_ID` intent extra to automatically pop open the editing dialog for an existing link, updating metadata and dismissing back to the caller on save/cancel.
  - Added "Edit" menu action to PWA and Link items in `HybridGridPageView` (both main grid and inside folders) and `AppsPageView`, routing to `PwaEditActivity` with `PWA_ID` or `LinkPickerActivity` with `EDIT_ELEMENT_ID`.
  - Added unit tests in `OfflinePwaFoundationTest` verifying close button preference fallback logic and menu item construction.
* How it was verified: local build only (`gradle :app:testDebugUnitTest --tests "com.example.feature.pwa.OfflinePwaFoundationTest"` passed; all project unit tests passed; `compile_applet` passed cleanly).
* Any deviation from what was requested, and why: None. Internet Speed Monitor, Notifications, Media, Calculator, Compass, Scheduler, and launcher components remained completely untouched.
* Any known issue or follow-up needed: Ready for on-device manual testing.

## Entry 103
* Timestamp: 2026-10-30T19:18:00Z
* One-line summary: Overhauled long-press action menus to match the Library popup visual reference (176dp x 52dp white pills), replaced separate Change Icon with single unified Edit action, implemented PWA and Link icon customization with instant grid refresh, fixed cross-process PWA settings propagation, added SAF portable backup export/import, and enabled continuous widget adding.
* Exact files touched:
  - app/src/main/java/com/example/feature/sidebar/SidebarActionMenuHelper.kt
  - app/src/main/java/com/example/feature/element/ElementViewRenderer.kt
  - app/src/main/java/com/example/feature/element/ElementActionDispatcher.kt
  - app/src/main/java/com/example/feature/pwa/PwaPlayerActivity.kt
  - app/src/main/java/com/example/feature/pwa/PwaEditActivity.kt
  - app/src/main/java/com/example/feature/pwa/PwaListActivity.kt
  - app/src/main/java/com/example/feature/pwa/PwaStorageManager.kt
  - app/src/main/java/com/example/feature/pwa/PwaZipImporter.kt
  - app/src/main/java/com/example/feature/sidebar/ElementMetadataStore.kt
  - app/src/main/java/com/example/feature/settings/LinkPickerActivity.kt
  - app/src/main/java/com/example/WidgetPickerActivity.kt
  - app/src/test/java/com/example/feature/pwa/OfflinePwaFoundationTest.kt
  - receipts/RECEIPTS_098.md
* What was actually done:
  - Overhauled `SidebarActionMenuHelper` visual styling to match Library popup: updated pill dimensions to 176dp width, 52dp height, 26dp corner radius, 18dp horizontal padding, 24dp icons, 16sp bold text, and 8dp vertical gap.
  - Eliminated separate "Change Icon" and "Reset Icon" menu items in favor of a single unified "Edit" action for customizable elements across `HybridGridPageView`, `AppsPageView`, and `ElementViewRenderer` (routing Apps to `IconPickerActivity`, PWAs to `PwaEditActivity`, Links to `LinkPickerActivity`, and Force Stop to `AppTrackerSettingsActivity`).
  - Fixed root cause of slow/stale PWA settings propagation across Main and `:heavy` processes by passing `PREF_ORIENTATION` and `PREF_CLOSE_BUTTON` in Intent launch extras, using synchronous SharedPreferences `.commit()`, and broadcasting via `OverlaySyncManager.syncString()`.
  - Removed duplicate bottom "Save Changes" button in `PwaEditActivity` so the top-bar checkmark (✓) is the sole confirmation action.
  - Added full icon customization inside `PwaEditActivity` (SAF image picker, squircle preview, and Reset option) and `LinkPickerActivity` (`AddEditLinkDialog`), updating disk storage, cache manager, and broadcasting `UPDATE_SIDEBAR_ICONS` so the grid updates instantly without restart.
  - Added SAF portable PWA backup export (`exportPwaBackup` creating a ZIP with `package.zip`, `pwa_metadata.json`, and `custom_icon.webp`) and seamless backup archive restore in `PwaZipImporter` via `OpenDocument`.
  - Enabled continuous additions in `WidgetPickerActivity` (`ADD_TO_WIDGETS_GRID`) by allocating the next widget ID and keeping the picker open until the user presses Back.
  - Added unit tests in `OfflinePwaFoundationTest` verifying backup archive restoration and preferences.
* How it was verified: local build only (`gradle :app:testDebugUnitTest` passed all 308 tests; `compile_applet` passed cleanly).
* Any deviation from what was requested, and why: None. Internet Speed Monitor, Notifications, Media, Calculator, Compass, Scheduler, and launcher components remained completely untouched.
* Any known issue or follow-up needed: Ready for physical device testing.

### Entry 104
* Timestamp: 2026-10-30T19:42:00Z
* One-line summary: Implemented Offline PWA Mini App management architecture (Mini App Info screen, real capability enforcement, SAF file grants, and Danger Zone deletion).
* Exact files touched:
  - app/src/main/java/com/example/feature/pwa/PwaPermissionManager.kt
  - app/src/main/java/com/example/feature/pwa/PwaEditActivity.kt
  - app/src/main/java/com/example/feature/pwa/PwaPlayerActivity.kt
  - app/src/main/java/com/example/feature/pwa/PwaStorageManager.kt
  - app/src/main/java/com/example/feature/pwa/PwaZipImporter.kt
  - app/src/main/java/com/example/feature/element/ElementActionDispatcher.kt
  - app/src/main/java/com/example/feature/pwa/PwaListActivity.kt
  - app/src/test/java/com/example/feature/pwa/OfflinePwaFoundationTest.kt
  - receipts/RECEIPTS_098.md
* What was actually done:
  - Created `PwaPermissionManager.kt` defining zero-trust `PwaPermissionPolicy` (all capabilities default strictly to false/OFF: Internet, Files, Camera, Microphone, Location, Sound), synchronous SharedPreferences persistence, multi-process `OverlaySyncManager` synchronization, and SAF persistable URI grant tracking (get, add, revoke).
  - Overhauled `PwaEditActivity.kt` into the dedicated "Mini App Info" management screen organized into five clean sections:
    1. Appearance: Editable Name, Icon preview with SAF image picker and Reset button, Orientation radio options (Follow Manifest, Auto, Portrait, Landscape), and Close Button options (Show, Hide).
    2. Permissions & Access: Sandbox capability policies defaulting to OFF. When Files (SAF) is enabled, shows active granted URIs with a Revoke button per grant and an "Add File Grant" button.
    3. Storage / Package: Read-only metadata including entrypoint, display mode, package size, version, origin domain (`https://pwa-${id}.local/`), import date, and last launch timestamp.
    4. Backup & Export: Portable backup ZIP archive creation containing package.zip, pwa_metadata.json, custom icons, and capability policies.
    5. Danger Zone: "Delete Mini App" with confirmation AlertDialog permanently removing package files, unpacked assets, isolated WebView storage, and Room database record.
    Sole confirmation action is the top-bar checkmark (✓); no duplicate bottom buttons.
  - Implemented real capability enforcement in `PwaPlayerActivity.kt`:
    - Internet: Local assets from virtual domain origin (`https://pwa-${id}.local/`) always load; external requests return 403 Forbidden via `shouldInterceptRequest` and external navigation is blocked via `shouldOverrideUrlLoading` when disabled.
    - Files: `WebChromeClient.onShowFileChooser` prompts Android SAF document picker when enabled and records persistable URI grants; blocks input with notification when disabled.
    - Camera & Microphone: `WebChromeClient.onPermissionRequest` grants or denies HTML5/WebRTC media requests based on policy and Android runtime permissions (`Manifest.permission.CAMERA` and `RECORD_AUDIO`).
    - Location: `WebChromeClient.onGeolocationPermissionsShowPrompt` checks location policy and requests Android `ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION` before granting.
    - Sound: Enforces user gesture requirement and evaluates audio suppression JavaScript in WebView to mute `HTMLMediaElement` and suspend `AudioContext` when disabled.
  - Updated `PwaStorageManager.kt` and `PwaZipImporter.kt` to serialize capability policies to `pwa_metadata.json` and restore them when importing backup archives.
  - Updated `ElementActionDispatcher.kt` and `PwaListActivity.kt` to pass resolved capability policy flags directly via launch Intent extras to eliminate multi-process cache lag in `:heavy`.
  - Added unit tests in `OfflinePwaFoundationTest.kt` verifying default-off security, policy persistence, per-PWA isolation, and SAF grant tracking.
* How it was verified: local build only (`gradle :app:testDebugUnitTest` passed all 313 unit tests; `compile_applet` passed cleanly).
* Any deviation from what was requested, and why: None. PWAs remain full-screen Heavy-process apps; floating PWA windows, eReader, Dictionary, AI features, and launcher integration were intentionally avoided. Internet Speed Monitor, Notifications, Media, Calculator, Compass, and Scheduler remained completely untouched.
* Any known issue or follow-up needed: Ready for on-device physical testing.
