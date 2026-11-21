package com.example.feature.language.translation

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Abstraction boundary over Google ML Kit on-device Translation and Language Identification
 * so business logic, model management, and UI wiring can be verified deterministically in JVM tests
 * without live Google model downloads.
 */
interface TranslationEngineBackend {
    fun getSupportedLanguageCodes(): List<String>
    fun normalizeLanguageTag(tag: String): String?
    fun getDisplayName(code: String): String
    fun isNetworkConnected(): Boolean
    fun isWifiConnected(): Boolean

    suspend fun getDownloadedLanguageCodes(): Result<Set<String>>
    suspend fun downloadModel(languageCode: String, requireWifi: Boolean): Result<Unit>
    suspend fun deleteModel(languageCode: String): Result<Unit>

    /**
     * Identifies the BCP-47 language tag for [text], or returns `null` when undetermined (`"und"`).
     */
    suspend fun identifyLanguageTag(text: String): Result<String?>

    /**
     * Translates [text] locally using already-installed on-device models.
     */
    suspend fun translate(text: String, sourceCode: String, targetCode: String): Result<String>
}

/**
 * Production implementation backed exclusively by official Google ML Kit on-device APIs:
 * - `com.google.mlkit:translate:17.0.3`
 * - `com.google.mlkit:language-id:17.0.6`
 *
 * Strictly closes every ML Kit `LanguageIdentifier` and `Translator` instance in `finally`
 * to prevent native buffer/heap leaks.
 */
class MlKitTranslationBackend(
    private val context: Context
) : TranslationEngineBackend {

    override fun getSupportedLanguageCodes(): List<String> {
        return try {
            val all = TranslateLanguage.getAllLanguages()
            if (all.isNullOrEmpty()) FALLBACK_MLKIT_CODES else all.toList()
        } catch (_: Throwable) {
            FALLBACK_MLKIT_CODES
        }
    }

    override fun normalizeLanguageTag(tag: String): String? {
        val clean = tag.trim()
        if (clean.isEmpty() || clean.equals("und", ignoreCase = true)) return null
        return try {
            TranslateLanguage.fromLanguageTag(clean)
        } catch (_: Throwable) {
            val base = clean.lowercase(Locale.ROOT).substringBefore('-')
            getSupportedLanguageCodes().firstOrNull { it.equals(base, ignoreCase = true) }
        }
    }

    override fun getDisplayName(code: String): String {
        if (code == TranslationService.CODE_AUTO_DETECT) return "Auto-detect"
        val locale = Locale.forLanguageTag(code)
        val display = locale.getDisplayLanguage(Locale.getDefault())
        return if (display.isNullOrBlank() || display.equals(code, ignoreCase = true)) {
            code.uppercase(Locale.ROOT)
        } else {
            display.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }
    }

    override fun isNetworkConnected(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val active = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Throwable) {
            true
        }
    }

    override fun isWifiConnected(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val active = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } catch (_: Throwable) {
            true
        }
    }

    override suspend fun getDownloadedLanguageCodes(): Result<Set<String>> {
        return try {
            val models = RemoteModelManager.getInstance()
                .getDownloadedModels(TranslateRemoteModel::class.java)
                .awaitTask()
            Result.success(models.map { it.language }.toSet())
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    override suspend fun downloadModel(languageCode: String, requireWifi: Boolean): Result<Unit> {
        return try {
            val model = TranslateRemoteModel.Builder(languageCode).build()
            val conditionsBuilder = DownloadConditions.Builder()
            if (requireWifi) {
                conditionsBuilder.requireWifi()
            }
            RemoteModelManager.getInstance()
                .download(model, conditionsBuilder.build())
                .awaitTask()
            Result.success(Unit)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    override suspend fun deleteModel(languageCode: String): Result<Unit> {
        return try {
            val model = TranslateRemoteModel.Builder(languageCode).build()
            RemoteModelManager.getInstance()
                .deleteDownloadedModel(model)
                .awaitTask()
            Result.success(Unit)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    override suspend fun identifyLanguageTag(text: String): Result<String?> {
        val options = LanguageIdentificationOptions.Builder()
            .setConfidenceThreshold(0.35f)
            .build()
        val identifier = LanguageIdentification.getClient(options)
        return try {
            val tag = identifier.identifyLanguage(text).awaitTask()
            if (tag.isNullOrBlank() || tag.equals("und", ignoreCase = true)) {
                Result.success(null)
            } else {
                Result.success(tag)
            }
        } catch (e: Throwable) {
            Result.failure(e)
        } finally {
            try {
                identifier.close()
            } catch (_: Throwable) {}
        }
    }

    override suspend fun translate(text: String, sourceCode: String, targetCode: String): Result<String> {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceCode)
            .setTargetLanguage(targetCode)
            .build()
        val translator = Translation.getClient(options)
        return try {
            val output = translator.translate(text).awaitTask()
            Result.success(output)
        } catch (e: Throwable) {
            Result.failure(e)
        } finally {
            try {
                translator.close()
            } catch (_: Throwable) {}
        }
    }

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { value ->
            if (cont.isActive) cont.resume(value)
        }
        addOnFailureListener { error ->
            if (cont.isActive) cont.resumeWithException(error)
        }
        addOnCanceledListener {
            if (cont.isActive) cont.cancel()
        }
    }

    companion object {
        // Fallback copy of ML Kit's 59 official BCP-47 language codes used only if JVM stubs return empty
        internal val FALLBACK_MLKIT_CODES = listOf(
            "af", "sq", "ar", "be", "bg", "bn", "ca", "zh", "hr", "cs",
            "da", "nl", "en", "eo", "et", "fi", "fr", "gl", "ka", "de",
            "el", "gu", "ht", "he", "hi", "hu", "is", "id", "ga", "it",
            "ja", "kn", "ko", "lt", "lv", "mk", "mr", "ms", "mt", "no",
            "fa", "pl", "pt", "ro", "ru", "sk", "sl", "es", "sw", "sv",
            "tl", "ta", "te", "th", "tr", "uk", "ur", "vi", "cy"
        )
    }
}
