package com.example.feature.language

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.example.core.WindowBounds

/**
 * Shared visual tokens, system light/dark theme palette, compact bounds calculators, and
 * status-card builders used by Dictionary, Quick Meaning popup, and Note floating UI.
 */
object LanguageMiniAppUiHelper {
    // Legacy dark-mode token constants kept for source compatibility
    const val COLOR_SURFACE_BG: Int = 0xFF1E1E2E.toInt()
    const val COLOR_CARD_BG: Int = 0xFF252639.toInt()
    const val COLOR_INPUT_BG: Int = 0xFF2A2B3D.toInt()
    const val COLOR_BORDER: Int = 0xFF45475A.toInt()
    const val COLOR_TEXT_PRIMARY: Int = 0xFFCDD6F4.toInt()
    const val COLOR_TEXT_SECONDARY: Int = 0xFFA6ADC8.toInt()
    const val COLOR_TEXT_MUTED: Int = 0xFF7F849C.toInt()
    const val COLOR_ACCENT_DICTIONARY: Int = 0xFF89B4FA.toInt()
    const val COLOR_WARNING_BG: Int = 0xFF2E2A3B.toInt()
    const val COLOR_WARNING_ACCENT: Int = 0xFFF9E2AF.toInt()

    const val ACCENT_DICTIONARY_HEX = "#89B4FA"

    data class FloatingUiPalette(
        val isDark: Boolean,
        val surfaceBg: Int,
        val windowSurfaceBg: Int,
        val topBarBg: Int,
        val cardBg: Int,
        val inputBg: Int,
        val buttonBg: Int,
        val border: Int,
        val divider: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val textMuted: Int,
        val selectionHighlight: Int,
        val accentDictionary: Int,
        val onAccent: Int,
        val chipBg: Int,
        val warningBg: Int,
        val warningAccent: Int,
        val dangerText: Int,
        val successText: Int,
        val scrim: Int
    )

    private val LIGHT_PALETTE: FloatingUiPalette = FloatingUiPalette(
        isDark = false,
        surfaceBg = 0xFFFFFFFF.toInt(),
        windowSurfaceBg = 0xFFFFFFFF.toInt(),
        topBarBg = 0xFFF2F4F8.toInt(),
        cardBg = 0xFFF5F7FB.toInt(),
        inputBg = 0xFFF1F4F9.toInt(),
        buttonBg = 0xFFEDF1F7.toInt(),
        border = 0xFFDCE2EC.toInt(),
        divider = 0xFFE2E8F0.toInt(),
        textPrimary = 0xFF181A20.toInt(),
        textSecondary = 0xFF4A5162.toInt(),
        textMuted = 0xFF6B7280.toInt(),
        selectionHighlight = 0xFFCFE0FC.toInt(),
        accentDictionary = 0xFF1E66F5.toInt(),
        onAccent = 0xFFFFFFFF.toInt(),
        chipBg = 0xFFE8F0FE.toInt(),
        warningBg = 0xFFFFF8E6.toInt(),
        warningAccent = 0xFF9A6700.toInt(),
        dangerText = 0xFFC62828.toInt(),
        successText = 0xFF1E7E34.toInt(),
        scrim = 0x55000000
    )

    private val DARK_PALETTE: FloatingUiPalette = FloatingUiPalette(
        isDark = true,
        surfaceBg = 0xFF181825.toInt(),
        windowSurfaceBg = 0xFF1E1E2E.toInt(),
        topBarBg = 0xFF2B2D42.toInt(),
        cardBg = 0xFF252639.toInt(),
        inputBg = 0xFF2A2B3D.toInt(),
        buttonBg = 0xFF313244.toInt(),
        border = 0xFF45475A.toInt(),
        divider = 0xFF45475A.toInt(),
        textPrimary = 0xFFCDD6F4.toInt(),
        textSecondary = 0xFFA6ADC8.toInt(),
        textMuted = 0xFF7F849C.toInt(),
        selectionHighlight = 0xFF585B70.toInt(),
        accentDictionary = 0xFF89B4FA.toInt(),
        onAccent = 0xFF181825.toInt(),
        chipBg = 0xFF2E334D.toInt(),
        warningBg = 0xFF2E2A3B.toInt(),
        warningAccent = 0xFFF9E2AF.toInt(),
        dangerText = 0xFFF38BA8.toInt(),
        successText = 0xFFA6E3A1.toInt(),
        scrim = 0x73000000
    )

    /**
     * Detects whether Android's active system UI mode is Dark Mode.
     */
    fun isDarkTheme(context: Context): Boolean {
        val uiMode = try {
            context.resources?.configuration?.uiMode ?: 0
        } catch (_: Throwable) {
            0
        }
        return (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    fun resolvePalette(isDark: Boolean): FloatingUiPalette {
        return if (isDark) DARK_PALETTE else LIGHT_PALETTE
    }

    fun resolvePalette(context: Context): FloatingUiPalette {
        return resolvePalette(isDarkTheme(context))
    }

    /**
     * Ensures a user-chosen accent color (such as pastel Note presets) remains legible when used
     * for text or button strokes on light surfaces without changing its hue family.
     */
    fun adaptAccentForSurface(accentColor: Int, isDark: Boolean): Int {
        if (isDark) return accentColor
        val r = (accentColor ushr 16) and 0xFF
        val g = (accentColor ushr 8) and 0xFF
        val b = accentColor and 0xFF
        val luminance = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        if (luminance <= 0.45f) return accentColor
        val scale = (0.42f / luminance).coerceIn(0.45f, 0.85f)
        val nr = (r * scale).toInt().coerceIn(0, 255)
        val ng = (g * scale).toInt().coerceIn(0, 255)
        val nb = (b * scale).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
    }

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
        palette: FloatingUiPalette = resolvePalette(context),
        onClick: () -> Unit
    ): TextView {
        val density = context.resources.displayMetrics.density
        val minTouchPx = (40 * density).toInt()
        val displayAccent = adaptAccentForSurface(accentColor, palette.isDark)
        return TextView(context).apply {
            this.text = text
            this.contentDescription = contentDesc
            if (tag != null) this.tag = tag
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minHeight = minTouchPx
            setTextColor(if (filled) palette.onAccent else displayAccent)
            background = if (filled) {
                roundedBg(density, accentColor, null, 8f)
            } else {
                roundedBg(density, palette.buttonBg, displayAccent, 8f)
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
        palette: FloatingUiPalette = resolvePalette(context),
        onActionClick: (() -> Unit)? = null
    ): LinearLayout {
        val density = context.resources.displayMetrics.density
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(density, palette.warningBg, palette.border, 10f)
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())

            addView(TextView(context).apply {
                text = badgeText
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(palette.warningAccent)
                letterSpacing = 0.05f
            })

            addView(TextView(context).apply {
                text = titleText
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(palette.textPrimary)
                setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
            })

            addView(TextView(context).apply {
                text = bodyText
                textSize = 12f
                setTextColor(palette.textSecondary)
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
                        accentColor = palette.warningAccent,
                        filled = false,
                        contentDesc = actionLabel,
                        tag = actionTag,
                        palette = palette,
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
