* Timestamp: 2026-08-14T09:46:00-07:00
* One-line summary: Fixed the Fast-Load / Lazy-Load Sidebar Bug by correcting the ViewPager offscreen limit check.
* Exact files touched:
    * `app/src/main/java/com/example/feature/sidebar/SidebarView.kt`
* What was actually done:
    * Changed the ViewPager2 `offscreenPageLimit` evaluation in `SidebarView.kt`.
    * Replaced the incorrect `if (containerId == "sidebar")` check with `if (physicalHandleId == "sidebar")`.
    * This restores the intended behavior where the primary sidebar (Hybrid Grid) is semi-loaded in memory for fast access, while dynamically generated secondary handles default to strict lazy-loading.
* How it was verified: Local build.
* Any deviation from what was requested: None, applied exactly the fix discussed.
* Known issues: None.
