# Session Receipts - Floating Apps Migration Fixes (Part 3)

## Actions Taken
- Fixed `BrowserReceiverActivity`'s import of `FloatingBrowserService` which pointed to the wrong package level.
- Cleaned up duplicated and conflicting imports in `DictionaryPopupActivity` (e.g. `Intent`).
- Addressed `Unresolved reference: ActiveAppTracker` and `ServiceLifecycleOwner` in `FloatingBrowserWindowManager` and `FloatingReaderService` by commenting out legacy telemetry code that relied on them (since they haven't been ported yet).
- Replaced `HandleShapeDrawable` with `BubbleDrawable` in `ReaderHandleView` as `HandleShapeDrawable` does not exist in the new structure.

## Verification
- Running `gradle assembleDebug` again to ensure the compiler is happy.
