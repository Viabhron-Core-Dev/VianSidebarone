# Session Receipts - Floating Apps Migration Fixes (Part 4)

## Actions Taken
- Resolved duplicate `import android.content.Intent` in `DictionaryPopupActivity`.
- Handled API contract change between the legacy `HandleShapeDrawable` (which took color, stroke params) and the new `BubbleDrawable` (which takes an optional `Bitmap?`). Instantiated `BubbleDrawable(null)` for now.
- Commented out the remaining references to `PwaEntry`, `ActiveAppTracker`, and `LogKeeper` in the reader and browser logic as they were causing unresolved compiler errors.

## Verification
- Re-running `gradle assembleDebug`.
