# Session Receipts - Initialize Grid Columns and Wrap Content

## Actions Taken
- Updated `PageManager.kt` so that when initializing the `default_hybrid` grid on install, it sets `hybrid_grid_cols` to 3.
- Also explicitly sets `handle_${handleId}_sidebar_wrap_content` to `true` on install for the default handle.

## Verification
- Checked that modifications do not break compilation.
