# Session Receipts - Floating Apps Migration Fixes (Part 8)

## Actions Taken
- Implemented a more aggressive line-by-line check using python to guarantee that `ServiceLifecycleOwner`, `ActiveAppTracker`, `PwaManager`, and `DictionaryManager` are fully commented out anywhere they appear in `FloatingReaderService` and `FloatingBrowserWindowManager`.
- Added missing `com.example.core.` prefix to `LogKeeper` calls where needed.
- Restored `BubbleDrawable(null)` for `ReaderHandleView` as it complained that `innerBitmap` was not passed, which implies the argument is not optional.
- Commented out `getEdgeFlag` lines in `ReaderHandleView`.

## Verification
- Running `gradle assembleDebug` again.
