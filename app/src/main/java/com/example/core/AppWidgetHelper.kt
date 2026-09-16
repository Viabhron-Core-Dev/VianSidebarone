package com.example.core

import android.appwidget.AppWidgetHost
import android.content.Context

object AppWidgetHelper {
    const val HOST_ID = 10001
    private var _host: AppWidgetHost? = null

    fun getHost(context: Context): AppWidgetHost {
        if (_host == null) {
            _host = AppWidgetHost(context.applicationContext, HOST_ID)
        }
        return _host!!
    }

    fun startListening(context: Context) {
        getHost(context)
        try {
            _host?.startListening()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopListening() {
        try {
            _host?.stopListening()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
