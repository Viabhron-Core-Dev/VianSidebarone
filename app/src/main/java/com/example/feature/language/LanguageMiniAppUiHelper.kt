package com.example.feature.language

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.example.core.WindowBounds

/**
 * Shared visual tokens, compact bounds calculators, and placeholder-card builders used by the
 * separate Dictionary and Translation floating mini-apps.
 *
 * Keeps visual styling cohesive without merging the two mini-apps into one UI or tab container.
 */
object LanguageMiniAppUiHelper {
    val COLOR_SURFACE_BG: Int = Color.parseColor("#1E1E2E")
    val COLOR_CARD_BG: Int = Color.parseColor("#252639")
    val COLOR_INPUT_BG: Int = Color.parseColor("#2A2B3D")
    val COLOR_BORDER: Int = Color.parseColor("#45475A")
    val COLOR_TEXT_PRIMARY: Int = Color.parseColor("#CDD6F4")
    val COLOR_TEXT_SECONDARY: Int = Color.parseColor("#A6ADC8")
    val COLOR_TEXT_MUTED: Int = Color.parseColor("#7F849C")
    val COLOR_ACCENT_DICTIONARY: Int = Color.parseColor("#89B4FA")
    val COLOR_ACCENT_TRANSLATION: Int = Color.parseColor("#A6E3A1")
    val COLOR_WARNING_BG: Int = Color.parseColor("#2E2A3B")
    val COLOR_WARNING_ACCENT: Int = Color.parseColor("#F9E2AF")

    const val ACCENT_DICTIONARY_HEX = "#89B4FA"
    const val ACCENT_TRANSLATION_HEX = "#A6E3A1"

    /**
     * Computes compact initial floating bounds for the Dictionary mini-app, bounded safely to the
     * current screen dimensions instead of using oversized hardcoded dimensions.
     */
    fun computeCompactDictionaryBounds(
        context: Context,
        screenWidth: Int,
        screenHeight: Int
    ): WindowBounds {
        val density = context.resources.displayMetrics.density
        val safeW = screenWidth.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels.coerceAtLeast(720)
        val safeH = screenHeight.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels.coerceAtLeast(1280)
        val maxW = (safeW * 0.88f).toInt().coerceAtLeast((240 * density).toInt())
        val maxH = (safeH * 0.60f).toInt().coerceAtLeast((260 * density).toInt())
        val width = (300 * density).toInt().coerceAtMost(maxW)
        val height = (330 * density).toInt().coerceAtMost(maxH)
        val x = ((safeW - width) / 2).coerceAtLeast((12 * density).toInt())
        val y = (96 * density).toInt().coerceAtMost(maxOf(0, safeH - height - (48 * density).toInt()))
        return WindowBounds(x, y, width, height)
    }

    /**
     * Computes compact initial floating bounds for the Translation mini-app, slightly staggered
     * from Dictionary so both can be opened side-by-side cleanly.
     */
    fun computeCompactTranslationBounds(
        context: Context,
        screenWidth: Int,
        screenHeight: Int
    ): WindowBounds {
        val density = context.resources.displayMetrics.density
        val safeW = screenWidth.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels.coerceAtLeast(720)
        val safeH = screenHeight.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels.coerceAtLeast(1280)
        val maxW = (safeW * 0.90f).toInt().coerceAtLeast((250 * density).toInt())
        val maxH = (safeH * 0.65f).toInt().coerceAtLeast((280 * density).toInt())
        val width = (312 * density).toInt().coerceAtMost(maxW)
        val height = (380 * density).toInt().coerceAtMost(maxH)
        val x = ((safeW - width) / 2 + (14 * density).toInt()).coerceIn(0, maxOf(0, safeW - width))
        val y = (128 * density).toInt().coerceAtMost(maxOf(0, safeH - height - (48 * density).toInt()))
        return WindowBounds(x, y, width, height)
    }

    fun roundedBg(
        density: Float,
        fillColor: Int,
        strokeColor: Int? = null,
        cornerDp: Float = 10f
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = cornerDp * density
        setColor(fillColor)
        if (strokeColor != null) {
            setStroke((1 * density).toInt().coerceAtLeast(1), strokeColor)
        }
    }

    fun createPillButton(
        context: Context,
        text: String,
        accentColor: Int,
        filled: Boolean = false,
        contentDesc: String = text,
        tag: String? = null,
        onClick: () -> Unit
    ): TextView {
        val density = context.resources.displayMetrics.density
        val minTouchPx = (40 * density).toInt()
        return TextView(context).apply {
            this.text = text
            this.contentDescription = contentDesc
            if (tag != null) this.tag = tag
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minHeight = minTouchPx
            setTextColor(if (filled) Color.parseColor("#1E1E2E") else accentColor)
            background = if (filled) {
                roundedBg(density, accentColor, null, 8f)
            } else {
                roundedBg(density, COLOR_CARD_BG, accentColor, 8f)
            }
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    fun createStatusNoticeCard(
        context: Context,
        badgeText: String,
        titleText: String,
        bodyText: String,
        actionLabel: String? = null,
        actionTag: String? = null,
        onActionClick: (() -> Unit)? = null
    ): LinearLayout {
        val density = context.resources.displayMetrics.density
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(density, COLOR_WARNING_BG, COLOR_BORDER, 10f)
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())

            addView(TextView(context).apply {
                text = badgeText
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_WARNING_ACCENT)
                letterSpacing = 0.05f
            })

            addView(TextView(context).apply {
                text = titleText
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_TEXT_PRIMARY)
                setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
            })

            addView(TextView(context).apply {
                text = bodyText
                textSize = 12f
                setTextColor(COLOR_TEXT_SECONDARY)
                setLineSpacing(0f, 1.25f)
            })

            if (actionLabel != null && onActionClick != null) {
                addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(1, (8 * density).toInt())
                })
                addView(
                    createPillButton(
                        context = context,
                        text = actionLabel,
                        accentColor = COLOR_WARNING_ACCENT,
                        filled = false,
                        contentDesc = actionLabel,
                        tag = actionTag,
                        onClick = onActionClick
                    ).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    }
                )
            }
        }
    }
}
