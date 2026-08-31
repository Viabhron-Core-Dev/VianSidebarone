package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import com.example.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MidnightResetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppLogger.d("MidnightResetReceiver", "Triggered midnight reset")
        
        CoroutineScope(Dispatchers.IO).launch {
            val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
            
            val initialMobileRx = TrafficStats.getMobileRxBytes()
            val initialMobileTx = TrafficStats.getMobileTxBytes()
            val initialTotalRx = TrafficStats.getTotalRxBytes()
            val initialTotalTx = TrafficStats.getTotalTxBytes()

            val initialWifiRx = initialTotalRx - initialMobileRx
            val initialWifiTx = initialTotalTx - initialMobileTx

            prefs.edit()
                .putLong("daily_mobile_rx_baseline", initialMobileRx)
                .putLong("daily_mobile_tx_baseline", initialMobileTx)
                .putLong("daily_wifi_rx_baseline", initialWifiRx)
                .putLong("daily_wifi_tx_baseline", initialWifiTx)
                .putLong("daily_mobile_rx", 0L)
                .putLong("daily_mobile_tx", 0L)
                .putLong("daily_wifi_rx", 0L)
                .putLong("daily_wifi_tx", 0L)
                .apply()
                
            AppLogger.d("MidnightResetReceiver", "Network stats reset completed")
        }
    }
}
