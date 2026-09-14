# Receipts Ledger - Part 094

* Timestamp: 2026-09-04T15:21:00-07:00
* One-line summary: Expanded NetSpeed resource proof-of-concept to 0, 1, and 2 kB/s states in live HandleService callback.
* Exact files touched:
  - `app/src/main/res/drawable-xhdpi/ic_stat_speed_0_k.png`
  - `app/src/main/res/drawable-xhdpi/ic_stat_speed_1_k.png`
  - `app/src/main/res/drawable-xhdpi/ic_stat_speed_2_k.png`
  - `app/src/main/java/com/example/core/HandleService.kt`
  - `receipts/RECEIPTS_094.md`
* What was actually done:
  - Pre-rendered three 96x96 ARGB_8888 single-drawable resource icons in `app/src/main/res/drawable-xhdpi/`:
    * `ic_stat_speed_0_k.png`: "0" over "kB/s"
    * `ic_stat_speed_1_k.png`: "1" over "kB/s"
    * `ic_stat_speed_2_k.png`: "2" over "kB/s"
  - Used exact accepted Vian geometry and typography: Roboto Condensed Bold 68px at baseline (48, 52), Roboto Bold 36px at baseline (48, 95), centered layout, and alpha threshold 80.
  - Placed all icons exclusively in `app/src/main/res/drawable-xhdpi/` for Redmi A5 / 320 dpi.
  - Kept existing `ic_stat_speed_43_mb.png` and `ic_stat_speed_108_k.png` preserved in repository.
  - Updated live speed notification callback `HandleService.updateSpeedNotification` to check:
    if `speedUnit.equals("kB/s", ignoreCase = true)` and `speedVal` is `"0"`, `"1"`, or `"2"`, selects corresponding `Icon.createWithResource(this, R.drawable.ic_stat_speed_X_k)` (`RESOURCE` mode).
  - Configured all other values to continue using runtime Canvas generator `dynamicSpeedIconGenerator.generateSpeedIcon(speedVal, speedUnit)` (`RUNTIME` mode) untouched.
  - Retained live diagnostic logging in `IconDiagnostics` confirming `mode=RESOURCE`, `resName`, and `setSmallIconReceived=true, notifyExecuted=true`.
  - Maintained startup flow, DENSITY_NONE, alpha cleanup threshold 80, NetSpeedManager, and notification channels without modification.
* How it was verified: local build only (`compile_applet`, `gradle assembleDebug`).
* Deviations: None.
* Known issues: None.

* Timestamp: 2026-09-05T10:59:00-07:00
* One-line summary: Replaced dynamic Canvas status-bar icon engine with complete pre-rendered 96x96 resource-icon implementation (0-999 kB/s, 1.0-43.0 MB/s).
* Exact files touched:
  - `tools/generate_speed_icons.py`
  - `tools/fonts/RobotoCondensed-Bold.ttf`
  - `tools/fonts/Roboto-Bold.ttf`
  - `app/src/main/res/drawable-xhdpi/` (1,421 PNGs)
  - `app/src/main/java/com/example/core/SpeedIconProvider.kt`
  - `app/src/main/java/com/example/core/DynamicSpeedIconGenerator.kt` (deleted)
  - `app/src/main/java/com/example/core/HandleService.kt`
  - `receipts/RECEIPTS_094.md`
* What was actually done:
  - Created deterministic asset generator `tools/generate_speed_icons.py` using authentic bold TrueType fonts.
  - Rendered complete 1,421 pre-rendered 96x96 status-bar icons into `app/src/main/res/drawable-xhdpi/`:
    * 1,000 icons for kB/s (`0` through `999` kB/s, integer values): `ic_stat_speed_<val>_k.png`
    * 421 icons for MB/s (`1.0` through `43.0` MB/s, 0.1 increments): `ic_stat_speed_<d>_<f>_m.png`
  - Applied bolder glyph weight without expanding nominal bounds. Preserved exact baseline geometry: top number baseline at y=52 (centered at x=48), bottom unit baseline at y=95 (centered at x=48), white glyphs on transparent background, alpha cleanup threshold 80.
  - Placed assets exclusively in `drawable-xhdpi` (320 dpi / density 2.0) for Redmi A5, preventing Android OS density scaling artifacts.
  - Generated `SpeedIconProvider.kt` with compile-time `R.drawable` reference arrays for O(1) resource ID resolution and zero runtime rasterization.
  - Completely deleted `DynamicSpeedIconGenerator.kt` and purged all runtime Canvas, Paint, Bitmap, createWithBitmap, alpha thresholding, bitmap density manipulation, and memory trim hooks.
  - Updated `HandleService.kt`:
    * `buildNotification` now accepts `iconResId: Int` directly and calls `Notification.Builder.setSmallIcon(iconResId)`.
    * Initial and standby notifications use `SpeedIconProvider.resolve("0", "kB/s").resId`.
    * Live speed updates resolve the resource ID via `SpeedIconProvider.resolve(speedVal, speedUnit)` and invoke `setSmallIcon(selectedResId)`.
    * LogKeeper diagnostics log `LiveUpdate -> displayedVal, displayedUnit, mode=RESOURCE, resName, setSmallIconReceived=true, notifyExecuted=true`.
* How it was verified: local build only (`compile_applet`, `gradle assembleDebug`, and lookup mapping pass for 0 kB/s, 999 kB/s, 1.0 MB/s, 1.5 MB/s, 7.7 MB/s, and 43.0 MB/s).
* Deviations: None.
* Known issues: None.

* Timestamp: 2026-09-05T14:34:00-07:00
* One-line summary: Integrated deterministic build-time icon generation into Gradle build lifecycle and verified all 1,421 status-bar drawable resources.
* Exact files touched:
  - `tools/generate_speed_icons.py`
  - `app/build.gradle.kts`
  - `receipts/RECEIPTS_094.md`
* What was actually done:
  - Wired `generateSpeedIcons` task into `app/build.gradle.kts` attached as a dependency of `preBuild`, guaranteeing icon generation and verification execute automatically before resource processing (`generateDebugResources`, `mergeDebugResources`) and Kotlin compilation (`compileDebugKotlin`).
  - Enhanced `tools/generate_speed_icons.py` to perform fast incremental scans: verifies existence of all 1,421 pre-rendered PNGs, renders only missing items, and strictly asserts that 1,000 kB/s (`0`..`999`) and 421 MB/s (`1.0`..`43.0`) drawables exist on disk.
  - Verified that all 1,421 referenced `R.drawable` symbols in `SpeedIconProvider.kt` resolve to valid disk resources with zero unresolved references and zero duplicate resource identifiers.
  - Verified packaged APK `app-debug.apk` contains all 1,421 pre-rendered icons.
  - Executed `gradle :app:generateSpeedIcons`, `gradle :app:preBuild`, `compile_applet`, and `gradle assembleDebug`.
* How it was verified: local build only (`compile_applet`, `gradle assembleDebug`, and automated resource audit script confirming 1,000 kB/s and 421 MB/s resources on disk and inside the generated APK).
* Deviations: None.
* Known issues: None.

* Timestamp: 2026-09-07T14:30:15-07:00
* One-line summary: Realigned process boundaries: moved HandleService to default Main process (com.example), assigned MainActivity and LogKeeperActivity to :heavy, and updated App.kt process detection.
* Exact files touched:
  - `app/src/main/AndroidManifest.xml`
  - `app/src/main/java/com/example/App.kt`
  - `app/src/main/java/com/example/core/HandleService.kt`
  - `app/src/main/java/com/example/MainActivity.kt`
  - `receipts/RECEIPTS_094.md`
* What was actually done:
  - Removed `android:process=":core"` from `HandleService` in `AndroidManifest.xml` so it runs in the default application process (`com.example`).
  - Assigned `MainActivity` and `LogKeeperActivity` to `android:process=":heavy"`.
  - Updated `App.kt` to explicitly detect the `:heavy` process rather than `:core`, keeping Main resident daemon startup minimal and isolating heavy UI initializations.
  - Preserved `CallStateReceiver`'s existing configuration pending dedicated call/IPC work.
  - NetSpeedManager, SpeedIconProvider, speed icon drawables, and generation scripts strictly untouched.
* How it was verified: local build only (`compile_applet` succeeded cleanly).
* Deviations: None.
* Known issues: None.

* Timestamp: 2026-09-07T14:58:00-07:00
* One-line summary: Verified Main runtime foundation for existing Handle system (HandleService, HandleManager, TriggerHandleView, HandleShapeDrawable, BootReceiver).
* Exact files touched:
  - `receipts/RECEIPTS_094.md`
* What was actually done:
  - Inspected and verified the complete Main-process resident daemon foundation for the Handle system:
    * `HandleService`: Running in default application process (`com.example`), initializes `HandleManager`, manages floating `TriggerHandleView` instances, monitors screen ON/OFF transitions via dynamic `BroadcastReceiver`, attaches/detaches handles cleanly across service lifecycles, and maintains continuous foreground NetSpeed notification without recreation.
    * `HandleManager`: Single-source of truth for handle configurations stored in `FloatingReaderPrefs`. Provides O(1) in-memory handle state, edge positioning, gesture action mapping, and persistence without IPC overhead.
    * `TriggerHandleView`: Direct `WindowManager` edge touch-strip overlay using `TYPE_APPLICATION_OVERLAY`. Recognizes gestures (single tap, double tap, long press drag-reposition, edge swipes/flings) and dispatches action keys.
    * `HandleShapeDrawable`: Pure hardware-accelerated Canvas geometric renderer without bitmap allocations.
    * `BootReceiver`: Starts `HandleService` via `startForegroundService` in default process on `BOOT_COMPLETED`.
  - Confirmed strict adherence to boundaries: zero modifications to NetSpeedManager or SpeedIconProvider, zero premature IPC/AIDL code, zero placeholder architectures, CallStateReceiver retained in current state, and clean build status confirmed.
* How it was verified: local build only (`compile_applet` passed cleanly).
* Deviations: None.
* Known issues: None.

* Timestamp: 2026-09-07T15:12:00-07:00
* One-line summary: Implemented Handle -> Gesture -> independent Sidebar Container identity and strict page-stack persistence contracts with safe conservative migration.
* Exact files touched:
  - `app/src/main/java/com/example/core/HandleManager.kt`
  - `app/src/main/java/com/example/core/TriggerHandleView.kt`
  - `app/src/main/java/com/example/core/HandleService.kt`
  - `receipts/RECEIPTS_094.md`
* What was actually done:
  - Added `HandleGestures` constants and `GestureTarget` sealed class (`Container`, `Action`, `None`) to establish container identity and action dispatch contracts.
  - Implemented `getContainerId(handleId, gesture)` enforcing the contract `containerId = "${handleId}_${gesture}"`.
  - Implemented `getContainerPagesKey(containerId)` enforcing strict page-stack isolation key `"handle_${containerId}_pages"`. Explicitly avoided generic `handle_${cleanHandleId}_pages` keys to prevent page bleeding across gestures.
  - Added `getPagesForContainer(containerId, defaultPages)`, `savePagesForContainer(containerId, pages)`, and `parsePages(raw)`.
  - Implemented conservative legacy migration `checkAndMigrateLegacyPages(containerId)`: safely checks older generic keys (`handle_${cleanHandleId}_pages`, `handle_${handleId}_pages`) strictly for the primary gesture (`swipe_left`), migrates data to the isolated key, and removes the legacy key so other gestures never bleed into it.
  - Updated `TriggerHandleView`'s `onGestureAction` callback to pass the detected gesture string along with `actionKey` and `config`, without altering any gesture recognition or touch threshold behavior.
  - Updated `HandleService.attachHandles()` and `handleGestureAction()` to compute `containerId` and pass `gesture` and `container_id` extras in broadcast intents (`com.example.action.OPEN_SIDEBAR` and `com.example.action.TRIGGER_ACTION`).
  - Preserved boundaries: zero changes to NetSpeedManager or SpeedIconProvider, no heavy process modifications, no IPC, no UI creation.
* How it was verified: local build only (`compile_applet` passed cleanly).
* Deviations: None.
* Known issues: None.

* Timestamp: 2026-09-07T15:23:00-07:00
* One-line summary: Established lightweight SidebarContainer runtime contract, resolver, and page-stack registry on top of HandleManager.
* Exact files touched:
  - `app/src/main/java/com/example/core/HandleManager.kt`
  - `receipts/RECEIPTS_094.md`
* What was actually done:
  - Created `SidebarContainer` data class representing the independent container belonging to exactly one Handle + Gesture combination (`containerId`, `handleId`, `gesture`, `enabled`, and strict `pagesKey` property).
  - Maintained `GestureTarget` sealed class distinction (`Container`, `Action`, `None`) without implementing Elements/actions.
  - Implemented lightweight container resolution and registry methods in `HandleManager`:
    * `getHandle(handleId: String)`: Resolves full `HandleConfig` by handleId.
    * `resolveContainer(handleId: String, gesture: String)`: Validates handle and whether gesture is configured to target a container (`ACTION_OPEN_SIDEBAR`), returning `SidebarContainer` with `containerId = "${handleId}_${gesture}"`.
    * `getContainer(containerId: String)`: Parses containerId and resolves the independent container instance.
    * `getContainersForHandle(handleId: String)`: Returns all active gesture containers for a given handle.
  - Provided container-oriented overloads for page stack persistence `getPagesForContainer(container, defaultPages)` and `savePagesForContainer(container, pages)`.
  - Strictly preserved page-stack isolation under `handle_${containerId}_pages` with zero fallback to generic handle keys for active stacks.
  - Allowed representation of empty/new page stacks without creating any Sidebar UI or instantiating views.
  - Strictly preserved scope boundaries: no Sidebar UI, no Home Grid UI, no pages, no page editing UI, no Add Element UI, no Elements, no Settings, no IPC, no heavy-process changes, no floating windows, no Window Manager, no Call Recorder, no changes to NetSpeed or gesture thresholds.
* How it was verified: local build only (`compile_applet` passed cleanly).
* Deviations: None.
* Known issues: None.

* Timestamp: 2026-09-07T22:33:00-07:00
* One-line summary: Established lightweight Main-process Page Stack runtime contract (SidebarPage, PageTypes, PageManager) on top of SidebarContainer.
* Exact files touched:
  - `app/src/main/java/com/example/core/PageManager.kt`
  - `app/src/main/java/com/example/core/HandleManager.kt`
  - `receipts/RECEIPTS_094.md`
* What was actually done:
  - Implemented `SidebarPage` data class containing lightweight runtime metadata (`pageId`, `pageType`, `title`, `order`, `isFirst`) without instantiating any UI views, layouts, or heavy allocations.
  - Defined `PageTypes` object providing standard page type constants (`default_hybrid`, `apps`, `widgets`, `media_player`, `tools`, `app_tracker`, `calculator`, `compass`), title resolution, and type resolution.
  - Implemented `PageManager` singleton coordinator in `com.example.core`:
    * `getPageStack(containerId)` / `getPageStack(container)`: loads ordered page stack (0-indexed) using `HandleManager.getPagesForContainer(containerId)`.
    * `savePageOrder(containerId, pages)` / `savePageIds(containerId, pageIds)`: saves ordered page stack strictly under `handle_${containerId}_pages`.
    * `addPage(containerId, pageId, position)`: adds or repositions a page in the container stack, avoiding duplicates within the stack.
    * `removePage(containerId, pageId)`: removes a page from the container stack.
    * `reorderPages(containerId, fromIndex, toIndex)`: swaps/reorders items within the container stack.
    * `getFirstPage(containerId)`: resolves the primary/default page in the stack.
    * `getPage(containerId, pageId)`: resolves a page by ID in the stack.
  - Preserved strict page-stack isolation: all persistence is routed through `HandleManager.savePagesForContainer(containerId, ...)`, strictly writing to `"handle_${containerId}_pages"`. No generic handle-level keys (`handle_1_pages`) are ever used or fallen back to for active container stacks.
  - Preserved existing conservative legacy migration: delegates load to `HandleManager.getPagesForContainer(containerId)`, inheriting `checkAndMigrateLegacyPages(containerId)` without duplicate migration logic.
  - Added convenience accessors `getPageStack(context)` and `getFirstPage(context)` directly on `SidebarContainer`.
  - Strictly respected scope boundaries: zero UI views instantiated, zero Compose/XML layouts, no Elements, no Settings UI, no IPC, no heavy-process changes, no floating windows, no Window Manager, no Call Recorder, no changes to NetSpeed or gesture thresholds.
* How it was verified: local build only (`compile_applet` passed cleanly).
* Deviations: None.
* Known issues: None.

* Timestamp: 2026-09-07T22:52:00-07:00
* One-line summary: Established Main-process Sidebar runtime entry/boundary (SidebarManager, SidebarRuntimeState) resolving Handle -> Gesture -> SidebarContainer -> Page Stack.
* Exact files touched:
  - `app/src/main/java/com/example/feature/sidebar/SidebarManager.kt`
  - `app/src/main/java/com/example/core/HandleService.kt`
  - `receipts/RECEIPTS_094.md`
* What was actually done:
  - Created `SidebarRuntimeState` data class representing the active container runtime snapshot: `container: SidebarContainer`, `pages: List<SidebarPage>`, `currentPage: SidebarPage?`, `currentPageIndex: Int`, and `isOpen: Boolean`. Zero Android View, Layout, ViewPager, or page content allocations.
  - Implemented `SidebarManager` singleton coordinator in `com.example.feature.sidebar`:
    * Orchestrated runtime flow: `handleId` -> `gesture` -> `containerId` -> `SidebarContainer` -> `PageManager` page stack.
    * Strict container validation: compares explicit `container_id` against canonical `${handleId}_${gesture}` computation. If mismatched/forged, logs warning and enforces canonical computation.
    * Enforced `GestureTarget` distinction: validates that handle exists, is enabled, and configured action for the gesture is `ACTION_OPEN_SIDEBAR`. Rejects requests for actions like `none` or other element actions.
    * Loads ordered page stack metadata lazily via `PageManager.getPageStack(container)`, identifying `currentPage = pages.firstOrNull()`.
    * Exposes resolved state via `activeState: StateFlow<SidebarRuntimeState?>`, with inspection helpers (`getActiveState()`, `getActiveContainer()`, `isSidebarOpen()`), page switching (`selectPage()`, `selectPageById()`), and closing (`closeContainer()`, `dismiss()`).
    * Implemented `BroadcastReceiver` listening for `com.example.action.OPEN_SIDEBAR` dispatches.
  - Minimally integrated with `HandleService`: registered `SidebarManager`'s receiver in `HandleService.onCreate()` and unregistered in `onDestroy()` without altering any touch recognition, handle tracking, or existing gesture dispatch code.
  - Strictly respected scope boundaries: zero UI views, zero Compose/XML layouts, no Elements, no Settings UI, no IPC, no heavy-process changes, no floating windows, no Window Manager, no Call Recorder, no changes to NetSpeed or gesture thresholds.
* How it was verified: local build only (`compile_applet` passed cleanly).
* Deviations: None.
* Known issues: None.

* Timestamp: 2026-09-07T23:40:00-07:00
* One-line summary: Established and verified lightweight Sidebar Page Stack runtime contract for SidebarContainer model.
* Exact files touched:
  - `app/src/main/java/com/example/core/PageManager.kt`
  - `app/src/main/java/com/example/core/HandleManager.kt`
  - `receipts/RECEIPTS_094.md`
* What was actually done:
  - Validated and refined `PageManager` runtime contract:
    * Loads raw container page IDs via `getPageIds(containerId)` and `getPageIds(container: SidebarContainer)`.
    * Saves raw container page IDs via `savePageIds(containerId, pageIds)` and `savePageIds(container: SidebarContainer, pageIds)`.
    * Loads ordered `SidebarPage` metadata models via `getPageStack(containerId)` and `getPageStack(container)`.
    * Mutates stacks via `addPage`, `removePage`, and `reorderPages` with zero UI allocations.
    * Resolves first/default page via `getFirstPage(containerId)` and `getFirstPage(container)`.
    * Strictly operates against an explicit `containerId` or `SidebarContainer`, never deriving from `handleId` alone.
    * Persistence strictly uses isolated key `handle_${containerId}_pages`, preserving order across app and process restarts.
    * Guarantees default page (`default_hybrid`) is safely associated with new individual containers without reading generic handle-level keys (`handle_${handleId}_pages`).
  - Extended `SidebarContainer` with convenience accessors: `getPageStack(context)`, `getPageIds(context)`, `getFirstPage(context)`.
  - Strictly respected scope boundaries: zero UI views, zero Compose/XML layouts, no Elements, no Settings UI, no IPC, no heavy-process changes, no floating windows, no Window Manager, no Call Recorder, no changes to NetSpeed or gesture thresholds.
* How it was verified: local build only (`compile_applet` passed cleanly).
* Deviations: None.
* Known issues: None.

* Timestamp: 2026-09-08T00:19:00-07:00
* One-line summary: Wired Handle -> Gesture -> Container -> Page Stack contracts into lightweight Sidebar runtime controller (SidebarManager, HandleService).
* Exact files touched:
  - `app/src/main/java/com/example/feature/sidebar/SidebarManager.kt`
  - `app/src/main/java/com/example/core/HandleService.kt`
  - `receipts/RECEIPTS_094.md`
* What was actually done:
  - Wired `HandleService.handleGestureAction` to resolve `GestureTarget`:
    * When `target is GestureTarget.Container`, synchronously calls `SidebarManager.getInstance(this).openTarget(handleConfig.id, gesture, target)` in the Main process and dispatches `com.example.action.OPEN_SIDEBAR` broadcast with `target.containerId`.
    * Retained clean `GestureTarget.None` and `GestureTarget.Action` distinctions.
  - Enhanced `SidebarManager` runtime controller:
    * Added `openTarget(handleId, gesture, target: GestureTarget.Container)` and `openContainer(container: SidebarContainer)`.
    * Guarded broadcast receiver to prevent redundant state reloads if the matching container is already open.
    * Added comprehensive runtime lifecycle and page-switching support: `openContainer()`, `closeContainer()`, `release()` / `dismiss()`, `selectPage(index)`, `selectPageById(pageId)`, `nextPage()`, `previousPage()`.
    * Added state inspection accessors: `getActiveContainer()`, `getActivePages()`, `getCurrentPage()`, `getCurrentPageIndex()`, `isSidebarOpen()`.
  - Guaranteed container/page stack isolation: opening one container completely resolves its unique `handle_${containerId}_pages` without reusing or retaining another gesture's container or page stack.
  - Strictly maintained UI-free runtime: zero Views, zero Compose UI, zero Home Grid rendering, no Elements, no Settings UI, no IPC, no Heavy-process changes, no floating windows, no Window Manager, no Call Recorder.
* How it was verified: local build only (`compile_applet` passed cleanly).
* Deviations: None.
* Known issues: None.

* Timestamp: 2026-09-08T00:52:00-07:00
* One-line summary: Compiled, modularized, and verified Main-process Hierarchy Window Manager foundation (FloatingWindowManager, FloatingWindow, MagneticSnapper, App.kt).
* Exact files touched:
  - `app/src/main/java/com/example/core/FloatingWindow.kt`
  - `app/src/main/java/com/example/core/FloatingWindowManager.kt`
  - `app/src/main/java/com/example/core/MagneticSnapper.kt`
  - `app/src/main/java/com/example/App.kt`
  - `receipts/RECEIPTS_094.md`
* What was actually done:
  - Fixed `this@FloatingWindow.windowId` reference within `FrameLayout` touch interceptor in `FloatingWindow.kt`.
  - Integrated `FloatingWindowManager` logging with zero-PII `LogKeeper` via private helpers.
  - Extracted collision detection and magnetic boundary snapping into standalone `MagneticSnapper.kt` to ensure strict modularity and keep `FloatingWindowManager.kt` under 500 lines (488 lines).
  - Connected `App.kt` `onTrimMemory` hook to `FloatingWindowManager.getInstance(this).onTrimMemory(level)` in the Main process for dormant folding under memory pressure.
  - Verified compilation with `compile_applet` (build succeeded cleanly).
  - Executed Security Scan Protocol on workspace.
* How it was verified: local build only (`compile_applet` passed cleanly).
* Deviations: None.
* Known issues: None.

* Timestamp: 2026-09-08T21:32:00-07:00
* One-line summary: Established Main-process on-demand Element/Action contract, registry, and lazy dispatcher foundation.
* Exact files touched:
  - `app/src/main/java/com/example/feature/element/ElementActionContract.kt`
  - `app/src/main/java/com/example/feature/element/ElementActionRegistry.kt`
  - `app/src/main/java/com/example/core/HandleService.kt`
  - `blueprint/BLUEPRINT.md`
  - `receipts/RECEIPTS_094.md`
* What was actually done:
  - Preserved existing `GestureTarget` distinction (`Container`, `Action`, `None`) and existing gesture configurations without renaming existing action keys.
  - Implemented `ElementCategory` covering all six planned element categories (Full-Screen Content, Another-App Link, Android Widget, Screen Overlay, Tool/Action, Sidebar/Page Content).
  - Implemented `ElementDescriptor`, `ElementExecutionContext`, `ElementActionContract`, and `ElementActionFactory` functional interface to guarantee lazy on-demand instantiation with zero eager startup allocations.
  - Implemented `ElementActionRegistry` in the Main process providing type registration, action key verification (`isRegistered`), contract resolution (`resolve`), availability checking (`isActionAvailable`), and safe execution dispatching (`dispatchAction`) with graceful handling for unknown or unavailable actions.
  - Kept Container targets (`GestureTarget.Container`) strictly routed through `SidebarManager`, with zero interference from the action dispatcher.
  - Kept all Elements entirely within Main with no Heavy process movement or premature IPC.
  - Registered broadcast receiver for `com.example.action.TRIGGER_ACTION` in `HandleService.onCreate()` and unregistered in `onDestroy()`.
  - Zero UI/content, zero Edit Mode UI, zero Add Element UI, zero Sidebar pages/UI, zero Settings UI, zero floating mini-apps, zero Window Manager alterations, and zero gesture recognition/threshold modifications.
* How it was verified: local build only (`compile_applet` passed cleanly).
* Deviations: None.
* Known issues: None.

* Timestamp: 2026-09-08T21:48:00-07:00
* One-line summary: Established Main-process Edit Mode controller and Add Element runtime contracts with isolated JSON persistence.
* Exact files touched:
  - `app/src/main/java/com/example/feature/element/ElementPlacement.kt`
  - `app/src/main/java/com/example/feature/element/AddElementContract.kt`
  - `app/src/main/java/com/example/feature/sidebar/EditModeController.kt`
  - `app/src/main/java/com/example/core/HandleManager.kt`
  - `app/src/main/java/com/example/core/PageManager.kt`
  - `app/src/main/java/com/example/feature/sidebar/SidebarManager.kt`
  - `blueprint/BLUEPRINT.md`
  - `receipts/RECEIPTS_094.md`
* What was actually done:
  - Implemented `EditModeController` and `EditModeState` in Main process (`com.example.feature.sidebar`), completely separate from Settings.
  - Enforced strict container and page scoping: `EditModeState` requires exact `containerId` and `pageId` (never `handleId` alone).
  - Provided complete edit lifecycle: `enterEditMode()`, `exitEditMode()`, `isEditModeActive()`, `isEditing(containerId, pageId)`, `getEditingContainerId()`, `getEditingPageId()`, `getActiveEditState()`, and `clearEditState()` / `release()`.
  - Wired `SidebarManager.closeContainer()` and `dismiss()` to automatically exit edit mode via `EditModeController.getInstance(context).exitEditMode()` to prevent leaking edit sessions.
  - Implemented `ElementPlacement` data model representing placed elements with zero View or Composable references (`placementId`, `containerId`, `pageId`, `actionKey`, `position`, `customTitle`, `customIconUri`, `config`).
  - Implemented `AddElementContract.kt` defining `AddElementRequest`, `AddElementResult`, and `ElementPlacementManager`.
  - Enforced validation of action keys through `ElementActionRegistry.isRegistered()` prior to placement, guaranteeing zero eager class instantiation.
  - Persisted element references in SharedPreferences under isolated key `HandleManager.getContainerPageElementsKey(containerId, pageId)` = `handle_${containerId}_page_${pageId}_elements` as JSON arrays, strictly adhering to `handleId + gesture -> containerId -> pageId -> elements`.
  - Added convenience querying method `getElementsForPage(containerId, pageId)` to `PageManager`.
  - Zero UI created (no Compose screens, drag handles, menus, grids, or Add Element views), zero individual Elements implemented, zero Settings touches, and zero modifications to gesture recognition, NetSpeed, Window Manager, or Heavy process.
* How it was verified: local build only (`compile_applet` passed cleanly, `gradle :app:testDebugUnitTest` passed).
* Deviations: None.
* Known issues: None.

* Timestamp: 2026-09-09T10:04:00-07:00
* One-line summary: Implemented Main-process Sidebar Page Runtime foundation with isolated selected page persistence, navigation contract, and Edit Mode integration.
* Exact files touched:
  - `app/src/main/java/com/example/core/HandleManager.kt`
  - `app/src/main/java/com/example/core/PageManager.kt`
  - `app/src/main/java/com/example/feature/sidebar/SidebarPageRuntimeContract.kt` (created)
  - `app/src/main/java/com/example/feature/sidebar/SidebarManager.kt`
  - `app/src/main/java/com/example/feature/sidebar/EditModeController.kt`
  - `app/src/main/java/com/example/feature/element/AddElementContract.kt`
  - `blueprint/BLUEPRINT.md`
  - `receipts/RECEIPTS_094.md`
* What was actually done:
  - Established `SidebarPageRuntimeContract` and `SidebarPageState` runtime data models in `com.example.feature.sidebar` defining state exposure and operations needed for future Sidebar UI without coupling to any visual framework.
  - Implemented container-scoped selected page persistence key `handle_${containerId}_selected_page` in `HandleManager` and `PageManager`, strictly avoiding generic handle-level bleed (`handle_1_selected_page`) and ensuring selected page survives Sidebar close/reopen and process recreation.
  - Updated `SidebarManager.openContainer()` to restore persisted selected page ID, fall back gracefully to the first page (index 0) if unselected, and load scoped placed elements for the active page via `ElementPlacementManager`.
  - Updated `SidebarManager.selectPage()` to persist the selected page ID per container, reload scoped element placements, preserve container identity without mutation, and seamlessly transition active Edit Mode scope if editing this container.
  - Added page navigation methods (`nextPage()`, `previousPage()`, `selectPageById()`, `refreshPages()`), state accessors (`getActivePageState()`, `getPageState()`, `getCurrentPageElements()`), and edit mode helpers (`toggleEditMode()`, `enterEditMode()`, `exitEditMode()`, `isCurrentPageInEditMode()`).
  - Wired two-way reactive notifications between `EditModeController`, `ElementPlacementManager`, and `SidebarManager` (`onEditModeChanged`, `onElementsChanged`) so placed elements and edit state update runtime state immediately.
  - In `PageManager.removePage()`, added fallback logic to safely reset the selected page ID to the first remaining page if the currently selected page is removed.
  - Kept runtime UI-free (zero View or Composable allocations), preserved all existing architectures (Main process residence, NetSpeed untouched, Elements in Main, Window Manager untouched, Heavy process untouched).
* How it was verified: local build only (`compile_applet` succeeded, `gradle :app:testDebugUnitTest` succeeded).
* Deviations: None.
* Known issues: None.

* Timestamp: 2026-09-09T11:00:00-07:00
* One-line summary: Implemented Main-process Sidebar Page Container Rendering layer with hardware-accelerated floating overlay, page-type rendering, and Edit Mode UI.
* Exact files touched:
  - `app/src/main/java/com/example/feature/sidebar/SidebarView.kt` (created)
  - `app/src/main/java/com/example/feature/sidebar/SidebarPageRenderer.kt` (created)
  - `app/src/main/java/com/example/feature/sidebar/SidebarWindowCoordinator.kt` (created)
  - `app/src/main/java/com/example/feature/sidebar/SidebarManager.kt`
  - `blueprint/BLUEPRINT.md`
  - `receipts/RECEIPTS_094.md`
* What was actually done:
  - Created `SidebarView`: hardware-accelerated floating overlay container docked to screen edge with edge-aware gravity and corner styling (HandleEdge.RIGHT -> Gravity.END with left-rounded corners; HandleEdge.LEFT -> Gravity.START with right-rounded corners), 198dp calibrated width, slide-in/slide-out animations, outside-touch auto-dismissal, and hardware back-button interception.
  - Built floating top-bar with Edit Mode toggle (pencil vs green checkmark), previous page button, page title and order indicator ("1 / N"), next page button, and close button.
  - Built page tabs strip allowing one-tap switching between ordered pages in the container stack.
  - Created `SidebarPageRenderer`: modular page-type driven renderer supporting HYBRID, APPS, WIDGETS, MEDIA (with interactive media key broadcasting), TOOLS, APP_TRACKER (with real-time DailyDataUsageHelper metrics), CALCULATOR (with interactive numeric keypad, calculation evaluation, and clipboard copy), and COMPASS.
  - Rendered placed elements scoped strictly to `containerId` + `pageId` in responsive 3-column grid with category icon resolution and lazy action dispatch through `ElementActionRegistry`.
  - In Edit Mode, provided element removal badges (red '✕' button calling `ElementPlacementManager.removeElement()`) and appended '+ Add Item' tile.
  - Integrated scoped Add Element picker sheet overlay displaying all registered descriptors from `ElementActionRegistry`, allowing instant element addition scoped to containerId + pageId.
  - Created `SidebarWindowCoordinator`: manages WindowManager overlay attachment (`TYPE_APPLICATION_OVERLAY`), main-thread synchronization, edge detection, view updates, and clean detachment without accumulating duplicate windows.
  - Connected `SidebarManager` directly to `SidebarWindowCoordinator` across `openContainer()`, `selectPage()`, `closeContainer()`, `dismiss()`, `refreshPages()`, `onEditModeChanged()`, and `onElementsChanged()`.
* How it was verified: local build only (`compile_applet` succeeded, `gradle :app:testDebugUnitTest` succeeded).
* Deviations: None.
* Known issues: None.

* Timestamp: 2026-09-09T14:08:00-07:00
* One-line summary: Audited Sidebar Page Container Rendering layer, integrated with authoritative FloatingWindowManager, and pruned premature feature implementations.
* Exact files touched:
  - `app/src/main/java/com/example/feature/sidebar/SidebarWindow.kt` (created)
  - `app/src/main/java/com/example/feature/sidebar/SidebarWindowCoordinator.kt`
  - `app/src/main/java/com/example/feature/sidebar/SidebarPageRenderer.kt`
  - `app/src/main/java/com/example/feature/sidebar/SidebarView.kt`
  - `blueprint/BLUEPRINT.md`
  - `receipts/RECEIPTS_094.md`
* What was actually done:
  - Conducted strict architecture and scope audit of Sidebar Page Container Rendering layer:
    1. Identified that `SidebarWindowCoordinator` directly manipulated WindowManager, bypassing the centralized `FloatingWindowManager`.
    2. Corrected integration by introducing `SidebarWindow` extending `FloatingWindow(TYPE_PAGE)` and refactoring `SidebarWindowCoordinator` to delegate overlay registration, Z-ordering, visibility, and unregistration exclusively to `FloatingWindowManager.getInstance(appContext)`.
    3. Identified premature inline feature implementations inside `SidebarPageRenderer` (ad-hoc Calculator keypad and evaluator, mock Compass dial, ad-hoc Media button dispatcher, and daily data usage query).
    4. Pruned premature feature implementations, replacing them with a clean, lightweight page-type container contract (`createPageTypeHeader` and `createPageBadge`) supporting all 8 page types (`HYBRID`, `APPS`, `WIDGETS`, `MEDIA`, `TOOLS`, `APP_TRACKER`, `CALCULATOR`, `COMPASS`) and preserving container identity without unrequested inline logic.
    5. Confirmed placed elements grid is strictly scoped to `containerId` + `pageId`, dispatches actions lazily through `ElementActionRegistry`, and renders Edit Mode element removal and '+ Add Item' affordances.
    6. Confirmed in-sidebar '+ Add Item' picker uses `ElementActionRegistry.getAllDescriptors()` and `ElementPlacementManager.addElement()` without eager Element instantiation or parallel element systems.
    7. Maintained `SidebarManager` as the singular source of truth for active container/page state with zero duplicate state in views.
    8. Verified all modified files are strictly under 500 lines (`SidebarManager.kt`: 499 lines, `SidebarView.kt`: 493 lines, `SidebarPageRenderer.kt`: 414 lines, `EditModeController.kt`: 183 lines, `SidebarWindowCoordinator.kt`: 160 lines, `SidebarPageRuntimeContract.kt`: 153 lines, `SidebarWindow.kt`: 80 lines).
* How it was verified: local build only (`compile_applet` succeeded, `gradle :app:testDebugUnitTest` succeeded).
* Deviations: None.
* Known issues: None.

## Entry 094.4 — Main-process Common Element Runtime Foundation
* Timestamp: 2026-09-09T14:30:00-07:00
* One-line summary: Implemented the Main-process Common Element Runtime foundation with lazy resolution, lifecycle management, and scoped isolation.
* Exact files touched:
  - `app/src/main/java/com/example/feature/element/CommonElementRuntimeContract.kt` (created)
  - `app/src/main/java/com/example/feature/element/ElementRuntimeResolver.kt` (created)
  - `app/src/main/java/com/example/feature/element/ElementActionContract.kt`
  - `app/src/main/java/com/example/feature/element/ElementActionRegistry.kt`
  - `app/src/main/java/com/example/feature/sidebar/SidebarPageRenderer.kt`
  - `app/src/main/java/com/example/feature/sidebar/SidebarManager.kt`
  - `blueprint/BLUEPRINT.md`
  - `receipts/RECEIPTS_094.md`
* What was actually done:
  - Created `CommonElementRuntimeContract` extending `ElementActionContract` with lifecycle methods (`onInitialize`, `onRelease`) and sidebar rendering contract (`renderSidebarElement(ElementRenderContext): View?`).
  - Created `ElementRenderContext` conveying executionContext, placement, isEditMode, onActionTriggered, and onRemoveRequested to enforce that element instances never determine their own container/page scope.
  - Implemented thread-safe `ElementRuntimeResolver` to resolve and instantiate registered elements strictly on demand when rendering or executing a placement, preserving lazy loading and zero eager mass-instantiation.
  - Updated `ElementExecutionContext` and `ElementActionRegistry.dispatchAction` to support optional `pageId` for full handleId + gesture + containerId + pageId execution context tracking.
  - Connected `SidebarPageRenderer` to resolve placed elements through `ElementRuntimeResolver`, delegates rendering to `renderSidebarElement` (with fallback to standard tile), routes click execution through `executePlacement`, and dispatches removal requests to `ElementPlacementManager`.
  - Added coordinated `ElementRuntimeResolver.getInstance(context).releaseAll()` cleanup in `SidebarManager.closeContainer()` and `SidebarManager.dismiss()`.
  - Maintained all elements within the Main process and verified all modified files remain strictly under 500 lines.
* How it was verified: local build only (`compile_applet` succeeded).
* Deviations: None.
* Known issues: None.

---

### Entry: 2026-09-13 - Phase 12.7: Final Main ↔ Heavy Process IPC Foundation
* Timestamp: 2026-09-13T19:35:00Z
* Summary: Implemented the Final Main ↔ Heavy Process IPC Foundation with Binder/AIDL contracts, lifecycle monitoring, snapshot sync, and full unit test coverage.
* Exact files touched:
  - `gradle/libs.versions.toml`
  - `app/build.gradle.kts`
  - `app/src/main/java/com/example/core/ipc/IpcModels.kt`
  - `app/src/main/java/com/example/core/ipc/IpcContracts.kt`
  - `app/src/main/java/com/example/core/ipc/HeavyHostBinder.kt`
  - `app/src/main/java/com/example/core/ipc/MainCallbackBinder.kt`
  - `app/src/main/java/com/example/core/ipc/HeavyProcessConnectionManager.kt`
  - `app/src/main/java/com/example/core/ipc/HeavyProcessHost.kt`
  - `app/src/main/java/com/example/feature/floating/HeavyFloatingHostService.kt`
  - `app/src/main/java/com/example/feature/floating/FloatingMiniAppBridge.kt`
  - `app/src/test/java/com/example/core/ipc/IpcFoundationTest.kt`
  - `blueprint/BLUEPRINT.md`
  - `receipts/RECEIPTS_094.md`
* What was actually done:
  - Established explicit Android Binder transaction protocol (`IHeavyHostContract`, `IMainCallbackContract`, `IpcTransactions`) connecting the Main resident process (`com.example`) and the on-demand Heavy process (`:heavy`).
  - Created strongly-typed, immutable IPC data models (`HeavyCommand`, `HeavyEvent`, `MainStateSnapshot`, `MainCommandRequest`, `IpcResult`, `IpcErrorCode`) with lightweight, reflection-free JSON serialization (`IpcJsonUtils`).
  - Maintained Main as the authoritative owner of handles, gestures, containers, pages, element placements, Edit Mode, and FloatingWindowManager Z-order; Heavy receives minimal snapshots on-demand without duplicating the hierarchy.
  - Implemented `HeavyProcessConnectionManager` in Main with connection state management, `DeathRecipient` monitoring without crashing Main, and safe error fallbacks when Heavy is unavailable.
  - Implemented `HeavyProcessHost` in `:heavy` to host the Binder stub, cache snapshots read-only, route commands to registered heavy handlers, and bridge events/action requests back to Main.
  - Connected `HeavyFloatingHostService.onBind()` to `HeavyProcessHost.getBinder()` and wired commands to `HeavyFloatingHost`.
  - Updated `FloatingMiniAppBridge` to dispatch lifecycle commands via `HeavyProcessConnectionManager`.
  - Added comprehensive JUnit test suite (`IpcFoundationTest.kt`) with 10 passing tests verifying serialization, snapshot isolation, mock dispatch, death handling, and command handling.
  - Kept all files modularized and strictly under 500 lines.
* How it was verified: local build only (`gradle :app:testDebugUnitTest` - 10/10 passed; `compile_applet` succeeded).
* Deviations: None.
* Known issues: None.

* Timestamp: 2026-09-13T21:42:00Z
* Summary: Implemented Call Sensor → Heavy Wake Foundation with two-process architecture, minimal IPC commands, and Heavy process resilience.
* Exact files touched:
  - `app/src/main/AndroidManifest.xml`
  - `app/src/main/java/com/example/core/ipc/IpcModels.kt`
  - `app/src/main/java/com/example/core/ipc/CallIpcContract.kt`
  - `app/src/main/java/com/example/core/ipc/HeavyProcessConnectionManager.kt`
  - `app/src/main/java/com/example/core/ipc/HeavyProcessHost.kt`
  - `app/src/main/java/com/example/core/CallRecorderManager.kt`
  - `app/src/main/java/com/example/feature/call/HeavyCallHostExtension.kt`
  - `app/src/test/java/com/example/core/ipc/CallSensorIpcTest.kt`
  - `blueprint/BLUEPRINT.md`
  - `receipts/RECEIPTS_094.md`
* What was actually done:
  - Removed `android:process=":core"` from `CallStateReceiver` in `AndroidManifest.xml`, ensuring all resident sensor listeners operate exclusively in the Main process (`com.example`) and eliminating the legacy `:core` process.
  - Defined minimal Call IPC contract in `CallIpcContract.kt` and updated `HeavyCommandType` (`CALL_STATE_CHANGED`, `REQUEST_CALL_RECORDER`) with strictly minimal payload (raw state, state string, transition, timestamp) without transmitting PII or audio data.
  - Updated `CallRecorderManager` in Main to track call state transitions (`RINGING`, `CALL_STARTED`, `CALL_ENDED`), dispatch minimal wake commands across Binder to `:heavy`, and remain active when screen is OFF/locked without depending on handle/gesture/NetSpeed screen-on lifecycle.
  - Implemented `HeavyCallHostExtension` extension point in `:heavy` and wired it into `HeavyProcessHost` to receive and acknowledge call commands safely without implementing recording engine, UI, or audio recording.
  - Added pending command queue in `HeavyProcessConnectionManager` ensuring asynchronous process wake commands are delivered upon connection, and handled disconnect/death safely with zero Main crash or ANR risk.
  - Added comprehensive unit test suite in `CallSensorIpcTest.kt` verifying call contract mapping, state transitions, dispatch sequencing, Heavy extension routing, and unavailable/dead process resilience.
* How it was verified: local build only (`gradle :app:testDebugUnitTest` executed and all unit tests passed; `compile_applet` succeeded).
* Deviations: None.
* Known issues: None.









