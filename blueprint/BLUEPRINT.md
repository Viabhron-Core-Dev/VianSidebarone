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

---

## Phase 13: Floating Window Mini-Apps (Ordered by Complexity & Difficulty)

### 13.0 — Floating Windows Foundation & Heavy Process Boundary (Completed)
- [x] **Hierarchy Window Manager & Multi-Process Boundary**:
  - Main-process `FloatingWindowManager` remains strictly authoritative for hierarchy, Z-order, focus, layout parameters, touch interception, magnetic snapping, and folding.
  - `HeavyFloatingHost` & `HeavyFloatingHostService` in `:heavy` establish on-demand, disposable host infrastructure for floating mini-app instances without Main having compile-time dependencies on individual mini-apps.
  - `MiniAppContract`: Formalized `MiniAppRecord`, `MiniAppInstance`, `MiniAppLifecycleState`, and `MiniAppFactory` contracts.
  - `DurableMiniAppStore`: Atomic JSON file persistence (`filesDir/floating_miniapps/`) preserving mini-app state across Heavy process death, Heavy process restart, and full app restarts.
  - `FloatingMiniAppBridge`: Main-side coordinator bridging lifecycle events to `:heavy` via minimal IPC intents while maintaining all window hierarchy and Z-ordering in Main.

### 13.1 — Daily Utilities & Fast-Feedback Tools (Tier 1 - Low Difficulty)
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
