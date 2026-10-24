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
