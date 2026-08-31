* Timestamp: 2026-08-14T09:51:53-07:00
* One-line summary: Fixed compilation syntax error in IntentPickerActivity.
* Exact files touched:
    * `app/src/main/java/com/example/feature/settings/IntentPickerActivity.kt`
* What was actually done:
    * Moved the `import java.net.URLEncoder` statement from inside the lambda block to the top of the file alongside the other imports.
* How it was verified: Local build.
* Any deviation from what was requested: None.
* Known issues: None.
