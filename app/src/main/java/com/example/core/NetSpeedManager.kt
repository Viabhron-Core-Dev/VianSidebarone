package com.example.core

import android.content.Context
import android.content.SharedPreferences
import android.net.TrafficStats
import com.example.util.AppLogger
import kotlinx.coroutines.*
import kotlin.math.max

class NetSpeedManager(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val onSpeedUpdate: (down: Long, up: Long) -> Unit,
    private val onDailyDataUpdate: (mobileBytes: Long, wifiBytes: Long) -> Unit
) {
    private var job: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var lastTotalRx = TrafficStats.getTotalRxBytes()
    private var lastTotalTx = TrafficStats.getTotalTxBytes()
    private var lastMobileRx = TrafficStats.getMobileRxBytes()
    private var lastMobileTx = TrafficStats.getMobileTxBytes()
    private var lastTime = System.currentTimeMillis()
    
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true
        AppLogger.d("NetSpeedManager", "Speed polling started")
        
        lastTotalRx = TrafficStats.getTotalRxBytes()
        lastTotalTx = TrafficStats.getTotalTxBytes()
        lastMobileRx = TrafficStats.getMobileRxBytes()
        lastMobileTx = TrafficStats.getMobileTxBytes()
        lastTime = System.currentTimeMillis()
        
        job = coroutineScope.launch {
            syncSystemDataUsage()
            var tickCount = 0
            while (isActive && isRunning) {
                delay(1000)
                updateSpeed()
                tickCount++
                if (tickCount % 60 == 0) {
                    syncSystemDataUsage()
                }
            }
        }
    }

    private suspend fun syncSystemDataUsage() {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
        val hasPermission = if (mode == android.app.AppOpsManager.MODE_DEFAULT) {
            context.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            mode == android.app.AppOpsManager.MODE_ALLOWED
        }

        if (!hasPermission) return
        
        try {
            val manager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as android.app.usage.NetworkStatsManager
            val cal = java.util.Calendar.getInstance()
            val end = cal.timeInMillis
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.clear(java.util.Calendar.MINUTE)
            cal.clear(java.util.Calendar.SECOND)
            cal.clear(java.util.Calendar.MILLISECOND)
            val start = cal.timeInMillis
            
            var mobileBytes = 0L
            var wifiBytes = 0L
            
            try {
                val bucket = manager.querySummaryForDevice(android.net.NetworkCapabilities.TRANSPORT_WIFI, null, start, end)
                wifiBytes = bucket.rxBytes + bucket.txBytes
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            try {
                val bucket = manager.querySummaryForDevice(android.net.NetworkCapabilities.TRANSPORT_CELLULAR, null, start, end)
                mobileBytes = bucket.rxBytes + bucket.txBytes
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            prefs.edit()
                .putLong("daily_mobile_rx", mobileBytes)
                .putLong("daily_mobile_tx", 0L)
                .putLong("daily_wifi_rx", wifiBytes)
                .putLong("daily_wifi_tx", 0L)
                .apply()
                
            withContext(Dispatchers.Main) {
                onDailyDataUpdate(mobileBytes, wifiBytes)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        job?.cancel()
        job = null
        AppLogger.d("NetSpeedManager", "Speed polling stopped")
    }
    
    private suspend fun updateSpeed() {
        val currentTotalRx = TrafficStats.getTotalRxBytes()
        val currentTotalTx = TrafficStats.getTotalTxBytes()
        val currentMobileRx = TrafficStats.getMobileRxBytes()
        val currentMobileTx = TrafficStats.getMobileTxBytes()
        val currentTime = System.currentTimeMillis()
        
        if (currentTotalRx == TrafficStats.UNSUPPORTED.toLong()) {
            return
        }

        val timeDiff = currentTime - lastTime
        if (timeDiff <= 0) return

        val totalRxDiff = if (lastTotalRx == TrafficStats.UNSUPPORTED.toLong() || currentTotalRx < lastTotalRx) 0L else max(0L, currentTotalRx - lastTotalRx)
        val totalTxDiff = if (lastTotalTx == TrafficStats.UNSUPPORTED.toLong() || currentTotalTx < lastTotalTx) 0L else max(0L, currentTotalTx - lastTotalTx)
        val mobileRxDiff = if (lastMobileRx == TrafficStats.UNSUPPORTED.toLong() || currentMobileRx == TrafficStats.UNSUPPORTED.toLong() || currentMobileRx < lastMobileRx) 0L else max(0L, currentMobileRx - lastMobileRx)
        val mobileTxDiff = if (lastMobileTx == TrafficStats.UNSUPPORTED.toLong() || currentMobileTx == TrafficStats.UNSUPPORTED.toLong() || currentMobileTx < lastMobileTx) 0L else max(0L, currentMobileTx - lastMobileTx)
        
        val wifiRxDiff = max(0L, totalRxDiff - mobileRxDiff)
        val wifiTxDiff = max(0L, totalTxDiff - mobileTxDiff)

        val rxSec = (totalRxDiff * 1000L) / timeDiff
        val txSec = (totalTxDiff * 1000L) / timeDiff
        
        withContext(Dispatchers.Main) {
            onSpeedUpdate(rxSec, txSec)
        }
        
        // Cap max diff to 500 MB per interval to prevent spikes on state changes
        val maxDiff = 500L * 1024 * 1024
        val safeMobileRxDiff = if (mobileRxDiff > maxDiff) 0L else mobileRxDiff
        val safeMobileTxDiff = if (mobileTxDiff > maxDiff) 0L else mobileTxDiff
        val safeWifiRxDiff = if (wifiRxDiff > maxDiff) 0L else wifiRxDiff
        val safeWifiTxDiff = if (wifiTxDiff > maxDiff) 0L else wifiTxDiff
        
        var dailyMobileRx = prefs.getLong("daily_mobile_rx", 0) + safeMobileRxDiff
        var dailyMobileTx = prefs.getLong("daily_mobile_tx", 0) + safeMobileTxDiff
        var dailyWifiRx = prefs.getLong("daily_wifi_rx", 0) + safeWifiRxDiff
        var dailyWifiTx = prefs.getLong("daily_wifi_tx", 0) + safeWifiTxDiff
        
        prefs.edit()
            .putLong("daily_mobile_rx", dailyMobileRx)
            .putLong("daily_mobile_tx", dailyMobileTx)
            .putLong("daily_wifi_rx", dailyWifiRx)
            .putLong("daily_wifi_tx", dailyWifiTx)
            .apply()
            
        val totalMobileBytes = dailyMobileRx + dailyMobileTx
        val totalWifiBytes = dailyWifiRx + dailyWifiTx

        withContext(Dispatchers.Main) {
            onDailyDataUpdate(totalMobileBytes, totalWifiBytes)
        }

        lastTotalRx = currentTotalRx
        lastTotalTx = currentTotalTx
        lastMobileRx = currentMobileRx
        lastMobileTx = currentMobileTx
        lastTime = currentTime
    }
}
