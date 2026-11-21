package com.example.feature.selection

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.SettingsActivity
import com.example.feature.language.LanguageMiniAppUiHelper
import com.example.feature.settings.ROUTE_DICTIONARY_SETTINGS

/**
 * Shared compact dialog shell for Android text-selection (`ACTION_PROCESS_TEXT`) popup activities.
 *
 * Distinct from full floating mini-apps: renders a lightweight, focused popup card over a
 * translucent scrim so the underlying host app stays visible and tapping outside dismisses cleanly.
 * Follows Android system light/dark theme automatically.
 */
object SelectionPopupShell {

    data class ShellViews(
        val root: FrameLayout,
        val cardContent: LinearLayout
    )

    fun create(
        activity: Activity,
        title: String,
        accentColor: Int,
        onDismiss: () -> Unit
    ): ShellViews {
        val density = activity.resources.displayMetrics.density
        val palette = LanguageMiniAppUiHelper.resolvePalette(activity)
        val maxCardWidthPx = minOf(
            (340 * density).toInt(),
            (activity.resources.displayMetrics.widthPixels - (32 * density).toInt()).coerceAtLeast((240 * density).toInt())
        )

        val scrim = FrameLayout(activity).apply {
            setBackgroundColor(palette.scrim)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding((16 * density).toInt(), (24 * density).toInt(), (16 * density).toInt(), (24 * density).toInt())
            isClickable = true
            setOnClickListener { onDismiss() }
        }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = LanguageMiniAppUiHelper.roundedBg(
                density = density,
                fillColor = palette.windowSurfaceBg,
                strokeColor = palette.border,
                cornerDp = 18f
            )
            elevation = (10 * density)
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(
                maxCardWidthPx,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        // Header row: accent indicator + title + Close button (48dp touch target)
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val dot = View(activity).apply {
            background = LanguageMiniAppUiHelper.roundedBg(density, accentColor, null, 6f)
            layoutParams = LinearLayout.LayoutParams((10 * density).toInt(), (10 * density).toInt()).apply {
                marginEnd = (8 * density).toInt()
            }
        }
        header.addView(dot)

        val titleView = TextView(activity).apply {
            text = title
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(titleView)

        val closeBtn = TextView(activity).apply {
            text = "×"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(palette.textSecondary)
            contentDescription = "Close $title popup"
            tag = "selection_popup_close_button"
            val touchPx = (48 * density).toInt()
            minimumWidth = touchPx
            minimumHeight = touchPx
            isClickable = true
            isFocusable = true
            setOnClickListener { onDismiss() }
        }
        header.addView(closeBtn)

        card.addView(header)
        card.addView(View(activity).apply {
            setBackgroundColor(palette.divider)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * density).toInt().coerceAtLeast(1)
            ).apply {
                topMargin = (4 * density).toInt()
                bottomMargin = (12 * density).toInt()
            }
        })

        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        card.addView(body)
        scrim.addView(card)

        return ShellViews(root = scrim, cardContent = body)
    }

    fun openDictionarySettings(activity: Activity) {
        try {
            val intent = Intent(activity, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("start_route", ROUTE_DICTIONARY_SETTINGS)
            }
            activity.startActivity(intent)
        } catch (_: Throwable) {}
    }
}
