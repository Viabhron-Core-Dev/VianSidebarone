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

---

## Phase 13: Floating Window Mini-Apps (Ordered by Complexity & Difficulty)

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
