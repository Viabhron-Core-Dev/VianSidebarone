# Implementation Plan - Privacy Curtain (Anti-Peep Utility Element)

Integrate an on-demand "Privacy Curtain" (Anti-Peep / Peek Proof) screen masking feature as a new element under Utilities, matching the behavior found on Lava/Infinix devices with resizable rectangular viewing windows, touch pass-through, dark region swipe-to-reposition, and long-press dismissal.

## Proposed User Review Required

> [!IMPORTANT]
> **Touch Pass-Through Architecture**:
> To guarantee 100% native touch responsiveness for underlying apps (typing passwords, scrolling feeds, reading private chats) without requiring synthetic touch dispatches or accessibility delays, we will implement a **4-Panel Overlay Curtain** (`Top`, `Bottom`, `Left`, `Right` panels around the clear window). Because the center rectangular area contains no overlay window, Android routes touches directly to the active application underneath.
>
> **Gesture Mapping**:
> - **Inside Clear Rect**: 100% native pass-through to apps below.
> - **On Dark Areas**: Vertical drag moves the clear rectangular window up and down.
> - **Long Press on Dark Area**: Acts as the exit gate to dismiss the Privacy Curtain.
> - **Window Border Handle**: Allows resizing the width and height of the clear aperture.
> - **Mini Floating Pill**: Quick opacity slider (50% to 100% blackout) and close (X) button.

---

## User-Confirmed Requirements
1. **Window Shape**: Resizable rectangular window box.
2. **Touch Dynamics**: Touches pass directly through the clear window; swiping up/down on the dark area translates the viewing box; long-pressing the dark area triggers the exit gate.
3. **Activation**: On-demand toggle via the "Privacy Curtain" element in Utilities.
4. **Display Label**: `Privacy Curtain` in the element picker, sidebar, and container pages.

---

## Proposed Changes

### System Hub & Overlay Architecture

#### [NEW] `com.example.feature.system_hub.PrivacyCurtainManager`
Create a centralized manager for the Privacy Curtain overlay:
- **State Management**:
  - `isEnabled: Boolean`: Tracks whether the curtain overlay is active.
  - `windowBounds: Rect`: Coordinates (`left`, `top`, `right`, `bottom`) of the clear aperture.
  - `opacity: Float`: Darkness level of the curtain (0.5f to 1.0f, default 0.90f).
- **Window Management**:
  - Adds 4 synchronized dark overlay views (`Top`, `Bottom`, `Left`, `Right`) using `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`.
  - Configures `FLAG_NOT_FOCUSABLE` and `FLAG_LAYOUT_IN_SCREEN` / `FLAG_LAYOUT_NO_LIMITS` with `PixelFormat.TRANSLUCENT`.
- **Gesture Handling on Dark Panels**:
  - `GestureDetector` / `OnTouchListener` attached to the dark panels:
    - `ACTION_DOWN` + `ACTION_MOVE`: Translates `windowBounds.top` and `windowBounds.bottom` smoothly with finger delta.
    - Long-press listener (500ms hold on any dark panel) invokes `dismiss(context)`.
- **Aperture Controls & Resize Handle**:
  - A subtle resize tab at the corner of the clear rectangle allowing horizontal and vertical size adjustments.
  - A compact control pill with an opacity slider and an exit icon.

---

### Element System & Metadata Integration

#### `app/src/main/java/com/example/feature/sidebar/SidebarItem.kt`
- Add `SidebarItem.DisplayAction("privacy_curtain", "Privacy Curtain", android.R.drawable.ic_menu_view)` to `ALL_UTILITIES_ACTIONS`.

#### `app/src/main/java/com/example/feature/settings/AddElementActivity.kt` & `ActionPickerActivity.kt`
- Ensure `privacy_curtain` appears under "Utilities" when adding elements to container pages or floating triggers.

#### `app/src/main/java/com/example/feature/system_hub/DisplayHandler.kt`
- Add handling for `"privacy_curtain"` action:
  - Calls `PrivacyCurtainManager.toggle(context)`.
  - Broadcasts element state update to refresh sidebar and page icons.

#### `app/src/main/java/com/example/feature/element/ElementActionRegistry.kt`
- Register `display:privacy_curtain` so it is recognized by `ElementPlacementManager` and container pages without runtime errors.

---

## Verification Plan

### Automated JVM Tests
- Create unit test `PrivacyCurtainManagerTest` in `app/src/test/java/com/example/feature/system_hub/`:
  - Verify `toggle()` enables and disables the curtain state.
  - Verify boundary clamping (prevents clear window from moving off-screen).
  - Verify opacity clamping between 0.3f and 1.0f.
  - Verify action registration in `ElementActionRegistry`.

### On-Device Manual Verification
1. Open Sidebar or Settings -> Add Element -> Utilities -> select **Privacy Curtain**.
2. Tap the **Privacy Curtain** element:
   - Full screen dims/blacks out with a clear rectangular viewing window in the center.
3. Interact with the app underneath through the clear window:
   - Verify tapping, typing, and scrolling work normally in the underlying app.
4. Drag finger on the dark area:
   - Verify the clear viewing window moves up and down tracking the swipe.
5. Long-press on any dark region:
   - Verify the curtain immediately dismisses (exit gate).
