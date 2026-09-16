package com.example.core

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

object OverlaySyncManager {
    const val ACTION_SYNC_PREF = "com.example.ACTION_SYNC_PREF"
    const val EXTRA_KEY = "extra_key"
    const val EXTRA_VALUE = "extra_value"
    const val EXTRA_TYPE = "extra_type"

    fun syncString(context: Context, key: String, value: String) {
        val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString(key, value).commit()
        sendSync(context, key, value, "STRING")
    }

    fun syncInt(context: Context, key: String, value: Int) {
        val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
        prefs.edit().putInt(key, value).commit()
        sendSync(context, key, value.toString(), "INT")
    }

    fun syncBoolean(context: Context, key: String, value: Boolean) {
        val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(key, value).commit()
        sendSync(context, key, value.toString(), "BOOLEAN")
    }

    fun syncFloat(context: Context, key: String, value: Float) {
        val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
        prefs.edit().putFloat(key, value).commit()
        sendSync(context, key, value.toString(), "FLOAT")
    }

    fun syncStringSet(context: Context, key: String, set: Set<String>) {
        val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
        prefs.edit().putStringSet(key, set).commit()
        sendSync(context, key, set.joinToString("|||"), "STRING_SET")
    }

    fun syncRemove(context: Context, key: String) {
        val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
        prefs.edit().remove(key).commit()
        sendSync(context, key, "", "REMOVE")
    }

    fun triggerReload(context: Context) {
        sendSync(context, "", "", "RELOAD_ALL")
    }

    private fun sendSync(context: Context, key: String, value: String, type: String) {
        // 1. Send via startService to HandleService if running
        try {
            val svcIntent = Intent(context, HandleService::class.java).apply {
                action = ACTION_SYNC_PREF
                putExtra(EXTRA_KEY, key)
                putExtra(EXTRA_VALUE, value)
                putExtra(EXTRA_TYPE, type)
            }
            context.startService(svcIntent)
        } catch (e: Exception) {
            // Ignored if background start is restricted
        }

        // 2. Also send explicit broadcast with app package target
        try {
            val bIntent = Intent(ACTION_SYNC_PREF).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_KEY, key)
                putExtra(EXTRA_VALUE, value)
                putExtra(EXTRA_TYPE, type)
            }
            context.sendBroadcast(bIntent)
        } catch (e: Exception) {
            // Ignored
        }
    }
}
