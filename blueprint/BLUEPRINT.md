# Vian OS App Migration Blueprint (Updated)

## Completed Core Architecture
- [x] **Phase 8: Floating Apps & Utilities (Part 1)**: `CalculatorFloatingWindow`, `CompassFloatingWindow`, `DictionaryFloatingWindow`, `MiniAppManager`, Grid adapters.
- [x] **Phase 8.5: Orphaned Sidebar Pages Catch-up**: `MediaPlayerPageView`, `WidgetPageView`, `AppTrackerPageView`, `ResourcesTrackerPageView`.
- [x] **Phase 9: The UI Spines**: Settings & Handle Customization (`SettingsActivity`, `SidebarSettingsScreen`, `HandlesListSettingsScreen`, `AddElementActivity`, `ActionPickerActivity`).
- [x] **Phase 10: The Background System Hub (Plugins & Accessibility)**: `VianSideAccessibilityService` unified System Tools Hub with on-demand module instantiation (Cursor, AutoScroll, Screenshot, AppKiller), hardware controls, CallRecorder, and screen-aware NetSpeedManager with Android 13+ POST_NOTIFICATIONS support.
- [x] **Phase 11: Sidebar On-Demand Optimization**: `ViewPager2` lazy-loading, robust page ID resolution, zero phantom duplicate pages.
- [x] **Phase 11.5: Native Buffer & Heap Lifecycle Optimization**: Strict ML Kit on-demand lifecycle (`close()` on translation, OCR, and barcode analyzers) and complete heap reclaim on `SidebarView.detach()` (adapter nullification, ViewHolder unbinding, view pool recycling).
- [x] **Phase 12: Unified Z-Window Manager & OS Popups (Part 1)**: Centralized `FloatingWindowManager` with automated Z-ordering, dormant folding, and magnetic grouping.
- [x] **Phase 12.5: Multi-Process Isolation & IPC Architecture**: Service isolation (`:overlay` process for `HandleService` and `SidebarService` keeping idle background RAM at ~15-20MB), cross-process `OverlaySyncManager` IPC with broadcast synchronization, native status bar icon dimension query (44px on Xiaomi/Redmi) eliminating downsampling blur, standalone decoupled Force Stop Apps execution from widgets/gestures without requiring App Tracker page open, and overhauled Log Keeper with a clean Light Theme and separate Crash Log tab.
- [x] **Phase 12.6: 3-Process Architecture & Gesture Container Isolation**: Split runtime into `:core` (resident touch/sensors daemon ~18MB), `:sidebar` (ephemeral on-demand overlay host stopping via `stopSelf()` on dismiss, reducing permanent idle RAM to ~30-32MB), and `:ui` (main Settings/Compose process ~70MB). Strict gesture container isolation (`${handleId}_${gesture}`) with default `swipe_left` gesture mapping to the primary Home Grid page deck, backed by `IconCacheManager` downsampled 48x48 WebP disk caching to eliminate main-thread Binder IPC.
- [x] **Process 1 Restructure: Persistent `:core` Process Optimization**: Streamlined `:core` to contain strictly essential background components (`HandleService`, edge gesture detection, `NetSpeedManager` + continuous foreground notification with exact-dimension immutable `DynamicSpeedIconGenerator` snapshots, `DailyDataUsageHelper` via `NetworkStatsManager`, `CallStateReceiver` / `CallRecorderManager`, and `BootReceiver`). Implemented `onTrimMemory()` forwarding and deferred UI cache initializations away from `:core` startup.
- [x] **Main Runtime Element/Action Foundation**: Lightweight, on-demand Element/Action contracts (`ElementActionContract`, `ElementDescriptor`, `ElementExecutionContext`, `ElementCategory`, `ElementActionFactory`) and lazy dispatcher (`ElementActionRegistry`) in Main process preserving `GestureTarget` distinction (`Container` vs `Action` vs `None`).
- [x] **Main Runtime Edit Mode & Add Element Foundation**: Scoped `EditModeController` / `EditModeState` (`containerId` + `pageId`), `AddElementRequest` / `AddElementResult`, `ElementPlacement`, and `ElementPlacementManager` isolated SharedPreferences JSON persistence (`handle_${containerId}_page_${pageId}_elements`). Zero UI, zero eager Element instantiation.
- [x] **Main Runtime Sidebar Page Runtime Foundation**: Established `SidebarPageRuntimeContract` and implemented full page runtime in `SidebarManager` (scoped page stack, stable page IDs, ordered pages, first/default page, current page selection, next/previous navigation, page switching preserving container identity, persistent selected page survival across close/reopen and process recreation under `handle_${containerId}_selected_page`, scoped element placements, and synchronized Edit Mode lifecycle). UI-free.
- [x] **Main Runtime Sidebar Page Container Rendering Layer**: Connected `SidebarManager` and `SidebarPageRuntimeContract` to hardware-accelerated floating overlay UI (`SidebarView`, `SidebarPageRenderer`, `SidebarWindowCoordinator`, `SidebarWindow`). Audited and strictly aligned with reference architecture:
  - **Authoritative Hierarchy Integration**: Created `SidebarWindow` (extending `FloatingWindow(TYPE_PAGE)`) and updated `SidebarWindowCoordinator` to delegate overlay registration, Z-ordering, display, and teardown directly to `FloatingWindowManager`.
  - **Reference UI Structure**: 198dp calibrated width, edge-aware docking (`HandleEdge.RIGHT` -> `Gravity.END` with left-rounded corners; `HandleEdge.LEFT` -> `Gravity.START` with right-rounded corners), slide-in/slide-out animations, outside-touch auto-dismissal, hardware back-button interception, floating top bar (Edit Mode toggle, previous/next page navigation, page position indicator, and close affordance), and horizontal page tabs strip.
  - **Scope Discipline & Minimal Page-Type Contract**: Stripped premature inline feature implementations (ad-hoc calculator arithmetic evaluator, mock compass dial, media key broadcaster, and daily data usage query) from `SidebarPageRenderer`. Retained clean page-type header badges (`HYBRID`, `APPS`, `WIDGETS`, `MEDIA`, `TOOLS`, `APP_TRACKER`, `CALCULATOR`, `COMPASS`) and the placed elements grid strictly scoped to `containerId` + `pageId`.
  - **In-Sidebar Add Element Picker**: Uses `ElementActionRegistry.getAllDescriptors()` and `ElementPlacementManager.addElement()` without creating parallel element systems or eager element instances.
  - **Single Source of Truth**: All page navigation, edit mode toggling, and element mutations route exclusively through `SidebarManager`. All files kept under 500 lines.
- [x] **Main Runtime Common Element Runtime Foundation**:
  - **Unified Contract (`CommonElementRuntimeContract`)**: Extends `ElementActionContract` with on-demand lifecycle (`onInitialize`, `onRelease`) and sidebar page element rendering (`renderSidebarElement(ElementRenderContext): View?`).
  - **Supported Element Categories**: Unified across all 6 categories (`FULL_SCREEN_CONTENT`, `ANOTHER_APP_LINK`, `ANDROID_WIDGET`, `SCREEN_OVERLAY`, `TOOL_ACTION`, `SIDEBAR_PAGE_CONTENT`).
  - **On-Demand Lazy Resolver (`ElementRuntimeResolver`)**: Resolves, initializes, and caches active element contracts strictly on demand when an element placement needs to be rendered or executed. Zero eager mass-instantiation on Sidebar open.
  - **Enforced Execution Scope & Isolation**: Provides strongly-typed `ElementRenderContext` and `ElementExecutionContext` conveying `handleId`, `gesture`, `containerId`, `pageId`, and placement config. Element instances never determine their own container/page scope.
  - **Renderer Integration**: `SidebarPageRenderer` resolves placed elements via `ElementRuntimeResolver`, delegates rendering to `renderSidebarElement` with canonical fallback tile rendering, routes clicks to `executePlacement`, and dispatches scoped removal to `ElementPlacementManager`.
  - **Lifecycle Management**: Coordinated `releaseAll()` invoked on `SidebarManager.closeContainer()` and `SidebarManager.dismiss()`, properly cleaning up runtime resources. All files under 500 lines.
- [x] **Phase 12.7: Final Main ↔ Heavy Process IPC Foundation**:
  - **Strict Two-Process Architecture**: Main process (`com.example`) is the lightweight resident runtime and sole authoritative source of truth (Handles, Gestures, Containers, Pages, Element Placements, Edit Mode, FloatingWindowManager hierarchy/Z-order). Heavy process (`:heavy`) is on-demand and disposable for heavy content without maintaining a duplicate hierarchy.
  - **Contract & Transaction Protocol (`IpcContracts.kt`, `IpcModels.kt`)**: Explicit Binder/AIDL-compatible transaction protocol using standard IBinder transactions, transaction codes, descriptors, and parcel contracts (`IHeavyHostContract`, `IMainCallbackContract`).
  - **Command, Event & Snapshot Data Models**:
    - Commands: `HeavyCommand` (`START_OPERATION`, `STOP_OPERATION`, `PAUSE_OPERATION`, `RESUME_OPERATION`, `UPDATE_CONFIG`, `PING`, `CUSTOM`).
    - Events: `HeavyEvent` (`LIFECYCLE_CHANGED`, `STATE_UPDATED`, `OPERATION_FINISHED`, `ERROR_REPORTED`, `HEARTBEAT`, `CUSTOM`).
    - Snapshots: `MainStateSnapshot` (minimal, immutable read-only context pushed on-demand).
    - Actions: `MainCommandRequest` (`CLOSE_WINDOW`, `SHOW_TOAST`, `TRIGGER_ACTION`, etc.).
    - Results: `IpcResult` with typed `IpcErrorCode` (`OK`, `HEAVY_UNAVAILABLE`, `TIMEOUT`, `MARSHAL_ERROR`, `REJECTED`, `DEAD_BINDER`, `UNKNOWN_ERROR`).
    - Lightweight, zero-dependency serialization via `IpcJsonUtils` ensuring JVM unit test stability without Android JSON stubs.
  - **Main Process Connection Manager (`HeavyProcessConnectionManager.kt`)**: Thread-safe connection lifecycle (`DISCONNECTED`, `CONNECTING`, `CONNECTED`, `DEAD`), `IBinder.DeathRecipient` monitoring with automatic state transition to `DEAD` without crashing Main, on-demand asynchronous binding, listener callbacks, and safe error fallbacks when Heavy is unavailable.
  - **Heavy Process Host Controller (`HeavyProcessHost.kt`)**: Implements `IHeavyHostHandler`, stores read-only cached snapshot, dispatches commands to registered heavy modules, and reports events/action requests back to Main.
  - **Host Service Integration (`HeavyFloatingHostService.kt`)**: Implemented `onBind` returning `HeavyProcessHost`'s `HeavyHostBinder` and wired command listeners to `HeavyFloatingHost`.
  - **Bridge Integration (`FloatingMiniAppBridge.kt`)**: Routed window lifecycle commands through `HeavyProcessConnectionManager` with graceful fallback.
  - **Comprehensive Unit Testing (`IpcFoundationTest.kt`)**: 10 unit tests covering command/event serialization, snapshot isolation, unavailable behavior, mock connection dispatch, death handling, and command registration.

- [x] **Phase 12.8: Call Sensor → Heavy Wake Foundation**:
  - **Strict Two-Process Alignment**: Eliminated legacy `:core` process by removing `android:process=":core"` from `CallStateReceiver` in `AndroidManifest.xml`. Call Sensor operates exclusively in Main (`com.example`), available continuously when screen is OFF/locked without depending on handle, gesture, or NetSpeed screen-on lifecycles.
  - **Minimal Call IPC Contract (`CallIpcContract.kt`, `IpcModels.kt`)**: Added `CALL_STATE_CHANGED` and `REQUEST_CALL_RECORDER` commands to `HeavyCommandType`. Transmits minimal payloads (state integer, state name, transition identifier, timestamp) strictly preserving privacy with zero audio, contacts, or PII.
  - **CallRecorderManager Sensor & Wake Dispatcher (`CallRecorderManager.kt`)**: Tracks transitions (`RINGING`, `CALL_STARTED`, `CALL_ENDED`), dispatches minimal wake commands across Binder to `:heavy`, and queues asynchronous delivery via `HeavyProcessConnectionManager`.
  - **Heavy Process Extension Point (`HeavyCallHostExtension.kt`, `HeavyProcessHost.kt`)**: Implemented pluggable `HeavyCallHostExtension` in `:heavy` acknowledging call events and recorder requests safely without implementing audio recording, UI, or recording engines.
  - **Resilience & Queueing (`HeavyProcessConnectionManager.kt`)**: Asynchronous process wake command queueing (capped at 20 commands) ensures commands are delivered when Heavy finishes connecting, and binder death is handled with zero Main crash or ANR risk.
  - **Unit Testing Suite (`CallSensorIpcTest.kt`)**: Comprehensive test suite covering call contract mapping, state transition sequencing, Heavy extension routing, and unavailable/dead process resilience.

### 12.9 — Heavy-Process Welcome Page (Permission / Setup Entry of Settings) (Completed)
- [x] **Heavy-Process Welcome Foundation**:
  - **Process Assignment**: `WelcomeActivity` and `MainActivity` reside strictly in `android:process=":heavy"` with `launchMode="singleTop"`. Main process remains purely the lightweight resident runtime with zero Compose UI dependencies.
  - **Reference UI Preservation**: Reused the exact layout, text, test tags (`grant_overlay_button`, `net_speed_switch`, `start_core_button`, `stop_core_button`, `open_log_keeper_button`), and permission flows (overlay check and notification launcher).
  - **Narrow IPC Boundary (`WelcomeIpcContract.kt`, `HeavyProcessHost.kt`)**: Added `HeavyCommandType.SHOW_WELCOME` with minimal payload (`reason`, `timestamp`). No internal managers or runtime hierarchies exposed to Heavy.
  - **Launch Deduplication & Debounce (`HeavyWelcomeHostExtension.kt`)**: `DefaultHeavyWelcomeHostExtension` detects active foreground state (`WelcomeActivity.isWelcomeActive`) and suppresses rapid redundant launches via a configurable debounce window.
  - **Main-Process Request Controller (`MainWelcomeController.kt`)**: Provides `requestWelcome(reason)` in Main via `HeavyProcessConnectionManager`, queuing or reporting gracefully if Heavy is offline or dead.
  - **Process Death & Lifecycle Resilience**: Heavy process terminates on demand without lingering services. If killed, Main daemon (`HandleService`, handles, gestures, NetSpeed) continues running uninterrupted.
  - **Test Coverage (`WelcomeIpcTest.kt`)**: Validated contract serialization, IPC routing, launch deduplication/debouncing, and process death resilience.

### 12.10 — Capability Architecture & Heavy Module Foundation (Completed)
- [x] **Capability Architecture & Heavy Module Foundation**:
  - **Main Process Capability Layer (`CapabilityContract.kt`, `CapabilityManager.kt`, `CapabilityTypes.kt`)**: Lightweight, process-safe session tracking (`CapabilitySession`), on-demand lazy registration, reference-counted mount/use/unmount lifecycle, and auto-discard when all sessions release.
  - **Remote Heavy Routing (`HeavyCapabilityRouter.kt`)**: Bridges Main capability requests across existing Binder IPC to `:heavy` (`START_OPERATION`, `CUSTOM`, `STOP_OPERATION`) without leaking heavy UI or engine dependencies into Main. Survives Heavy process death with typed `IpcErrorCode.DEAD_BINDER` mapping to `CapabilityErrors.PROCESS_DIED`.
  - **Element Runtime Integration (`ElementCapabilityExtensions.kt`)**: Safe extension helpers (`mountCapability`, `unmountCapabilities`) enabling placed Elements to bind to capabilities without manual lifecycle management.
  - **Heavy Module System (`HeavyModuleContract.kt`, `HeavyModuleTypes.kt`, `HeavyModuleManager.kt`)**: Process-local registry resident strictly in `:heavy`. Enforces lazy instantiation, concurrent reference-counted mounting, safe operation dispatch (`onUse`), and guaranteed resource disposal (`onDispose`) when active session count reaches 0.
  - **IPC Command Translation (`HeavyProcessHost.kt`)**: Integrated `isModuleCommand` routing module commands directly to `HeavyModuleManager.handleIpcCommand()` with full JSON/payload marshalling.
  - **Disposable Teardown Sync (`HeavyFloatingHostService.kt`)**: `checkDisposableTeardown()` queries active mini-apps and active heavy module sessions, calling `stopSelf()` when idle to prevent `:heavy` from lingering.
  - **Test Suites (`CapabilityManagerTest.kt`, `HeavyModuleManagerTest.kt`)**: 20 comprehensive unit tests verifying registration, reference counting, unmount teardown, error mapping, and IPC execution.

### 12.11 — Real Call Recorder Engine & Multi-Process Architecture (Completed)
- [x] **Strict 2-Process Architecture**:
  - **Main Process (`com.example`)**: Resident, lightweight Call Sensor (`CallRecorderManager.kt`, `CallStateReceiver.kt`). Holds 0 audio buffers, 0 MediaRecorder instances, 0 recording databases, and 0 recording UI. Maintains only authoritative call state, expected recording intent, and active session tokens. Handles IPC reconnect/recovery and duplicate callback deduplication.
  - **Heavy Process (`:heavy`)**: Real recording engine (`HeavyCallRecorderEngine.kt`, `RealHeavyCallHostExtension.kt`). Hosts `MediaRecorder` lifecycle, audio configuration (`MPEG_4`/`THREE_GPP`), SAF and app-private storage resolution (`.Records/CALL_*.m4a`), fallback audio source capture (`VOICE_RECOGNITION` -> `MIC`), and dispatches async lifecycle events (`STATE_UPDATED`, `OPERATION_FINISHED`, `ERROR_REPORTED`) back to Main across Binder.
  - **Authoritative Idempotent State Machine**: Deduplicates incoming Telephony callbacks and BroadcastReceivers. `IDLE -> OFFHOOK` requests exactly one session; duplicate `OFFHOOK` calls are ignored; `OFFHOOK -> IDLE` stops the session cleanly once.
  - **Fault Tolerance & Recovery**: If `:heavy` crashes during an active call, Main catches the dead Binder, prevents infinite reconnect loops, initiates on-demand reconnection, and resynchronizes active session state upon reconnect.
  - **Test Coverage (`CallSensorIpcTest.kt`, `HeavyCallRecorderEngineTest.kt`)**: Validates contract payload serialization, state transitions, duplicate callback filtering, heavy process death resilience, reconnect resynchronization, and engine lifecycle delegation.

### 12.12 — Elements System & Structural Separation (Completed)
- [x] **Strict Hierarchy Architecture**: `Handle → Gesture → Container → Page → Element`
  - **SidebarView Minimal Structural Split**: Extracted page edit navigation routing to `SidebarEditNavigator.kt`, preserving `SidebarView` strictly as high-level Sidebar coordinator, keyboard/IME handler, and window layout orchestrator.
  - **Authoritative Element Models (`SidebarItem.kt`)**: Decoupled sealed class `SidebarItem` and all action lists (`ALL_QUICK_TILES`, `ALL_SYSTEM_ACTIONS`, `ALL_SCREEN_CAPTURE_ACTIONS`, `ALL_VOLUME_ACTIONS`, `ALL_MEDIA_ACTIONS`, `ALL_SETTINGS_SHORTCUTS`, `ALL_DISPLAY_ACTIONS`, `ALL_UTILITIES_ACTIONS`, `ALL_FLOATING_WINDOWS`) from `SidebarAppsManager.kt` into dedicated model file.
  - **Dedicated Element View Rendering (`ElementViewRenderer.kt`)**: Extracted tile layout binding, dynamic icon binding, launchable action close coordination, context menu popups ("App Info", "Remove", "Change Icon", "Reset Icon"), and modal popups (Folders with custom grids, Popup Widgets with `AppWidgetHostView`).
  - **Action Registration & Dispatch (`ElementActionRegistry.kt`, `ElementActionDispatcher.kt`)**: Modular prefix resolution (`app:`, `system:`, `display:`, `volume:`, `quicktile:`, `media:`, `settings_shortcut:`, `folder:`, `widget:`, `link:`) and full system/accessibility action dispatching.
  - **HybridGridPageView Optimization**: Delegated element view rendering, popup management, and context menus directly to `ElementViewRenderer`, eliminating ~700 lines of duplicated logic while preserving container isolation and page persistence.
  - **Test Suite (`ElementSystemTest.kt`)**: Comprehensive unit tests covering element hierarchy, ID prefixing, action list coverage, registry prefix resolution, and edit navigation intent creation.

### 12.13 — Modular Accessibility Architecture & Action Execution Engine (Completed)
- [x] **Modular Accessibility Architecture**:
  - **Modular Service Coordinator**: `VianSideAccessibilityService` coordinates and dispatches via `AccessibilityActionRegistry`, strictly avoiding monolithic `when(action)` branches.
  - **On-Demand Lifecycle**: Registered modules (`AutoScrollActionModule`, `CursorActionModule`, `LongScreenshotActionModule`, `ScreenshotActionModule`, `GlobalNavigationActionModule`, etc.) load lazily when invoked and immediately unload on completion (or on toggle-off for continuous overlays like auto-scroll and cursor).
  - **State Discrimination**: Strict typed hierarchy (`AccessibilityActionResult.Success`, `AccessibilityActionResult.Unavailable`, `AccessibilityActionResult.Failed`, `AccessibilityActionResult.ServiceUnavailable`). UI layers (`ElementActionDispatcher`, `AppsPageView`, `HybridGridPageView`) only open Android Accessibility Settings when the service itself is actually disabled or disconnected, never on module absence or execution failure.
  - **Optional Heavy Scanner Boundary (`ScannerCapabilityContract.kt`)**: Decoupled ML/OCR/QR/barcode functionality (`qr_scan`, `barcode_scanner`, `redact_screenshot`) as optional downloadable capabilities with clean boundary interfaces. Main startup remains lightweight with zero heavy ML resource initialization.
  - **Structured Diagnostic Logging**: Exact diagnostic format logging requested action ID, selected module/handler, module load, execution result, module unload, and failure reason.
  - **Test Suite (`ModularAccessibilityActionTest.kt`)**: 8 comprehensive unit tests verifying service unavailable redirection, global navigation dispatch, screenshot execution, toggle lifecycle loading/unloading, scanner capability unavailable reporting without settings redirect, and diagnostic log verification.

### 12.14 — Precision Tooling: Freehand Redaction, Multi-Shape Scanner & Cursor Click (Completed)
- [x] **Freehand Brush-Based Screenshot Redaction** (`RedactScreenshotActivity`):
  - Replaced legacy drag bounding boxes with a path-based freehand brush engine (`RedactStroke`, `RedactPoint`).
  - Supports Blackout mode (solid black `#000000` with round cap/join) and Blur / Mosaic mode (high-quality localized pixelated mosaic rendering).
  - Top bar features Undo, Clear All, and Save & Share; toolbar provides brush size presets (Small, Medium, Large) and mode toggles.
- [x] **Interactive Multi-Shape Scanner Selection & Handles** (`SecureScreenScannerActivity`, `SecureCameraScannerActivity`, `ScannerSelectionHelper`):
  - Replaced multi-point tap-to-start with an immediate single-drag initial region creation gesture.
  - Interactive grab handles across all shapes: 4 corner handles + 4 edge handles for Rectangle & Square, 4 cardinal handles for Circle, and 4 independently movable vertex handles for Custom freeform polygon.
  - Full region translation by dragging inside bounds, and single-drag redraw by dragging outside or tapping Reset.
  - Post-selection action bar with Share, QR Code ("QR Code scanning coming soon"), and OCR ("OCR coming soon") with zero external recognition dependencies.
- [x] **Cursor Precision Double-Tap Synthetic Click Injection** (`CursorManager`):
  - Restored reliable synthetic click delivery by solving Android `InputDispatcher` hit-test overlay interception.
  - Arms on second tap `ACTION_DOWN` and dispatches only upon physical `ACTION_UP` release.
  - Temporarily sets `FLAG_NOT_TOUCHABLE`, hides trackpad view (`visibility = View.GONE`), and delays dispatch by 80ms for Binder IPC synchronization.
  - Employs calibrated 50ms synthetic touch stroke with buffered touch restoration upon completion.

---

## Phase 13: Floating Window Mini-Apps (Ordered by Complexity & Difficulty)

### 13.0 — Floating Windows Foundation & Heavy Process Boundary (Completed)
- [x] **Hierarchy Window Manager & Multi-Process Boundary**:
  - Main-process `FloatingWindowManager` remains strictly authoritative for hierarchy, Z-order, focus, layout parameters, touch interception, magnetic snapping, and folding.
  - `HeavyFloatingHost` & `HeavyFloatingHostService` in `:heavy` establish on-demand, disposable host infrastructure for floating mini-app instances without Main having compile-time dependencies on individual mini-apps.
  - `MiniAppContract`: Formalized `MiniAppRecord`, `MiniAppInstance`, `MiniAppLifecycleState`, and `MiniAppFactory` contracts.
  - `DurableMiniAppStore`: Atomic JSON file persistence (`filesDir/floating_miniapps/`) preserving mini-app state across Heavy process death, Heavy process restart, and full app restarts.
  - `FloatingMiniAppBridge`: Main-side coordinator bridging lifecycle events to `:heavy` via minimal IPC intents while maintaining all window hierarchy and Z-ordering in Main.

- [x] **Privacy Curtain (Anti-Peep Mode)** (`PrivacyCurtainManager`): Full-screen darkening overlay with resizable transparent rectangular viewing box, 100% native touch pass-through for underlying apps, dark area swipe-to-reposition, long-press dismissal exit gate, opacity control, and on-demand toggle in Utilities.
- [x] **Redact Screenshot Dual-Engine** (`RedactScreenshotActivity`): Simultaneous support for Freehand Brush and Bounding Box Blocks with Blackout and Blur styles, unified chronological undo/clear history stack, and combined bitmap rendering.
- [x] **Scanner Tap-to-Join Custom Area System** (`SecureScreenScannerActivity`, `SecureCameraScannerActivity`, `ScannerSelectionHelper`): Blank-slate Custom polygon marking with discrete tap-to-tap vertex placement, interactive connection path, tap-to-join closing near origin or via action pill, post-join vertex grab handles, and multi-point arbitrary polygon bitmap cropping.
- [x] **Screen Capture & Recording Settings** (`ScreenCapSettingsScreen`, `SettingsActivity`, `ScreenshotActionModule`): Integrated "Record" item into Settings home and dedicated `ScreenCapSettingsScreen`. Manages SAF custom save location with persistable URI grants and reset button, screenshot delay slider (0–10s), screen record microphone audio toggle (`record_audio`), video resolution quality picker (720p, 1080p, Original), and connected delayed execution / SAF storage pipeline in `ScreenshotActionModule`.
- [x] **Link Element & Custom Tab Subsystem** (`CustomTabLauncher`, `LinkPickerActivity`, `ElementMetadataStore`, `SidebarItem.Link`): Full integration of verified HTTPS Link Elements with per-link Custom Tab browser/provider selection (dynamically resolving Chrome, Firefox, etc.), unified `CustomTabLauncher` execution across Sidebar views (`AppsPageView`, `HybridGridPageView`, `WidgetsGridPageView`), dedicated `LinkPickerActivity` management destination with FAB `+`, responsive grid, tap to open, long-press CRUD actions (Select, Open, Edit, Delete), and extensible non-invasive Account configuration.
- [x] **Site Brand Favicon Engine & Direct Add-to-Grid** (`FaviconFetcher`, `ElementMetadataStore`, `LinkPickerActivity`, `SidebarAppsManager`): High-resolution asynchronous brand favicon downloader (`FaviconFetcher`) caching site icons (e.g. Duolingo owl, ChatGPT, Google) to compact WebP disk cache (`icons_webp_cache/`) with fallback to Material globe (`ic_language`). Grid tap action streamlined to immediately select/place links into sidebar grid, with custom browser launch retained in the options menu.
- [x] **Continuous Fast-Add Subpage Architecture** (`ElementPlacementHelper`, `AddElementActivity`, `ActionPickerActivity`, `AppPickerActivity`, `LinkPickerActivity`, `HybridGridEditActivity`): Multi-add flow allowing rapid consecutive additions of apps, actions, and sites without kicking the user back to the edit grid on each selection. Subpages stay active with instant placement and visual toast confirmation, with immediate automatic synchronization on return.
- [x] **Scanner Area Selection Standard Android Share** (`FileProvider`, `file_paths.xml`, `ScannerSelectionHelper`, `AndroidManifest.xml`): Declared `FileProvider` component and paths mapping cache directories, resolving `IllegalArgumentException` on cropped screenshot/camera selections and enabling clean sharing to any external target app.
- [x] **E-Ink Paper Mode** (`EInkPaperFilterManager`, `DisplayHandler`, `ALL_UTILITIES_ACTIONS`): 100% Out-of-the-Box (Zero ADB required) eye-comfort screen overlay. Features calibrated Warm Sepia / Unbleached Paper Tint (`#54DCD4C0`) that completely extinguishes the harsh 6500K blue-light spike (450nm) and clamps glaring white points down to natural book-paper reflectance. Zero blur, zero grain, zero shaders for 100% razor-sharp typography and subpixel font readability. 100% native touch pass-through (`FLAG_NOT_TOUCHABLE`) with 0 ms added latency, persistent preference storage, and real-time active tile state indicator (`#E6C280`) in Sidebar.
- [x] **Standalone Force Stop Apps Element & On-Demand Container Sync** (`AppTrackerHelper`, `AppTrackerSettingsActivity`, `AppTrackerOpenerActivity`, `HybridGridPageView`, `ElementViewRenderer`, `AppsPageView`): Decoupled the Force Stop Apps utility element into a completely standalone button independent of whether an App Tracker sidebar page is present. Implemented container-isolated whitelist storage (`handle_${containerId}_force_stop_whitelist`). Added long-press "Edit" context menu option specifically for Force Stop Apps across Hybrid Grid tiles, folder popups, and standard Apps lists. Opens a focused setup interface (`AppTrackerSettingsActivity` in `force_stop_only` mode) displaying "Running" whitelist configuration with system/user filtering and "Permissions" guidance with loop testing. Strictly enforces sequential manual loop presentation ("No clicking. Only loop. User will click.") without accessibility automated clicking, and performs on-demand whitelist synchronization strictly within the same container when an App Tracker page is configured.
- [x] **Strict Container Isolation & First-Handle Invariant Architecture** (`HandleManager`, `PageManager`, `AppsPageView`, `SidebarEditActivity`, `ElementIdParser`, `ElementMetadataStore`): Hardened the two-process architecture (resident Main vs on-demand Heavy). Enforced strict container isolation across handles and gestures (`handle_${containerId}_*` and `sidebar_apps_${containerId}_*`), eliminating cross-container column/row and page deck bleed. Secured First-Handle Invariant (`handle_1` undeletable, permanent default sidebar gesture, and seamless container migration upon primary gesture reassignment while enabling multi-gesture coexistence). Validated on-demand `ElementIdParser` and `ElementActionRegistry` execution without resident runtime overhead in Main.
- [x] **Per-Container Gesture Action Flow & Sidebar Page Management** (`HandleSettingsScreen`, `ContainerPageManagementScreen`, `HandleManager`, `PageManager`, `AddElementActivity`): Streamlined gesture configuration into exactly two primary choices ("Sidebar Page" vs "Element directly"). "Sidebar Page" automatically resolves `containerId = HandleManager.getContainerId(handleId, gesture)` and routes to dedicated `ContainerPageManagementScreen` allowing users to view, add (from all 11 supported `PageFactory` types), delete, rename, reorder, and select opening face (`handle_${containerId}_selected_page`). "Element directly" launches `AddElementActivity` and connects the selected action/element cleanly via `HandleManager.configureGesture`. Preserves first handle startup invariant with `default_hybrid` strictly on handle 1 primary gesture and isolated defaults on all others.
- [ ] **Work Notes & Scratchpad** (`WorkNotesFloatingWindow`): Lightweight Room-backed markdown/scratchpad floating window with auto-save and responsive drag resize.
- [ ] **Floating Web Browser** (`FloatingBrowserWindow`): Android `WebView` overlay with back/forward history navigation stack, URL search bar, desktop mode toggle, and multi-tab state.

### 13.2 — Real-Time Sensors & Tactical Monitors (Tier 2 - Medium Difficulty)
- [ ] **Network Radar** (`NetworkRadarFloatingWindow`):
  - **Tactical Circular Radar**: Rotating $360^\circ$ sweep beam, concentric dBm distance rings, center device dot with nearby Wi-Fi & cellular tower blips.
  - **Dropdown Selector**: Switch between Wi-Fi and Cellular (SIM) scanning.
  - **Cross-Carrier Detection**: Scans and displays serving + neighboring telecom towers (AT&T, T-Mobile, Verizon, etc.) with MCC/MNC, PLMN, RSRP, and band identity via `TelephonyManager.getAllCellInfo()`.
  - **Interactive Multi-Touch Canvas**: Pinch-to-zoom (adjust dBm sensitivity range) and drag-to-pan.
  - **Retro Frequency Tuner Dial**: Vintage radio-style 2.4 GHz, 5 GHz, and 6 GHz spectrum dial.
  - **Network Controls**: Wi-Fi toggle, Data shortcuts, and live ping/latency gauge.
- [ ] **Floating Audio Recorder & Tools** (`AudioRecorderFloatingWindow`): Real-time waveform amplitude visualizer, recording pause/resume buffer, and local audio management.

### 13.3 — System Control & File Traversal (Tier 3 - Medium-High Difficulty)
- [ ] **Floating File Explorer** (`FileExplorerFloatingWindow`): Storage Access Framework (SAF) integration, internal/SD card navigation, fast file operations (copy, move, delete, rename, share), and MIME-type intent dispatching.
- [ ] **Virtual Cursor & Precision Trackpad** (`CursorFloatingOverlay`): Floating trackpad overlay with virtual mouse pointer, sensitivity scaling, and accessibility gesture injection.

### 13.4 — Document Engines & Developer Subsystems (Tier 4 - High Difficulty)
- [ ] **eReader Subsystem (EPUB & PDF)** (`EpubReaderFloatingWindow`): EPUB archive parser, pagination layout canvas, reading progress state, night mode/theming, font scale, and bookmarks.
- [ ] **Local Terminal & Termux Bridge** (`TerminalFloatingWindow`): Pseudo-terminal (PTY) process runner, ANSI color escape code parser, virtual keyboard bridge, and Termux Intent bridge.

### 13.5 — Isolated Appywork Module (Tier 5 - High Architectural Isolation)
- [ ] **Appywork Module** (`feature/appywork/`): Fully isolated and deprecatable vibe-coding execution engine with dedicated database, clean decoupled Intent triggers, and zero cross-module dependencies for clean excision once external AI Host is connected.

---

### 12.10 — Sidebar Page Identity, Opening Face & Container Isolation Corrections (Completed)
- [x] **Page Type Identity & Resolution**:
  - `PageTypes.resolvePageType`: Restricted to exact standard tokens (`HYBRID`, `HYBRID_GRID`, `home_grid`, `APPS`, `APPS_GRID`, `WIDGETS`, `WIDGETS_GRID`, `WIDGET`, `SINGLE_WIDGET`) and system timestamp prefixes (`apps_`, `widgets_grid_`, `widget_`, `hybrid_grid_`). Eradicated loose `.contains(...)` substring matching to prevent arbitrary IDs (`user_apps_notes`, `my_widgets_area`, `my_widget_view`) from misclassifying as built-in types.
  - Legacy `tools` type mapped cleanly to `HYBRID_GRID` without deleting constants or factory branches.
  - Display titles strictly resolved: "Apps Grid", "Widgets Grid", "Single Widget", and "Home Grid".
- [x] **Configured Opening Face vs. Session Selection**:
  - `PageManager.getOpeningFaceId`: Guarantees independent opening face per container, with fallback to clean handle / legacy `default_page_index`, defaulting to first page of container stack. Never falls back to session swiped pages.
  - `SidebarManager.openContainer`: Reopening a container strictly restores its configured opening face. Live swiping updates only runtime session selection (`saveSelectedPageId`).
- [x] **SidebarAppsManager Compound Page ID Extraction**:
  - Eradicated `substringAfterLast("_")` truncation. Introduced `parseContainerAndPageId` resolving gestures and compound IDs (`apps_grid_123`, `default_apps`) preserving full canonical IDs and migrating legacy keys without data loss.
- [x] **Hybrid & Widgets Grid Container Persistence Isolation**:
  - Container-scoped preferences (`handle_${containerId}_hybrid_grid_$pageId`, `handle_${containerId}_widgets_grid_$pageId`) with alt key and legacy migration preserving the resident Home Grid.
  - Wired `containerId` across `WidgetsGridEditActivity` and `HybridGridEditActivity` editors.

### 12.11 — Notification Sidebar DiffUtil & Scroll-Stability Correction (Completed)
- [x] **Notification DiffUtil Updates**: Replaced unconditional `notifyDataSetChanged()` with Android identity-aware `DiffUtil.calculateDiff` matching `StatusBarNotification.key` (with package/id/tag fallback) and comparing displayed notification content fields (`title`, `text`, `subText`, `bigText`, `postTime`). Prevents viewport jumps while preserving in-place card updates for rapidly updating notifications.
- [x] **Height Dispatch Guard**: Stabilized `loadNotifications()` with `lastDispatchedHeightPx` check, suppressing redundant `onHeightChanged` callbacks when calculated target height is unchanged to eliminate unnecessary WindowManager relayouts during scroll.

### 12.12 — Main → Heavy Sidebar Page-Runtime Split: Calculator Embedded-Surface Vertical Slice (Implemented — Awaiting Physical Retest)
- [x] **Cross-Process Embedded Surface Host (`SurfaceControlViewHost` / `SurfaceView`)**: Implemented Calculator cross-process embedded surface hosting gated on API 30+ (`Build.VERSION_CODES.R`). Main's `HeavyPagePlaceholderView` hosts a local `SurfaceView` (`setZOrderMediaOverlay(true)` and `PixelFormat.TRANSLUCENT`). When attached, it supplies its `hostToken: IBinder` and dimensions to `:heavy` across Binder.
- [x] **Typed Binder IPC (`createSurfacePackage` / `relayoutSurfaceHost` / `releaseSurfaceHost`)**: Extended `IHeavyHostContract`, `HeavyHostBinder`, `HeavyProcessHost`, and `HeavyProcessConnectionManager` with typed `Bundle` transactions transporting `SurfacePackage` (Parcelable) and `hostToken` (IBinder) directly without string serialization.
- [x] **Elimination of Secondary WindowManager Overlay on API 30+**: On API 30+, Calculator no longer creates a second `TYPE_APPLICATION_OVERLAY` window, eliminating independent screen-coordinate positioning, global Rect drift, and navigation-bar overlap. Main's Sidebar overlay is the sole WindowManager window.
- [x] **Main ↔ Heavy Height Synchronization & In-Place Relayout**: `CalculatorPageView` measures and reports desired content height across Binder (`page_height_changed`). Main updates `SidebarView` layout, and the `SurfaceView` resize triggers `relayoutSurfaceHost(width, height)` to update the embedded `SurfaceControlViewHost` viewport without recreating the view instance.
- [x] **Isolated Legacy Fallback**: Preserved legacy overlay fallback for API 26-29 without bumping application `minSdk`.
- [ ] **Physical On-Device Verification**: Calculator cross-process embedded-surface vertical slice implemented — awaiting physical verification on Android 15/API 35.

### 12.13 — Main → Heavy Sidebar Page-Runtime Split: Step 1 (Home Grid Residency & Heavy-to-Main Action IPC) (Implemented — Awaiting Physical Verification)
- [x] **Step 1A — Home Grid Residency Rule (`SidebarPageFactory.isResidentDefaultHybrid`)**:
  - Restricts Main-process residency to exactly ONE page: `config.id == "default_hybrid"` belonging to Handle 1's primary gesture/container (`handle_1_swipe_left`, `sidebar`, `handle_1`, or `handleManager.getFirstHandlePrimaryContainerId()`).
  - All secondary Home Grids, generated `hybrid_grid_*` instances, Home Grids on Handle 2+, and Home Grids on non-primary gestures now route to `HeavyPagePlaceholderView` without modifying Calculator or other page types.
- [x] **Step 1B — Heavy → Main Action Callback IPC**:
  - Extended `MainActionType` enum with `SET_DIMMED`.
  - Added typed convenience helpers on `HeavyProcessHost` (`requestCloseSidebar`, `requestSetDimmed`, `requestTriggerAction`).
  - Implemented Main execution in `HeavyProcessConnectionManager.dispatchMainAction` dispatching `CLOSE_WINDOW` (via `SidebarWindowCoordinator.closeSidebar()`), `SET_DIMMED` (via `SidebarWindowCoordinator.setSidebarDimmed()`), `TRIGGER_ACTION` (via `ElementActionDispatcher.handleSystemAction()`), and `OPEN_CONTAINER` (via `SidebarManager.openContainerById()`).
- [ ] **Physical On-Device Verification**: Awaiting physical testing of Handle 1 resident Home Grid, secondary grid placeholder routing, and Heavy-to-Main action dispatching.

### 12.14 — Main → Heavy Sidebar Split: Hybrid Grid (Implemented — Awaiting Physical Verification)
- [x] **Heavy Hybrid Grid Instantiation (`HeavyPageHostManager`)**:
  - Extended `HeavyPageHostManager` on both `createSurfacePackage` (API 30+ SurfaceControlViewHost) and `mountPage` (API 26-29 overlay fallback) to support `"hybrid_grid"`, `"default_hybrid"`, and `"tools"` page types.
  - Instantiates real `HybridGridPageView` in `:heavy` with `pageId`, `containerId`, `hostScope`, and wires action callbacks:
    - `onClose` -> `HeavyProcessHost.getInstance(context).requestCloseSidebar(containerId)`
    - `onDimSidebar` -> `HeavyProcessHost.getInstance(context).requestSetDimmed(dimmed, containerId)`
    - `onHeightChanged` -> `reportDesiredPageHeight(pageId, newH)`
  - Applies theme background (`applyThemeBackground`) with configured color, transparency, and corner radii matching the Sidebar theme.
- [x] **Home Grid Main-Residency & Zero-Page Container Invariant**:
  - Strictly enforced ownership rule: ONLY the original Handle 1 -> Swipe Left container (`handle_1_swipe_left`, `sidebar`, `handle_1`) gets the special `default_hybrid` Home Grid with its default starter tiles.
  - All other newly created Sidebar containers (secondary gestures on Handle 1, Handle 2+ containers) start with ZERO pages.
  - Eliminated automatic generation/cloning of `default_hybrid_<containerId>`.
- [x] **Add Page List & Terminology Alignment**:
  - Addable grid page type strictly displays and resolves as "Hybrid Grid", creating normal `hybrid_grid_*` pages running in `:heavy`.
  - "Home Grid" is reserved exclusively for the special resident page and never appears as an addable type.
- [x] **Truly Empty Hybrid Grid**:
  - Newly created Hybrid Grids start completely empty: zero starter tiles, zero dummy elements, zero empty-state text, rendering the clean themed background. Edit Mode remains the mechanism for populating it.
  - Storage isolation strictly enforced under `"handle_${containerId}_hybrid_grid_$pageId"`.
- [x] **Heavy Hybrid Grid Startup Geometry & Jerkiness Elimination**:
  - Called `loadWidgets()` upon instantiation in `:heavy` before SVCH measurement so `:heavy` reports legitimate initial desired height across Binder.
  - In Main `SidebarView.calculateTargetHeight`, reused legitimate cached height (`"handle_${containerId}_hybrid_grid_height_${page.id}"`) avoiding generic 280px fallbacks.
  - In `HeavyPagePlaceholderView`, deferred `SurfaceView.alpha = 1f` unmasking until stable bounds layout completes when initial dimensions differ.
- [x] **Horizontal Line Below Sidebar Top Bar Removal**:
  - Removed duplicated `setStroke` from `HybridGridPageView.applyThemeBackground`, preserving Main's ownership of the Sidebar shell/header chrome and eliminating the horizontal border under the top bar.
- [ ] **Physical On-Device Verification**: Awaiting physical testing of newly created Hybrid Grids on secondary gestures/handles running in `:heavy` via SurfaceControlViewHost.

### 12.15 — Automatic Initial Placement for Newly Created Sidebar Handles (Completed)
- [x] **Deterministic Non-Overlapping Placement Algorithm (`HandleManager.calculateInitialPosition`)**:
  - Gated exclusively to new handle creation (`createHandle()`); existing, saved, or reloaded handles strictly retain persisted coordinates without continuous auto-layout.
  - Same-edge collision avoidance: Inspects existing handles exclusively along the target edge (e.g. RIGHT vs LEFT). Opposite-edge handles do not alter positioning.
  - Spacing & Bounds Clamping: Applies 16dp minimum inter-handle spacing and clamps positions within usable bounds (`0.05f` to `0.95f`).
  - Hierarchical slot search:
    1. Preferred placement directly after/below the lowest existing handle on the edge.
    2. Inter-handle gap search if bottom bound is reached.
    3. Above/before the highest handle if interior space is saturated.
    4. Proportional largest-gap centering fallback for heavily crowded edges.
- [x] **Unit & Regression Test Coverage (`HandleInitialPlacementTest`)**:
  - Validated Handle 2 non-overlapping initial position below Handle 1 on the same edge.
  - Validated Handle 3 creation preserves Handle 1 and Handle 2 positions untouched.
  - Validated strict bounds enforcement (`0.05f`..`0.95f`) across sequentially added handles.
  - Validated custom manual positions are preserved without recalculation.
  - Validated independence of handles on opposing screen edges.
  - Validated reload/restart does not trigger re-placement.

### 12.16 — Main → Heavy Sidebar Split: Apps Grid + Widgets Grid + Single Widget (Completed)
- [x] **Main-Side Delegation (`SidebarPageFactory`)**:
  - Routed `"apps"`, `"apps_grid"`, `"widgets_grid"`, `"widgets"`, `"widget"`, and `"single_widget"` to `HeavyPagePlaceholderView(context, config, containerId)`.
  - Main keeps exclusively the single resident Home Grid (`default_hybrid` on Handle 1 -> Swipe Left -> first/default container).
  - Main owns the single Sidebar window/overlay, gesture tracking, container metadata, and navigation routing.
- [x] **Heavy Host Execution (`HeavyPageHostManager`)**:
  - API 30+ SurfaceControlViewHost (`createSurfacePackageApi30`) and API 26-29 overlay fallback (`mountPage`):
    - Apps Grid: instantiates `AppsPageView` with container-isolated `SidebarAppsManager` (`sidebar_apps_${containerId}_$pageId`), action callbacks (`onCloseSidebar`, `onDimSidebar`, `onHeightChanged`), theme background (`applyThemeBackground`), and initial active-apps data binding without falling back to all installed apps.
    - Widgets Grid: initializes `AppWidgetHelper.startListening(context)` in `:heavy`, instantiates `WidgetsGridPageView` with `pageId`, `containerId`, `onClose` IPC callback, `onHeightChanged`, and `applyThemeBackground`.
    - Single Widget: initializes `AppWidgetHelper.startListening(context)` in `:heavy`, instantiates `WidgetPageView` with robust ID parsing (`removePrefix("widget:").removePrefix("widget_").removePrefix("single_widget_")`), `onHeightChanged`, and `applyThemeBackground`.
  - Geometry & Sizing: Reused `getCurrentHeightPx()` for dynamic child measurement and Binder event reporting (`reportDesiredPageHeight`).
- [x] **Theme Background & Styling Synchronization**:
  - Added `applyThemeBackground(bgColor, isRight)` and `themeBackgroundDrawable` across `AppsPageView`, `WidgetsGridPageView`, and `WidgetPageView` ensuring identical visual appearance, corner radii, and transparency to the Main Sidebar shell.
- [x] **Unit & Regression Test Coverage (`HeavyAppWidgetSplitTest`)**:
  - Validated Apps Grid, Widgets Grid, and Single Widget routing to Heavy.
  - Validated exact `default_hybrid` Home Grid remains resident in Main.
  - Validated secondary/other grids remain in Heavy.
  - Validated storage and container isolation across containers and pages.
  - Validated Calculator residency remains unchanged.
  - Validated Internet Speed Monitor is untouched.

### 12.17 — Main → Heavy Sidebar Split: Compass (Completed)
- [x] **Main-Side Delegation (`SidebarPageFactory`)**:
  - Routed `"compass"` to `HeavyPagePlaceholderView(context, config, containerId) { onHeightChanged(it) }`.
  - Main preserves exclusively the single resident Home Grid (`default_hybrid` on Handle 1 -> Swipe Left -> first/default container).
  - Main owns the single Sidebar window/overlay, gesture tracking, container metadata, and navigation routing.
- [x] **Heavy Host Execution (`HeavyPageHostManager`)**:
  - API 30+ SurfaceControlViewHost (`createSurfacePackageApi30`) and API 26-29 overlay fallback (`mountPage`):
    - Instantiates `CompassPageView` in `:heavy` with dynamic height reporting via Binder (`reportDesiredPageHeight`).
    - Applies Sidebar theme background and corner radii (`applyThemeBackground`).
  - Height & Geometry: Dynamic child measurement via `getCurrentHeightPx()`.
- [x] **Sensor Registration & Lifecycle Management (`CompassPageView`)**:
  - Sensors (`TYPE_ACCELEROMETER`, `TYPE_MAGNETIC_FIELD`) are registered only while Compass is active in Heavy (`startListening()`).
  - Registered upon window attachment (`onAttachedToWindow()`) or page selection (`onPageSelected()`).
  - Completely unregistered when detached (`onDetachedFromWindow()`), unselected (`onPageUnselected()`), or when the Heavy embedded host is released (`releaseSurfaceHost()`, `unmountPageInternal()`).
  - Safe null handling for sensor manager and defensive programmatic fallback for test environments.
- [x] **Unit & Regression Test Coverage (`HeavyCompassSplitTest`)**:
  - Validated Compass routes to Heavy in Main.
  - Validated exact `default_hybrid` Home Grid remains resident in Main.
  - Validated Calculator, Apps Grid, Widgets Grid, and Single Widget remain in Heavy.
  - Validated secondary grids remain in Heavy.
  - Validated sensor lifecycle starts on attach/select and unregisters on detach/unselect/release.
  - Validated theme background and height measurement.
  - Validated Internet Speed Monitor is untouched.

### 12.18 — Main → Heavy Sidebar Split: Scheduler (Completed)
- [x] **Main-Side Delegation (`SidebarPageFactory`)**:
  - Routed `"scheduler"` to `HeavyPagePlaceholderView(context, config, containerId) { onHeightChanged(it) }`.
  - Main preserves exclusively the single resident Home Grid (`default_hybrid` on Handle 1 -> Swipe Left -> first/default container).
  - Main owns the single Sidebar window/overlay, gesture tracking, container metadata, and navigation routing.
- [x] **Heavy Host Execution (`HeavyPageHostManager`)**:
  - API 30+ SurfaceControlViewHost (`createSurfacePackageApi30`) and API 26-29 overlay fallback (`mountPage`):
    - Instantiates `SchedulerPageView` in `:heavy` with dynamic height reporting via Binder (`reportDesiredPageHeight`).
    - Applies Sidebar theme background and corner radii (`applyThemeBackground`).
  - Height & Geometry: Dynamic child measurement via `getCurrentHeightPx()`.
- [x] **Persistence & Data Isolation (`SchedulerPageView`)**:
  - Data stored in standard Room database (`litereader_db`) using `SchedulerTask` entity and `SchedulerTaskDao`.
  - Heavy and Main processes safely share the SQLite database via Room with zero database duplication.
  - Alarms, tasks, and stored reminders survive Sidebar closures and `:heavy` process teardown/kills.
  - Observers and background Coroutine collectors cleanly unregistered when detached (`onDetachedFromWindow()`), unselected (`onPageUnselected()`), or when the host is released (`cleanup()`).
- [x] **Unit & Regression Test Coverage (`HeavySchedulerSplitTest`)**:
  - Validated Scheduler routes to Heavy in Main.
  - Validated exact `default_hybrid` Home Grid remains resident in Main.
  - Validated Calculator, Apps Grid, Widgets Grid, Single Widget, and Compass remain in Heavy.
  - Validated secondary grids remain in Heavy.
  - Validated Scheduler lifecycle, coroutine job cancellation, and safe cleanup.
  - Validated theme background and height measurement.
  - Validated SchedulerTask entity integrity for cross-process persistence.
  - Validated Internet Speed Monitor is untouched.
  - Validated Scheduler FAB click switches from list to editor and cancel restores list (`testFabClickShowsEditUiAndCancelingRestoresList`).
- [x] **Scheduler FAB Touch Fix (`page_scheduler.xml`, `SchedulerPageView`)**:
  - Configured `android:elevation="8dp"`, `android:translationZ="8dp"`, `android:clickable="true"`, and `android:focusable="true"` on `fab_add` to trigger `hasChildWithZ()` pre-ordering in ViewGroup touch dispatch.
  - Enforced `fabAdd?.bringToFront()` on initialization and in `hideEditUi()` to place `fab_add` at top of child index order above `ll_list`.
  - Re-verified test suite and assembleDebug cleanly.

### 12.19 — Main → Heavy Sidebar Split: App Tracker & Media Player (Completed)
- [x] **Main-Side Delegation (`SidebarPageFactory`)**:
  - Routed `"app_tracker"` and `"media_player"` to `HeavyPagePlaceholderView(context, config, containerId) { onHeightChanged(it) }`.
  - Main preserves exclusively the single resident Home Grid (`default_hybrid` on Handle 1 -> Swipe Left -> first/default container).
  - Main owns the single Sidebar window/overlay, gesture tracking, container metadata, and navigation routing.
- [x] **Heavy Host Execution (`HeavyPageHostManager`)**:
  - API 30+ SurfaceControlViewHost (`createSurfacePackageApi30`) and API 26-29 overlay fallback (`mountPage`):
    - Instantiates `AppTrackerPageView` and `MediaPlayerPageView` in `:heavy` with dynamic height reporting via Binder (`reportDesiredPageHeight`).
    - Applies Sidebar theme background and corner radii (`applyThemeBackground`).
    - Driven lifecycle (`onPageSelected()` / `onPageUnselected()`) upon host attachment and release.
- [x] **App Tracker Physical Bug Fixes & Swipe Regression Resolution**:
  - Eliminated initial bottom-navigation overlap: aligned `SidebarView.kt` initial fallback heights (`"app_tracker" -> 180`, `"media_player" -> 100`) and clamped `AppTrackerPageView` to 180dp-360dp.
  - Fixed empty results on permission grant: added initial `loadData()` call in `AppTrackerPageView.init`, and added `onWindowFocusChanged` / `onWindowVisibilityChanged` to immediately refresh permission and reload data upon return from system Settings.
  - Enhanced `AppTrackerHelper.getRecentApps()`: added `MOVE_TO_FOREGROUND` event checking, `queryAndAggregateUsageStats`, and 7-day query fallback when 24h events are empty.
  - Restored horizontal swiping: removed `android:clickable="true"` and `android:focusable="true"` from `item_app_tracker_running_icon.xml` and `item_app_tracker_grid.xml`. Configured `tv_empty` as `match_parent` centered and hid `RecyclerView` when empty so unhandled touches pass through to host ViewPager2.
- [x] **Media Player Physical Bug Fixes & Swipe Regression Resolution**:
  - Implemented real layout measurement in `MediaPlayerPageView.getCurrentHeightPx()` (80dp-120dp), preventing 360dp placeholder ballooning.
  - Initialized `updateState()` and `updateUI()` in `init` and `onAttachedToWindow()`, correctly displaying unclickable "Nothing playing" banner when inactive so touches fall through to host ViewPager2.
- [x] **Unit & Regression Test Coverage (`HeavyAppTrackerSplitTest`, `HeavyMediaPlayerSplitTest`)**:
  - Validated App Tracker and Media Player routing to Heavy in Main (7/7 tests passed each).
  - Validated exact `default_hybrid` Home Grid remains resident in Main.
  - Validated Calculator, Compass, Scheduler, Apps, and Widgets remain in Heavy.
  - Validated secondary grids remain in Heavy.
  - Validated theme background, dynamic height measurement, and idempotent cleanup.
  - Validated Internet Speed Monitor is untouched.

---

## Phase 13: Floating Mini-App Framework Optimization — Resize Efficiency & Keyboard Position Stability (Completed)
- [x] **Safe Display Bounds Lookup Caching (`FloatingMiniAppWindow.kt`)**:
  - Eliminated repetitive WindowManager display inquiries (previously ~202 queries during a single drag session).
  - Cached `safeDisplayBounds` at gesture start (`ACTION_DOWN`) for both sticker resize (`setupStickerResizeTouch`) and top-bar drag (`setupTopBarGestures`).
  - Added 1-second TTL memoization to `getSafeDisplayBounds(context)` so high-frequency mid-gesture coordinate clamping never queries WindowManager repeatedly.
- [x] **Surface Relayout Suppression & Post-RESIZE_END Settlement (`FloatingMiniAppWindow.kt`)**:
  - Resolved root cause of 29 relayouts occurring after `RESIZE_END`: `WindowManager.updateViewLayout` during active resizing triggered asynchronous `SurfaceHolder.Callback.surfaceChanged` events which queued up on the main looper.
  - Added `isResizing` state flag to suppress intermediate `surfaceChanged` callbacks during active resize and drop stale queued callbacks whose dimensions do not match authoritative `bounds`.
  - Added ~60fps / 8px delta throttling to embedded SurfaceControl IPC relayout during active drag to maintain 120fps smooth window frame motion without Binder buffer congestion.
  - Added single unconditional surface settlement upon `ACTION_UP` (`RESIZE_END`), guaranteeing 0 subsequent relayouts arrive after resize concludes.
- [x] **Keyboard Position Stability & Reversible Elevation (`FloatingMiniAppWindow.kt`, `FloatingMiniAppBridge.kt`)**:
  - Shift calculations anchored strictly against `normalFloatingBounds.y` (`baselineY`) rather than live `layoutParams.y`, eliminating recursive upward drift and offscreen pushing.
  - `normalFloatingBounds` is strictly invariant to keyboard state; all keyboard elevation adjustments are recorded in temporary state (`imeShiftOffsetY` / `isImeShifted`).
  - Restores exact original `normalFloatingBounds.y` resting coordinates upon keyboard dismissal.
  - Reset IME shift offset smoothly on manual top-bar drag without visual jumping.
  - `FloatingMiniAppBridge.sleepMiniApp` persists `w.normalFloatingBounds`, guaranteeing durable recovery records never capture temporary keyboard coordinates.
  - `toggleMaximize()` captures `normalFloatingBounds` into `maximizeRestoreBounds`, preventing keyboard offsets from corrupting maximize/restore bounds.
- [x] **Unit & Regression Test Coverage (`FloatingMiniAppFrameworkTest.kt`)**:
  - Validated cached display bounds across rapid inquiries.
  - Validated clean resize settlement with 0 continuing relayouts post `RESIZE_END`.
  - Validated keyboard open applies temporary shift without overwriting `normalFloatingBounds`.
  - Validated fold while keyboard is open persists unshifted `normalFloatingBounds`.
  - Validated maximize while keyboard is open preserves unshifted restore bounds.
  - All 27 tests in `FloatingMiniAppFrameworkTest` passing.
- [x] **Test Notes IME Bridge Repair & Window Attachment Crash Fix (`FloatingMiniAppWindow.kt`, `TestNotesMiniApp.kt`)**:
  - Eliminated `NullPointerException at FloatingMiniAppWindow$createImeBridgeEditText$1.setText` by removing constructor-unsafe `setText()` override and manual `stubWatchers` dispatch from anonymous `EditText` subclass.
  - Attached native Android `TextWatcher` post-construction with echo loop protection (`isSyncingImeBridge`).
  - Enforced real Android `Editable` in production runtime, isolating stub fallbacks to headless JVM mock environments (`super.getText() == null`).
  - Enforced explicit `TextAuthority` tracking (distinguishing Snapshot, Heavy, and IME Bridge) and empty-note preservation across fold/sleep/wake cycles.
  - Implemented real system window focus gate (`hasWindowFocus() == true`) before requesting soft input.
  - Added precision diagnostic checkpoints without logging note content.
  - Added regression test `testCreateImeBridgeEditTextAndContentViewNoCrash()`; all 33 tests in `FloatingMiniAppFrameworkTest` passing.
- [x] **Log Keeper Floating Mini-apps Filter Pill (`FloatingMiniAppLogFilter.kt`, `LogKeeperActivity.kt`)**:
  - Implemented pure unit-testable log filter `FloatingMiniAppLogFilter` matching dedicated tags (`FloatingMiniAppBridge`, `FloatingMiniAppWindow`, `FloatingMiniAppLauncherBubble`, `HeavyFloatingHost`, `HeavyFloatingHostService`, `TestNotesImeDebug`), contextual shared component tags (`miniapp_`, `test_notes`, `speed_monitor`, `ebook_reader`), and uncaught crash report blocks.
  - Added Material 3 `FilterChip` labelled "Floating Mini-apps" with test tag `filter_floating_mini_apps` on Diagnostic and Crash Reports tabs.
  - Preserved filter state across tab switching and log refreshes; hidden on Running tab.
  - Integrated filtered export into "Copy Logs" and TopAppBar "Share Logs" actions.
  - Added `FloatingMiniAppLogFilterTest` unit tests covering all matching, exclusion, multiline exception, and empty state cases.
- [x] **Phase 13.4: Shared Floating Mini-App Framework Stabilization & Note Mini-App (Stages B–E)**:
  - **Stage B — Shared Shell & 16dp Rounded Clipping (`FloatingMiniAppWindow.kt`)**:
    - Enforced 16dp rounded corner clipping across all four corners using `ViewOutlineProvider` with `setRoundRect` and `clipToOutline = true` on `rootCardView` and `contentContainer`.
    - Protected embedded `SurfaceView` using reflection-safe `setCornerRadius(cardCornerRadius)` on API 30+ with `outlineProvider` + `clipToOutline = true` fallback.
    - Updated `applyCardCornerRadius` to dynamically remove clipping (0dp radius) upon safe maximize and restore 16dp radius upon restore, cleanly filling display bounds.
    - Implemented 48dp bottom clearance padding in `NoteMiniAppInstance` content view and fallback editor, ensuring scrollable text and cursor are never obscured by the bottom-right 3-control sticker `[ ×  —  ↘ ]`.
    - Maintained top-bar drag with touch-slop detection, double-tap maximize/restore, and 36dp launcher bubble.
  - **Stage C — Single Input Route & Focus Stabilization (`FloatingMiniAppWindow.kt`, `TestNotesMiniApp.kt`)**:
    - Maintained Main-owned IME bridge (`createImeBridgeEditText`) and Heavy-owned editor (`EmbeddedNoteEditText`) via narrow Binder IPC.
    - Strictly removed `JvmStubEditable` fallback from production `getText()`, always exposing real Android `Editable` (`super.getText()`) for native IME and selection handling.
    - Stabilized overlay window focus sequence: clears `FLAG_NOT_FOCUSABLE` and `FLAG_ALT_FOCUSABLE_IM`, requests bridge view focus, listens to legitimate WMS window focus transitions, and requests soft-input without artificial synthetic touch simulation.
    - Bidirectional synchronization (`syncLiveTextToHeavy` and `applyLiveTextFromHost`) with `isSyncingImeBridge` and `isApplyingHostUpdate` flags preventing echo loops.
  - **Stage D — Production Note Mini-App in Heavy (`TestNotesMiniApp.kt`, `FloatingMiniAppWindow.kt`, `HeavyFloatingHost.kt`)**:
    - Renamed displayed title to "Note" and registered `"note"` alias alongside `"test_notes"`.
    - Added compact "Edit" control button in top bar and toggleable in-window Edit popover card (`editPopoverView`).
    - Implemented formatting options: Font Size (- / +), Font Style (Regular, Bold, Italic, Monospace), Word wrap toggle (ON/OFF), and Line spacing (1.0x, 1.2x, 1.5x).
    - Implemented content-scoped Undo/Redo stack (`undoStack`, `redoStack`) and Clear action in `:heavy` Note runtime.
    - Persisted formatting attributes durably in `DurableMiniAppStore` snapshots across Sleep (Fold) / Wake cycles; discarded cleanly on Close.
  - **Stage E — Test Validation (`FloatingMiniAppFrameworkTest.kt`)**:
    - Added unit tests for 16dp rounded clipping, Note formatting controls, undo/redo state stack, and snapshot serialization across sleep/wake.
    - All 36 tests in `FloatingMiniAppFrameworkTest` and all tests in `FloatingMiniAppLogFilterTest` passing 100%. Compile succeeded.
- [x] **Phase 13.5: Main-Process Isolated Keyboard Diagnostic (Test A)**:
  - Added temporary isolated diagnostic mode directly inside `FloatingMiniAppWindow` using the existing WindowManager overlay attachment (no Dialog, no Activity, no second window).
  - Toggled via dedicated top-bar `[Test A]` / `[Exit Diag]` pill button.
  - Temporarily detaches and disables embedded `SurfaceView` / fallback view so it cannot intercept touches or obscure the test view.
  - Visible Main-owned multiline `EditText` with native `InputConnection` target, disconnected from Heavy and IPC.
  - Genuine WindowManager overlay focus sequence upon touch: records `ACTION_DOWN`/`ACTION_UP`, clears `FLAG_NOT_FOCUSABLE` and `FLAG_ALT_FOCUSABLE_IM` via `applyLayoutUpdate()`, waits for actual WMS `onWindowFocusChanged(true)`, verifies view attachment & token, logs `onCreateInputConnection()`, and invokes `showSoftInput()`.
  - Updated IME insets listener to prevent premature restoration of `FLAG_NOT_FOCUSABLE` while awaiting keyboard animation.
  - Zero-PII diagnostics logging touch events, focus transitions, window flags, connection nullability, and text length/selection.
  - Unit test `testIsolatedMainProcessImeDiagnosticModeTestA()` added; 37/37 framework tests passing 100%.

- [x] **Phase 13.6: Floating Mini-App Initial Rendering Delay & Transparent Flash Fix**:
  - Diagnosed initial transparency / un-rendered hole on opening/waking floating mini-apps.
  - Added zero-PII timing logs in `HeavyPageHostManager`, `TestNotesMiniApp`, and `FloatingMiniAppWindow` using `SystemClock.elapsedRealtime()`.
  - Added neutral 16dp rounded content placeholder (`contentPlaceholderView`) in `FloatingMiniAppWindow` container to mask un-rendered surface frames cleanly.
  - Primed `lastRelayoutWidth = w` and `lastRelayoutHeight = h` in `tryAttachSurfacePackage()` to eliminate redundant IPC relayout during initial frame rendering.
  - Implemented `miniapp_frame_committed` signal via `registerFrameCommitCallback` on Q+ in `:heavy` to notify Main when the first frame is drawn; Main unmasks surface and dismisses placeholder with 400ms fallback.
  - Unit test `testContentPlaceholderAndFrameRevealTiming()` added; all 38 framework tests passing 100%.

- [x] **Phase 13.7: Heavy Note Editor Typing Fix (IME Insets Focus Race & IMM Binding)**:
  - Repaired WindowInsets focus race in `FloatingMiniAppWindow.kt`: added `isNoteEditorImeActive`, `isImeVisibleInInsets`, and `isAwaitingImeVisibility` state tracking, preventing `setOnApplyWindowInsetsListener` from prematurely re-arming `FLAG_NOT_FOCUSABLE` before soft keyboard opens.
  - Implemented `deactivateEmbeddedEditorIme()` to safely restore `FLAG_NOT_FOCUSABLE`, clear bridge focus, and hide IME when keyboard is dismissed by user, or when the window folds/hides (`onHide()`) or closes (`onDestroy()`).
  - Repaired InputMethodManager target binding in `triggerImeFocusOnBridge()`: verified bridge view attachment and valid window token, invoked `imm.viewClicked(bridge)` and `imm.restartInput(bridge)` before `imm.showSoftInput()`, and added `windowInsetsController?.show(WindowInsets.Type.ime())` on API 30+.
- [x] **Phase 13.8: Note Launch Routing Diagnostics & Heavy Editor Touch Delivery Tracing**:
  - **Problem A — Note Launch Routing Diagnostics (`SidebarView.kt`, `SidebarEditNavigator.kt`, `AddElementActivity.kt`, `SidebarEditActivity.kt`, `HybridGridEditActivity.kt`, `ElementActionDispatcher.kt`, `MiniAppManager.kt`, `FloatingMiniAppBridge.kt`)**:
    - Added minimal zero-PII logging tagged `MiniAppLaunchRouting` and `ROUTING_DIAG` across the full action and navigation pipeline.
    - Captures exact session IDs, container IDs, page IDs, target edit activities, element picker clicks (distinguishing `"system:test_notes"`), activity result intents, and caller stack traces (`class.method:line`) on `MiniAppManager.toggleMiniApp`, `FloatingMiniAppBridge.openMiniApp`, and `FloatingMiniAppBridge.toggleMiniApp`.
    - Added `MiniAppLaunchRouting` to `FloatingMiniAppLogFilter` so routing diagnostics appear under the "Floating Mini-apps" filter chip in Log Keeper.
  - **Problem B — Touch Delivery Path Tracing & Single IME Authority (`FloatingMiniAppWindow.kt`, `HeavyPageHostManager.kt`, `TestNotesMiniApp.kt`)**:
    - Established end-to-end 6-boundary zero-PII touch logging:
      * Boundary 1: `TOUCH_BOUNDARY_1_MAIN_OVERLAY` on `FloatingMiniAppWindow.rootCardView.dispatchTouchEvent`.
      * Boundary 2: `TOUCH_BOUNDARY_2_MAIN_SURFACE_VIEW_DISPATCH` & `TOUCH_BOUNDARY_2_MAIN_SURFACE_VIEW_TOUCH` on `SurfaceView`.
      * Boundary 3: Added `"note"` pageType support and logged `TOUCH_BOUNDARY_3_HEAVY_HOST_LIFECYCLE` on `HeavyPageHostManager.host.setView`.
      * Boundary 4: `TOUCH_BOUNDARY_4_HEAVY_ROOT_DISPATCH` & `TOUCH_BOUNDARY_4_HEAVY_ROOT_TOUCH` on root layout in `TestNotesMiniApp`.
      * Boundary 5: `TOUCH_BOUNDARY_5_HEAVY_EDITOR_DISPATCH` on `EmbeddedNoteEditText.dispatchTouchEvent`.
      * Boundary 6: `TOUCH_BOUNDARY_6_HEAVY_EDITOR_TOUCH` on `EmbeddedNoteEditText.onTouchEvent` for both `ACTION_DOWN` and `ACTION_UP`.
    - Focus Priming on `ACTION_DOWN`: Triggered `onEditorFocusedOrTouched()` on `ACTION_DOWN` so Main clears `FLAG_NOT_FOCUSABLE` and arms focus in advance of the `ACTION_UP` lift event.
    - Single IME Authority: Removed competing `imm.showSoftInput(this)` and `windowInsetsController?.show` in `:heavy` within windowless `SurfaceControlViewHost`. Main alone owns window focus and soft keyboard activation.
    - Added unit tests `testTouchDeliveryBoundariesAndActionDownPriming()` and `testRoutingDiagnosticsLoggingAndFilterTag()` in `FloatingMiniAppFrameworkTest.kt`; all 42 framework tests passing 100%. Compile succeeded.

- [x] **Phase 14.1: Controlled Heavy-Native IME Experiment (`FloatingMiniAppWindow.kt`, `TestNotesMiniApp.kt`)**:
  - Implemented reversible `EXPERIMENT_HEAVY_NATIVE_IME` toggle to test whether Heavy inside `SurfaceControlViewHost` could directly receive native `InputConnection` when Main acts strictly as the focus carrier.
  - Demonstrated that Android's `WindowlessWindowManager` does not grant top-level window tokens for IME handoff across process boundaries, establishing that Main must host the authoritative `InputConnection`.

- [x] **Phase 14.2: Complete Note Mini-App Rebuild — Main-Owned Native Editor Architecture (`FloatingMiniAppWindow.kt`, `TestNotesMiniApp.kt`)**:
  - **Single Authoritative Editor**: Replaced cross-process `SurfaceControlViewHost` input forwarding and `alpha = 0.01f` bridge hack with a single, visible, authoritative native multiline `EditText` hosted directly in Main's `contentContainer` for `"note"` and `"test_notes"`.
  - **Native IME & Gboard Integration**: Android `InputMethodManager` and Gboard bind directly to the visible Note editor with a valid top-level window token (`WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`). Full support for typing, word suggestions, swipe typing, cursor positioning, and selection handles with zero IPC latency.
  - **Window Focus & Flag Management**: Touch `ACTION_DOWN` immediately clears `FLAG_NOT_FOCUSABLE` and `FLAG_ALT_FOCUSABLE_IM`, updates window layout, and requests soft input. Dismissal or fold/sleep safely restores `FLAG_NOT_FOCUSABLE`.
  - **Shared Floating Shell Integrity**: Preserved 16dp rounded card clipping, top-bar drag with touch-slop detection, double-tap maximize/restore, persistent normal bounds, 36dp launcher bubble, and bottom-right sticker controls `[ ×  —  ↘ ]`. Single "Edit" button in top bar; obsolete "Test A" button removed.
  - **Editor Layout & Clearance**: Single continuous multiline editable area filling the viewport with `48dp` bottom clearance padding to guarantee content and cursor are never obscured by `[ ×  —  ↘ ]`.
  - **Formatting & Actions**: In-window formatting popover controls Font Size (- / +), Font Style (Regular, Bold, Italic, Monospace), Word Wrap toggle (ON/OFF), and Line Spacing (1.0x, 1.2x, 1.5x) directly on the editor, with Note-scoped Undo / Redo and Clear.
  - **State Persistence & Lifecycle**: Text and formatting attributes are durably saved into `DurableMiniAppStore` on Sleep (Fold) and restored on Wake. Close with discard permanently deletes snapshot content.
  - **Non-Note Mini-App Preservation**: `SurfaceView` and `SurfaceControlViewHost` continue to serve other embedded mini-apps (Reader, Speed Monitor, future PWAs) as designed.
  - **Testing**: All 43 tests in `FloatingMiniAppFrameworkTest` passing 100%. Compile succeeded.

- [x] **Phase 14.3: Main-Only Multi-Instance Note Mini-App Architecture (`ElementPlacementHelper.kt`, `ElementIdParser.kt`, `ElementActionDispatcher.kt`, `MiniAppManager.kt`, `FloatingMiniAppBridge.kt`, `FloatingMiniAppWindow.kt`)**:
  - **Zero Heavy Dependency**: Note mini-app never acquires, starts, communicates with, or depends on `:heavy`. All IPC command sending (`sync_live_text`, `note_format`, `note_undo`, `START_OPERATION`, `PAUSE_OPERATION`, `STOP_OPERATION`), Heavy consumer acquisition (`acquireConsumer("miniapp_$instanceId")`), and `SurfaceView` / `SurfaceControlViewHost` dependencies are completely bypassed for `"note"` and `"test_notes"`. Result: 0 MB memory in `:heavy`, 0 IPC latency, 0 cross-process crash risk.
  - **Multi-Instance Support Driven by Placement Identity**:
    - Each placed Note element receives a stable unique identity: `system:test_notes:note_${timestamp}_${uuid}` generated at placement time via `ElementPlacementHelper.assignInstanceIfNote`.
    - `ElementIdParser.parseSystemAction` parses `baseAction` (`test_notes`) while preserving full action ID (`test_notes:note_...`) on `SidebarItem.SystemAction`.
    - `ElementActionDispatcher` and `MiniAppManager` route the placement's `instanceId` directly to `FloatingMiniAppBridge.toggleMiniApp(pageType, instanceId)`.
    - Different Note elements open separate independent floating Note windows simultaneously.
    - Tapping an existing Note element brings its window forward or wakes its folded bubble without creating duplicate instances.
    - Each Note instance owns independent text, formatting (Font size, style, word wrap, line spacing), window bounds, folded launcher bubble, undo/redo stack, and snapshot in `DurableMiniAppStore`.
    - Closing one Note instance deletes only its own snapshot, leaving all other open or sleeping Note windows intact.
    - Legacy Note preservation: Clicking or restoring legacy `system:test_notes` without an instance ID seamlessly routes to `miniapp_test_notes` without data loss or crash.
  - **Direct Native Input in Main**: Native multiline `EditText` hosted in `contentContainer` with direct Android IME and Gboard bindings under `TYPE_APPLICATION_OVERLAY`.
  - **Shared Floating Shell Integrity**: Preserves `FloatingWindowManager`, `FloatingMiniAppWindow`, `FloatingMiniAppBridge`, `FloatingMiniAppLauncherBubble`, top-bar drag, maximize/restore, bottom-right sticker controls `[ ×  —  ↘ ]`, and `DurableMiniAppStore`.
- [x] **Phase 14.4: Note Persistence Reliability & Undo/Redo Restoration (`NotePersistenceCoordinator.kt`, `FloatingMiniAppWindow.kt`, `FloatingMiniAppBridge.kt`)**:
  - **Continuous Debounced Persistence**:
    - Implemented `NotePersistenceCoordinator` encapsulating continuous saving for typing edits and formatting updates through `DurableMiniAppStore`.
    - Safe 400ms debounce (`scheduleDebouncedSave(400L)`) eliminates disk I/O thrashing during fast typing.
    - Single-threaded background executor (`NotePersistenceWorker`) offloads snapshot encoding and disk I/O off the main UI thread.
    - Synchronous flush (`flushPendingSave()`) executed immediately before fold (`sleepMiniApp`), close (`closeMiniApp`), window hide (`onHide`), and window teardown (`onDestroy`), guaranteeing zero lost keystrokes.
    - Monotonic sequence numbers (`saveSequence` vs `latestCommittedSequence`) prevent delayed background write tasks from overwriting newer flushes.
  - **Undo / Redo History Persistence**:
    - Bounded history limit (max 50 states) serialized cleanly into `undo_history` and `redo_history` as JSON arrays inside the durable snapshot payload.
    - Full restoration of undo/redo stacks when reopening or waking Note from sleep.
    - Strict editing behavior: editing after undo clears redo history; clear is undoable (pushes pre-clear text to undo stack); programmatic state restoration (`isRestoringState` flag) does not generate duplicate undo entries.
    - Multiline text, double quotes, formatting characters, and emojis safely preserved via `JSONArray` escaping.
    - Graceful fallback: legacy snapshots without history keys load with empty undo/redo stacks with zero migration crashes.
- [x] **Phase 14.5: Serialize Note Persistence and Safe Lifecycle Flush (`NotePersistenceCoordinator.kt`, `FloatingMiniAppBridge.kt`, `DurableMiniAppStore.kt`)**:
  - **Per-Instance Commit Serialization**:
    - Serialized commit transactions per Note instance via `instanceCommitLock = getInstanceLock(instanceId)` using a `ConcurrentHashMap<String, Any>`.
    - Protects the complete commit pipeline (stale check, snapshot loading, payload merge, and file writing) as one unified atomic transaction.
    - Prevents interleaving between asynchronous debounced saves and synchronous lifecycle flushes without introducing a global persistence bottleneck between independent Note instances.
  - **Safe UI Thread Lifecycle Flushing**:
    - Eliminated main-thread disk I/O during `flushPendingSave()`.
    - When called from the Android main thread, offloads disk writes to `backgroundExecutor` and safely awaits completion using a bounded `Future.get(timeoutMs, TimeUnit.MILLISECONDS)` with a 2000ms safety timeout to prevent deadlocks.
    - Preserves durability guarantee: fold (`sleepMiniApp`) and non-deleted close (`closeMiniApp`) await background commit before concluding the lifecycle transition.
    - Explicit failure handling: returns `false` on save failure or timeout; aborts fold transaction if durable write fails.
  - **Clear Interaction Optimization**:
    - Removed redundant synchronous flush from `prepareClear()`. Clear pushes pre-clear state to undo stack, empties latest text, and schedules debounced save through the normal async persistence path.
  - **Testing**: 59/59 tests in `FloatingMiniAppFrameworkTest` passing 100%. `compile_applet` succeeded cleanly.
- [x] **Phase 14.6: Authoritative, Non-Blocking Note Lifecycle Flush & Teardown Idempotency (`NotePersistenceCoordinator.kt`, `FloatingMiniAppWindow.kt`, `FloatingMiniAppBridge.kt`, `DurableMiniAppStore.kt`)**:
  - **Authoritative Single-Coordinator Persistence**:
    - Removed duplicate persistence paths: deleted secondary `durableStore.saveSnapshot()` in `FloatingMiniAppBridge.sleepMiniApp` and skipped redundant `durableStore.updatePayload()` in `FloatingMiniAppWindow.onDestroy()` for Main-only apps.
    - Eliminated duplicate synchronous `flushPendingSave()` from `FloatingMiniAppWindow.onHide()` and `onDestroy()`. Replaced with idempotent `if (notePersistence.hasPendingEdits()) scheduleDebouncedSave(0L)`. Guarantees zero main-thread disk I/O during window teardown.
    - `NotePersistenceCoordinator` is the sole authority for Note content, formatting, history, bounds, and lifecycle state (`MiniAppState.ACTIVE`, `SLEEPING`, `CLOSED`).
    - Merged target lifecycle state into the atomic commit transaction so snapshots are persisted consistently in one atomic disk operation.
  - **Non-Blocking Asynchronous UI Lifecycle Flush**:
    - Added `flushPendingSaveAsync(timeoutMs, targetState, onComplete: (Boolean) -> Unit)` to `NotePersistenceCoordinator` and `FloatingMiniAppWindow`.
    - Lifecycle transitions (`sleepMiniApp`, `closeMiniApp`) no longer block the Android UI/main thread. Dispatches disk I/O on background thread pool and invokes completion callback on `Looper.getMainLooper()`.
    - Window teardown and launcher bubble display happen strictly after confirmed successful write.
    - Failed or timed-out flushes abort the fold/close transaction, keeping the active Note window visible and recoverable with zero data loss.
  - **Operation Generation Authorization & Race-Safety**:
    - Implemented operation generation (`currentOperationGeneration`, `activeOperationGeneration`) tracking.
    - Timed-out or aborted flushes invalidate `activeOperationGeneration`, preventing late background workers from committing target states (`SLEEPING` or `CLOSED`).
    - Snapshot content (text and formatting) is preserved safely without corrupting lifecycle state.
    - Backed timeouts by daemon `ScheduledExecutorService` (`timeoutScheduler`) for deterministic timeout triggers across Android devices and test environments.
  - **Multi-Instance Thread Pool Isolation**:
    - Replaced single-thread executor with `Executors.newCachedThreadPool` using daemon worker threads.
    - Independent Note instances never head-of-line block each other's persistence or lifecycle flushes.
    - Strict per-instance serialization preserved via `getInstanceLock(instanceId)`.
  - **Testing**: 71/71 tests in `FloatingMiniAppFrameworkTest` passing 100% with genuine asynchronous multi-threaded verification. `compile_applet` succeeded cleanly.

### Phase 14.7: Sidebar Three Combined Updates (COMPLETED)
- **1. Note Floating-Window Keyboard Dismissal Stabilization**:
  - Prevented premature editor deactivation caused by transient layout passes or insets updates during initial keyboard display.
  - Implemented `scheduleDebouncedImeDismissCheck(source: String)` with `IME_DISMISS_DEBOUNCE_MS = 250L`.
  - Added session tracking with `imeSessionToken: Long` to invalidate stale dismiss callbacks across new editor activations.
  - Double verification of insets (`ViewCompat.getRootWindowInsets`) and display frame visible geometry (`getWindowVisibleDisplayFrame`) before confirming keyboard dismissal.
  - Protected window resizing (`isResizing`) and window dragging (`isDraggingWindow`) from falsely terminating IME sessions.
- **2. Android Settings Shortcuts Catalogue Expansion**:
  - Expanded `ALL_SETTINGS_SHORTCUTS` in `SidebarItem.kt` from 11 items to 39 items covering all primary Android Settings categories: Wi-Fi, Bluetooth, Mobile Network, Data Usage, Hotspot, Airplane Mode, VPN, NFC, Cast, Display, Night Light, Wallpaper, Sound, DND, Notifications, All Apps, Default Apps, Permission Manager, Battery, Battery Saver, Storage, Security, Privacy, Biometrics, Accounts, Languages, Keyboard/Input, Backup, Accessibility, Captions, Developer Options, Device Info, and System Update.
  - Handled all actions in `ElementActionDispatcher.kt` with explicit fallback to `Settings.ACTION_SETTINGS` in a `catch` block for OEM resilience.
  - Added a search bar with real-time text matching and synonym keywords in `ActionPickerActivity.kt`.
- **3. Internet Speed Monitor Daily Total Manual Correction**:
  - Enhanced `DailyDataUsageHelper.kt` with `DailyCorrectionInfo`, offset accumulation (`rawBytes + offset`), and `convertToBytes` supporting B, KB, MB, and GB.
  - Added persistent daily adjustment state with automatic midnight expiration (`yyyy-MM-dd`).
  - Added "Overall Daily Data Total" control in `NetSpeedSettingsScreen.kt` with Material 3 adjustment dialog, unit selector, validation, and reset button.
  - `HandleService.kt` notification displays corrected daily totals automatically.
- **Testing**: 78/78 tests in `FloatingMiniAppFrameworkTest` passing 100%.

### Phase 14.8: Sidebar eReader — Floating Reader (Part 1 Only) (COMPLETED)
- **1. EPUB Parser Rebuild & Authoritative Ordering Defect Fix**:
  - Replaced regex parsing with Android `XmlPullParser` for container and OPF package manifest/spine.
  - Authoritative OPF Spine: Follows `<spine>` `<itemref>` element sequence as strict source of truth for reading order.
  - Path Normalization: Implemented `normalizeZipPath` resolving `.` and `..` relative directories, URL encoding, and OPF base folder.
  - Exact Normalized ZIP Lookup: Implemented `findZipEntry` with exact case-insensitive matching; eliminated ambiguous suffix-based matches across volumes/directories.
  - Non-Reading Exclusion: Automatically filters out navigation documents (`properties="nav"`, `toc.ncx`) and non-linear items (`linear="no"`).
  - Natural Numeric Ordering Fallback: Implemented `naturalCompare` ensuring "chapter_1" < "chapter_2" < "chapter_10" when spine is malformed/absent.
  - Clean HTML to Text Conversion: Unescapes HTML entities, strips `<head>`, `<style>`, `<script>`, preserves paragraphs and headings, and writes `chapter_X.txt` and `toc.json`.
- **2. Library Shelf & SAF Import**:
  - Created `ReaderLibraryView.kt`: Shelf listing recent and imported books with reading progress bars, percentages, last read timestamps, and search filtering.
  - SAF Integration: Added `ReaderImportActivity.kt` declared with `@android:style/Theme.Translucent.NoTitleBar` to pick EPUB/TXT files via SAF.
  - Managed Storage: Copies imported files into private app storage (`context.filesDir`) with duplicate detection by title and file size.
  - Bundled Sample: Provided "Load Sample Classic" generator ("The Time Machine") for immediate zero-file verification.
- **3. Floating Reading Experience**:
  - Created `ReaderReadingView.kt`: Scrollable reading screen with selectable text (`setTextIsSelectable(true)`), chapter navigation (`Prev` / `Next`), Table of Contents modal drawer, in-chapter search with live highlighting, in-book search across chapters with snippet preview, font scaling (12sp-28sp), auto-scroll with speed controls, and Text-to-Speech (TTS).
  - Debounced Progress Saving: Persists scroll position debounced during reading, and immediately upon chapter change, pause, sleep, or leaving the reading screen.
- **4. Mini-App Lifecycle & Process Architecture**:
  - Replaced placeholder `DefaultMiniAppFactory` registration in `HeavyFloatingHost` with `ReaderMiniAppFactory`.
  - Implemented `ReaderMiniAppInstance.kt` managing `MiniAppInstance` lifecycle (`onCreate`, `onResume`, `onPause`, `onDestroy`, `prepareForSleep`, `restoreFromSnapshot`, `releaseRuntime`, `onSaveState`) seamlessly preserving reading mode, book ID, chapter, scroll position, and font size across mini-app fold, sleep, wake, and restore.
- **5. Testing**:
  - Added `EpubParserTest.kt` with 9 regression test cases covering natural numeric sort, spine authority, nested OPF paths, URL-encoded hrefs, directory collisions, navigation exclusion, broken spine fallback, repeatable idempotence, and TXT parsing.
  - Added `ReaderMiniAppTest.kt` covering factory registration, lifecycle states, snapshot sleep/restore recovery, and view creation.
  - 90/90 tests passing in `FloatingMiniAppFrameworkTest`, `EpubParserTest`, and `ReaderMiniAppTest`. Whole-project compilation clean.

### Phase 14.9: Note, Floating Shell Corners, Reader Refinements & Floating PWA Loader (COMPLETED)
- **1. Note Mini-App Enhancements (Main-Only Multi-Instance)**:
  - Blank-slate new Notes with `TestNotesMiniAppInstance.isLegacyDefaultTemplate` sanitization for legacy snapshots.
  - Multi-instance launcher `FloatingMiniAppBridge.openNewNoteInstance` with staggered bounds.
  - In-editor Copy action and persistent per-Note `accent_color` (`NotePersistenceCoordinator`) tinting the top-bar indicator, Edit button, popover active swatch, and folded bubble border stroke.
- **2. Shared Floating Shell 16dp Rounded Corners & Z-Order Stabilization**:
  - Applied uniform `16dp` rounded outline clipping on `FloatingMiniAppWindow` when floating (`0f` when maximized) with non-interactive `rootBorderOverlay` so inner surfaces and video/WebView layers never bleed square corners over the frame.
  - `FloatingWindowManager.bringToFront` skips redundant layout passes when only hidden/detached windows sit above the target window.
- **3. Reader Fixes, Generic IME Bridge & SAF Folder Explorer**:
  - Replaced `+`-mangling `URLDecoder` with strict `%XX` UTF-8 decoding in `EpubParser.normalizeZipPath` so OPF/ZIP paths with literal `+` characters resolve accurately.
  - Extracted reusable `EmbeddedImeBridge` (`EmbeddedImeEditText` / `:heavy` blur dispatch) shared across `:heavy` mini-app inputs.
  - Added SAF folder tree picker (`ReaderFolderTreeActivity`), synced `ReaderFolderPrefs`, and `ReaderFolderBrowserView` with type filtering and natural numeric sort; preserved `CoroutineScope` across screen switches within `ReaderMiniAppInstance`.
- **4. Separate Floating PWA Loader (`floating_pwa`)**:
  - Kept full-screen `PwaPlayerActivity.kt` untouched.
  - Built `:heavy` `FloatingPwaMiniAppFactory` + `FloatingPwaMiniAppInstance` (reusing `PwaEntity`, `PwaPermissionPolicy`, `PwaStorageManager`, and `https://pwa-<id>.local/` virtual origin + external web URLs + audio muting script + IME focus bridge).
  - Built Main-side `FloatingPwaWindowController` (compact browser toolbar, URL bar with focusability arbitration, linear progress indicator, installed PWA picker modal) and `FloatingPwaRelayActivity` (SAF document chooser and runtime Camera/Mic/Location permission relay).
  - Wired `"floating_pwa"` across `HeavyFloatingHost`, `HeavyFloatingHostService`, `HeavyPageHostManager`, `FloatingMiniAppWindow`, `ElementActionDispatcher`, `SidebarItem`, `AddElementActivity`, `FloatingMiniAppLauncherBubble`, `FloatingMiniAppLogFilter`, and `AndroidManifest.xml`.
- **5. Testing**: 111/111 JVM unit tests passing across `FloatingMiniAppFrameworkTest` (82), `EpubParserTest` (10), `ReaderMiniAppTest` (6), `FloatingMiniAppLogFilterTest` (7), and `FloatingPwaMiniAppTest` (6). Whole-project build compiled cleanly.

### Phase 14.10: Complete Sidebar Reader Architecture, Book Tracker & Backup System (COMPLETED)
- **1. Main-Process Reader Runtime & Threading**:
  - Moved `"reader"` / `"ebook_reader"` out of `:heavy` `HeavyFloatingHost` so floating Reader runtime and UI run exclusively in Main (`com.example`).
  - Fixed `ReaderDispatchers.main` to bind directly to `Looper.getMainLooper()` in Android runtime rather than silently falling back to `Dispatchers.Default`.
- **2. Room v10 → v11 Schema & Strict Book Identity**:
  - Added `MIGRATION_10_11` and `.enableMultiInstanceInvalidation()` on `AppDatabase` (`litereader_db`).
  - Added `author` and `lastActivityTimestamp` to `EpubBook`; added `linkedEpubBookId` (FK to `epubs(id)` with `ON DELETE SET NULL`), `lastReadingActivityTimestamp`, `lastMetadataEditTimestamp`, and `backupOriginKey` to `TrackerBook`.
  - Preserved distinct identities for duplicate titles; deleting an `EpubBook` unlinks (`linkedEpubBookId = null`) without deleting Tracker progress.
- **3. Lazy Metadata-First Parser, 21-Chapter Rolling Cache & Virtualized TOC**:
  - Updated `EpubParser` and `LibraryRepository` to parse metadata/manifest/spine/TXT ranges into `toc.json` and `cache_manifest.json` on import without upfront full-book extraction.
  - Maintained a rolling 21-chapter cache window (`[current - 10 .. current + 10]`) during reading and streamed out-of-window chapters transiently via `readChapterTextTransient` during full-book search.
  - Upgraded TOC overlay to a virtualized `RecyclerView` (`TocChapterAdapter`) displaying 1 ellipsized line per chapter (`"${index + 1}. ${title}"`) with current-chapter highlighting.
- **4. Recent (Last 10) vs All Books & Resume vs High-Water Separation**:
  - `ReaderLibraryView` defaults to **Recent** (up to 10 distinct opened books ordered by `lastOpenedTimestamp DESC`) with a separate uncapped **All Books** tab; cold restarts open **Recent** by default while warm fold/unfold preserves the active session.
  - Strictly separated `EpubBook` resume position (`lastReadChapter`/`lastReadScrollY`) from `TrackerBook.readChapters` high-water progress; TOC/search jumps and restorations update resume only, while genuine reading advances `readChapters` and shows an in-Reader **Undo** banner.
- **5. Heavy Book Tracker, Reader Settings & LiteReader/Moon+ Backups**:
  - Added `:heavy` `BookTrackerActivity` (search, genre filter, sort, Add/Edit/Delete, manual progress & flags, exact `linkedEpubBookId` **Open in Reader** dispatch) and `ReaderSettingsScreen` in `SettingsActivity`.
  - Wired floating Reader top-bar `Edit` button in Main to open `:heavy` Book Tracker editor for the exact current `EpubBook.id`.
  - Implemented `LiteReaderBackupManager` in `:heavy` supporting Full ZIP, NoBooks ZIP, Tracker TSV import/export, and Moon+ `.mrstd` import via safe non-destructive temp-dir merging.
- **6. Verification**: 111/111 JVM tests passing (`EpubParserTest`: 12, `ReaderMiniAppTest`: 6, `ReaderArchitectureAndBackupTest`: 5, `FloatingMiniAppFrameworkTest`: 82, `FloatingPwaMiniAppTest`: 6); `compile_applet` build succeeded.

### Phase 14.11: Reader Quick Fixes — Streaming Large TXT & High-Water Progress Policy (COMPLETED)
- **1. Genuine Streaming & Byte-Indexed Large TXT Loading (`EpubParser.kt`)**:
  - Replaced whole-file TXT memory reads and string slicing with `detectTxtEncoding` (BOM + streaming UTF-8 validation with `windows-1252` / `ISO-8859-1` fallback) and `streamTxtLinesWithByteOffsets` recording exact `"txt_bytes:<start>:<end>:<charset>"` offsets in `toc.json` and `charsetName` in `cache_manifest.json`.
  - `materializeChaptersFromSource` reads only requested chapters via `readTxtByteRange` (`RandomAccessFile` + `FileChannel` + `CharsetDecoder` preserving multi-byte character boundaries across chunk reads), with streaming `readTxtCharRangeStreaming` fallback for legacy `txt_chars:` entries.
- **2. Prevent False Progress on `Next` (`ReaderModels.kt`, `ReaderReadingView.kt`)**:
  - Added `ReaderProgressPolicy` distinguishing normal chapters from short chapters (`<= 220` words / `<= 1400` chars or small/unscrollable ranges), requiring `2500ms..10000ms` dwell or meaningful proportional scroll before `Next` counts a short chapter as read.
  - Updated `ReaderReadingView` (`advanceToNextChapterByUser`, `recordGenuineReadingActivityOnCurrentChapter`, `openChapterJob` cancellation) so TOC/search jumps, chapter restoration, and clicking `Next` on unscrolled normal chapters or immediately on short chapters never inflate `TrackerBook.readChapters`.
- **3. Verification**: 114/114 targeted JVM unit tests passing (`EpubParserTest`: 14, `ReaderArchitectureAndBackupTest`: 6, `ReaderMiniAppTest`: 6, `FloatingMiniAppFrameworkTest`: 82, `FloatingPwaMiniAppTest`: 6); `compile_applet` succeeded.

### Phase 14.12: Sidebar Calculator Quantity Converter & Expanded Settings Shortcuts (COMPLETED)
- **1. Sidebar Calculator In-Place Quantity Converter (`QuantityConverter.kt`, `page_calculator.xml`, `CalculatorPageView.kt`, `ic_swap_horiz.xml`, `strings.xml`)**:
  - Added `btn_convert_quantity` beside `btn_copy_result` on `CalculatorPageView` with strict eligibility gating (`resolveConvertibleValue`): enabled only when the calculator holds a single finite numeric literal OR a finalized finite `=` total (explicitly excluding empty `"0"`, unfinalized expressions, `Infinity`/`∞`, `NaN`, and `Error`).
  - Added in-place `layout_converter_overlay` inside `page_calculator.xml` (no `AlertDialog`/`PopupWindow` inside `:heavy` `SurfaceControlViewHost`), toggling `layout_calculator_main` to `View.INVISIBLE` to preserve exact sidebar height without flicker or resizing while keeping all existing calculator state intact on close.
  - Supported 8 categories (`Length`, `Weight`, `Temp`, `Volume`, `Area`, `Time`, `Data` using 1024-based `B`/`KB`/`MB`/`GB`/`TB`, `Speed`), interactive **From ↔ To** pair selector with central **Swap**, 3-column scrollable unit selector chips, dedicated numeric-only **Copy** (`formatConvertedValue` using `Locale.US` decimal / scientific fallback), and `FloatingReaderPrefs` persistence per category.
- **2. Expanded Settings Shortcuts & Unified Dispatch (`SidebarItem.kt`, `ElementActionDispatcher.kt`, `AppsPageView.kt`, `HybridGridPageView.kt`, `ActionPickerActivity.kt`)**:
  - Added 18 new `SettingsShortcut` entries covering Battery Optimization (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`), Special App Access & Permission pages (`overlay_permission`, `modify_system_settings`, `usage_access`, `notification_access`, `dnd_access`, `all_files_access`, `install_unknown_apps`, `alarms_reminders`, `picture_in_picture`), and additional Android settings screens (`wireless_network`, `app_usage`, `default_home`, `voice_input`, `physical_keyboard`, `printing`, `screen_saver`, `webview_impl`) with zero new dangerous manifest permissions.
  - Added `resolveSettingsActionCandidates` + `resolveWithFallbacks` in `ElementActionDispatcher` for safe API-level gating and OEM resolution fallbacks, and unified `AppsPageView` and `HybridGridPageView` (grid + folder popups) to delegate to `ElementActionDispatcher.handleSettingsShortcut` (removing duplicated 9-case `when` blocks).
  - Expanded search keyword synonyms in `ActionPickerActivity.matchesQuery` to cover all battery optimization, permission, and system access shortcuts.
- **3. Verification**: 129/129 targeted JVM unit tests passing (`QuantityConverterTest`: 6, `HeavyCalculatorSplitTest`: 23, `FloatingMiniAppFrameworkTest`: 83, `ElementSystemTest`: 4, `UnifiedPageSystemTest`: 13); `compile_applet` build succeeded.

### Phase 14.13: Folded Launcher Bubble Non-Focusable Overlay Fix (COMPLETED)
- **1. Launcher Bubble Focus Policy (`FloatingMiniAppLauncherBubble.kt`)**:
  - Set `layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE` inside `FloatingMiniAppLauncherBubble.init` while preserving `setWindowBounds`, touchability (no `FLAG_NOT_TOUCHABLE`), drag repositioning, tap-to-wake, and accent color rendering.
  - Left `FloatingWindow.kt` base flags, `FloatingMiniAppWindow.kt` active mini-app focus/IME behavior, `FloatingMiniAppBridge.kt`, and `:heavy` runtime untouched.
- **2. Verification**: `FloatingMiniAppFrameworkTest` (84/84 passed including `testFoldedLauncherBubbleFlagsDragWakeAndRestore`), `FloatingMiniAppLogFilterTest` (7/7), `SidebarImeInteractionTest` (3/3), `ReaderMiniAppTest` (6/6), `FloatingPwaMiniAppTest` (6/6); `compile_applet` build succeeded. Requires physical-device validation for cross-app soft keyboard opening while bubbles are folded.

### Phase 14.14: Global Folded Launcher Bubble Circular Shape & Touch-Area Alignment (COMPLETED)
- **1. Circular Visual Shape (`FloatingMiniAppLauncherBubble.kt`)**:
  - Removed `elevation = 10 * density` (`elevation = 0f`, `stateListAnimator = null`) so RenderNode drop shadows no longer hard-clip at the `36dp x 36dp` overlay surface corners into a visible dark square box.
  - Applied an explicit circular `ViewOutlineProvider` (`outline.setOval(...)`) with `clipToOutline = true`, preserving exact `36dp` bounds, `#313244` fill, `2dp` accent border, and centered `7dp`-padded white app icon.
- **2. Circular Container & Earliest Touch Rejection (`FloatingMiniAppLauncherBubble.kt`)**:
  - Overrode `getOrCreateContainerView()`, `createContentView()`, `getView()`, and `onDestroy()` directly in `FloatingMiniAppLauncherBubble` (leaving `FloatingWindow.kt` and all other `FloatingWindow` subclasses untouched) so no unclipped rectangular base wrapper surrounds the bubble.
  - Added `isPointInsideCircle` and `dispatchTouchEvent` gating (`shouldAcceptAndFocusOnTouch` / `handleAcceptedTouchAction`) to reject outside-circle square corner presses on `ACTION_DOWN` before focus shifts, `bringToFront`, drag tracking, `buttonPosition` writes, or `onBubbleTapped` wake callbacks, while allowing active in-circle drags to continue cleanly even if the pointer leaves the circle mid-drag.
- **3. Security Cleanup & Verification**:
  - Deleted generated `/debug.keystore`, `/debug.keystore.base64`, and `/.build-outputs/app-debug.apk` and kept them absent (did not invoke APK signing/packaging).
  - Kotlin debug compilation (`:app:compileDebugKotlin`, `:app:compileDebugUnitTestKotlin`) and 107/107 targeted JVM unit tests passed (`FloatingMiniAppFrameworkTest`: 85/85 including `testFoldedLauncherBubbleCircularShapeAndTouchArea`, `ReaderMiniAppTest`: 6/6, `FloatingPwaMiniAppTest`: 6/6, `FloatingMiniAppLogFilterTest`: 7/7, `SidebarImeInteractionTest`: 3/3). Note: public Android SDK (`compileSdk = 34`) has no non-rectangular `WindowManager` overlay touchable-region API, so OS compositor frame bounds remain `36dp x 36dp` while corner touches are ignored before triggering any bubble action.

### Phase 14.15: Sidebar Reader UI Rebuild — Library, Reading View, Book Details & Active-Book-Only Storage (COMPLETED)
- **1. Library Interface Rebuild (`ReaderLibraryView.kt`, `ReaderModels.kt`, `ReaderFolderBrowserView.kt`)**:
  - Replaced oversized header with compact `Library` header + compact icon actions: Chart (`BookTrackerActivity` via Reader hide-and-restore), Book Details (current/selected book details panel — no Quick Notes), Wrench (`SettingsActivity` `reader_settings`), and `+` (SAF single-file import).
  - Tabs: `Recent` (up to 10 distinct opened books by `lastOpenedTimestamp DESC`) and `File Explorer` (embedded `ReaderFolderBrowserView`).
  - Compact book rows, expandable search bar toggle, and compact sort mode cycling (`Opened`, `Title`, `Progress`).
- **2. Current-Book Details Panel (`ReaderBookDetailsView.kt`)**:
  - Single-source-of-truth metadata viewer & editor backed by `LibraryRepository.getOrCreateLinkedTrackerBook` / `saveTrackerMetadataEdit` (`title`, `author`, `genres`, `rating`, `comment`, `readChapters`, `totalChapters`, `isFinished`, `isWebNovel`), clearly separating Reader resume position vs Tracker high-water progress and binary status, and dismissing cleanly to the exact prior Library state.
- **3. Tracker Hide/Restore Lifecycle (`FloatingMiniAppBridge.kt`, `core/ipc/HeavyProcessConnectionManager.kt`, `BookTrackerActivity.kt`, `SettingsActivity.kt`, `FloatingMiniAppWindow.kt`)**:
  - Hides floating Reader window without spawning a launcher bubble before opening `BookTrackerActivity` (or Reader Settings from floating Reader), preserving session/book/scroll state and restoring the floating Reader window on return (or opening the selected book directly via `Open in Reader`). Scoped strictly to Reader; leaves global `FloatingWindow.kt` / `FloatingWindowManager.kt` and unrelated mini-apps untouched.
- **4. Reading View Rebuild (`ReaderReadingView.kt`, `ReaderBookmarkStore`)**:
  - Near-black reading background (`#08080B` / `#000000`) + crisp white text (`#F5F5F7`) and line spacing, compact header, and thin bottom reading progress indicator.
  - Bottom action toolbar hidden by default; clean tap on reading area toggles toolbar visibility without disrupting scrolling, text selection, or triggering unintended chapter turns.
  - Toolbar actions: Library, Chapter list / TOC (`RecyclerView`), Bookmark (toggle/list/jump persisted per book without inflating Tracker progress), TTS, Auto-scroll, Search (in-chapter & in-book), and Reader Settings (`SettingsActivity` + inline `Prev`/`Next` & font `A-/A+` controls).
- **5. Active-Book-Only Binary Retention (`LibraryRepository.kt`, `ReaderImportActivity.kt`, `ReaderMiniAppInstance.kt`)**:
  - Retains source binary and extracted cache ONLY for the current/last active book; never deletes the prior active binary until replacement import + parse (`totalChapters > 0`) + Room persistence succeed; keeps `EpubBook` and `TrackerBook` metadata & progress for older books; clearly indicates binary unavailability and supports in-place SAF re-import preserving metadata and resume/high-water progress.
- **6. Verification**:
  - Full project build (`compile_applet`) succeeded and `/debug.keystore`, `/debug.keystore.base64`, and `/.build-outputs/app-debug.apk` were removed and verified absent afterward.
  - Targeted JVM unit tests passed cleanly: **124/124** (`EpubParserTest` 14/14, `ReaderMiniAppTest` 10/10, `ReaderArchitectureAndBackupTest` 8/8, `FloatingMiniAppFrameworkTest` 86/86, `FloatingPwaMiniAppTest` 6/6) with zero uncaught exceptions. Requires physical-device testing for visual overlay rendering, HWUI contrast, touch/scroll feel, and Activity-return overlay restoration.

### Phase 14.16: Settings Navigation Correction — Remove Standalone Book Tracker From Main Settings (COMPLETED)
- **1. Main Settings Cleanup (`SettingsActivity.kt`)**:
  - Removed only the standalone `Book Tracker` `ListItem` (click handler launching `BookTrackerActivity` and `settings_item_book_tracker` tag) from `MainSettingsScreen`.
  - Preserved `Reader Settings` (`settings_item_reader_settings`, `onNavigateToReaderSettings()`, `"reader_settings"` route) and all unrelated main Settings entries unchanged.
  - Kept `ReaderSettingsScreen.kt` (`open_book_tracker_button` and `open_reader_backups_button`), `BookTrackerActivity`, backup import/export, and Reader navigation completely intact.
- **2. Verification**:
  - Kotlin compilation (`:app:compileDebugKotlin`, `:app:compileDebugUnitTestKotlin`) and 24/24 targeted JVM unit tests passed (`SettingsNavigationStructureTest` 2/2, `WelcomeNavigationTest` 4/4, `ReaderMiniAppTest` 10/10, `ReaderArchitectureAndBackupTest` 8/8). Requires on-device UI verification of Settings list rendering and navigation clicks.

### Phase 14.17: Reader & Book Tracker Refinement — Restoration Fix, Auto-Open, Compact UI, SeekBar & Light Tracker (COMPLETED)
- **1. Reader Restoration Attachment Fix (`FloatingWindow.kt`, `FloatingWindowManager.kt`, `FloatingMiniAppBridge.kt`)**:
  - Added `@Volatile internal var isOverlayViewAttached` on `FloatingWindow` and updated `isAttached` so `HIDDEN` (`View.GONE`) overlay views remain recognized as attached without calling duplicate `WindowManager.addView()` in `restoreReaderAfterExternalActivity()` → `showWindow()` → `attachOverlay()`. Preserved `FOLDED` view detachment and `DETACHED`/`onDestroy()` cleanup, and made `restoreReaderAfterExternalActivity()` idempotent.
- **2. Auto-Open Last-Read Book (`ReaderMiniAppInstance.kt`, `ReaderReadingView.kt`, `ReaderModels.kt`)**:
  - Added `tryAutoOpenLastReadBook()` on clean/cold launches to automatically reopen the last active book at `lastReadChapter` / `lastReadScrollY` without duplicating `EpubBook`/`TrackerBook` entries, staying safely in `LIBRARY` when none exists or when the source binary is missing.
- **3. Library & File Explorer Compactness (`ReaderLibraryView.kt`, `ReaderFolderBrowserView.kt`)**:
  - Tightened header/tab/filter/card paddings and margins across Library and embedded `File Explorer`, removed `" • Tap to import & open"` verbose suffix, and hid Recent-only controls cleanly while in `File Explorer`.
- **4. Reading Toolbar, Single Progress Indicator & Reader-Only Sticker Hiding (`ReaderReadingView.kt`, `FloatingMiniAppWindow.kt`, `ic_edit_pencil.xml`)**:
  - Removed `bottomProgressStrip` and inline `A-`/sp/`A+` font controls; added proportional full-book `SeekBar` (`fullBookSeekBar`) with lightweight drag preview and seek+resume persist on release (never inflating Tracker high-water).
  - Enforced the single-progress-indicator rule: thin `topBookProgressBar` shows ONLY when the bottom toolbar is hidden; bottom `SeekBar` shows ONLY when the toolbar is visible.
  - Replaced Reader's top-bar `"Edit"` button with compact accessible `ImageButton` (`ic_edit_pencil.xml`, keeping Note's `"Edit"` text button) and hid Reader's bottom-right `[ × — ↘ ]` sticker + removed `46dp` clearance exclusively while Reader's reading toolbar is visible (restoring on hide, Library/Explorer switch, `onShow`, `onFold`, and `releaseRuntime`).
- **5. Book Tracker Light Theme & Backup Icon (`BookTrackerActivity.kt`)**:
  - Converted `BookTrackerActivity` exclusively to a crisp high-contrast light palette (`BookTrackerLightColors`, `lightColorScheme()`, white bordered cards, cobalt blue primary, dark slate ink) and replaced the TopAppBar `"Backups"` `TextButton` with an accessible `IconButton` using `ic_download.xml`.
- **6. Verification**:
  - Full project build (`compile_applet`) succeeded and 139/139 targeted JVM unit tests passed (`ReaderMiniAppTest` 15/15, `ReaderArchitectureAndBackupTest` 9/9, `FloatingMiniAppFrameworkTest` 86/86, `SettingsNavigationStructureTest` 2/2, `ScreenCapSettingsIntegrationTest` 6/6, `FloatingMiniAppLogFilterTest` 7/7, `MainProcessRecoveryTest` 8/8, `MainRuntimeRestorationTest` 6/6), with 0 failures/errors/skipped and empty `system-err`.

### Phase 14.18: Floating Window Attachment & Z-Order Lifecycle Follow-Up Fix (COMPLETED)
- **1. Authoritative Attachment Tracking (`FloatingWindow.kt`, `FloatingMiniAppLauncherBubble.kt`)**:
  - Removed `state == WindowState.ATTACHED || state == WindowState.VISIBLE` inference from `FloatingWindow.isAttached`, deriving attachment exclusively from `(getView()?.isAttachedToWindow == true) || isOverlayViewAttached`.
  - Added `View.OnAttachStateChangeListener` (`attachLifecycleStateListener` / `detachLifecycleStateListener`) bound in `FloatingWindow.getOrCreateContainerView()` and `FloatingMiniAppLauncherBubble.getOrCreateContainerView()` and unbound in `onDestroy()`.
- **2. Safe Z-Order Refresh & Failure Consistency (`FloatingWindowManager.kt`, `FloatingMiniAppBridge.kt`)**:
  - Exposed `refreshOverlayZOrder(windowId)` and fixed `refreshOverlayZOrder(window)` so visible and `HIDDEN` (`View.GONE`) attached windows reattach preserving exact `previousState` and `View.GONE` visibility without showing hidden windows, detached/folded windows are skipped, removal failure aborts re-add, and re-add failure (or revoked overlay permission) transitions cleanly to `WindowState.DETACHED` + `onDetach()` + focus handoff rather than leaving `WindowState.VISIBLE` while detached.
  - Hardened `attachOverlay`, `detachOverlay`, `showWindow`, `hideWindow`, `foldWindow`, `unfoldWindow`, and `bringToFront` so `isOverlayViewAttached`, `isFolded`, and lifecycle states advance only after `WindowManager` operations succeed, and hardened `FloatingMiniAppBridge.restoreReaderAfterExternalActivity` / `openMiniApp` to check `!isAttached`, `WindowState`, `View.visibility`, and `showWindow()` return status.
- **3. Focused Verification**:
  - 137/137 targeted JVM unit tests passed (`FloatingMiniAppFrameworkTest`: 92/92 including 6 new strict fake-`WindowManager` + `TrackableFrameLayout` Z-order/failure/attachment tests, `ReaderMiniAppTest`: 15/15, `ReaderArchitectureAndBackupTest`: 9/9, `FloatingMiniAppLogFilterTest`: 7/7, `MainProcessRecoveryTest`: 8/8, `MainRuntimeRestorationTest`: 6/6) and `compile_applet` succeeded. Android attachment/visibility simulated on JVM without Robolectric or physical-device traversal; hardware overlay Z-order & restoration require physical-device verification.

### Phase 14.19: Reader UI Refinement + Shared Floating Window Maximize Fix (COMPLETED)
- **1. Shared Floating-Window Maximize & Sticker Resize (`FloatingMiniAppWindow.kt`)**:
  - Added `FLAG_LAYOUT_IN_SCREEN`, `fitInsetsTypes = 0` (API 30+), and cutout modes (`ALWAYS` on R+, `SHORT_EDGES` on P–Q) plus `applyMaximizeWindowFlags()` (clearing `FLAG_LAYOUT_NO_LIMITS` only while maximized and restoring it on unmaximize).
  - Updated `getSafeDisplayBounds()` to read authoritative `WindowManager.currentWindowMetrics` + `systemBars() or displayCutout()` (`maxOf` with `getInsetsIgnoringVisibility(systemBars())`) instead of unmaximized sub-window `rootWindowInsets`, added pre-R `defaultDisplay` real-vs-app fallback, invalidated cache on display dimension / configuration changes (`handleConfigurationChangedOnRoot`), and extracted `computeSafeDisplayBoundsFromInsets`.
  - Tracked `stickerResizeDividerView` and `stickerResizeButtonView` via `isStickerResizeVisible` so `↘` + its divider hide on maximize (`[ × | — ]` stays visible) and restore cleanly on unmaximize, composing orthogonally with Reader's full sticker-pill hiding when the reading bottom toolbar is visible.
- **2. Reader Reading Top Bar & Toolbar Refinements (`FloatingMiniAppWindow.kt`, `ReaderReadingView.kt`)**:
  - Omitted the blue dot icon for Reader windows (keeping Note's accent dot), reduced Reader top bar height to `32dp` (updating `getTopBarHeightPx()` / `getContentViewportHeight()`), kept top bar title strictly `"Reader"`, and converted Reader's top-bar action icon to `ic_settings_cog` (`"Reader Settings"` launching `launchReaderSettingsFromReader`).
  - Tightened `topHeaderLayout` padding (`dp(2)` vertical) showing chapter title only in minimal mode; added `toolbarHeaderRow` inside `bottomToolbarLayout` displaying the wrapped full book/file name (`resolveDisplayBookName`, `maxLines = 3`) and strictly single-row `Prev (◀) → SeekBar → Next (▶)` (`navSeekRow`) above the 7-icon action row.
- **3. Library, Embedded Explorer, Continue FAB & Session Restoration (`ReaderLibraryView.kt`, `ReaderFolderBrowserView.kt`, `ReaderMiniAppInstance.kt`, `FloatingMiniAppBridge.kt`)**:
  - Removed the redundant `"Library"` `headerBar` from `ReaderLibraryView`, compacted tab/utility/card spacing, added `52dp` bottom scroll clearance (`clipToPadding = false`), and added a root-anchored **Continue** FAB (`Gravity.BOTTOM or Gravity.END`, `bottomMargin = 48dp`) visible across both `Recent` and `File Explorer` tabs when a last-read book exists (hidden when empty or Details open).
  - Hid `ReaderFolderBrowserView` search bar by default behind a compact search toggle beside `↑ Up` / `📁 Folder`, collapsing and clearing query/focus cleanly on close/folder change/empty folder, and added `52dp` bottom scroll padding when embedded.
  - Marked warm sessions on `hideReaderForExternalActivity` and cancelled stale `openBookJob` on `onOpenFolderBrowser` / `onBackToLibrary`.
- **4. Verification**:
  - `compile_applet` succeeded and **123/123** targeted JVM unit tests passed (`FloatingMiniAppFrameworkTest` 95/95, `ReaderMiniAppTest` 18/18, `ReaderArchitectureAndBackupTest` 10/10; mockable `android.jar` JVM environment without Robolectric). Note: pre-existing full-suite test `CallSensorIpcTest.testHeavyUnavailableAndQueueingBehavior` fails on `CallSensorIpcTest.kt:279` (untouched).

### Phase 14.20: Floating Window Framework Fixes + Reader UI & Session Improvements (COMPLETED)
- **1. Shared Floating-Window Framework (`FloatingMiniAppWindow.kt`)**:
  - **Maximize Above Nav/Gesture Bar & Applied Bounds Verification**: Hardened `getSafeDisplayBounds()` to combine `systemBars()`, `navigationBars()`, `statusBars()`, `displayCutout()`, `tappableElement()`, `mandatorySystemGestures()`, live `rootWindowInsets`, `display.cutout`, `realSize - appSize` frame diffs, and Android `status_bar_height`/`navigation_bar_height` fallbacks. Added live `OnApplyWindowInsetsListener` reconciliation while maximized so applied `layoutParams` and `bounds` stop cleanly above system navigation bars and restore exact pre-maximize bounds.
  - **Resize Sticker & Divider Visibility**: `updateStickerResizeVisibility(maximized)` sets `View.GONE`, `isEnabled = false`, `isClickable = false`, and `requestLayout()` on both `stickerResizeDividerView` and `stickerResizeButtonView` while maximized, keeping `[ × | — ]` available and restoring `↘` on unmaximize.
  - **Off-Screen Dragging & Persistence**: Enabled `FLAG_LAYOUT_NO_LIMITS` in normal floating mode, introduced `computeReachableDragPosition()` permitting partial/substantial off-screen movement beyond all 4 edges and corners while retaining a reachable top-bar grab region (`minVisibleGrabWidthPx` & `minVisibleTopBarHeightPx`), and fixed `setupStickerResizeTouch` max dimensions when partially off-screen. Persisted off-screen `normalFloatingBounds` through drag, fold/unfold, maximize/restore, and IME shifts.
- **2. Reader Chapter Title, Last Active Book & Compact Library UI (`LibraryRepository.kt`, `ReaderReadingView.kt`, `ReaderMiniAppInstance.kt`, `ReaderLibraryView.kt`, `ReaderFolderBrowserView.kt`)**:
  - **Chapter Title in Top Bar**: Propagated chapter titles via `onChapterTitleChanged` into `FloatingMiniAppWindow.titleView` (`maxLines = 1`, `ellipsize = END`), detached `ReaderReadingView.topHeaderLayout` so no duplicate chapter bar remains below the top bar, and reset top-bar title to `"Reader"` when returning to Library/Explorer.
  - **Independent Last Active Reader Book**: Persisted `last_active_reader_book_id` in `LibraryRepository` independently of `getRecentBooks()`, cleared it cleanly on book deletion, switched `ReaderMiniAppInstance.tryAutoOpenLastReadBook()` to `getLastActiveReaderBook()`, and preserved re-import prompts for missing binaries and in-session Back-to-Library guards.
  - **Circular `▶` Continue Button, 3-Control Sticker & Dense Library Padding**: Converted `continueFab` into a compact `38dp` circular `"▶"` button (`contentDescription = "Continue Reading"`, no `"Continue"` label); moved `Sort | + File | Search` out of `rootLayout` into a compact floating bottom-start pill sticker hiding in Explorer/Details; tightened vertical padding across Library tabs, cards, labels, and File Explorer rows.
- **3. Verification**:
  - `compile_applet` succeeded (`Build succeeded - the applet is compiled`) and **127/127** targeted JVM unit tests passed (`FloatingMiniAppFrameworkTest` 98/98, `ReaderMiniAppTest` 18/18, `ReaderArchitectureAndBackupTest` 11/11; 0 failures/errors/skipped). On-device testing required for physical gesture/3-button nav insets, display cutouts, touch dragging past edges, and visual layout inspection.

### Phase 14.21: Reader Library Bottom Strip Removal & Last-Opened Book Restoration (COMPLETED)
- **1. Library Bottom Strip & Obsolete Bottom Utility Removal (`ReaderLibraryView.kt`, `ReaderFolderBrowserView.kt`)**:
  - Removed the `dp(36)` bottom padding on `ReaderLibraryView.rootLayout` (which exposed a `#0B0B10` full-width bottom strip under both `Recent` and `File Explorer` tabs unlike `ReaderReadingView.mainLayout` with `0` bottom padding).
  - Removed the obsolete bottom `utilityBar` (`Sort`, `+ File`, `Search`) from the Library view hierarchy without adding any replacement bottom bar, while keeping `FloatingMiniAppWindow`'s shared `[ × | — | ↘ ]` pill and the circular `"▶"` Continue button untouched directly above it across both `Recent` and `File Explorer` tabs.
  - Increased bottom scroll padding (`clipToPadding = false`) on both `ReaderLibraryView.bookListScrollView` and embedded `ReaderFolderBrowserView.listScrollView` from `dp(52)` to `dp(86)` so the bottom-most item scrolls cleanly clear of the Continue button and shared three-control pill without reintroducing any bottom strip.
  - Tightened `ReaderLibraryView.getLastReadBookForContinue()` so a deleted `last_active_reader_book_id` returns `null` rather than substituting an unrelated book.
- **2. Authoritative Last-Opened Book & Cold-Start / Process-Kill Restoration (`ReaderModels.kt`, `LibraryRepository.kt`, `ReaderReadingView.kt`, `ReaderMiniAppInstance.kt`, `FloatingMiniAppBridge.kt`)**:
  - Updated `ReaderPreferences` setters (`setDefaultFontSizeSp`, `setLineSpacingMultiplier`, `setThemePreset`, `setAutoScrollSpeed`, `setTtsSpeechRate`) to commit locally to `FloatingReaderPrefs` in addition to `OverlaySyncManager` IPC sync so font size and reader prefs persist reliably in single-process and cross-process launches.
  - Changed `setActiveBinaryBookId` and `setLastActiveReaderBookId` in `LibraryRepository` from `.apply()` to synchronous `.commit()`, updated `reimportFromInputStream` and duplicate `importFromInputStream` (`markOpenedOnImport`) to update `last_active_reader_book_id`, and added `SyncResumeCheckpoint` (`saveSyncResumeCheckpoint`, `readSyncResumeCheckpoint`, `clearSyncResumeCheckpoint`, reconciled in `getLastActiveReaderBook()`, cleared on `deleteBook`).
  - Hardened `getLastActiveReaderBook()`: clears stale `last_active_reader_book_id` if deleted from Room and returns `null` (never falling back to `Recent[0]`); reconciles newer `SyncResumeCheckpoint` coordinates for that same book; safely falls back only to `active_binary_book_id` (when present in Room with `lastOpenedTimestamp > 0L`) if `last_active_reader_book_id <= 0`.
  - Fixed `ReaderReadingView`: guarded `setOnScrollChangeListener` during `isProgrammaticScrollOrLoad` so chapter load/restore cannot zero out `currentScrollY` or overwrite saved progress; added one-shot `OnLayoutChangeListener` in `openChapter` when `readingContentTextView` isn't measured yet; switched `saveCurrentProgressImmediately()` to write a committed synchronous checkpoint alongside Room persistence; ensured `applyReaderPreferences()` and `loadBook()` apply saved `ReaderPreferences.KEY_DEFAULT_FONT_SIZE`.
  - Fixed `ReaderMiniAppInstance`: captured `ColdStartResumeHint` from cold-start reading snapshots (merging newer coordinates only for that valid book, never switching to an unrelated/deleted book); resolved saved font size from `ReaderPreferences` on cold start; reset `currentBookId = -1` / `currentChapterIndex = 0` / `currentScrollY = 0` on `onBackToLibrary`; preserved `KEY_COLD_START = "true"` in `onSaveState()` while `pendingAutoOpenLastReadBook` has not yet completed; and broadened `FloatingMiniAppBridge.toggleMiniApp` to match `isReaderMiniApp(it.appType)`.
- **3. Verification**:
  - `compile_applet` succeeded (`Build succeeded - the applet is compiled`) and **157/157** targeted JVM unit tests passed (`ReaderArchitectureAndBackupTest` 13/13, `ReaderMiniAppTest` 18/18, `FloatingMiniAppFrameworkTest` 98/98, `EpubParserTest` 14/14, `MainProcessRecoveryTest` 8/8, `MainRuntimeRestorationTest` 6/6; 0 failures/errors/skipped). Physical-device verification remains required for real window rendering, scroll restoration timing on large EPUBs, and abrupt OS process kill.

### Phase 14.22: Floating Trigger, Separate Dictionary & Translation Mini-Apps, Shared Settings, Selection Actions & Read Aloud (COMPLETED)
- **1. Standalone Floating Trigger (`FloatingTriggerStore.kt`, `FloatingTriggerButtonWindow.kt`, `FloatingTriggerManager.kt`, `AddElementActivity.kt`, `ElementActionDispatcher.kt`, `HybridGridPageView.kt`, `AppsPageView.kt`, `WidgetsGridPageView.kt`, `HandleService.kt`)**:
  - Implemented standalone circular floating button overlays (`FloatingTriggerButtonWindow`, 48dp accessible touch target, circular outline clip, zero elevation, clamped usable display bounds) distinct from mini-apps and legacy container navigation.
  - Kept `Floating Trigger` under `Add Element -> Floating Windows`; selecting it shows existing configured Sidebar grid elements (plus `"Browse all Sidebar elements…"` fallback) to associate an element, activates the standalone overlay immediately, and returns `"floating_trigger:<targetId>"`.
  - Tapping the standalone floating trigger button executes the associated element via central `ElementActionDispatcher.execute(...)`; long-press activates drag mode (with visual scale/border feedback) and persists clamped `(x, y)` to `FloatingTriggerPrefs` on release without triggering the tap action; fast swipes before long-press cancel tap cleanly; `HandleService.attachHandles()` restores active triggers across restarts.
  - Preserved backward compatibility (`FloatingTriggerCompat`): legacy saved `floating_trigger:<containerId>` IDs that do not parse as Sidebar elements continue opening `SidebarManager.openContainerById(targetId)`, while element-backed IDs toggle/activate standalone Floating Trigger buttons.
- **2. Separate Dictionary & Translation Floating Mini-Apps (`LanguageMiniAppUiHelper.kt`, `DictionaryMiniAppInstance.kt`, `TranslationMiniAppInstance.kt`, `FloatingMiniAppBridge.kt`, `FloatingMiniAppWindow.kt`)**:
  - Added separate `"Dictionary"` (`system:dictionary_floating`) and `"Translation"` (`system:translation_floating`) entries under `Add Element -> Floating Windows`.
  - Hosted both as independent Main-process mini-apps (`appType = "dictionary"` and `appType = "translation"`, distinct instances, distinct compact initial bounds, internal scrolling, native `EmbeddedInputEditText` IME focus, sleep/wake/close durable snapshots, preserved top bar + shared `[ × | — | ↘ ]` controls).
  - Dictionary shell: search input, Search/Clear actions, scrollable result area, empty state, honest "No dictionary configured yet" placeholder (no fake lookup), top-bar `Edit` + inline `Open Settings` opening shared `dictionary_translation_settings`.
  - Translation shell: source/target language selectors, swap control, input text area, output area, Copy button (safe unconfigured feedback without copying fake text), honest "Translation engine not configured yet" placeholder, top-bar `Edit` + inline `Open Settings` opening shared `dictionary_translation_settings`.
- **3. Shared Dictionary & Translation Settings (`DictionaryTranslationSettingsScreen.kt`, `SettingsActivity.kt`)**:
  - Added shared Compose M3 route `dictionary_translation_settings` (with `dictionary_settings`/`translation_settings` aliases) reachable from both mini-apps' `Edit` actions, selection popups, and `MainSettingsScreen`.
  - Separated **Dictionary** section (configuration status, StarDict support planned placeholder, imported dictionaries status placeholder) and **Translation** section (configuration status, models/packs status placeholder, language configuration placeholder).
- **4. Android Text-Selection Popup Actions (`ACTION_PROCESS_TEXT`) & Built-In `TextToSpeech` Read Aloud (`SelectionTextHelper.kt`, `ReadAloudController.kt`, `SelectionPopupShell.kt`, `ReadAloudSelectionActivity.kt`, `MeaningSelectionActivity.kt`, `TranslateSelectionActivity.kt`, `themes.xml`, `AndroidManifest.xml`)**:
  - Registered three exported translucent `ACTION_PROCESS_TEXT` (`text/plain`) activities: `Read aloud`, `Meaning`, and `Translate`, with safe null/empty text handling.
  - Implemented `ReadAloudController` using built-in Android `TextToSpeech` (zero 3P dependencies): queued speak during init, slower/faster speech controls (`0.5x–2.0x`), unsupported/missing language handling with `Locale.US` fallback, init/speak error handling, cross-popup session preemption (`ReadAloudSessionCoordinator`), and clean `release()`/`shutdown()` on Activity destroy.
  - `MeaningSelectionActivity` displays the selected word prominently at top with inline Read Aloud and honest unconfigured dictionary placeholder; `TranslateSelectionActivity` displays selected text, honest unconfigured translation placeholder, and safe Copy button.
- **5. Verification**:
  - `compile_applet` succeeded (`Build succeeded - the applet is compiled`).
  - Targeted JVM unit tests passed (**151/151** across 8 suites, 0 failures/errors/skipped): `FloatingTriggerTest` (6/6), `DictionaryAndTranslationMiniAppTest` (5/5), `SelectionActionsAndReadAloudTest` (6/6), `FloatingMiniAppFrameworkTest` (98/98), `ReaderMiniAppTest` (18/18), `ElementIdParserTest` (12/12), `ElementSystemTest` (4/4), `SettingsNavigationStructureTest` (2/2). Physical-device verification remains required for real Android overlay dragging, keyboard IME input, `ACTION_PROCESS_TEXT` host-app menu visibility, and live `TextToSpeech` audio playback.

### Phase 14.23: Focused Floating Trigger Target Eligibility Audit & `LiteReader_Log.txt` Cleanup (COMPLETED)
- **1. Dispatcher-Derived Floating Trigger Target Eligibility (`FloatingTriggerStore.kt`, `FloatingTriggerManager.kt`, `AddElementActivity.kt`)**:
  - Audited `ElementActionDispatcher.execute(...)` vs. `ElementIdParser.parse(...)` and replaced broad parse-based acceptance with explicit `FloatingTriggerTargetValidation` (`ValidStandalone`, `LegacyContainer`, `Unsupported`) derived directly from standalone `ElementActionDispatcher` branches (`onShowFolder = null`, `onShowWidget = null`).
  - **Eligible Standalone Targets**: `App` (non-blank package), `Link` (non-blank UUID and valid non-placeholder URL), `Pwa` (non-blank ID), `QuickTile`, `VolumeAction`, `MediaAction`, `DisplayAction`, `SystemAction`, `SettingsShortcut`, `IntentAction` (non-blank URI), and non-widget `PageWindow`.
  - **Strictly Excluded Targets**: `Folder`, `PopupWidget`, `Widget`, `Spacer`, recursive `FloatingTrigger`, `page_window:widget`, and malformed/incomplete known-prefix IDs (preventing `popup_widget:` or invalid known-prefix IDs from falling back to `LegacyContainer`), while preserving backward compatibility for legacy container-navigation IDs (e.g. `left_0_swipe_right`, `notes`).
  - **Picker & Runtime Enforcement**:
    - Filtered `FloatingTriggerStore.discoverExistingSidebarGridElements()` using `FloatingTriggerCompat.resolveExecutableItem(...)`.
    - Updated `AddElementActivity` full-picker fallback (`IS_FLOATING_TRIGGER_SELECTION == true`) to hide `Widget`, `Popup Widget`, `Folder`, and `Floating Trigger`, force `IS_HYBRID_GRID = false` in `launchFullFloatingTriggerPicker` so inner pickers return `ELEMENT_ID` to `requestCode = 700` instead of inserting raw items into a Hybrid Grid, and guard `handleElementSelected` / `onActivityResult(700)` with `FloatingTriggerCompat.validateTarget(...)` + user-facing `AlertDialog`/`Toast` explanations.
    - Enforced validation in `FloatingTriggerManager` across `handleSidebarTriggerItem`, `toggleTriggerForElement`, `ensureActiveTrigger`, `executeAssociatedElement` (showing clear user feedback instead of silently attaching/running dead triggers), and `restoreActiveTriggers()` (safely skipping & deactivating unsupported persisted targets without crashing).
- **2. `app/LiteReader_Log.txt` & `LogKeeper.kt` Cleanup**:
  - Verified lines 1198–1221 (`2026-11-19 21:02:...`, `[pid-0] [Test worker]`) in `app/LiteReader_Log.txt` were generated during the Phase 14.22 JVM test run when mock `Context.filesDir == null` resolved `File(null, "LiteReader_Log.txt")` to `app/LiteReader_Log.txt`.
  - Removed ONLY those 24 newly appended lines from `app/LiteReader_Log.txt` (preserving lines 1–1197 verbatim) and guarded `LogKeeper.kt` (`appendToFile`, `readLogFile`, `deleteLogFile`) against `context.filesDir == null` so future JVM unit tests do not pollute the repository file.
- **3. Verification**:
  - `compile_applet` succeeded (`Build succeeded - the applet is compiled`).
  - Targeted JVM unit tests (`--rerun-tasks`) passed **156/156** across 8 suites (`FloatingTriggerTest` 11/11, `ElementIdParserTest` 12/12, `ElementSystemTest` 4/4, `FloatingMiniAppFrameworkTest` 98/98, `ReaderMiniAppTest` 18/18, `SettingsNavigationStructureTest` 2/2, `DictionaryAndTranslationMiniAppTest` 5/5, `SelectionActionsAndReadAloudTest` 6/6; 0 failures/errors/skipped), and `app/LiteReader_Log.txt` stayed clean at 1197 lines. Physical-device testing remains required for live overlay picker dialogs, Hybrid Grid trigger placement, and Toast/dialog feedback.

### Phase 14.24: Floating Trigger `PageWindow` Registry Audit & Quick Calls Sidebar Feature (COMPLETED)
- **1. Floating Trigger `PageWindow` Eligibility Hardening (`HeavyFloatingHost.kt`, `FloatingTriggerStore.kt`, `FloatingTriggerTest.kt`)**:
  - Audited `ElementActionDispatcher` -> `MiniAppManager` -> `FloatingMiniAppBridge` -> `HeavyPageHostManager.createEmbeddedPage` -> `HeavyFloatingHost` execution paths. Found that `HeavyFloatingHost.startInstance()` falls back to `defaultFactory` for unknown app types, and `FloatingTriggerCompat.validateItem()` previously accepted any non-blank non-`widget` `PageWindow` base type (such as `page_window:not_a_real_app`).
  - Added `HeavyFloatingHost.isFactoryRegistered(appType)` and `getRegisteredAppTypes()` so eligibility checks query the live factory registry rather than relying solely on a hardcoded list.
  - Updated `FloatingTriggerCompat.validateItem(context, item)` / `isSupportedPageWindowType(context, baseType)` to accept all genuinely supported Main-only mini-apps (`reader`, `epub_reader`, `text_reader`, `calculator_floating`, `note_floating`, `notes_floating`, `dictionary_floating`, `translation_floating`), Heavy embedded non-widget page views (`calculator`, `compass`, `scheduler`, `notifications`, `notification`, `app_tracker`, `resources_tracker`, `media_player`, `hybrid_grid`, `default_hybrid`, `tools`, `apps`, `apps_grid`, `widgets_grid`, `widgets`), and registered `HeavyFloatingHost` factories (including dynamically registered factories via `registerFactory(...)`).
  - Strictly rejected `page_window:widget`, `page_window:single_widget`, malformed colon IDs (`page_window:`, `page_window::inst`, `page_window:calculator:`), and unknown/unregistered types (`page_window:not_a_real_app`), while preserving all valid existing Floating Trigger targets and legacy container-navigation IDs.
  - Added 4 targeted regression tests in `FloatingTriggerTest` covering every supported registered `PageWindow` type, dynamic factory registration/unregistration, unknown/widget/malformed IDs (with zero execution), and safe deactivation on `restoreActiveTriggers()`.
- **2. Quick Calls Sidebar Element (`QuickCallStore.kt`, `QuickCallContactsLoader.kt`, `QuickCallActionHandler.kt`, `QuickCallPermissionActivity.kt`, `QuickCallPickerActivity.kt`, `QuickCallEditActivity.kt`, `SidebarItem.kt`, `ElementIdParser.kt`, `ElementActionDispatcher.kt`, `AddElementActivity.kt`, `SidebarAppsManager.kt`, `ElementViewRenderer.kt`, `HybridGridPageView.kt`, `AppsPageView.kt`, `WidgetsGridPageView.kt`, `FloatingTriggerManager.kt`, `IconPickerActivity.kt`, `AndroidManifest.xml`, `QuickCallTest.kt`)**:
  - **Storage & Privacy**: Added dedicated `SidebarItem.QuickCall` with UUID-based element IDs (`quick_call:<uuid>`) that never embed phone numbers. Persisted `QuickCallRecord` snapshots in `QuickCallStore` (`SharedPreferences` + synchronous `.commit()`) so saved Quick Calls survive restarts, contact deletions, and `READ_CONTACTS` revocation. Enforced zero phone-number PII in `LogKeeper` entries.
  - **Contacts Picker & Permissions**: Added a **Quick Calls** section in `AddElementActivity` launching Compose M3 `QuickCallPickerActivity`. Queries `ContactsContract.CommonDataKinds.Phone` on-demand (picker open / explicit Refresh only; zero `READ_CALL_LOG`, ContentObservers, workers, or polling), requests `READ_CONTACTS` only when opening Quick Calls if missing, groups & deduplicates multi-number contacts with a number-selection dialog, filters out invalid numbers, and supports search by name or digits.
  - **Calling Execution & Safety**: Declared `CALL_PHONE`, `tel:` `<queries>`, and optional telephony `<uses-feature>` in `AndroidManifest.xml`. Routed `SidebarItem.QuickCall` through `ElementActionDispatcher` -> `QuickCallActionHandler`: emergency numbers route strictly to `ACTION_DIAL` (never `ACTION_CALL`), missing `CALL_PHONE` launches translucent `QuickCallPermissionActivity` only on first direct-call attempt with an `ACTION_DIAL` fallback dialog on denial, and missing call/dialer handlers fail gracefully with user feedback. Left Call Recorder completely untouched.
  - **Editing, Custom Icons & Floating Trigger Compatibility**: Wired long-press Edit across `ElementViewRenderer`, `HybridGridPageView`, and `AppsPageView` into Compose M3 `QuickCallEditActivity` to edit display name, validated phone number (authoritatively setting `manuallyEditedNumber = true`), custom icon via zero-permission `PickVisualMedia` + `IconCacheManager`, and Reset Icon back to default Android caller icon (`android.R.drawable.sym_action_call`). Marked valid `SidebarItem.QuickCall` items as eligible standalone Floating Trigger targets in `FloatingTriggerCompat` and `FloatingTriggerManager`.
- **3. Verification**:
  - `compile_applet` succeeded (`Build succeeded - the applet is compiled`).
  - Targeted JVM unit tests (`--rerun-tasks`) passed **169/169** across 9 suites (`QuickCallTest` 9/9, `FloatingTriggerTest` 15/15, `ElementIdParserTest` 12/12, `ElementSystemTest` 4/4, `FloatingMiniAppFrameworkTest` 98/98, `ReaderMiniAppTest` 18/18, `SettingsNavigationStructureTest` 2/2, `DictionaryAndTranslationMiniAppTest` 5/5, `SelectionActionsAndReadAloudTest` 6/6; 0 failures/errors/skipped), and `app/LiteReader_Log.txt` stayed clean at 1197 lines. Physical-device QA remains outstanding for real contacts loading, `READ_CONTACTS`/`CALL_PHONE` runtime dialogs, `ACTION_CALL`/`ACTION_DIAL` handoff, custom icon picking, and Floating Trigger overlay execution.

### Phase 14.25: Functional Offline StarDict Dictionary & Translation Engine Architecture (SPLIT: StarDict ACTIVE in Sidebar / ML Kit Translation DEFERRED to Vian World in Phase 14.26)
- **1. Completed & Active in Sidebar — Shared Offline StarDict Dictionary Engine (`feature/language/dictionary/DictionaryModels.kt`, `StarDictFormatParser.kt`, `StarDictDiskIndex.kt`, `StarDictImporter.kt`, `DictionaryService.kt`, `DictionaryMiniAppInstance.kt`, `MeaningSelectionActivity.kt`, `DictionarySettingsScreen.kt`)**:
  - Pure-Kotlin offline StarDict engine supporting ZIP archives with 1 or more dictionaries (`.ifo`, `.idx`/`.idx.gz`, `.dict`/`.dict.dz`, optional `.syn`), StarDict v2.4.2 and v3.0.0 with 32-bit and 64-bit `.idx` offsets, textual types `m`, `h`, `x`, `g`, `t`, `w` (both `sametypesequence` and self-tagged records), UTF-8 headwords/definitions, and graceful fallback for unsupported binary resources (`W`, `P`, etc.).
  - Streaming `.dict.dz` and `.idx.gz` decompression via `GZIPInputStream` (no full-file RAM loading), bounded-RAM external merge-sort disk index (`sorted_keys.bin` + `key_offsets.bin`) with `O(log N)` `RandomAccessFile` lookup supporting exact, case-insensitive, `.syn` synonym, and priority-ordered multi-dictionary lookup with dictionary attribution.
  - Hardened import & storage lifecycle: ZIP path traversal rejection, disk-space precheck, duplicate fingerprint rejection, `.staging_<uuid>` cleanup, atomic cross-process `filesDir/stardict_dictionaries/catalog.json` persistence (`:heavy` Settings <-> Main floating/popup views), and clean deletion removing both catalog entry and disk files.
  - Connected `DictionaryMiniAppInstance` (`appType = "dictionary"`), `MeaningSelectionActivity` (`Meaning` via `ACTION_PROCESS_TEXT`), and `DictionarySettingsScreen` (`ROUTE_DICTIONARY_SETTINGS` / `dictionary_settings`, plus legacy alias `dictionary_translation_settings`), with zero storage permissions (`ActivityResultContracts.OpenDocument`). Removed `ReadAloudSelectionActivity` from `ACTION_PROCESS_TEXT` and custom Read Aloud from `MeaningSelectionActivity` while keeping Reader TTS untouched.
- **2. Deferred to Vian World (`deferred/vian-world/translation/`) — Google ML Kit On-Device Translation Engine**:
  - Originally implemented in Phase 14.25 with `com.google.mlkit:translate:17.0.3` and `com.google.mlkit:language-id:17.0.6` (`TranslationModels.kt`, `TranslationEngineBackend.kt`, `TranslationService.kt`, `TranslationMiniAppInstance.kt`, `TranslateSelectionActivity.kt`, `DictionaryTranslationSettingsDialogs.kt`, `TranslationServiceTest.kt`).
  - Removed from active Sidebar in Phase 14.26 due to secondary-process `MlKitContext` initialization issues in `:heavy` (`SettingsActivity`) and the ~64.12 MiB multi-ABI native/TFLite footprint; full source, tests, UI references, and integration checklist are preserved in `deferred/vian-world/translation/`.

### Phase 14.26: Defer ML Kit Translation & Language ID, Preserve StarDict & Prepare Vian World Handoff (COMPLETED)
- **1. Preserved Handoff Archive (`deferred/vian-world/translation/`)**:
  - Preserved all Translation sources (`TranslationModels.kt`, `TranslationEngineBackend.kt`, `TranslationService.kt`, `TranslationMiniAppInstance.kt`, `LanguageMiniAppUiHelperTranslationAdditions.kt`, `TranslateSelectionActivity.kt`, `DictionaryTranslationSettingsDialogs.kt`, `TranslationSettingsSectionReference.kt`), unit/integration tests (`TranslationServiceTest.kt`, `TranslationUiAndSelectionTest.kt`), and a comprehensive `README.md` (exact ML Kit dependencies, 64.12 MiB footprint breakdown, Sidebar integration hooks, `:heavy` `MlKitContext has not been initialized` root cause, and Vian World integration checklist) outside active Gradle source sets.
  - Preserved any existing on-device `filesDir/mlkit_translation/` preferences/models untouched (no destructive wipe).
- **2. Clean Active Removal (`app/build.gradle.kts`, `AndroidManifest.xml`, Floating/Sidebar/Settings/Selection modules)**:
  - Removed `com.google.mlkit:translate:17.0.3` and `com.google.mlkit:language-id:17.0.6` from `app/build.gradle.kts`; verified zero ML Kit/TFLite entries in both `debugRuntimeClasspath` and `debugCompileClasspath`.
  - Removed `.feature.selection.TranslateSelectionActivity` from `AndroidManifest.xml`, leaving `MeaningSelectionActivity` (`Meaning`) as the sole `ACTION_PROCESS_TEXT` entry.
  - Removed `"translation"` / `system:translation_floating` entry points from `AddElementActivity`, `SidebarItem`, `ElementActionDispatcher`, `FloatingTriggerStore`, `FloatingMiniAppBridge`, `FloatingMiniAppWindow`, `FloatingMiniAppLauncherBubble`, `HeavyFloatingHost`, `HeavyPageHostManager`, and `LanguageMiniAppUiHelper`, while adding defensive guards so stale persisted `"translation"` snapshots or `floating_trigger:system:translation_floating` / `floating_trigger:page_window:translation` targets safely skip/reject without crashing.
  - Replaced `DictionaryTranslationSettingsScreen` with clean StarDict-only `DictionarySettingsScreen.kt` (renaming Settings entry to `"Dictionary Settings"` at `dictionary_settings`, keeping `dictionary_translation_settings` alias routed to `DictionarySettingsScreen`, and routing legacy `translation_settings` to non-ML-Kit `DeferredTranslationNoticeScreen`).
- **3. Verification & APK Size Reduction**:
  - `compile_applet` succeeded (`Build succeeded - the applet is compiled`).
  - Targeted JVM unit tests (`--rerun-tasks`) passed **263/263** across 21 suites (`DictionaryMiniAppTest` 4/4, `StarDictDictionaryEngineTest` 6/6, `SelectionActionsAndReadAloudTest` 6/6, `SettingsNavigationStructureTest` 2/2, `FloatingTriggerTest` 15/15, `FloatingMiniAppFrameworkTest` 98/98, `ReaderMiniAppTest` 18/18, `ReaderArchitectureAndBackupTest` 13/13, `EpubParserTest` 14/14, `QuickCallTest` 9/9, `OfflinePwaFoundationTest` 20/20, `FloatingPwaMiniAppTest` 6/6, `ElementIdParserTest` 12/12, `ElementSystemTest` 4/4, `FolderElementLifecycleTest` 7/7, `HybridGridStorageScopeTest` 4/4, `LinkCustomTabIntegrationTest` 6/6, `VectorDrawablePathTest` 1/1, `NotificationHistoryBufferTest` 4/4, `MainProcessRecoveryTest` 8/8, `MainRuntimeRestorationTest` 6/6; 0 failures/errors/skipped). On an earlier full `:app:testDebugUnitTest` sweep before the `DictionaryMiniAppTest` assertion fix, the only unrelated failure was pre-existing `CallSensorIpcTest.testHeavyUnavailableAndQueueingBehavior` (missing `callManager.testEnabledOverride = true` in that test).
  - Clean `app-debug.apk` size dropped from **80.87 MiB** (Receipt 129 baseline) to **15.51 MiB** (`16,267,127` bytes / `16.27 MB` decimal), with zero `.tflite`/ML Kit native binaries remaining (only 4 CameraX `libimage_processing_util_jni.so` stubs totaling 132,208 bytes). `app/LiteReader_Log.txt` remained untouched at 1197 lines. Physical-device QA remains outstanding for live StarDict SAF ZIP import, floating overlay interaction, and host-app `Meaning` selection popup.

### Phase 14.27: Dictionary, Note & Shared System Light/Dark Theme Refinement (COMPLETED)
- **1. Translation Policy (Option B — Preserved Deferred)**:
  - Kept Translation deferred and hidden in `deferred/vian-world/translation/` (untouched). Added zero ML Kit dependencies, zero new translation engines, and zero active Translate entries in `AndroidManifest.xml` or `AddElementActivity`.
- **2. Dictionary Quick Meaning Popup & Full Dictionary Refinement (`DictionaryModels.kt`, `StarDictFormatParser.kt`, `StarDictDiskIndex.kt`, `DictionaryMiniAppInstance.kt`, `SelectionPopupShell.kt`, `MeaningSelectionActivity.kt`, `FloatingMiniAppBridge.kt`, `FloatingMiniAppWindow.kt`)**:
  - **Genuine Metadata Only**: Added optional `partOfSpeech: String? = null` to `DictionaryDefinitionEntry` and `DecodedDefinition`, extracted only when explicitly present in StarDict XDXF (`<gr>`/`<pos>`), HTML (`<pos>` / `pos` class), or unambiguous leading plain-text POS prefixes; never fabricates POS, pronunciation, or frequency/"Most Common" rankings. Added factual `extractConciseMeaning(...)` helper.
  - **Compact Quick Meaning Popup (`MeaningSelectionActivity.kt`, `SelectionPopupShell.kt`)**: Shows selected word, optional genuine phonetic & POS chip, concise primary meaning excerpt (plus factual `"+N more entries in Full Dictionary"` count when multiple definitions exist), primary `"Open Full Dictionary"` action calling `FloatingMiniAppBridge.openDictionaryWithQuery(term)` and dismissing the popup, Close button, and Settings shortcut.
  - **Single-Instance Handoff & Reader-Grade Lifecycle (`DictionaryMiniAppInstance.kt`, `FloatingMiniAppBridge.kt`, `FloatingMiniAppWindow.kt`)**: Added `DEFAULT_DICTIONARY_INSTANCE_ID = "miniapp_dictionary"` and `openDictionaryWithQuery(...)` to reuse/wake/focus a single Dictionary floating window without duplicates. Implemented full `prepareForSleep()` -> `releaseRuntime()` (cancels active `lookupJob`, clears `lastOutcome` buffer, clears IME focus, detaches/nulls views), wired `onResume()` from `FloatingMiniAppWindow.onShow()` with duplicate-job guard, and updated residual `"Dictionary & Translation Settings"` copy to `"Dictionary Settings"`.
- **3. Note Rename (`Test Notes` → `Note`) & Lifecycle Hardening (`AddElementActivity.kt`, `SidebarItem.kt`, `ElementIdParser.kt`, `SidebarAppsManager.kt`, `TestNotesMiniApp.kt`, `NotePersistenceCoordinator.kt`, `FloatingMiniAppBridge.kt`, `FloatingMiniAppWindow.kt`)**:
  - **Zero-Migration Rename**: Renamed all remaining user-facing `"Test Notes"` labels to `"Note"` across active Kotlin sources while preserving internal keys (`system:test_notes`, `test_notes`, `note_text`, `DurableMiniAppStore` snapshots) verbatim so existing saved notes and Sidebar placements survive untouched.
  - **Lifecycle & Permanent Delete Guard**: Hardened `NotePersistenceCoordinator` with `lifecycleEpoch`, `deletedPermanently`, `cancelPendingDebounce()`, `release()`, and `markDeletedPermanently()`. Wired `FloatingMiniAppBridge.closeMiniApp(..., deleteDurable = true)` to call `window?.prepareForPermanentDelete()` before `unregisterWindow`, preventing stale debounced saves or `onDestroy()` from resurrecting deleted notes while keeping flush-before-sleep and undo/redo history intact across fold/reopen.
- **4. Shared System Light/Dark Theme (`LanguageMiniAppUiHelper.kt`, `SelectionPopupShell.kt`, `MeaningSelectionActivity.kt`, `DictionaryMiniAppInstance.kt`, `FloatingMiniAppWindow.kt`, `TestNotesMiniApp.kt`)**:
  - Added `FloatingUiPalette`, `LIGHT_PALETTE`, `DARK_PALETTE` (compile-time ARGB integer constants), `isDarkTheme(context)`, `resolvePalette(...)`, and `adaptAccentForSurface(accentColor, isDark)` following Android's system light/dark mode (`Configuration.UI_MODE_NIGHT_MASK`).
  - Applied dynamically across Quick Meaning popup, Dictionary floating window, and Note floating window (including runtime `refreshSystemThemeIfApplicable()` on configuration change) while leaving Reader (`isReaderApp()`) 100% untouched.
- **5. Verification**:
  - `compile_applet` succeeded (`Build succeeded - the applet is compiled`).
  - Targeted JVM unit tests (`--rerun-tasks`) passed **195/195** across 12 suites (`DictionaryMiniAppTest` 6/6, `StarDictDictionaryEngineTest` 7/7, `NoteRefinementAndLifecycleTest` 3/3, `FloatingMiniAppFrameworkTest` 98/98, `FloatingTriggerTest` 15/15, `SelectionActionsAndReadAloudTest` 6/6, `ElementIdParserTest` 12/12, `QuickCallTest` 9/9, `ReaderMiniAppTest` 18/18, `ReaderArchitectureAndBackupTest` 13/13, `ScreenCapSettingsIntegrationTest` 6/6, `SettingsNavigationStructureTest` 2/2; 0 failures/errors/skipped).
  - Verified zero ML Kit in `app/build.gradle.kts` / `app/src/main`, zero `"Test Notes"` in `app/src/main/java/**/*.kt`, `deferred/vian-world/translation/README.md` intact, and `app/LiteReader_Log.txt` untouched at 1197 lines. Physical-device QA remains required for live system Light/Dark transitions, `ACTION_PROCESS_TEXT` popup -> floating window handoff, Dictionary IME/sleep/wake, Note editing/deletion, and Reader visual regression check.

### Phase 14.28: Dictionary Handoff Reliability & Note Lifecycle Integration Tests (COMPLETED)
- **1. Failure-Aware Quick Meaning → Full Dictionary Handoff (`FloatingMiniAppWindow.kt`, `FloatingMiniAppBridge.kt`, `MeaningSelectionActivity.kt`, `FloatingWindowManager.kt`)**:
  - **Root Causes Addressed**:
    - `FloatingMiniAppBridge.openMiniApp()` previously ignored the `Boolean` result of `FloatingWindowManager.showWindow(instanceId)`, returning an unattached `CREATED` window with `dictionaryInstance == null` when overlay permission was missing or `WindowManager.addView(...)` threw.
    - `MeaningSelectionActivity.openFullDictionaryAndDismiss()` previously swallowed all `Throwable`s and called `finish()` unconditionally, losing the user's selected word and displayed definition on handoff failure.
  - **Verified Handoff API (`FloatingMiniAppBridge.DictionaryHandoffResult`)**: Added `openDictionaryWithQueryResult(query, instanceId = DEFAULT_DICTIONARY_INSTANCE_ID)` reusing the single `"miniapp_dictionary"` instance, explicitly verifying `window.isAttached && window.state == WindowState.VISIBLE && window.isDictionaryReady()` and `window.openDictionaryQuery(trimmed)`, cleaning up any newly-created unattached zombie window on failure, reporting structured failure codes (`OVERLAY_PERMISSION_DENIED`, `WINDOW_SHOW_FAILED`, `DICTIONARY_NOT_READY`, `HANDOFF_EXCEPTION`), and logging non-content diagnostics via `LogKeeper`. Preserved `openDictionaryWithQuery(...)` for source compatibility.
  - **Non-Destructive Quick Meaning UI (`MeaningSelectionActivity.kt`)**: Replaced blind catch+`finish()` with `attemptOpenFullDictionary(...)`. Dismisses the popup ONLY on verified `Success`; on `Failure` or exception, keeps the popup open, preserves `selectedText`, `activeQueryTerm`, `lastOutcome`, and rendered definition views, displays an inline non-destructive error banner (`TAG_HANDOFF_ERROR_BANNER`), updates the action label to `"Retry Full Dictionary"`, and logs `QUICK_MEANING_HANDOFF_FAILED` via `LogKeeper` without logging user query text.
  - **Dictionary Handoff Tests (`DictionaryMiniAppTest.kt`)**: Added 4 focused tests verifying (1) successful handoff opens Full Dictionary and dismisses popup, (2) failed `WindowManager.addView` handoff keeps popup open and preserves result/error banner with zero zombie windows, (3) retry after failure succeeds cleanly, and (4) repeated handoffs reuse a single Dictionary instance without duplicates.
- **2. Deterministic Real Note Fold/Wake Integration Tests (`NoteRefinementAndLifecycleTest.kt`, `FloatingMiniAppWindow.kt`, `DurableMiniAppStore.kt`, `FloatingWindowManager.kt`)**:
  - Audited the real production Note fold/wake pipeline (`FloatingMiniAppBridge.openMiniApp` -> live edits/formatting/accent/undo-redo -> `FloatingMiniAppBridge.sleepMiniApp` -> `flushPendingSaveAsync` -> runtime release & unregistration -> launcher bubble -> `FloatingMiniAppBridge.wakeMiniApp` -> `FloatingMiniAppBridge.closeMiniApp(deleteDurable = true)`). No production lifecycle defect was found; added test-only seams (`DurableMiniAppStore.setInstanceForTesting`, `FloatingWindowManager.resetForTesting`, `FloatingMiniAppWindow.applyNoteFormatting`) and `TemporaryFolder` isolation so tests never write into repo paths.
  - Added 2 deterministic integration tests using `NotePersistenceCoordinator.TEST_DISPATCH_IMMEDIATELY = false` and `CountDownLatch` (zero `Thread.sleep()`):
    - Full fold/wake/delete transaction verifying latest text right before fold, all 4 formatting settings (`fontSize`, `fontStyle`, `wordWrap`, `lineSpacing`), accent color, undo/redo history (including live undo & redo after wake), `MiniAppState.SLEEPING`, launcher bubble lifecycle, stale pending debounce callbacks ignored across fold/wake, and permanent deletion preventing resurrection.
    - Fold-failure abort path verifying that when `DurableMiniAppStore.saveSnapshot` returns `false`, `sleepMiniApp` aborts cleanly without destroying/unregistering the active window or spawning a launcher bubble, preserves live text/undo state, and folds cleanly once storage recovers.
- **3. Verification**:
  - `compile_applet` succeeded (`Build succeeded - the applet is compiled`) and `gradle :app:assembleDebug` succeeded (`app-debug.apk` = `16,420,024` bytes / ~15.66 MiB).
  - Focused JVM suites (`--rerun-tasks`): **126/126** passed across 5 suites (`DictionaryMiniAppTest` 10/10, `StarDictDictionaryEngineTest` 7/7, `SelectionActionsAndReadAloudTest` 6/6, `NoteRefinementAndLifecycleTest` 5/5, `FloatingMiniAppFrameworkTest` 98/98; 0 failures/errors/skipped).
  - Full `:app:testDebugUnitTest` run: **532/533** passed across 58 suites; the sole failure is the pre-existing unrelated `CallSensorIpcTest.testHeavyUnavailableAndQueueingBehavior` (`CallSensorIpcTest.kt:279`, previously documented in Receipt 130 and left untouched outside Phase 14.28 scope).
  - Verified `app/LiteReader_Log.txt` untouched at 1197 lines, zero ML Kit in active build/source, `deferred/vian-world/translation/README.md` intact, and stray `app/floating_miniapps/` removed. Physical-device QA remains required for live overlay permission failure/recovery, `ACTION_PROCESS_TEXT` popup handoff, and real Android Note fold/wake/delete behavior.

### Phase 14.29: Ephemeral Floating HTML / MHT Viewer (CODE COMPLETE — PENDING ON-DEVICE QA)
- **1. Dedicated Main-Process-Only Ephemeral Viewer Architecture (`feature/htmlviewer/*`, `FloatingMiniAppBridge.kt`, `FloatingMiniAppWindow.kt`, `FloatingMiniAppLauncherBubble.kt`, `App.kt`, `AndroidManifest.xml`)**:
  - Implemented a completely separate, one-time-use floating webpage viewer (`appType = "html_mht_viewer"`) without modifying EPUB Reader, `AddElementActivity`, `ALL_FLOATING_WINDOWS`, `FloatingTrigger*`, `app/build.gradle.kts`, or `:heavy` services.
  - Registered exported Main-process `.feature.htmlviewer.HtmlMhtViewerActivity` (`label="Vian Sidebar"`, `excludeFromRecents="true"`) for `ACTION_VIEW` on `content://` and `file://` URIs only with explicit HTML/MHT MIME types (`text/html`, `application/xhtml+xml`, `multipart/related`, `message/rfc822`, `application/x-mimearchive`, `application/mime`, `text/mhtml`), a null-MIME tiered `android:pathPattern` extension fallback, and an `application/octet-stream` filter strictly scoped by the same tiered `android:pathPattern` entries (`.html`, `.htm`, `.mht`, `.mhtml` & uppercase, 1–4 dots). Zero `*/*`, zero `http`/`https`, zero new storage permissions.
- **2. Format Detection, Zero-Copy MHTML Parser & Offline Sanitizer (`HtmlMhtFormatDetector.kt`, `MhtmlParser.kt`, `MhtmlDecoderUtils.kt`, `HtmlDocumentSanitizer.kt`)**:
  - `HtmlMhtFormatDetector`: Validates `content`/`file` URI scheme, rejects known binary magic (PDF, ZIP/EPUB/APK, PNG, JPEG, GIF, ELF, dense NULs), performs BOM-aware header sniffing (including UTF-16LE/UTF-16BE/UTF-8 and leading-blank-line / spaced `boundary =` MHTML headers), resolves MIME/extension disagreements safely, and bounds raw reads to `10 MiB`.
  - `MhtmlParser` + `MhtmlDecoderUtils`: Zero-copy `ByteSlice` multipart scanning (never copies embedded image/media/font/CSS/JS/attachment payloads), extracts only the primary `text/html` or `application/xhtml+xml` part (preferring RFC 2387 `start="<cid>"` Content-ID when declared; max `5 MiB` decoded, max 256 parts, max depth 4), handles `7bit`/`8bit`/`binary`, RFC 2045 `quoted-printable` (including trailing-whitespace soft breaks), RFC 2045 MIME `base64`, BOM charset detection before UTF-8 fallback, multi-word RFC 2047 `Subject:` decoding, and accurate leading+trailing ignored part counts.
  - `HtmlDocumentSanitizer`: Quote-aware stripping of `<script>`, `<iframe>`, `<object>`, `<embed>`, `<applet>`, `<picture>`, `<video>`, `<audio>`, `<img>`, `<source>`, `<meta http-equiv="refresh">`, document-authored `<meta name="viewport">` (so `user-scalable=no` cannot lock pinch-zoom), `<link>`, and `<base>`; scrubs CSS `url(...)` and `@import` strictly inside `<style>` blocks and `style="..."`/`style='...'` attributes (preserving visible body prose containing `url(...)`); hides `svg image`; injects strict offline CSP (`default-src 'none'; style-src 'unsafe-inline'; img-src 'none'; script-src 'none'; ...`) and zoom-capable viewport without `overflow-x: hidden`.
- **3. Strict WebView Sandbox, FileProvider-Isolated Cache & Ephemeral Lifecycle (`HtmlMhtCacheManager.kt`, `HtmlMhtWebViewSecurity.kt`, `HtmlMhtViewerMiniAppInstance.kt`, `FloatingMiniAppBridge.kt`, `FloatingMiniAppWindow.kt`, `App.kt`)**:
  - `HtmlMhtCacheManager`: Stores randomized UUID temp files strictly under `context.codeCacheDir/ephemeral_html_viewer/` (outside `res/xml/file_paths.xml`, never exposed through `FileProvider`), enforces a single unified `MAX_PREPARED_HTML_BYTES` (`5 MiB + 64 KiB` guard headroom) across write and read, and sweeps abandoned files (including any legacy `cacheDir/ephemeral_html_viewer/` files) on Main process startup in `App.onCreate()`.
  - `HtmlMhtWebViewSecurity` / `RealAndroidWebViewHandle`: Enforces JS off, `blockNetworkLoads = true`, `blockNetworkImage = true`, `loadsImagesAutomatically = false`, `allowFileAccess = false`, `allowContentAccess = false`, `allowFileAccessFromFileURLs = false`, `allowUniversalAccessFromFileURLs = false`, DOM storage/DB/geolocation/multi-window off, `LOAD_NO_CACHE`, `MIXED_CONTENT_NEVER_ALLOW`, blocks all navigation except `about:blank`/`about:blank#...`, blocks all subresources with empty 403 responses, disables long-click action-mode popups and JS dialogs for `TYPE_APPLICATION_OVERLAY` crash immunity, and wires `onPause()`/`onResume()`.
  - Ephemeral in-memory `EphemeralHtmlViewerSession`: Guarded against `DurableMiniAppStore` writes across open, drag, resize, maximize, bubble drag, fold, unfold, close, `openMiniApp`, `toggleMiniApp`, and `restoreActiveMiniApps()`. Fold destroys WebView and retains only the temp file reference; unfold recreates a single hardened WebView; fold bubble attach failure, wake failure, open failure, activity coroutine cancellation, close (`×`), and non-folded direct `unregisterWindow()` immediately destroy WebView, drop session, create zero bubbles, and delete the temp file.
- **4. Phase 14.29 Reference-Based Audit & Targeted Hardening Follow-up (Receipt 134, Baseline `e945802c826049c46f03df9af47e81cb337fd65`)**:
  - Fixed 6 confirmed source-level defects without changing architecture or the offline/image-free/no-JS policy:
    1. Replaced API-31-only `android:pathAdvancedPattern` (ignored on Android 8–11 `minSdk = 26` and still non-backtracking on `.*`) with API 26–35-compatible 4-tier multi-dot `android:pathPattern` entries plus a path-scoped `application/octet-stream` filter in `AndroidManifest.xml`.
    2. Closed coroutine cancellation temp-file leak in `HtmlMhtViewerActivity` using an `AtomicReference<File?>` + `finally` cleanup guard and stripped `DocumentsContract` volume prefixes (`primary:`, `msf:`) in fallback display-name extraction.
    3. Closed fold bubble attach-failure orphan leak in `FloatingMiniAppBridge.sleepMiniApp` (`showEphemeralHtmlLauncherBubble` now verifies `showWindow && isAttached`, cleaning up session and temp file on failure).
    4. Stripped document-authored `<meta name="viewport">` in `HtmlDocumentSanitizer` so saved mobile pages with `user-scalable=no` / `maximum-scale=1.0` cannot lock pinch-zoom.
    5. Added RFC 2387 `start="<cid>"` primary HTML part preference and UTF-16/UTF-8 BOM detection before UTF-8 fallback in `MhtmlParser`, plus leading-newline / spaced `boundary =` sniffing in `HtmlMhtFormatDetector`.
    6. Included `isHtmlMhtViewerApp()` in `FloatingMiniAppWindow.refreshSystemThemeIfApplicable()` for live Light/Dark top-bar accent parity.
  - **Verification (Local Build & JVM Unit Tests Only — NOT Tested on Physical Android Device)**:
    - `compile_applet` succeeded (`Build succeeded - the applet is compiled`) and `gradle :app:assembleDebug` succeeded (`app-debug.apk` = `16,879,209` bytes / ~16.10 MiB, zero new dependencies).
    - Focused Phase 14.29 suites (`--rerun-tasks`): **36/36** passed (`HtmlMhtParserAndFormatTest` 16/16, `HtmlMhtViewerLifecycleAndSecurityTest` 20/20; 0 failures/errors/skipped).
    - Full `:app:testDebugUnitTest --rerun-tasks`: **567/569** passed across **60 suites**; 2 unrelated failures: pre-existing `CallSensorIpcTest.testHeavyUnavailableAndQueueingBehavior` (`CallSensorIpcTest.kt:279`) and a timing-sensitive `ReaderArchitectureAndBackupTest.testPreventFalseNextProgressShortChapterCompletionAndResumeSeparation` (`ReaderArchitectureAndBackupTest.kt:593`) which passed **13/13** on immediate solo re-run.
    - `app/LiteReader_Log.txt` verified untouched at 1197 lines; all 8 `feature/htmlviewer/*.kt` files are 155–446 lines (<500 cap); zero stray test files.
    - **Remaining Blocker for Full Completion**: Physical Android device QA is still required across real file managers (Android 8–14 **Open with** chooser matching), real Chromium WebView rendering/scrolling/pinch-zoom inside `TYPE_APPLICATION_OVERLAY`, fold/unfold RAM release, and temp cache file deletion on close/restart.

---

## Phase 14: PWA Engine & External AI App Integration (Tier 5 - Highest Complexity)
- [ ] **14.1 Local PWA Runner Core**: Embedded lightweight local HTTP/asset server (`PwaServer`), `PwaWindowManager`, `PwaDatabase`, and ZIP manifest importer (`PwaImportActivity`).
- [ ] **14.2 Dual-Mode Windowing**: Floating overlay for lightweight PWAs vs. Fullscreen `PwaActivity` for complex web applications.
- [ ] **14.3 Remote AI Host App IPC**: `RemotePwaRepository` / `ContentResolver` querying `content://<ai_app_authority>/pwas`, asset streaming via `openFile()`, and state sync via `SidebarBridge.saveData()`.
- [ ] **14.4 Headless Local AI Inference Client**: AIDL streaming client for background token inference and dynamic assistant tools.

---

## Phase 15: Polish, Launcher Prep & Finalization
- [ ] Implement Advanced Floating Grouping & Snapping.
- [ ] JSON Backup & Restore for all handles, pages, and floating window states.
- [ ] Eradicate legacy `reference/` directory and prepare architecture hooks for external launcher merge.
