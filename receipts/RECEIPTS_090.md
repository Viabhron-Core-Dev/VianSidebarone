# RECEIPTS_090.md

- **Timestamp:** 2026-08-12T23:54
- **Requested:** Fix Phase 11 architectural flaw (ViewPager2 caching leaks).
- **Files touched:**
  - `app/src/main/java/com/example/feature/sidebar/ResourcesTrackerPageView.kt`
  - `app/src/main/java/com/example/feature/sidebar/SchedulerPageView.kt`
  - `app/src/main/java/com/example/feature/sidebar/NotificationPageView.kt`
- **What was done:**
  - Added `SidebarPageControllable` interface implementation to the remaining dual-mode pages.
  - In `ResourcesTrackerPageView`, moved the 1-second RAM/CPU polling coroutine and `Choreographer.FrameCallback` from `onAttachedToWindow()` to `onPageSelected()`. Canceled them in `onPageUnselected()`.
  - In `SchedulerPageView`, deferred the Room Database Flow collection job until `onPageSelected()` and canceled it in `onPageUnselected()`. Added missing `Job` import.
  - In `NotificationPageView`, moved the `AppNotificationListener` broadcast receiver registration and notification loading to `onPageSelected()`. Unregistered them in `onPageUnselected()`.
  - This ensures that while `ViewPager2` keeps the views attached to the window for fluid swiping, their heavy background logic is completely frozen (Zero CPU/RAM churn) when they are visually off-screen.
- **Verification:** Local build only (`gradle compileDebugKotlin`). Success.
- **Deviation:** None.
