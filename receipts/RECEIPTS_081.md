# Session Receipts - Floating Apps Architecture Fix (Part 1)

## Actions Taken
- Reverted `Calculator` from a Floating Window back into a Sidebar Page. Moved `CalculatorPageView.kt` from `feature/miniapps/` to `feature/sidebar/` and deleted the wrapper `CalculatorFloatingWindow.kt`.
- Reverted `Compass` from a Floating Window back into a Sidebar Page. Moved `CompassPageView.kt` and `CompassDrawView.kt` from `feature/miniapps/` to `feature/sidebar/` and deleted the wrapper `CompassFloatingWindow.kt`.
- Renamed `DictionaryPageView` to `DictionaryView` to correctly reflect its status as a Floating Window Mini-App and dropped the misleading "PageView" terminology.
- Cleaned up `MiniAppManager.kt` by removing the Calculator and Compass launching logic, as they are no longer standalone bubbles.
- Registered `CalculatorPageView` and `CompassPageView` inside `SidebarView.kt` so they instantiate correctly when their page type is invoked by the Sidebar container.
- Updated packages and imports for all moved views to match their new location (`com.example.feature.sidebar`).
- Fixed remaining unresolved references to `serviceLifecycleOwner` in `FloatingReaderService.kt` that were causing compilation errors.

## Verification
- Ran `gradle assembleDebug`. The build is fully successful (`BUILD SUCCESSFUL in 44s`).
