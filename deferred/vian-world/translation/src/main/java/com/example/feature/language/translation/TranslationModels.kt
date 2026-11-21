package com.example.feature.language.translation

/**
 * A selectable language supported by the on-device ML Kit Translation engine.
 */
data class LanguageOption(
    val code: String,
    val displayName: String,
    val isAutoDetect: Boolean = false
)

/**
 * Shared persisted configuration for the Translation floating mini-app, Translate popup,
 * and Translation Settings.
 */
data class TranslationConfig(
    val sourceLanguageCode: String = TranslationService.CODE_AUTO_DETECT,
    val targetLanguageCode: String = TranslationService.DEFAULT_TARGET_LANGUAGE_CODE,
    val wifiOnlyDownloads: Boolean = true
)

/**
 * Download lifecycle state for a single ML Kit on-device language model.
 */
sealed class ModelDownloadState {
    data object NotDownloaded : ModelDownloadState()
    data object Downloading : ModelDownloadState()
    data object Downloaded : ModelDownloadState()
    data class Failed(val message: String) : ModelDownloadState()
}

/**
 * Structured outcome of an on-device translation request.
 * Never fabricates or returns placeholder output as a [Success].
 */
sealed class TranslationOutcome {
    data object EmptyInput : TranslationOutcome()

    data class Success(
        val translatedText: String,
        val resolvedSourceCode: String,
        val targetCode: String,
        val wasAutoDetected: Boolean
    ) : TranslationOutcome()

    data class SameLanguage(
        val originalText: String,
        val languageCode: String,
        val wasAutoDetected: Boolean
    ) : TranslationOutcome()

    data class MissingModels(
        val resolvedSourceCode: String,
        val targetCode: String,
        val missingLanguageCodes: List<String>,
        val wasAutoDetected: Boolean
    ) : TranslationOutcome()

    data object UndeterminedLanguage : TranslationOutcome()

    data class UnsupportedLanguage(
        val detectedLanguageTag: String
    ) : TranslationOutcome()

    data class Error(
        val message: String
    ) : TranslationOutcome()
}
