package com.example.feature.system_hub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            CallRecorderManager.getInstance(context).onBroadcastEvent(stateStr, number)
        } else if (action == Intent.ACTION_NEW_OUTGOING_CALL) {
            val number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
            CallRecorderManager.getInstance(context).onBroadcastEvent(TelephonyManager.EXTRA_STATE_OFFHOOK, number)
        }
    }
}
