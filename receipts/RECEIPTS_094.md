# Receipts Ledger - Part 094

* Timestamp: 2026-09-04T15:21:00-07:00
* One-line summary: Expanded NetSpeed resource proof-of-concept to 0, 1, and 2 kB/s states in live HandleService callback.
* Exact files touched:
  - `app/src/main/res/drawable-xhdpi/ic_stat_speed_0_k.png`
  - `app/src/main/res/drawable-xhdpi/ic_stat_speed_1_k.png`
  - `app/src/main/res/drawable-xhdpi/ic_stat_speed_2_k.png`
  - `app/src/main/java/com/example/core/HandleService.kt`
  - `receipts/RECEIPTS_094.md`
* What was actually done:
  - Pre-rendered three 96x96 ARGB_8888 single-drawable resource icons in `app/src/main/res/drawable-xhdpi/`:
    * `ic_stat_speed_0_k.png`: "0" over "kB/s"
    * `ic_stat_speed_1_k.png`: "1" over "kB/s"
    * `ic_stat_speed_2_k.png`: "2" over "kB/s"
  - Used exact accepted Vian geometry and typography: Roboto Condensed Bold 68px at baseline (48, 52), Roboto Bold 36px at baseline (48, 95), centered layout, and alpha threshold 80.
  - Placed all icons exclusively in `app/src/main/res/drawable-xhdpi/` for Redmi A5 / 320 dpi.
  - Kept existing `ic_stat_speed_43_mb.png` and `ic_stat_speed_108_k.png` preserved in repository.
  - Updated live speed notification callback `HandleService.updateSpeedNotification` to check:
    if `speedUnit.equals("kB/s", ignoreCase = true)` and `speedVal` is `"0"`, `"1"`, or `"2"`, selects corresponding `Icon.createWithResource(this, R.drawable.ic_stat_speed_X_k)` (`RESOURCE` mode).
  - Configured all other values to continue using runtime Canvas generator `dynamicSpeedIconGenerator.generateSpeedIcon(speedVal, speedUnit)` (`RUNTIME` mode) untouched.
  - Retained live diagnostic logging in `IconDiagnostics` confirming `mode=RESOURCE`, `resName`, and `setSmallIconReceived=true, notifyExecuted=true`.
  - Maintained startup flow, DENSITY_NONE, alpha cleanup threshold 80, NetSpeedManager, and notification channels without modification.
* How it was verified: local build only (`compile_applet`, `gradle assembleDebug`).
* Deviations: None.
* Known issues: None.
