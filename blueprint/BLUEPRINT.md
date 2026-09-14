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
