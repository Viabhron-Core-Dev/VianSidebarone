package com.example.feature.sidebar

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView
import com.example.R
import com.example.core.LogKeeper

class CompassPageView(
    context: Context,
    private val onHeightChanged: ((Int) -> Unit)? = null
) : FrameLayout(context), SensorEventListener, SidebarPageControllable {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    
    private var gravity: FloatArray? = null
    private var geomagnetic: FloatArray? = null

    private val compassView: CompassDrawView
    private val tvAzimuth: TextView
    private val tvDirection: TextView

    @Volatile
    private var isListening = false
    private var lastCalculatedHeightPx = 0

    var themeBackgroundDrawable: GradientDrawable? = null
        private set

    init {
        LogKeeper.writeLog("Compass", "Opened compass page in :heavy")
        var cv: CompassDrawView? = null
        var ta: TextView? = null
        var td: TextView? = null
        try {
            LayoutInflater.from(context).inflate(R.layout.page_compass, this, true)
            cv = findViewById(R.id.compass_view)
            ta = findViewById(R.id.tv_azimuth)
            td = findViewById(R.id.tv_direction)
        } catch (_: Throwable) {}

        if (cv == null || ta == null || td == null) {
            val fallbackContainer = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            cv = CompassDrawView(context)
            ta = TextView(context).apply { text = "0°" }
            td = TextView(context).apply { text = "N" }
            fallbackContainer.addView(cv)
            fallbackContainer.addView(ta)
            fallbackContainer.addView(td)
            addView(fallbackContainer)
        }

        compassView = cv
        tvAzimuth = ta
        tvDirection = td

        notifyHeight()
    }

    fun getCurrentHeightPx(): Int {
        if (lastCalculatedHeightPx == 0) {
            val density = try { resources?.displayMetrics?.density ?: 1f } catch (_: Throwable) { 1f }
            val fallbackW = (320 * density).toInt()
            val w = if (width > 0) width else fallbackW
            measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            lastCalculatedHeightPx = measuredHeight
        }
        return lastCalculatedHeightPx
    }

    fun applyThemeBackground(bgColor: Int = 0, isRight: Boolean = true) {
        val finalColor = if (bgColor != 0) {
            bgColor
        } else {
            val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
            val opacity = prefs.getFloat("sidebar_transparency", 0.9f)
            val alphaInt = (opacity * 255).toInt().coerceIn(0, 255)
            val colorHex = prefs.getString("sidebar_color", "#1E1E2E") ?: "#1E1E2E"
            val baseColor = try { Color.parseColor(colorHex) } catch (_: Exception) { Color.parseColor("#1E1E2E") }
            val r = Color.red(baseColor)
            val g = Color.green(baseColor)
            val b = Color.blue(baseColor)
            Color.argb(alphaInt, r, g, b)
        }

        val density = try { resources?.displayMetrics?.density ?: 1f } catch (_: Throwable) { 1f }
        val radius = 16f * density
        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(finalColor)
            cornerRadii = if (isRight) {
                floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, radius, radius)
            } else {
                floatArrayOf(0f, 0f, 0f, 0f, radius, radius, 0f, 0f)
            }
        }
        themeBackgroundDrawable = bgDrawable
        background = bgDrawable
    }

    private fun notifyHeight() {
        post {
            val density = try { resources?.displayMetrics?.density ?: 1f } catch (_: Throwable) { 1f }
            val fallbackW = (320 * density).toInt()
            val w = if (width > 0) width else fallbackW
            measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            if (measuredHeight > 0) {
                lastCalculatedHeightPx = measuredHeight
                onHeightChanged?.invoke(measuredHeight)
            }
        }
    }

    fun startListening() {
        if (!isListening) {
            accelerometer?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            magnetometer?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            isListening = true
        }
        notifyHeight()
    }

    fun stopListening() {
        if (isListening) {
            sensorManager?.unregisterListener(this)
            isListening = false
        }
    }

    fun isSensorListening(): Boolean = isListening

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startListening()
    }
    
    override fun onPageSelected() {
        startListening()
    }
    
    override fun onPageUnselected() {
        stopListening()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopListening()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            gravity = event.values.clone()
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            geomagnetic = event.values.clone()
        }
        
        if (gravity != null && geomagnetic != null) {
            val r = FloatArray(9)
            val i = FloatArray(9)
            if (SensorManager.getRotationMatrix(r, i, gravity, geomagnetic)) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(r, orientation)
                var azimuthInDegrees = Math.toDegrees(orientation[0].toDouble()).toFloat()
                if (azimuthInDegrees < 0) {
                    azimuthInDegrees += 360f
                }
                updateCompass(azimuthInDegrees)
            }
        }
    }

    private fun updateCompass(azimuth: Float) {
        compassView.setAzimuth(azimuth)
        tvAzimuth.text = "${azimuth.toInt()}°"

        val direction = when {
            azimuth >= 337.5 || azimuth < 22.5 -> "N"
            azimuth >= 22.5 && azimuth < 67.5 -> "NE"
            azimuth >= 67.5 && azimuth < 112.5 -> "E"
            azimuth >= 112.5 && azimuth < 157.5 -> "SE"
            azimuth >= 157.5 && azimuth < 202.5 -> "S"
            azimuth >= 202.5 && azimuth < 247.5 -> "SW"
            azimuth >= 247.5 && azimuth < 292.5 -> "W"
            else -> "NW"
        }
        tvDirection.text = direction
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

