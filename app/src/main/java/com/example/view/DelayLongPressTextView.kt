package com.example.view

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.TextView

class DelayLongPressTextView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : TextView(context, attrs, defStyleAttr) {

    private val longPressRunnable = Runnable {
        super.performLongClick()
    }
    private var downX = 0f
    private var downY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                val res = super.onTouchEvent(event)
                // Cancel the default 500ms long press
                cancelLongPress()
                postDelayed(longPressRunnable, 1000) // custom 1 second long press
                return res
            }
            MotionEvent.ACTION_MOVE -> {
                val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                if (Math.abs(event.x - downX) > slop || Math.abs(event.y - downY) > slop) {
                    removeCallbacks(longPressRunnable)
                    cancelLongPress()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                cancelLongPress()
            }
        }
        return super.onTouchEvent(event)
    }
}
