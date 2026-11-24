package com.example.feature.htmlviewer

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.example.core.LogKeeper
import com.example.feature.floating.FloatingMiniAppBridge
import com.example.feature.language.LanguageMiniAppUiHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Exported Android ACTION_VIEW receiver for local .html, .htm, .mht, and .mhtml files.
 *
 * Runs strictly in the Main process:
 * - Reads the incoming content:// or file:// URI while temporary read grants are active.
 * - Detects format safely via MIME metadata, display name, and bounded content sniffing.
 * - Parses MHT/MHTML archives and sanitizes HTML for offline, image-free, no-JS viewing.
 * - Writes sanitized HTML to an app-private temporary cache file.
 * - Hands off to [FloatingMiniAppBridge.openEphemeralHtmlViewer] and finishes promptly on success.
 * - On any error (unsupported file, unreadable URI, malformed MHT, missing HTML part, oversized
 *   document, missing overlay permission, temp storage or WebView failure), cleans up any temp
 *   file and displays a compact readable error card without crashing Sidebar.
 */
class HtmlMhtViewerActivity : ComponentActivity() {

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    internal sealed class PrepareDocumentResult {
        data class Ready(
            val tempFile: File,
            val displayTitle: String,
            val format: DetectedDocumentFormat
        ) : PrepareDocumentResult()

        data class Error(
            val reasonCode: String,
            val userMessage: String
        ) : PrepareDocumentResult()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        val action = incoming?.action
        val uri = incoming?.data
        if (action != Intent.ACTION_VIEW || uri == null) {
            LogKeeper.logError(this, TAG, "OPEN_REJECTED reason=MISSING_URI_OR_ACTION")
            showErrorCard("No readable local HTML or MHT document was provided.")
            return
        }

        if (!HtmlMhtFormatDetector.isAllowedUriScheme(uri)) {
            LogKeeper.logError(this, TAG, "OPEN_REJECTED reason=UNSUPPORTED_SCHEME scheme=${uri.scheme}")
            showErrorCard("Only local HTML and MHT documents can be opened here.")
            return
        }

        if (isOverlayPermissionDenied(this)) {
            LogKeeper.logError(this, TAG, "OPEN_REJECTED reason=OVERLAY_PERMISSION_DENIED")
            showErrorCard("Overlay permission is required to open floating HTML/MHT documents. Grant 'Display over other apps' for Vian Sidebar and try again.")
            return
        }

        val intentMime = incoming.type
        activityScope.launch {
            val createdTempFileRef = AtomicReference<File?>(null)
            var handedOffToBridge = false
            try {
                val prepared = withContext(Dispatchers.IO) {
                    prepareDocument(applicationContext, uri, intentMime, createdTempFileRef)
                }
                if (isFinishing || isDestroyed) {
                    return@launch
                }

                when (prepared) {
                    is PrepareDocumentResult.Error -> {
                        LogKeeper.logError(
                            applicationContext,
                            TAG,
                            "DOCUMENT_PREPARE_FAILED reason=${prepared.reasonCode}"
                        )
                        showErrorCard(prepared.userMessage)
                    }
                    is PrepareDocumentResult.Ready -> {
                        val result = FloatingMiniAppBridge.getInstance(applicationContext)
                            .openEphemeralHtmlViewer(
                                tempHtmlFile = prepared.tempFile,
                                displayTitle = prepared.displayTitle,
                                formatLabel = prepared.format.label
                            )
                        when (result) {
                            is FloatingMiniAppBridge.HtmlViewerOpenResult.Success -> {
                                handedOffToBridge = true
                                LogKeeper.log(
                                    applicationContext,
                                    TAG,
                                    "DOCUMENT_OPENED id=${result.instanceId} format=${prepared.format.label}"
                                )
                                finish()
                            }
                            is FloatingMiniAppBridge.HtmlViewerOpenResult.Failure -> {
                                // Bridge already cleaned up tempFile on failure; finally delete is idempotent
                                LogKeeper.logError(
                                    applicationContext,
                                    TAG,
                                    "FLOATING_OPEN_FAILED reason=${result.reasonCode}"
                                )
                                showErrorCard(result.userMessage)
                            }
                        }
                    }
                }
            } finally {
                if (!handedOffToBridge) {
                    createdTempFileRef.getAndSet(null)?.let { orphan ->
                        HtmlMhtCacheManager.deleteTempFile(orphan)
                    }
                }
            }
        }
    }

    private fun isOverlayPermissionDenied(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                !Settings.canDrawOverlays(context)
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun showErrorCard(message: String) {
        val density = resources.displayMetrics.density
        val palette = LanguageMiniAppUiHelper.resolvePalette(this)

        val backdrop = FrameLayout(this).apply {
            setBackgroundColor(0x66000000)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnClickListener { finish() }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16 * density
                setColor(palette.cardBg)
                setStroke((1 * density).toInt().coerceAtLeast(1), palette.border)
            }
            background = bg
            val pad = (18 * density).toInt()
            setPadding(pad, pad, pad, pad)
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(
                (310 * density).toInt().coerceAtMost((resources.displayMetrics.widthPixels * 0.9f).toInt()),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        val titleView = TextView(this).apply {
            text = "Unable to Open Document"
            setTextColor(palette.dangerText)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }
        val bodyView = TextView(this).apply {
            text = message
            setTextColor(palette.textPrimary)
            textSize = 13.5f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * density).toInt()
                bottomMargin = (16 * density).toInt()
            }
        }
        val closeButton = TextView(this).apply {
            text = "Close"
            contentDescription = "Close error dialog"
            setTextColor(palette.textPrimary)
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minHeight = (48 * density).toInt()
            val btnBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10 * density
                setColor(palette.buttonBg)
                setStroke((1 * density).toInt().coerceAtLeast(1), palette.border)
            }
            background = btnBg
            setOnClickListener { finish() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        card.addView(titleView)
        card.addView(bodyView)
        card.addView(closeButton)
        backdrop.addView(card)
        setContentView(backdrop)
    }

    companion object {
        private const val TAG = "HtmlMhtViewerActivity"

        internal fun prepareDocument(
            context: Context,
            uri: Uri,
            intentMimeType: String?,
            createdTempFileRef: AtomicReference<File?>? = null
        ): PrepareDocumentResult {
            val displayName = queryDisplayName(context, uri)
            val resolvedMime = try {
                context.contentResolver.getType(uri)
            } catch (_: Throwable) {
                null
            }
            return prepareDocumentInternal(
                context = context,
                uriScheme = uri.scheme,
                intentMimeType = intentMimeType,
                displayName = displayName,
                resolvedMime = resolvedMime,
                createdTempFileRef = createdTempFileRef,
                streamOpener = { context.contentResolver.openInputStream(uri) }
            )
        }

        internal fun prepareDocumentInternal(
            context: Context,
            uriScheme: String?,
            intentMimeType: String?,
            displayName: String?,
            resolvedMime: String?,
            createdTempFileRef: AtomicReference<File?>? = null,
            streamOpener: () -> InputStream?
        ): PrepareDocumentResult {
            val readResult = try {
                val stream = streamOpener()
                    ?: return PrepareDocumentResult.Error(
                        reasonCode = "URI_UNREADABLE",
                        userMessage = "Could not read this file. The file provider did not grant read access."
                    )
                stream.use { input ->
                    HtmlMhtFormatDetector.readBoundedBytes(
                        inputStream = input,
                        maxBytes = HtmlMhtFormatDetector.DEFAULT_MAX_FILE_BYTES
                    )
                }
            } catch (t: SecurityException) {
                LogKeeper.logError(context, TAG, "URI_PERMISSION_DENIED type=${t.javaClass.simpleName}", t)
                return PrepareDocumentResult.Error(
                    reasonCode = "URI_PERMISSION_DENIED",
                    userMessage = "Permission to read this file was denied or expired."
                )
            } catch (t: Throwable) {
                LogKeeper.logError(context, TAG, "URI_READ_EXCEPTION type=${t.javaClass.simpleName}", t)
                return PrepareDocumentResult.Error(
                    reasonCode = "URI_READ_FAILED",
                    userMessage = "Could not open or read the selected document."
                )
            }

            val rawBytes = when (readResult) {
                is BoundedReadResult.Success -> readResult.bytes
                is BoundedReadResult.Oversized -> {
                    return PrepareDocumentResult.Error(
                        reasonCode = "OVERSIZED_DOCUMENT",
                        userMessage = "This file exceeds the 10 MB limit for the lightweight floating viewer."
                    )
                }
                is BoundedReadResult.ReadFailure -> {
                    return PrepareDocumentResult.Error(
                        reasonCode = "URI_READ_FAILED",
                        userMessage = "Failed while reading this file."
                    )
                }
            }

            val detection = HtmlMhtFormatDetector.detect(
                uriScheme = uriScheme,
                intentMimeType = intentMimeType,
                resolvedMimeType = resolvedMime,
                displayName = displayName,
                headerBytes = rawBytes
            )
            val format = when (detection) {
                is FormatDetectionResult.Supported -> detection.format
                is FormatDetectionResult.Unsupported -> {
                    return PrepareDocumentResult.Error(detection.reasonCode, detection.userMessage)
                }
            }

            val rawHtml: String
            var fallbackTitle: String? = null
            when (format) {
                DetectedDocumentFormat.HTML -> {
                    if (rawBytes.size > MhtmlParser.DEFAULT_MAX_HTML_BYTES) {
                        return PrepareDocumentResult.Error(
                            reasonCode = "OVERSIZED_HTML",
                            userMessage = "This HTML document exceeds the 5 MB rendering limit."
                        )
                    }
                    rawHtml = HtmlDocumentSanitizer.decodeHtmlBytes(rawBytes)
                }
                DetectedDocumentFormat.MHT -> {
                    when (val parsed = MhtmlParser.parse(rawBytes)) {
                        is MhtmlParseResult.Success -> {
                            rawHtml = parsed.html
                            fallbackTitle = parsed.subjectTitle
                        }
                        is MhtmlParseResult.Error -> {
                            return PrepareDocumentResult.Error(
                                reasonCode = parsed.code.name,
                                userMessage = parsed.userMessage
                            )
                        }
                    }
                }
            }

            val sanitizedHtml = HtmlDocumentSanitizer.prepareForOfflineViewer(rawHtml)
            val htmlTitle = HtmlDocumentSanitizer.extractTitle(rawHtml)
            val cleanTitle = HtmlMhtFormatDetector.sanitizeDisplayTitle(
                displayName = displayName ?: htmlTitle ?: fallbackTitle,
                fallback = if (format == DetectedDocumentFormat.MHT) "MHT Document" else "HTML Document"
            )

            val tempFile = when (val writeRes = HtmlMhtCacheManager.writeTempHtmlChecked(context, sanitizedHtml)) {
                is HtmlMhtCacheManager.TempWriteResult.Success -> {
                    createdTempFileRef?.set(writeRes.file)
                    writeRes.file
                }
                is HtmlMhtCacheManager.TempWriteResult.Oversized -> {
                    return PrepareDocumentResult.Error(
                        reasonCode = "OVERSIZED_HTML",
                        userMessage = "This HTML document exceeds the 5 MB rendering limit after UTF-8 decoding."
                    )
                }
                is HtmlMhtCacheManager.TempWriteResult.Failed -> {
                    return PrepareDocumentResult.Error(
                        reasonCode = "TEMP_STORAGE_FAILED",
                        userMessage = "Could not create temporary cache storage for this document."
                    )
                }
            }

            return PrepareDocumentResult.Ready(
                tempFile = tempFile,
                displayTitle = cleanTitle,
                format = format
            )
        }

        private fun queryDisplayName(context: Context, uri: Uri): String? {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) {
                                val name = cursor.getString(idx)?.trim()
                                if (!name.isNullOrEmpty()) return name
                            }
                        }
                    }
            } catch (_: Throwable) {
                // Fall back safely to URI lastPathSegment
            }
            return extractFallbackDisplayName(uri.lastPathSegment)
        }

        internal fun extractFallbackDisplayName(lastPathSegment: String?): String? {
            return lastPathSegment
                ?.substringAfterLast('/')
                ?.substringAfterLast(':')
                ?.takeIf { it.isNotBlank() }
        }
    }
}
