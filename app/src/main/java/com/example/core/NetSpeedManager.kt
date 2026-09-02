package com.example.core

import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import java.util.Locale

data class SpeedData(
    val downBytesPerSec: Long,
    val upBytesPerSec: Long,
    val totalBytesPerSec: Long,
    val downFormatted: String,
    val upFormatted: String,
    val downValue: String,
    val downUnit: String,
    val upValue: String,
    val upUnit: String
)

/**
 * NetSpeedManager: Real-time network speed monitor using TrafficStats.
 * Ticks once per second; pauses polling when the screen is turned off to save battery.
 */
class NetSpeedManager(
    private val onSpeedUpdate: (SpeedData) -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private var lastRxBytes: Long = 0L
    private var lastTxBytes: Long = 0L
    private var lastTimestamp: Long = 0L

    private var isRunning = false
    private var isScreenOn = true

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            if (isScreenOn) {
                calculateSpeed()
            }
            handler.postDelayed(this, 1000L)
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        lastRxBytes = TrafficStats.getTotalRxBytes()
        lastTxBytes = TrafficStats.getTotalTxBytes()
        lastTimestamp = System.currentTimeMillis()
        handler.post(tickRunnable)
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacks(tickRunnable)
    }

    fun setScreenState(screenOn: Boolean) {
        this.isScreenOn = screenOn
        if (screenOn && isRunning) {
            // Reset base sample counters so we don't calculate a huge artificial spike after sleeping
            lastRxBytes = TrafficStats.getTotalRxBytes()
            lastTxBytes = TrafficStats.getTotalTxBytes()
            lastTimestamp = System.currentTimeMillis()
        }
    }

    private fun calculateSpeed() {
        val currentRx = TrafficStats.getTotalRxBytes()
        val currentTx = TrafficStats.getTotalTxBytes()
        val currentTime = System.currentTimeMillis()

        val timeDiff = (currentTime - lastTimestamp).coerceAtLeast(1L)
        val rxDiff = (currentRx - lastRxBytes).coerceAtLeast(0L)
        val txDiff = (currentTx - lastTxBytes).coerceAtLeast(0L)

        // Bytes per second
        val downSpeed = (rxDiff * 1000L) / timeDiff
        val upSpeed = (txDiff * 1000L) / timeDiff

        lastRxBytes = currentRx
        lastTxBytes = currentTx
        lastTimestamp = currentTime

        val (downVal, downUnit) = splitSpeed(downSpeed)
        val (upVal, upUnit) = splitSpeed(upSpeed)

        val speedData = SpeedData(
            downBytesPerSec = downSpeed,
            upBytesPerSec = upSpeed,
            totalBytesPerSec = downSpeed + upSpeed,
            downFormatted = formatSpeed(downSpeed),
            upFormatted = formatSpeed(upSpeed),
            downValue = downVal,
            downUnit = downUnit,
            upValue = upVal,
            upUnit = upUnit
        )

        onSpeedUpdate(speedData)
    }

    companion object {
        private const val ONE_MB = 1024L * 1024L
        private const val ONE_KB = 1024L
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        val safeBytes = bytesPerSec.coerceAtLeast(0L)
        return if (safeBytes >= ONE_MB) {
            String.format(Locale.US, "%.1f MB/s", safeBytes / (1024.0 * 1024.0))
        } else {
            String.format(Locale.US, "%d kB/s", safeBytes / ONE_KB)
        }
    }

    private fun splitSpeed(bytesPerSec: Long): Pair<String, String> {
        val safeBytes = bytesPerSec.coerceAtLeast(0L)
        return if (safeBytes >= ONE_MB) {
            Pair(
                String.format(Locale.US, "%.1f", safeBytes / (1024.0 * 1024.0)),
                "MB/s"
            )
        } else {
            Pair(
                String.format(Locale.US, "%d", safeBytes / ONE_KB),
                "kB/s"
            )
        }
    }
}
