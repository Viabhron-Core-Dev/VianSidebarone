# MASTER PLAN: Architecture, Multi-Container Gesture System & Clean Rebuild

## Executive Overview
This Master Plan defines the target clean architecture for the project, eliminating architectural bloat, enforcing sub-30 MB idle resident RAM, providing an exact pixel-grid NetSpeed monitor, separating gesture containers, establishing a 2-part Log Keeper, isolating heavy ML Kit / Floating Mini Apps into a separate process, and integrating the call recorder sensor/caller subsystem.

---

## 1. Process & Memory Architecture (The 4-Process Isolation Model)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ 1. :core Process (Resident Daemon — Target RAM: ~25-28 MB CONSTANT)         │
│    - Handles (1-4 Edge Touch Targets, WindowManager overlays)               │
│    - Lightweight Gesture Caller (IPC Dispatcher to containers/actions)      │
│    - Internet Speed Monitor (1:1 44px Xiaomi Status Bar Dynamic Icon)       │
│    - Call Recorder Caller / Sensor Wakeup Trigger                           │
│    - Log Keeper Catcher (Lightweight non-blocking event & RAM recorder)     │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │
            ┌──────────────────────────┼──────────────────────────┐
            │ Intent (Gesture)         │ Intent (Mini App)        │ User opens Settings
            ▼                          ▼                          ▼
┌─────────────────────────┐  ┌─────────────────────────┐  ┌─────────────────┐
│ 2. :sidebar Process     │  │ 3. :tools / :floating   │  │ 4. :ui Process  │
│ (Ephemeral Drawer)      │  │ (Mini Apps & ML Kit)    │  │ (Settings UI)   │
│ - Memory: 0 MB Idle     │  │ - Memory: 0 MB Idle     │  │ - Memory: 0 MB  │
│ - SidebarService        │  │ - FloatingWindowManager │  │   after exit    │
│ - Snaps to Handle Side  │  │ - Floating Dictionary   │  │ - Compose UI    │
│ - Multi-Containers      │  │ - Floating Translation  │  │ - Log Viewer UI │
│ - Pure XML View layouts │  │ - ML Kit OCR / Models   │  │ - finishAnd-    │
│ - stopSelf() on dismiss │  │ - Multi-Window Popups   │  │   RemoveTask()   │
│                         │  │ - Hard teardown on close│  │                 │
└─────────────────────────┘  └─────────────────────────┘  └─────────────────┘
```

---

## 2. Core Service & Sensor Subsystem (`:core`)

### 2.1 Resident Handle Manager & Lightweight Gesture Dispatch
* **Touch Handles**: 1-4 transparent or customizable overlay strips on screen edges.
* **Lightweight Gesture Caller**: Pure mathematical gesture detection (Swipe In, Swipe Up, Swipe Down, Tap, Double Tap, Long Press).
* **Direct IPC Dispatch**: Fires an explicit intent directly to `:sidebar` with the target `containerId` or executes system actions directly (e.g. Back, Home, Flashlight, Volume, Open Add Element) without spinning up the sidebar engine.

### 2.2 Dynamic NetSpeed Status Bar Monitor (Xiaomi Redmi Calibration)
* **Target Hardware Metric**: Xiaomi 320 dpi (2.0x density), `status_bar_icon_size` = 44px.
* **1:1 Native Drawing Formula**:
  * Bitmap size: exact 44x44 px (status bar slot dimension).
  * Density: explicitly stamped (`density = 320`).
  * Top 70% slot (0-31px): Centered numeric text (`sans-serif-condensed bold`), auto-scaled (26px for <=99, 22px for 3-4 digits like `14.8` or `1024`).
  * Bottom 30% slot (31-44px): Centered unit label (`KB/s` / `MB/s`, 11px).
  * Isolated buffer allocation per tick (no dirty shared IPC tearing).
  * Anti-aliasing flag enabled, zero subpixel/hinting blur.

### 2.3 Call Recorder Sensor & Trigger Subsystem
* **State Sensor**: Monitors Telephony state / Call State Audio Focus (e.g. `EXTRA_STATE_RINGING`, `EXTRA_STATE_OFFHOOK`, or Call Audio mode change).
* **Awakening Trigger**: Automatically awakens the Call Recorder engine upon active call connection and attaches call recording overlay / controls.

### 2.4 Compressed Icon Store (No Live App Scanning Overhead)
* **Zero Live Scans**: No background calls to `PackageManager.getInstalledApplications()`.
* **Compressed Disk Cache**: Downscaled $48 \times 48$ dp icons stored as local files; pages load items directly via serialized intent URIs and cached icons.

---

## 3. Ephemeral Sidebar & Multi-Container System (`:sidebar`)

### 3.1 Pure XML View Hierarchy for Instant Performance & 0 MB Teardown
* `SidebarView`, `HybridGridPageView`, `AppsPageView`, `ToolsPageView`, and `WidgetsGridPageView` are built using pure **Native Android XML Views / ViewGroups (`RecyclerView`, `ViewPager2`, `FrameLayout`)** for maximum speed and instant memory reclamation on dismiss.
* **Dynamic Snapping**: The single canonical `SidebarView` detects the triggering handle's screen edge (Left/Right) and vertical anchor, dynamically snapping its drawer gravity and entry animation.

### 3.2 Gesture-to-Container Independence
* **Handle 1 Gestures**:
  * `Swipe Right` $\rightarrow$ Container A (e.g. Default Hybrid Grid, Page 1: Hybrid, Page 2: Media)
  * `Swipe Up` $\rightarrow$ Container B (e.g. Tools Page)
* **Handle 2 Gestures**:
  * `Swipe Left` $\rightarrow$ Container C (e.g. Apps List)
  * `Swipe Down` $\rightarrow$ Container D (e.g. Widgets Grid)
* **First Chosen Page**: Each container configuration specifies its initial start page.

### 3.3 Connected Topbar Edit Mode & Universal Add Element
* **Topbar Edit Button**: Each page has a dedicated Edit button in its top bar to toggle item dragging, deletion, resizing, and the "+ Add Element" tile.
* **Universal `AddItemsActivity`**:
  * Reached directly via "+ Add Item" tile in Home Grid / App Grid edit mode or triggered as a standalone gesture action.
  * Categories: Apps (loaded on-demand only while this screen is open), Quick Tiles, Audio/Display, Folders, Widgets.
  * **Floating Mini Apps (Bottom Subsection)**: Dedicated placeholder and entry points for floating window mini-apps.

### 3.4 Ephemeral Lifecycle
* The `:sidebar` service is instantiated strictly on gesture receipt.
* Calling `closeWithAnimation`, outside touch, or item launch invokes `SidebarService.stopSelf()`, returning process memory to 0 MB.

---

## 4. Isolated Tools & Floating Mini-Apps (`:tools / :floating`)

### 4.1 Multi-Window Floating Engine (`FloatingWindowManager`)
* Manages concurrent, resizable, movable floating overlays with touch boundary clipping and minimize bubbles.
* **Mini-Apps Included**:
  1. **Floating Dictionary**: Instant word definition lookup popup.
  2. **Floating Translator / Screen OCR**: On-demand text capture and translation.
  3. **Floating Notes / Quick Calc**: Multi-window utility overlays.

### 4.2 Strict On-Demand ML Kit Lifecycle
* ML Kit OCR and Translation models are loaded **only inside `:tools`** when the user actively triggers a translation/lookup.
* Closing the floating window explicitly tears down the model (`close()`) and stops the `:tools` service, preventing 80+ MB of native memory from leaking into `:core` or `:sidebar`.

---

## 5. Log Keeper Architecture (2-Part Decoupled Model)

### Part A: Lightweight Event & Resource Catcher (`:core` / Background)
* **Zero Overhead**: In-memory ring buffer (up to 500 entries) with background file write.
* **Captured Telemetry**:
  * Lifecycle events (Handle creation, gesture dispatch, service start/stop).
  * Running processes & memory checkpoints (USS/PSS snapshots).
  * Error codes, stack traces, and component failure points.
* **Strict Privacy**: Master On/Off switch, zero PII, zero credentials, zero network payload capture.

### Part B: Log Viewer (`:ui` Process)
* **On-Demand Inspection**: Opened only when requested by the user from Settings.
* **Features**: Live filtering (Core, Sidebar, NetSpeed, Memory, Errors), single-tap log export to device storage, and buffer clearing.

---

## 6. Structural File Organization

```
app/src/main/java/com/example/
├── core/
│   ├── HandleService.kt              # Resident core touch daemon (:core ~25 MB)
│   ├── HandleManager.kt              # Manages 1-4 edge touch handle overlays
│   ├── GestureCaller.kt              # Lightweight gesture processor -> dispatches IPC
│   ├── NetSpeedManager.kt            # Network polling engine (1 Hz)
│   ├── DynamicSpeedIconGenerator.kt  # Exact Xiaomi 44px calibration
│   ├── CallSensorManager.kt          # Call recorder awakening trigger
│   ├── icon/
│   │   └── CompressedIconStore.kt    # Disk-cached 48dp icons (no live app scans)
│   └── log/
│       ├── LogCatcher.kt             # Lightweight telemetry & RAM monitor
│       └── MemorySnapshotUtil.kt     # Process & RAM monitor
├── model/
│   ├── ContainerConfig.kt            # Independent container configuration
│   ├── HandleGestureConfig.kt        # Gesture -> Container / Action bindings
│   └── SidebarItem.kt                # Item definitions (Apps, Tiles, Widgets, FloatingApps)
├── feature/
│   ├── sidebar/
│   │   ├── SidebarService.kt         # Ephemeral on-demand service (:sidebar)
│   │   ├── SidebarManager.kt         # Overlay window coordinator
│   │   ├── SidebarView.kt            # Single canonical sidebar view (snaps to handle side)
│   │   ├── AddItemsActivity.kt       # Universal Add Element screen (with bottom floating placeholders)
│   │   └── pages/
│   │       ├── HybridGridPageView.kt # Preserved Hybrid Grid with Topbar Edit Mode
│   │       ├── AppsPageView.kt       # Preserved Apps List/Grid with Topbar Edit Mode
│   │       ├── WidgetsGridPageView.kt# Preserved Widgets Grid with Topbar Edit Mode
│   │       ├── MediaPageView.kt      # Preserved Media Player
│   │       └── ToolsPageView.kt      # Preserved Quick Tools
│   ├── floating/
│   │   ├── FloatingWindowService.kt  # Dedicated service for :tools process
│   │   ├── FloatingWindowManager.kt  # Multi-window concurrent overlay manager
│   │   ├── FloatingDictionaryView.kt # Floating dictionary popup
│   │   └── ScreenTranslator.kt       # Strictly on-demand OCR & Translate
│   └── settings/
│       ├── SettingsActivity.kt       # Compose UI with finishAndRemoveTask()
│       ├── HandleGestureSettings.kt  # Map gestures to containers / Add Element action
│       ├── PageCustomization.kt      # Individual page adjustments & edit links
│       ├── LogViewerScreen.kt        # Log Keeper Viewer UI
│       └── NetSpeedSettingsScreen.kt # Dynamic speed calibration & preview
```
