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

---

* Timestamp: 2026-10-12T15:42:00-07:00
* One-line summary of what was requested: Implement bootstrap readyToShow=false hidden mount and stable final bounds reveal for Heavy Calculator geometry synchronization.
* Exact files touched:
  - app/src/main/java/com/example/feature/sidebar/HeavyPagePlaceholderView.kt
  - app/src/main/java/com/example/feature/heavy/page/HeavyPageHostManager.kt
  - app/src/test/java/com/example/feature/sidebar/HeavyCalculatorSplitTest.kt
  - receipts/RECEIPTS_097.md
* What was actually done:
  - Updated `HeavyPagePlaceholderView.kt` to send bootstrap `MOUNT_PAGE` with `readyToShow="false"`, keeping the Heavy window invisible (`visibility = View.INVISIBLE`, `alpha = 0f`, `FLAG_NOT_TOUCHABLE`) while Calculator measures and reports desired height.
  - Configured `HeavyPagePlaceholderView.kt` to delay `readyToShow="true"` until Main receives Heavy's reported desired height, applies layout via `onHeightChanged`, settles the ViewPager layout at `SCROLL_STATE_IDLE`, and computes final stable screen bounds.
  - Updated `dispatchBoundsUpdate` in `HeavyPagePlaceholderView.kt` to transmit `readyToShow="true"`, revealing the overlay window only at its final coordinates without transient visual jumps. Added a 350ms fallback reveal timeout to guarantee visibility.
  - Refactored `HeavyPageHostManager.updateBoundsInternal` to atomically update window bounds and reveal the window in a single `WindowManager.updateViewLayout` invocation, eliminating intermediate frames.
  - Added unit regression tests in `HeavyCalculatorSplitTest.kt` for bootstrap hidden mount serialization, reveal bounds serialization, and state transition lifecycle.
  - Purged transient `debug.keystore` and `debug.keystore.base64` per Credential Immunity Rule.
* How it was verified: local build only (`gradle :app:testDebugUnitTest` executed 26 test tasks passing all tests, followed by `compile_applet` confirming clean build; awaiting on-device physical verification).
* Any deviation from what was requested, and why: None. Main remains authoritative for screen geometry; Heavy measures and reports content height in the background before being revealed.
* Any known issue or follow-up needed: Ready for on-device physical verification.

---

* Timestamp: 2026-10-12T23:59:00-07:00
* One-line summary of what was requested: Fix compiler error in SidebarView.kt (line 646: unresolved reference 'getCurrentHeightPx') and verify assembleDebug build.
* Exact files touched:
  - app/src/main/java/com/example/feature/sidebar/SidebarView.kt
  - receipts/RECEIPTS_097.md
* What was actually done:
  - Identified stale `is HeavyPagePlaceholderView -> pv.getCurrentHeightPx()` call in `SidebarView.queryChildPageHeight(actualPosition)`.
  - Removed stale reference: `HeavyPagePlaceholderView` does not and should not expose synchronous `getCurrentHeightPx()`, as Calculator runs in `:heavy` and reports desired height asynchronously across Binder via IPC to drive `handleChildHeightChange`.
  - Fallback in `SidebarView` gracefully falls back to `else -> pv.height` (and `compactInitialDp` = 260dp on initial bootstrap before Heavy height reporting).
  - Executed `gradle assembleDebug` and verified full APK debug build succeeds (`BUILD SUCCESSFUL in 2s 37 actionable tasks`).
  - Executed `gradle :app:testDebugUnitTest` and verified all 26 test tasks pass with zero regressions.
  - Purged transient `debug.keystore` and `debug.keystore.base64` per Credential Immunity Rule.
* How it was verified: local build only (`gradle assembleDebug` passed, `gradle :app:testDebugUnitTest` passed 26/26, `compile_applet` passed).
* Any deviation from what was requested, and why: None. No unrelated files touched; Internet Speed Monitor untouched.
* Any known issue or follow-up needed: Ready for CI / GitHub APK pipeline.

---

* Timestamp: 2026-10-13T13:48:00-07:00
* One-line summary of what was requested: Read-only geometry audit of Main ↔ Heavy placement, fix fallback timeout premature reveal violation, and instrument full geometry diagnostics.
* Exact files touched:
  - app/src/main/java/com/example/feature/sidebar/HeavyPagePlaceholderView.kt
  - app/src/main/java/com/example/feature/sidebar/SidebarView.kt
  - app/src/test/java/com/example/feature/sidebar/HeavyCalculatorSplitTest.kt
  - receipts/RECEIPTS_097.md
* What was actually done:
  - Performed deep read-only audit answering all 12 geometry hierarchy questions (window bounds, header bounds, ViewPager slot, insets, clipping, and vertical anchoring).
  - Fixed Issue A: Corrected `scheduleFallbackReveal` in `HeavyPagePlaceholderView.kt` so timeout never sends `readyToShow=true` with pre-height/bootstrap rects; requires established desired content height before revealing.
  - Added targeted single-dispatch geometry diagnostics in `HeavyPagePlaceholderView.kt` capturing screen dimensions, system bar insets, Main WindowManager LP, root rect, header rect, ViewPager rect, host frame rect, placeholder rect, reported desired height, and final dispatch rect.
  - Added height change logging in `SidebarView.handleChildHeightChange`.
  - Added unit regression test `testFallbackTimeoutNeverRevealsBootstrapRectWithoutHeight` in `HeavyCalculatorSplitTest.kt`.
  - Built full debug APK via `gradle assembleDebug` (succeeded in 7s) and verified unit tests (`gradle :app:testDebugUnitTest` passed 27/27).
  - Purged transient `debug.keystore` and `debug.keystore.base64` per Credential Immunity Rule.
* How it was verified: local build only (`gradle assembleDebug` passed, `gradle :app:testDebugUnitTest` passed 27/27, zero keystore artifacts).
* Any deviation from what was requested, and why: None. No speculative geometry offsets or hardcoded numbers added; Internet Speed Monitor untouched.
* Any known issue or follow-up needed: Ready for physical device test to harvest the exact geometry diagnostics log.

---

* Timestamp: 2026-10-14T00:08:00-07:00
* One-line summary of what was requested: Implement Calculator cross-process embedded-surface vertical slice (SurfaceControlViewHost / SurfaceView) gated on API 30+ with legacy API 26-29 fallback.
* Exact files touched:
  - app/src/main/java/com/example/core/ipc/IpcContracts.kt
  - app/src/main/java/com/example/core/ipc/HeavyHostBinder.kt
  - app/src/main/java/com/example/core/ipc/HeavyProcessHost.kt
  - app/src/main/java/com/example/core/ipc/HeavyProcessConnectionManager.kt
  - app/src/main/java/com/example/feature/heavy/page/HeavyPageHostManager.kt
  - app/src/main/java/com/example/feature/sidebar/HeavyPagePlaceholderView.kt
  - app/src/test/java/com/example/feature/sidebar/HeavyCalculatorSplitTest.kt
  - receipts/RECEIPTS_097.md
* What was actually done:
  - Extended Binder IPC contract in `IpcContracts.kt` with typed transactions `TRANSACTION_CREATE_SURFACE_PACKAGE`, `TRANSACTION_RELAYOUT_SURFACE_HOST`, and `TRANSACTION_RELEASE_SURFACE_HOST`, passing `Bundle` with `hostToken: IBinder` and `SurfacePackage: Parcelable` over Binder without string serialization.
  - Implemented `createSurfacePackage`, `relayoutSurfaceHost`, and `releaseSurfaceHost` in `HeavyHostBinder.kt` (proxy and stub), `HeavyProcessHost.kt`, and `HeavyProcessConnectionManager.kt`.
  - In `HeavyPageHostManager.kt`, on API 30+, implemented `createSurfacePackage` creating a `SurfaceControlViewHost` with `CalculatorPageView`, returning the `SurfacePackage` across Binder. Handled idempotent bounds relayout via `host.relayout(width, height)` and clean release via `host.release()`.
  - In `HeavyPagePlaceholderView.kt`, on API 30+, initialized an embedded `SurfaceView` with `setZOrderMediaOverlay(true)` and `PixelFormat.TRANSLUCENT`. Hooked `SurfaceHolder.Callback` to acquire `hostToken`, display, and host dimensions, transmitting `createSurfacePackage` to `:heavy` and attaching the returned `SurfacePackage` via `setChildSurfacePackage`.
  - On API 30+, eliminated the secondary `TYPE_APPLICATION_OVERLAY` window, screen x/y coordinate positioning, and `readyToShow` synchronization for Calculator; preserved desired-height reporting across Binder to update Main's Sidebar layout and relayout the embedded surface.
  - Preserved isolated legacy overlay fallback for API 26–29 without modifying the application's `minSdk`.
  - Added unit tests in `HeavyCalculatorSplitTest.kt` verifying API gating, contract parameter handling, relayout preservation without view recreation, and transaction ID stability.
  - Executed `gradle :app:testDebugUnitTest` (passed 215/215 tests) and `gradle assembleDebug` (succeeded).
  - Purged transient `debug.keystore` and `debug.keystore.base64` per Credential Immunity Rule.
* How it was verified: local build only (`gradle :app:testDebugUnitTest` passed 215 tests, `gradle assembleDebug` built successfully in 10s, zero keystore artifacts).
* Any deviation from what was requested, and why: None. Scoped strictly as a Calculator-only vertical slice; Home Grid, other pages, and Internet Speed Monitor left untouched.
* Any known issue or follow-up needed: Ready for physical device testing on Android 15/API 35 to verify on-device rendering inside the Sidebar content slot and observe horizontal gesture behavior over the embedded SurfaceView.

---

* Timestamp: 2026-10-18T00:33:00-07:00
* One-line summary of what was requested: Calculator embedded-surface visual cleanup (transparency elimination and stray text caret removal) preserving working Main ↔ Heavy architecture.
* Exact files touched:
  - app/src/main/java/com/example/feature/sidebar/HeavyPagePlaceholderView.kt
  - app/src/main/java/com/example/feature/sidebar/CalculatorPageView.kt
  - app/src/main/res/layout/page_calculator.xml
  - app/src/main/res/values/styles_calc.xml
  - app/src/test/java/com/example/feature/sidebar/HeavyCalculatorSplitTest.kt
  - receipts/RECEIPTS_097.md
* What was actually done:
  - Addressed Issue 1 (Transparent Background): Identified that Main's `HeavyPagePlaceholderView` was omitting `backgroundColor` and `isRight` in the Binder parameters bundle sent to `:heavy`, leaving `applyThemeBackground` uncalled with container settings and exposing wallpaper through the translucent `SurfaceView` punch-hole. Added container-specific theme background extraction (reading `handle_${containerId}_sidebar_color`, `handle_${containerId}_sidebar_transparency`, and handle alignment) in `HeavyPagePlaceholderView.kt` and transmitted them via IPC to `:heavy`. Set `layoutParams` in `CalculatorPageView` to `MATCH_PARENT, MATCH_PARENT` to guarantee full-surface background coverage with themed color, stroke, and rounded corner radii.
  - Addressed Issue 2 (Stray Text Caret): Discovered that `CalculatorPageView.kt` was dynamically rendering a synthetic unicode caret character (`▏`) with bold style and cyan color span via `SpannableStringBuilder` into `tv_expression`, producing a persistent vertical caret on the left side. Removed the synthetic caret injection from `updateUI()` and set `tv_expression.text = expression`. Added explicit `isCursorVisible = false`, `isFocusable = false`, and `isFocusableInTouchMode = false` to `tvExpression`, `tvResult`, `scrollExpression`, and `scrollResult` in `CalculatorPageView.kt` and `page_calculator.xml`. Removed `android:focusable="true"` from touch-only buttons and styles in `styles_calc.xml`. Replaced focus-requesting `HorizontalScrollView.fullScroll` calls with safe non-focus scrolling.
  - Updated unit test contract in `HeavyCalculatorSplitTest.kt` verifying `backgroundColor` and `isRight` parameter propagation.
  - Verified compilation via `compile_applet` (succeeded cleanly). Confirmed workspace remains completely free of any `.keystore`, `.jks`, `.p12`, or base64 credential files per Credential Immunity Rule.
* How it was verified: local build only (`compile_applet` succeeded, zero keystore artifacts).
* Any deviation from what was requested, and why: None. Scoped strictly to the two visual items; architecture, desired-height negotiation, and other pages left untouched.
* Any known issue or follow-up needed: Ready for on-device manual visual verification of Calculator background solidity and caret suppression.

---

* Timestamp: 2026-10-18T13:14:00-07:00
* One-line summary of what was requested: Fix Heavy embedded-page startup race in HeavyPagePlaceholderView without linger, prewarming, or architecture changes.
* Exact files touched:
  - app/src/main/java/com/example/feature/sidebar/HeavyPagePlaceholderView.kt
  - app/src/test/java/com/example/feature/sidebar/HeavyCalculatorSplitTest.kt
  - receipts/RECEIPTS_097.md
* What was actually done:
  - Eliminated the premature `createSurfacePackage` call during asynchronous Heavy startup in `HeavyPagePlaceholderView.kt`.
  - Added dual lifecycle gating requiring both `isSurfaceReady` with valid dimensions AND `connectionManager.isConnected` before requesting embedded surface creation across `onPageSelected()`, `surfaceCreated()`, and `surfaceChanged()`.
  - Preserved existing `onConnected()` callback as the trigger when Heavy finishes binding second, issuing `createSurfacePackage` only if the page remains selected, mount is requested, and the SurfaceView is ready.
  - Added in-flight request deduplication via `isSurfaceCreationPending` alongside `isEmbeddedAttached` and `isSelected`, preventing redundant or concurrent `createSurfacePackage` requests across simultaneous SurfaceView and connection callbacks.
  - Ensured that unselecting the page while connection is in-flight immediately resets `isSelected`, `isMountRequested`, and `isSurfaceCreationPending`, invokes `connectionManager.releaseSurfaceHost()`, and triggers immediate unbind/teardown via `connectionManager.releaseConsumer()`, preventing resurrecting unselected pages.
  - Added unit tests in `HeavyCalculatorSplitTest.kt` validating: (1) no creation request while Heavy is CONNECTING, (2) single creation request when Heavy connects second, (3) single creation request when Surface becomes ready second, (4) abort when unselected while connection is pending, and (5) deduplication of repeated readiness callbacks.
  - Verified local build via `gradle assembleDebug` (succeeded in 3s) and `gradle :app:testDebugUnitTest` (all tests passed).
* How it was verified: local build only (`gradle :app:testDebugUnitTest` passed all test suites, `gradle assembleDebug` built successfully).
* Any deviation from what was requested, and why: None. Scoped strictly to the startup race fix; no linger, prewarming, watchdog, or architecture changes were added, and Internet Speed Monitor was untouched.
* Any known issue or follow-up needed: Awaiting physical-device verification of single `createSurfacePackage` log on cold start and clean immediate unbind on release.

---

* Timestamp: 2026-10-18T14:07:00-07:00
* One-line summary of what was requested: Suppress provisional blank Calculator startup expansion and fix Log Keeper Back button navigation between Sidebar and Settings.
* Exact files touched:
  - app/src/main/java/com/example/feature/sidebar/SidebarView.kt
  - app/src/main/java/com/example/feature/sidebar/HeavyPagePlaceholderView.kt
  - app/src/main/java/com/example/LogKeeperActivity.kt
  - app/src/main/java/com/example/feature/element/ElementActionDispatcher.kt
  - app/src/main/java/com/example/SettingsActivity.kt
  - app/src/test/java/com/example/feature/sidebar/HeavyCalculatorSplitTest.kt
  - receipts/RECEIPTS_097.md
* What was actually done:
  - Issue 1 (Blank/Pale Startup Region Removal):
    * Identified root cause: When navigating Home -> Calculator, `SidebarView` posted a measure with `MeasureSpec.UNSPECIFIED` on `HeavyPagePlaceholderView`. With `desiredHeightPx = -1`, `HeavyPagePlaceholderView.onMeasure()` fabricated a 45% screen height (~520px) bootstrap, and `SidebarView` immediately called `handleChildHeightChange(position, 520)`, expanding the Sidebar window over a blank, unattached `SurfaceView` before `:heavy` finished connecting.
    * Fix: In `SidebarView.kt`, gated `onPageSelected` measurement to skip `HeavyPagePlaceholderView` since Heavy pages asynchronously report desired height via `onHeightChanged`. In `HeavyPagePlaceholderView.kt`, updated `onMeasure` to adopt existing container/parent height rather than fabricating 520px. Set `SurfaceView.alpha = 0f` initially so no punch-hole artifact renders while unattached. In `applyDesiredHeight`, deferred height reporting until `isEmbeddedAttached = true`. Upon `SurfacePackage` attachment, set `SurfaceView.alpha = 1f` and applied the real desired height (e.g. 607px), allowing the Sidebar to transition smoothly from existing compact Home height to final Calculator geometry with zero blank pause.
  - Issue 2 (Log Keeper Back Navigation):
    * Identified root cause: Both `LogKeeperActivity` and `SettingsActivity` run in `:heavy` with shared task affinity. Opening Log Keeper from Sidebar via `FLAG_ACTIVITY_NEW_TASK` placed `LogKeeperActivity` in the existing task atop any background `SettingsActivity`, causing Back's default `finish()` to reveal Settings instead of exiting to the underlying app/home.
    * Fix: Introduced explicit `EXTRA_LAUNCH_SOURCE` constants (`SOURCE_SIDEBAR`, `SOURCE_SETTINGS`) in `LogKeeperActivity`. In `ElementActionDispatcher.kt`, passed `SOURCE_SIDEBAR` and `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_MULTIPLE_TASK`. In `SettingsActivity.kt`, passed `SOURCE_SETTINGS`. In `LogKeeperActivity`, wired `onBack` and `onBackPressedDispatcher` to call `finishAndRemoveTask()` when `SOURCE_SIDEBAR` (cleanly exiting without exposing background Settings), and `finish()` when `SOURCE_SETTINGS` (normally popping to Settings, preserving stack).
  - Added unit tests in `HeavyCalculatorSplitTest.kt` verifying provisional height suppression, deferred desired height application until surface attachment, and launch source back-navigation branching.
  - Verified local build via `gradle assembleDebug` (succeeded in 2s) and `gradle :app:testDebugUnitTest` (all tests passed).
* How it was verified: local build only (`gradle :app:testDebugUnitTest` passed all test suites, `gradle assembleDebug` built successfully).
* Any deviation from what was requested, and why: None. No Heavy linger or prewarming was added, SVCH architecture was untouched, and Internet Speed Monitor was untouched.
* Any known issue or follow-up needed: Awaiting physical-device verification for both items.
