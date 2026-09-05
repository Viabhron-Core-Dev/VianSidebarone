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

* Timestamp: 2026-09-05T10:59:00-07:00
* One-line summary: Replaced dynamic Canvas status-bar icon engine with complete pre-rendered 96x96 resource-icon implementation (0-999 kB/s, 1.0-43.0 MB/s).
* Exact files touched:
  - `tools/generate_speed_icons.py`
  - `tools/fonts/RobotoCondensed-Bold.ttf`
  - `tools/fonts/Roboto-Bold.ttf`
  - `app/src/main/res/drawable-xhdpi/` (1,421 PNGs)
  - `app/src/main/java/com/example/core/SpeedIconProvider.kt`
  - `app/src/main/java/com/example/core/DynamicSpeedIconGenerator.kt` (deleted)
  - `app/src/main/java/com/example/core/HandleService.kt`
  - `receipts/RECEIPTS_094.md`
* What was actually done:
  - Created deterministic asset generator `tools/generate_speed_icons.py` using authentic bold TrueType fonts.
  - Rendered complete 1,421 pre-rendered 96x96 status-bar icons into `app/src/main/res/drawable-xhdpi/`:
    * 1,000 icons for kB/s (`0` through `999` kB/s, integer values): `ic_stat_speed_<val>_k.png`
    * 421 icons for MB/s (`1.0` through `43.0` MB/s, 0.1 increments): `ic_stat_speed_<d>_<f>_m.png`
  - Applied bolder glyph weight without expanding nominal bounds. Preserved exact baseline geometry: top number baseline at y=52 (centered at x=48), bottom unit baseline at y=95 (centered at x=48), white glyphs on transparent background, alpha cleanup threshold 80.
  - Placed assets exclusively in `drawable-xhdpi` (320 dpi / density 2.0) for Redmi A5, preventing Android OS density scaling artifacts.
  - Generated `SpeedIconProvider.kt` with compile-time `R.drawable` reference arrays for O(1) resource ID resolution and zero runtime rasterization.
  - Completely deleted `DynamicSpeedIconGenerator.kt` and purged all runtime Canvas, Paint, Bitmap, createWithBitmap, alpha thresholding, bitmap density manipulation, and memory trim hooks.
  - Updated `HandleService.kt`:
    * `buildNotification` now accepts `iconResId: Int` directly and calls `Notification.Builder.setSmallIcon(iconResId)`.
    * Initial and standby notifications use `SpeedIconProvider.resolve("0", "kB/s").resId`.
    * Live speed updates resolve the resource ID via `SpeedIconProvider.resolve(speedVal, speedUnit)` and invoke `setSmallIcon(selectedResId)`.
    * LogKeeper diagnostics log `LiveUpdate -> displayedVal, displayedUnit, mode=RESOURCE, resName, setSmallIconReceived=true, notifyExecuted=true`.
* How it was verified: local build only (`compile_applet`, `gradle assembleDebug`, and lookup mapping pass for 0 kB/s, 999 kB/s, 1.0 MB/s, 1.5 MB/s, 7.7 MB/s, and 43.0 MB/s).
* Deviations: None.
* Known issues: None.

* Timestamp: 2026-09-05T14:34:00-07:00
* One-line summary: Integrated deterministic build-time icon generation into Gradle build lifecycle and verified all 1,421 status-bar drawable resources.
* Exact files touched:
  - `tools/generate_speed_icons.py`
  - `app/build.gradle.kts`
  - `receipts/RECEIPTS_094.md`
* What was actually done:
  - Wired `generateSpeedIcons` task into `app/build.gradle.kts` attached as a dependency of `preBuild`, guaranteeing icon generation and verification execute automatically before resource processing (`generateDebugResources`, `mergeDebugResources`) and Kotlin compilation (`compileDebugKotlin`).
  - Enhanced `tools/generate_speed_icons.py` to perform fast incremental scans: verifies existence of all 1,421 pre-rendered PNGs, renders only missing items, and strictly asserts that 1,000 kB/s (`0`..`999`) and 421 MB/s (`1.0`..`43.0`) drawables exist on disk.
  - Verified that all 1,421 referenced `R.drawable` symbols in `SpeedIconProvider.kt` resolve to valid disk resources with zero unresolved references and zero duplicate resource identifiers.
  - Verified packaged APK `app-debug.apk` contains all 1,421 pre-rendered icons.
  - Executed `gradle :app:generateSpeedIcons`, `gradle :app:preBuild`, `compile_applet`, and `gradle assembleDebug`.
* How it was verified: local build only (`compile_applet`, `gradle assembleDebug`, and automated resource audit script confirming 1,000 kB/s and 421 MB/s resources on disk and inside the generated APK).
* Deviations: None.
* Known issues: None.

* Timestamp: 2026-09-05T15:00:00-07:00
* One-line summary: Replaced external ImageMagick convert dependency in tools/generate_speed_icons.py with a self-contained in-process Python FreeType and headless Java fallback renderer.
* Exact files touched:
  - `tools/generate_speed_icons.py`
  - `receipts/RECEIPTS_094.md`
* What was actually done:
  - Eliminated the external `convert` executable invocation from `tools/generate_speed_icons.py`.
  - Implemented in-process font glyph rasterization, emboldening/stroke simulation, unit layer caching, and pure-Python PNG chunk encoding (`zlib` + `struct`) using Python standard library ctypes and system `libfreetype.so.6`.
  - Added headless Java AWT renderer fallback (`SpeedIconAwtRenderer`) for execution environments where `libfreetype` is absent, guaranteeing 100% portability on any clean runner with Java/JDK.
  - Preserved identical visual specifications: 96x96 dimensions, white text on transparent background, stacked number and unit, baseline y=52 for speed, baseline y=95 for unit, centered at x=48, bold typography, kB/s integer range 0–999, MB/s range 1.0–43.0 in 0.1 increments, and alpha cleanup threshold 80.
  - Preserved incremental caching behavior: only missing or incomplete PNGs are rendered.
  - Verified with incremental test deletion, `gradle :app:generateSpeedIcons`, `compile_applet`, and `gradle assembleDebug`.
* How it was verified: local build only (`gradle :app:generateSpeedIcons`, Python resource audit asserting 1,000 kB/s and 421 MB/s PNGs on disk with matching `SpeedIconProvider` references, `compile_applet`, and `gradle assembleDebug`).
* Deviations: None.
* Known issues: None.
