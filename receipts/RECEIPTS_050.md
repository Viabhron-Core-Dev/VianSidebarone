* 2026-08-08T15:19:24Z
* Swap ML Kit dependencies for Google Play Services unbundled equivalents.
* Touched: app/build.gradle.kts, app/src/main/AndroidManifest.xml
* Swapped com.google.mlkit:barcode-scanning for com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1.
* Swapped com.google.mlkit:text-recognition for com.google.android.gms:play-services-mlkit-text-recognition:19.0.1.
* Swapped com.google.mlkit:language-id for com.google.android.gms:play-services-mlkit-language-id:17.0.0.
* com.google.mlkit:translate remains as there is no play-services unbundled version available.
* Added meta-data tag com.google.mlkit.vision.DEPENDENCIES to AndroidManifest.xml for barcode and ocr.
* Verification: local build only (gradle assembleDebug successful).
* No deviations.
* 2026-08-08T16:23:02Z
* Removed com.google.mlkit:translate entirely for now to reduce APK size, per user request.
* Touched: app/build.gradle.kts
* Translation floating window and popup will be implemented later, and the dependency added back then.
* Marked in blueprint.
* Verification: local build only (gradle clean assembleDebug successful).
* No deviations.
