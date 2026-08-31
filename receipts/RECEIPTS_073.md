# Session Receipts - Floating Apps Migration Fixes (Part 6)

## Actions Taken
- Fixed `DictionaryPopupActivity` which was missing `import android.content.Intent` after the previous regex accidentally deleted all instances of it.
- Removed unresolved imports for `ActiveAppTracker`, `LogKeeper`, and `ServiceLifecycleOwner` in `FloatingBrowserWindowManager` and `FloatingReaderService`.
- Fixed `BubbleDrawable` instantiation in `ReaderHandleView` to correctly use `BubbleDrawable()` since it doesn't strictly require `null` for `Bitmap?` if it has a default, or it was passed too many arguments. (Changed `BubbleDrawable(null)` to `BubbleDrawable()`).

## Verification
- Re-running `gradle assembleDebug` to confirm build succeeds.
