# Session Receipts - Bug Fix: Missing HandleService in Manifest

## Actions Taken
- **Added Permissions**: Added `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE` permissions to `AndroidManifest.xml` which are required for `HandleService`.
- **Declared Service**: Registered `.core.HandleService` inside the `<application>` tag of `AndroidManifest.xml`.

## Verification
- Local build only: `gradle assembleDebug` executed to verify the manifest changes successfully compile.
