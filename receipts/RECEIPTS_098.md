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
