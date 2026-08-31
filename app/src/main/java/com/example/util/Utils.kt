package com.example.util

import android.content.Context
import android.util.TypedValue
import android.view.Gravity

object Utils {
    fun dpToPx(context: Context, dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
    
    fun getScreenWidth(context: Context): Int {
        return context.resources.displayMetrics.widthPixels
    }

    fun getScreenHeight(context: Context): Int {
        return context.resources.displayMetrics.heightPixels
    }
}

fun getEdgeFlag(edge: String): Int {
    return if (edge == "left") Gravity.START else Gravity.END
}
