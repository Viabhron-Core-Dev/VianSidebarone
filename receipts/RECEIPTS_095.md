# Receipts Ledger - Part 095

* Timestamp: 2026-09-13T21:58:00Z
* One-line summary: Implemented Heavy-process Welcome page with singleTop launch, IPC routing, launch deduplication, and safe process-death isolation.
* Exact files touched:
  - `app/src/main/java/com/example/core/ipc/IpcModels.kt`
  - `app/src/main/java/com/example/core/ipc/WelcomeIpcContract.kt`
  - `app/src/main/java/com/example/feature/welcome/WelcomeActivity.kt`
  - `app/src/main/java/com/example/feature/welcome/HeavyWelcomeHostExtension.kt`
  - `app/src/main/java/com/example/feature/welcome/MainWelcomeController.kt`
  - `app/src/main/java/com/example/core/ipc/HeavyProcessHost.kt`
  - `app/src/main/java/com/example/MainActivity.kt`
  - `app/src/main/AndroidManifest.xml`
  - `app/src/test/java/com/example/feature/welcome/WelcomeIpcTest.kt`
  - `receipts/RECEIPTS_095.md`
* What was actually done:
  - Added `SHOW_WELCOME` to `HeavyCommandType` enum in `IpcModels.kt`.
  - Created `WelcomeIpcContract.kt` defining minimal command generation (`createShowWelcomeCommand`), target `welcome`, and reason payload (`setup`, `overlay_required`, `user_request`) without passing Main hierarchy or internal managers.
  - Created `WelcomeActivity.kt` in package `com.example.feature.welcome` under `:heavy` process, housing `WelcomeScreen` and preserving the exact existing layout, wording, test tags (`grant_overlay_button`, `net_speed_switch`, `start_core_button`, `stop_core_button`, `open_log_keeper_button`), and permission flows (overlay permission check, notification permission launcher).
  - Maintained `MainActivity` as a clean subclass of `WelcomeActivity` in `:heavy` with `singleTop` launchMode.
  - Declared `WelcomeActivity` in `AndroidManifest.xml` assigned strictly to `android:process=":heavy"` and `android:launchMode="singleTop"`.
  - Implemented `HeavyWelcomeHostExtension` and `DefaultHeavyWelcomeHostExtension` in `:heavy` to handle `SHOW_WELCOME` commands, launch `WelcomeActivity` with `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_SINGLE_TOP`, and deduplicate rapid repeated launches using active foreground tracking and a debounce threshold.
  - Wired `HeavyProcessHost` in `:heavy` to route `SHOW_WELCOME` commands directly to `HeavyWelcomeHostExtension`.
  - Implemented `MainWelcomeController` in the Main process (`com.example`) allowing Main components to request the Welcome UI on demand via `HeavyProcessConnectionManager` without holding any heavy UI or Compose dependencies.
  - Ensured process-death resilience: If the `:heavy` process is terminated or killed while Welcome is displayed, Main process runtime (`HandleService`, handles, gestures, internet speed monitor) continues unaffected without crashes or ANRs.
  - Wrote comprehensive unit test suite in `WelcomeIpcTest.kt` covering contract integrity and JSON serialization, Main request → Heavy Welcome routing, duplicate launch debouncing and active state suppression, and Main process resilience when Heavy is disconnected or dead.
* How it was verified: local build only (`gradle :app:testDebugUnitTest` - all unit tests passed; `compile_applet` passed cleanly).
* Deviations: None.
* Known issues: None.
