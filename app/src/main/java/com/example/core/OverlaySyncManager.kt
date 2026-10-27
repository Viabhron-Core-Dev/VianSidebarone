package com.example.core

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

object OverlaySyncManager {
    const val ACTION_SYNC_PREF = "com.example.ACTION_SYNC_PREF"
    const val EXTRA_KEY = "extra_key"
    const val EXTRA_VALUE = "extra_value"
    const val EXTRA_TYPE = "extra_type"
    const val EXTRA_PREF_NAME = "extra_pref_name"

    fun syncString(context: Context, key: String, value: String, prefName: String = "FloatingReaderPrefs") {
        val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        prefs.edit().putString(key, value).commit()
        sendSync(context, key, value, "STRING", prefName)
    }

    fun syncInt(context: Context, key: String, value: Int, prefName: String = "FloatingReaderPrefs") {
        val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        prefs.edit().putInt(key, value).commit()
        sendSync(context, key, value.toString(), "INT", prefName)
    }

    fun syncBoolean(context: Context, key: String, value: Boolean, prefName: String = "FloatingReaderPrefs") {
        val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(key, value).commit()
        sendSync(context, key, value.toString(), "BOOLEAN", prefName)
    }

    fun syncFloat(context: Context, key: String, value: Float, prefName: String = "FloatingReaderPrefs") {
        val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        prefs.edit().putFloat(key, value).commit()
        sendSync(context, key, value.toString(), "FLOAT", prefName)
    }

    fun syncStringSet(context: Context, key: String, set: Set<String>, prefName: String = "FloatingReaderPrefs") {
        val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(key, set).commit()
        sendSync(context, key, set.joinToString("|||"), "STRING_SET", prefName)
    }

    fun syncRemove(context: Context, key: String, prefName: String = "FloatingReaderPrefs") {
        val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        prefs.edit().remove(key).commit()
        sendSync(context, key, "", "REMOVE", prefName)
    }

    fun triggerReload(context: Context) {
        sendSync(context, "", "", "RELOAD_ALL", "FloatingReaderPrefs")
    }

    private fun sendSync(context: Context, key: String, value: String, type: String, prefName: String = "FloatingReaderPrefs") {
        // 1. Send via startService to HandleService if running
        try {
            val svcIntent = Intent(context, HandleService::class.java).apply {
                action = ACTION_SYNC_PREF
                putExtra(EXTRA_KEY, key)
                putExtra(EXTRA_VALUE, value)
                putExtra(EXTRA_TYPE, type)
                putExtra(EXTRA_PREF_NAME, prefName)
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
                putExtra(EXTRA_PREF_NAME, prefName)
            }
            context.sendBroadcast(bIntent)
        } catch (e: Exception) {
            // Ignored
        }
    }
}
