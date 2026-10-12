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

---

* Timestamp: 2026-10-11T14:55:00-07:00
* One-line summary of what was requested: Implement focused Notification Sidebar scroll-stability correction using DiffUtil and guard redundant height dispatches.
* Exact files touched:
  - app/src/main/java/com/example/feature/sidebar/NotificationPageView.kt
  - app/src/test/java/com/example/feature/sidebar/NotificationDiffAndScrollStabilityTest.kt
* What was actually done:
  - Replaced full adapter `notifyDataSetChanged()` in `NotificationPageView` with `DiffUtil.calculateDiff(NotificationDiffCallback(list, newList))` and `diffResult.dispatchUpdatesTo(this)`.
  - Configured `NotificationDiffCallback` with `areItemsTheSame` matching `StatusBarNotification.key` (with package/id/tag fallback), and `areContentsTheSame` comparing title, text, subtext, bigText, and postTime.
  - Added `lastDispatchedHeightPx` check preventing redundant `onHeightChanged` callbacks when calculated height does not change, preventing spurious WindowManager layout resets.
  - Added unit regression tests in `NotificationDiffAndScrollStabilityTest.kt` verifying item identity matching, item content changes, fallback keys, targeted update dispatch, and height dispatch stabilization.
* How it was verified: local build only (`gradle :app:testDebugUnitTest --tests "com.example.feature.sidebar.NotificationDiffAndScrollStabilityTest"` passed, full test suite passed, and `compile_applet` confirmed clean compilation).
* Any deviation from what was requested, and why: None. Preserved existing notification filtering, actions, and deduplication behavior.
* Any known issue or follow-up needed: None.

---

* Timestamp: 2026-10-11T15:20:00-07:00
* One-line summary of what was requested: Implement first minimal vertical slice of Main -> Heavy Sidebar page-runtime split for Calculator.
* Exact files touched:
  - app/src/main/java/com/example/feature/sidebar/SidebarPageFactory.kt
  - app/src/main/java/com/example/feature/sidebar/HeavyPagePlaceholderView.kt
  - app/src/main/java/com/example/feature/heavy/page/HeavyPageHostManager.kt
  - app/src/main/java/com/example/core/ipc/HeavyProcessHost.kt
  - app/src/main/java/com/example/feature/floating/HeavyFloatingHostService.kt
  - app/src/test/java/com/example/feature/sidebar/HeavyCalculatorSplitTest.kt
* What was actually done:
  - Created `HeavyPagePlaceholderView` in Main to occupy the ViewPager slot, measure on-screen bounds, and communicate lifecycle to `:heavy` via IPC without instantiating heavy page views or logic in Main.
  - Routed `"calculator"` in `SidebarPageFactory` to `HeavyPagePlaceholderView` while keeping resident Home Grid (`"default_hybrid"` / `HybridGridPageView`) and all other page types unchanged in Main.
  - Implemented `HeavyPageHostManager` in `:heavy` to host `CalculatorPageView` inside a dedicated `TYPE_APPLICATION_OVERLAY` window perfectly synchronized to Main's placeholder screen rect.
  - Registered IPC command handlers in `HeavyProcessHost` for `MOUNT_PAGE`, `UNMOUNT_PAGE`, and `UPDATE_PAGE_BOUNDS`.
  - Added active page reference tracking in `HeavyFloatingHostService` so `:heavy` manages its lifecycle cleanly.
  - Added unit regression tests in `HeavyCalculatorSplitTest.kt` verifying command serialization, bound updates, and page factory routing.
  - Purged ephemeral `debug.keystore` / `debug.keystore.base64` per Credential Immunity Rule.
* How it was verified: local build only (`gradle :app:testDebugUnitTest` executed 26 test tasks passing all tests, followed by `compile_applet` confirming clean build).
* Any deviation from what was requested, and why: None. Strictly isolated to Calculator as the first vertical slice.
* Any known issue or follow-up needed: None. Subsequent phases can migrate additional non-home pages following this proven pattern.

---

* Timestamp: 2026-10-11T15:33:00-07:00
* One-line summary of what was requested: Resume from conversation compaction; perform integrity audit, sync blueprint milestones 12.11 and 12.12, and enforce Credential Immunity Rule.
* Exact files touched:
  - blueprint/BLUEPRINT.md
  - receipts/RECEIPTS_097.md
* What was actually done:
  - Validated repository compilation and verified 26 test tasks pass with zero regressions.
  - Documented sections 12.11 (Notification Sidebar DiffUtil & Scroll-Stability) and 12.12 (Main -> Heavy Page-Runtime Split: Calculator Vertical Slice) in `blueprint/BLUEPRINT.md`.
  - Audited workspace for exposed credentials and purged transient `debug.keystore` / `debug.keystore.base64` artifacts.
* How it was verified: local build only (`gradle :app:testDebugUnitTest` 26/26 passed, `compile_applet` succeeded, zero keystore artifacts confirmed).
* Any deviation from what was requested, and why: None.
* Any known issue or follow-up needed: None. Ready for next user instruction.

---

* Timestamp: 2026-10-12T13:50:00-07:00
* One-line summary of what was requested: Implement focused geometry/lifecycle corrections for Heavy Calculator (height reporting, idempotent mount, ViewPager idle synchronization, bootstrap sizing) awaiting physical verification.
* Exact files touched:
  - app/src/main/java/com/example/feature/sidebar/SidebarPageControllable.kt
  - app/src/main/java/com/example/feature/sidebar/SidebarView.kt
  - app/src/main/java/com/example/feature/sidebar/HeavyPagePlaceholderView.kt
  - app/src/main/java/com/example/feature/heavy/page/HeavyPageHostManager.kt
  - app/src/test/java/com/example/feature/sidebar/HeavyCalculatorSplitTest.kt
  - receipts/RECEIPTS_097.md
* What was actually done:
  - Neutralized 44px empty-TextView collapse in `HeavyPagePlaceholderView` by overriding `onMeasure` with a generic host geometry bootstrap strategy (parent height / 45% screen height fallback) and removing the empty TextView child.
  - Implemented Heavy → Main height reporting in `HeavyPageHostManager` by forwarding `newHeight` from `CalculatorPageView` via `HeavyProcessHost.reportEvent` using `action = "page_height_changed"`, filtering unchanged or non-positive values.
  - Added `HeavyConnectionListener` implementation to `HeavyPagePlaceholderView` to receive `page_height_changed`, update measured dimensions, and invoke `onHeightChanged` to drive `SidebarView.handleChildHeightChange` without feedback loops.
  - Enforced selection and mount idempotency in `HeavyPagePlaceholderView`, suppressing duplicate `onPageSelected` dispatches while already active.
  - Enforced Heavy-side mount idempotency in `HeavyPageHostManager.mountPage`, updating window bounds on duplicate mount requests instead of unmounting and recreating `CalculatorPageView`.
  - Added `onPageScrollStateChanged` to `SidebarPageControllable` and forwarded ViewPager2 scroll states from `SidebarView`; synchronized bounds dispatching in `HeavyPagePlaceholderView` so in-flight coordinates during dragging/settling are suppressed and stable bounds are dispatched at `SCROLL_STATE_IDLE`.
  - Added unit regression tests in `HeavyCalculatorSplitTest.kt` covering event serialization, height filtering/deduplication, bounds suppression, and bootstrap calculation.
  - Purged transient `debug.keystore` and `debug.keystore.base64` artifacts per Credential Immunity Rule.
* How it was verified: local build only (`gradle :app:testDebugUnitTest` executed 26 test tasks passing all tests, followed by `compile_applet` confirming clean build; awaiting on-device physical verification).
* Any deviation from what was requested, and why: None. Scoped strictly to the verified geometry and lifecycle synchronization problems.
* Any known issue or follow-up needed: Correction is pending physical-device retest to verify live overlay rendering and gesture interaction.
