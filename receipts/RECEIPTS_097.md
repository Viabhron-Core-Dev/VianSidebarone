# RECEIPTS_097

## Entry 1
* Timestamp: 2026-10-09T22:21:44Z
* One-line summary: Targeted corrections for page type identity, opening face vs session selection, SidebarAppsManager page ID parsing, and container grid persistence isolation.
* Exact files touched:
  - app/src/main/java/com/example/core/PageManager.kt
  - app/src/main/java/com/example/feature/sidebar/SidebarAppsManager.kt
  - app/src/main/java/com/example/feature/sidebar/SidebarEditNavigator.kt
  - app/src/main/java/com/example/feature/sidebar/WidgetsGridPageView.kt
  - app/src/main/java/com/example/feature/sidebar/HybridGridPageView.kt
  - app/src/main/java/com/example/WidgetsGridEditActivity.kt
  - app/src/main/java/com/example/HybridGridEditActivity.kt
  - app/src/test/java/com/example/core/UnifiedPageSystemTest.kt
  - blueprint/BLUEPRINT.md
* What was actually done:
  - Fixed `PageTypes.resolvePageType` by replacing broad `.contains(...)` substring matching with exact token comparisons (`HYBRID`, `HYBRID_GRID`, `home_grid`, `APPS`, `APPS_GRID`, `WIDGETS`, `WIDGETS_GRID`, `WIDGET`, `SINGLE_WIDGET`) and system timestamp prefix matches (`apps_`, `widgets_grid_`, `widget_`, `hybrid_grid_`). Preserved legacy `tools` compatibility routing to `HYBRID_GRID`. Arbitrary IDs (`user_apps_notes`, `my_widgets_area`, `my_widget_view`) now remain distinct without misclassification.
  - Sanitized default page titles in `SidebarPage.fromJson` to enforce canonical titles: "Apps Grid", "Widgets Grid", "Single Widget", and "Home Grid".
  - Enforced configured opening face independence in `PageManager.getOpeningFaceId` with clean handle / legacy index fallback and container isolation, preventing live session swiping from overriding the configured opening face.
  - Fixed `SidebarAppsManager.loadActiveApps` page ID parsing by eliminating `substringAfterLast("_")` truncation and introducing `parseContainerAndPageId` to parse gestures and compound page IDs (`apps_grid_123`, `default_apps`) without loss, while migrating legacy preference keys.
  - Supported `apps_grid`, `widgets`, and `single_widget` routing in `SidebarEditNavigator.createEditIntent`.
  - Added alt-key migration and `appsManagerKey` fallback in `WidgetsGridPageView` and `HybridGridPageView`.
  - Wired `containerId` parameter across `WidgetsGridEditActivity` and `HybridGridEditActivity` editors, callbacks, and persistence helpers (`loadHybridLocalItems`, `saveHybridItems`, `loadLocalItems`, `saveItems`).
  - Added regression unit tests to `UnifiedPageSystemTest.kt` covering page type resolution, opening face independence across session swipes, compound ID parsing in `SidebarAppsManager`, and container isolation.
  - Purged ephemeral `debug.keystore` / `debug.keystore.base64` generated during Gradle packaging per Credential Immunity Rule.
* How it was verified: local build only (`gradle :app:testDebugUnitTest` executed 26 test tasks with all tests passing, followed by `compile_applet` confirming successful compilation).
* Any deviation from what was requested, and why: None. Implemented only the scoped corrections requested.
* Any known issue or follow-up needed: None for this correction pass. Subsequent phase will address Main/Heavy Sidebar runtime migration per Master Plan.

---

* Timestamp: 2026-10-11T12:24:00-07:00
* One-line summary of what was requested: Fix Apps Grid initial-load fallback so unconfigured/empty grid does not substitute full installed-app catalogue.
* Exact files touched:
  - app/src/main/java/com/example/feature/sidebar/AppsPageView.kt
  - app/src/test/java/com/example/feature/sidebar/AppsGridInitialLoadTest.kt
* What was actually done:
  - Removed the fallback in `AppsPageView.refreshList()` that substituted `manager.allInstalledApps` into `baseItems` whenever `sourceApps` was empty.
  - Extracted resolution logic into `AppsPageView.resolveDisplayedItems` preserving configured element subset, ordering, folder hierarchy, and search filtering without falling back to the installed application catalogue.
  - Added visibility accessors `getDisplayedItems()`, `getEmptyViewVisibility()`, and `getRecyclerViewVisibility()`.
  - Added 4 unit regression tests in `AppsGridInitialLoadTest.kt` verifying:
    1. Unconfigured/empty Apps Grid displays empty state and no catalogue apps.
    2. Configured subset of apps preserves exact selection and order.
    3. Race between loading installed-app catalogue and loading saved selection does not substitute catalogue.
    4. Refreshing after Add Element -> OK updates selection correctly.
* How it was verified: local build only (`gradle :app:testDebugUnitTest --tests "com.example.feature.sidebar.AppsGridInitialLoadTest"` passed 4/4 tests, full `gradle :app:testDebugUnitTest` passed 26 actionable test tasks, and `compile_applet` confirmed clean build).
* Any deviation from what was requested, and why: None. Strictly scoped to the confirmed Apps Grid fallback problem.
* Any known issue or follow-up needed: None.
