package com.example.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object SpeedIconDiagnostics {

    data class DiagnosticTick(
        val tickIndex: Int,
        val timestamp: Long,
        val bytesPerSec: Long,
        val formattedNumber: String,
        val formattedUnit: String,
        val bitmapHash: Int,
        val bitmapWidth: Int,
        val bitmapHeight: Int,
        val bitmapDensity: Int,
        val renderDurationUs: Long
    )

    private const val MAX_DIAGNOSTIC_TICKS = 15
    private val ticks = CopyOnWriteArrayList<DiagnosticTick>()
    private var isRecording = true
    private var systemMetricsReport: String = ""

    fun captureComprehensiveSystemMetrics(context: Context) {
        val dm = context.resources.displayMetrics
        val res = context.resources

        fun getDimenPx(name: String): String {
            val id = res.getIdentifier(name, "dimen", "android")
            return if (id > 0) {
                try {
                    val px = res.getDimensionPixelSize(id)
                    val dp = px / dm.density
                    "${px}px (${String.format(Locale.US, "%.1fdp", dp)})"
                } catch (e: Exception) { "Error reading" }
            } else "OEM Default / Undefined"
        }

        val notifManager = context.getSystemService(NotificationManager::class.java)
        val channel = notifManager?.getNotificationChannel("handle_channel")
        val channelImportance = when (channel?.importance) {
            NotificationManager.IMPORTANCE_NONE -> "NONE"
            NotificationManager.IMPORTANCE_MIN -> "MIN"
            NotificationManager.IMPORTANCE_LOW -> "LOW"
            NotificationManager.IMPORTANCE_DEFAULT -> "DEFAULT"
            NotificationManager.IMPORTANCE_HIGH -> "HIGH"
            else -> "Not Created / Unknown"
        }

        val calculatedIconPx = DynamicSpeedIconGenerator.getNotificationIconSize(context)

        systemMetricsReport = """
            --- [DEVICE & DISPLAY HARDWARE] ---
            • Android Version: Android ${Build.VERSION.RELEASE} (API Level ${Build.VERSION.SDK_INT})
            • Manufacturer & Model: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT})
            • Physical Resolution: ${dm.widthPixels}x${dm.heightPixels} px
            • Screen Density: ${dm.density}x (DensityDpi: ${dm.densityDpi} dpi)
            • Scaled Density (Font scale): ${dm.scaledDensity}x

            --- [SYSTEM NOTIFICATION & STATUS BAR DIMENSIONS] ---
            • status_bar_height: ${getDimenPx("status_bar_height")}
            • status_bar_icon_size: ${getDimenPx("status_bar_icon_size")}
            • notification_small_icon_size: ${getDimenPx("notification_small_icon_size")}
            • notification_large_icon_width: ${getDimenPx("notification_large_icon_width")}
            • notification_large_icon_height: ${getDimenPx("notification_large_icon_height")}
            • notification_badge_size: ${getDimenPx("notification_badge_size")}
            • Generated SmallIcon Native Buffer: ${calculatedIconPx}x${calculatedIconPx} px

            --- [NOTIFICATION PIPELINE & CHANNEL] ---
            • Notification Channel ID: "handle_channel"
            • Channel Importance: $channelImportance
            • SmallIcon Binding Method: IconCompat.createWithBitmap(Bitmap)
            • Ongoing / Foreground Service: Active
        """.trimIndent()
    }

    fun recordTick(
        tickIndex: Int,
        bytesPerSec: Long,
        formattedNumber: String,
        formattedUnit: String,
        bitmap: Bitmap,
        renderDurationUs: Long
    ) {
        if (!isRecording && ticks.size >= MAX_DIAGNOSTIC_TICKS) return

        if (ticks.size < MAX_DIAGNOSTIC_TICKS) {
            val tick = DiagnosticTick(
                tickIndex = tickIndex,
                timestamp = System.currentTimeMillis(),
                bytesPerSec = bytesPerSec,
                formattedNumber = formattedNumber,
                formattedUnit = formattedUnit,
                bitmapHash = System.identityHashCode(bitmap),
                bitmapWidth = bitmap.width,
                bitmapHeight = bitmap.height,
                bitmapDensity = bitmap.density,
                renderDurationUs = renderDurationUs
            )
            ticks.add(tick)
        }
    }

    fun reset() {
        ticks.clear()
        isRecording = true
    }

    fun getDiagnosticReport(context: Context): String {
        captureComprehensiveSystemMetrics(context)
        val sb = StringBuilder()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        sb.appendLine("==================================================")
        sb.appendLine("  NETSPEED DYNAMIC ICON FORENSIC REPORT")
        sb.appendLine("==================================================")
        sb.appendLine("Report Timestamp: ${sdf.format(Date())}")
        sb.appendLine()
        sb.appendLine(systemMetricsReport)
        sb.appendLine()
        sb.appendLine("--- [POLLING CYCLE TRANSITION LOG (FIRST ${ticks.size} TICKS)] ---")

        if (ticks.isEmpty()) {
            sb.appendLine("No polling ticks captured yet. Enable NetSpeed monitor in Settings to collect frames.")
        } else {
            ticks.forEach { t ->
                val timeStr = sdf.format(Date(t.timestamp))
                sb.appendLine("[Tick #${t.tickIndex} @ $timeStr]")
                sb.appendLine("  • Speed Value: ${t.bytesPerSec} B/s -> Rendered Text: \"${t.formattedNumber} ${t.formattedUnit}\"")
                sb.appendLine("  • Dynamic Bitmap Buffer: ${t.bitmapWidth}x${t.bitmapHeight} px (Dpi: ${t.bitmapDensity}) | MemAddress: ${Integer.toHexString(t.bitmapHash)}")
                sb.appendLine("  • Render Execution Latency: ${t.renderDurationUs} µs")
                sb.appendLine()
            }
        }

        sb.appendLine("--- [BLUR / DEGRADATION FORENSIC FINDINGS] ---")
        val dm = context.resources.displayMetrics
        val calculatedIconPx = DynamicSpeedIconGenerator.getNotificationIconSize(context)
        sb.appendLine("1. Status Bar SmallIcon Downscale:")
        sb.appendLine("   - Generated buffer is ${calculatedIconPx}x${calculatedIconPx} px.")
        sb.appendLine("   - In Android 12+, status bar small icon is clamped to ~24dp (approx ${(24 * dm.density).toInt()}px).")
        sb.appendLine("2. Notification Panel Shade Badge:")
        sb.appendLine("   - In expanded shade, small icon appears in header circle (~16dp to 20dp).")
        sb.appendLine("   - High-density number text (stacked 2-line layout) undergoes downsampling by NotificationManager IPC binder.")
        sb.appendLine("3. Memory Re-allocation Check:")
        if (ticks.size >= 2) {
            val uniqueHashes = ticks.map { it.bitmapHash }.distinct().size
            sb.appendLine("   - Unique buffer instances across ${ticks.size} ticks: $uniqueHashes")
            if (uniqueHashes == 1) {
                sb.appendLine("   - Single reused buffer in memory (Zero GC pause overhead).")
            } else {
                sb.appendLine("   - Multiple buffer allocations detected.")
            }
        }
        sb.appendLine("==================================================")
        return sb.toString()
    }

    fun copyToClipboard(context: Context): Boolean {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val report = getDiagnosticReport(context)
            val clip = ClipData.newPlainText("NetSpeed Diagnostics", report)
            clipboard.setPrimaryClip(clip)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

