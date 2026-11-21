# Deferred On-Device Translation Implementation (Vian World Handoff)

> **Status**: Deferred from active **VianSidebarone** (Phase 14.26) and preserved cleanly outside the active Gradle source sets for future **Vian World** integration.
> **Important**: This folder is a **preserved reference and source archive**, NOT a standalone buildable Gradle module. These files must be wired into Vian World's Android Gradle module (with the required ML Kit dependencies and Vian World UI/navigation/process fixes below) to compile and run.

---

## 1. Preserved Contents

```text
deferred/vian-world/translation/
├── README.md
└── src/
    ├── main/java/com/example/
    │   ├── feature/language/
    │   │   ├── translation/
    │   │   │   ├── TranslationModels.kt                 # LanguageOption, TranslationConfig, ModelDownloadState, TranslationOutcome
    │   │   │   ├── TranslationEngineBackend.kt          # TranslationEngineBackend interface + MlKitTranslationBackend (strict close())
    │   │   │   └── TranslationService.kt                # Shared service, atomic filesDir/mlkit_translation/prefs.json, Wi-Fi/offline checks
    │   │   ├── TranslationMiniAppInstance.kt            # Floating Translation mini-app UI, inline searchable picker, sleep/wake state
    │   │   └── LanguageMiniAppUiHelperTranslationAdditions.kt  # Accent color (#A6E3A1) & computeCompactTranslationBounds reference
    │   ├── feature/selection/
    │   │   └── TranslateSelectionActivity.kt            # ACTION_PROCESS_TEXT popup for "Translate"
    │   └── feature/settings/
    │       ├── DictionaryTranslationSettingsDialogs.kt  # LanguageSelectionDialog, TranslationModelsBrowserDialog, ConfirmDeleteDialog
    │       └── TranslationSettingsSectionReference.kt   # Original Compose M3 On-Device Translation settings card + picker/download state
    └── test/java/com/example/
        └── feature/language/
            ├── TranslationServiceTest.kt                # FakeTranslationEngineBackend + 5 service/prefs/model/download/error unit tests
            └── TranslationUiAndSelectionTest.kt         # Preserved mini-app + TranslateSelectionActivity + Settings contract tests
```

Note: Existing device storage files (`filesDir/mlkit_translation/prefs.json` and any previously downloaded ML Kit models) are **not** wiped by Sidebar when deferring this feature, so users upgrading or integrating with Vian World do not lose persisted configuration unexpectedly.

---

## 2. Required Gradle Dependencies (Removed from Sidebar `app/build.gradle.kts`)

Add these dependencies to the target Android application module when integrating into Vian World:

```kotlin
dependencies {
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
}
```

### APK Size Impact Observed in Sidebar Baseline (`91b4dee6b7c2f9d2a58ba547a5a2e32b6cd70eda` / Receipt 129)
- Universal debug APK before removal: **80.87 MB**
- Combined ML Kit native JNI libraries (`libtranslate_jni.so`, `liblanguage_id_l2c_jni.so`, etc.) + language-ID TFLite assets across all 4 ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`): **64.12 MB** (~**16.9 MB** for `arm64-v8a` alone).
- Translation language packs are downloaded dynamically on device at runtime via `RemoteModelManager`, not bundled in the APK.

---

## 3. Original Sidebar Integration Points

When these files were active in Sidebar, they hooked into:

1. **Gradle & Manifest**:
   - `app/build.gradle.kts`: `com.google.mlkit:translate:17.0.3`, `com.google.mlkit:language-id:17.0.6`.
   - `app/src/main/AndroidManifest.xml`: Exported `<activity android:name=".feature.selection.TranslateSelectionActivity" android:label="Translate" ...>` with `android.intent.action.PROCESS_TEXT` (`text/plain`).
2. **Floating Window & Mini-App Routing**:
   - `AddElementActivity.kt`: `"Translation"` row under `Floating Windows` (`system:translation_floating`).
   - `SidebarItem.kt`: `SidebarItem.SystemAction("translation_floating", "Translation (Floating)", ...)` in `ALL_FLOATING_WINDOWS`.
   - `ElementActionDispatcher.kt`: `"translation_floating" -> MiniAppManager.toggleApp(context, "translation")`.
   - `FloatingTriggerStore.kt`: `"translation"` in `MAIN_ONLY_MINI_APP_TYPES` & fallback Heavy set.
   - `FloatingMiniAppBridge.kt`: `isTranslationMiniApp`, `isMainOnlyMiniApp`, `computeCompactTranslationBounds`, `launchDictionaryTranslationSettings`.
   - `FloatingMiniAppWindow.kt`: `translationInstance`, `isTranslationApp()`, `ACCENT_TRANSLATION_HEX`, `setupTranslationContentView`, `"Translation"` title, top-bar `Edit` button (`translation_topbar_edit_button`), `getCurrentPayload`, `saveLanguageMiniAppSnapshotInternal`, `onHide`, `onDestroy`.
   - `FloatingMiniAppLauncherBubble.kt`: `"translation"` icon mapping.
   - `HeavyFloatingHost.kt` & `HeavyPageHostManager.kt`: `"translation"` factory/pageType entries.
3. **Settings & Selection Popups**:
   - `SettingsActivity.kt` & `DictionaryTranslationSettingsScreen.kt`: Shared `dictionary_translation_settings` route and `"translation_settings"` alias.
   - `SelectionPopupShell.kt`: `openDictionaryTranslationSettings(activity)`.

---

## 4. Main Process vs `:heavy` Process Architecture Considerations

Sidebar splits execution between:
- **Main process (`com.example`)**: `HandleService`, `FloatingMiniAppWindow`, `TranslationMiniAppInstance`, and `TranslateSelectionActivity`.
- **`:heavy` process (`com.example:heavy`)**: `SettingsActivity` and Heavy Compose screens.

Design patterns built into the preserved code for multi-process compatibility:
- **Cross-process preference sync**: `TranslationService` stores `TranslationConfig` (`sourceLanguageCode`, `targetLanguageCode`, `wifiOnlyDownloads`) atomically using a `.tmp` write + `fsync` + `renameTo` at `filesDir/mlkit_translation/prefs.json` so changes in Settings can be read from the Main process.
- **Overlay-safe inline language picker**: Because floating overlay windows cannot reliably display standard Activity/Compose `AlertDialog` popups inside `TYPE_APPLICATION_OVERLAY`, `TranslationMiniAppInstance` includes a custom embedded scrollable/filterable picker container (`TAG_LANGUAGE_PICKER_PANEL`) with `EmbeddedInputEditText` and focus-window negotiation (`onRequestWindowFocusable`).

---

## 5. Observed Runtime Failure in `:heavy` (`MlKitContext has not been initialized`)

During Phase 14.25 testing, invoking ML Kit APIs inside `SettingsActivity` (`android:process=":heavy"`) threw:

```text
java.lang.IllegalStateException: MlKitContext has not been initialized
```

**Root Cause**:
- ML Kit relies on `com.google.mlkit.common.internal.MlKitInitProvider` (`ContentProvider`) registered in the Android manifest to auto-initialize `MlKitContext` during startup.
- Android only instantiates `ContentProvider`s without `android:multiprocess="true"` inside the **main application process** (`com.example`).
- When `SettingsActivity` (`android:process=":heavy"`) instantiated `TranslationService` and called `RemoteModelManager.getInstance()` / `TranslateLanguage` / `MlKitTranslationBackend`, `MlKitContext` had never been initialized in `:heavy`.

---

## 6. Checklist Before Integrating into Vian World

Before shipping Translation inside **Vian World**, resolve and verify all of the following:

1. **Fix Secondary-Process `MlKitContext` Initialization (Choose one architecture)**:
   - **Option A (Recommended for single-process Vian World)**: Run Vian World's Translation UI, Settings, and selection activities in the **main process** (where `MlKitInitProvider` auto-initializes cleanly), **OR**
   - **Option B (Multi-process apps)**: Either call `com.google.mlkit.common.sdkinternal.MlKitContext.initializeIfNeeded(applicationContext)` / `MlKit.initialize(context)` guarded cleanly in secondary-process entry points before any `RemoteModelManager` / `TranslateRemoteModel` / `Translation` / `LanguageIdentification` call, **or better yet**, keep all ML Kit execution strictly in one dedicated process and expose model download/query/translate operations over AIDL/Messenger/Binder IPC so duplicate native TensorFlow Lite runtimes are never loaded into two processes simultaneously.
2. **Isolate Heavy ML Kit Footprint**: Keep `com.google.mlkit:translate` and `com.google.mlkit:language-id` scoped only to the Vian World APK/module (consider Play Feature Delivery / ABI splits if APK size matters).
3. **Host Package Imports & DI**: Update `package com.example.*` and Sidebar-specific references (`FloatingMiniAppBridge`, `EmbeddedInputEditText`, `LanguageMiniAppUiHelper`, `SelectionPopupShell`, `LogKeeper`, `SettingsActivity`) to Vian World equivalents.
4. **Physical-Device Verification Still Required**:
   - Real Google ML Kit model downloads over Wi-Fi vs mobile data (`wifiOnlyDownloads = true/false`).
   - Real `LanguageIdentification` accuracy and short-text `"und"` handling on device.
   - Real `RemoteModelManager` downloaded model enumeration and deletion sync across Settings and popup/window UIs.
   - Live `ACTION_PROCESS_TEXT` behavior across third-party host apps (Chrome, Notes, Reader).
