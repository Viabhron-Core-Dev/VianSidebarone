package com.example.feature.sidebar

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import com.example.R
import java.text.DecimalFormat
import kotlin.math.pow
import kotlin.math.sqrt

class CalculatorPageView(
    context: Context,
    private val onHeightChanged: ((Int) -> Unit)? = null
) : FrameLayout(context), SidebarPageControllable {

    private var expression = ""
    private var cursorIndex = 0
    private var resultText = ""
    private var expressionCompleted = false

    private val tvExpression: TextView
    private val tvResult: TextView
    private val scrollExpression: HorizontalScrollView
    private val scrollResult: HorizontalScrollView
    private val layoutExtendedDrawer: View
    private val ivDrawerArrow: ImageView
    private val btnCopyResult: ImageView?
    private val btnConvertQuantity: ImageView?
    private val layoutCalculatorMain: View?
    private val layoutConverterOverlay: View?
    private val tvConverterHeader: TextView?
    private val btnConverterClose: View?
    private val layoutConverterCategories: LinearLayout?
    private val layoutConverterPairCard: View?
    private val layoutFromSlot: View?
    private val layoutToSlot: View?
    private val tvFromBadge: TextView?
    private val tvToBadge: TextView?
    private val tvFromUnit: TextView?
    private val tvToUnit: TextView?
    private val tvFromValue: TextView?
    private val tvToValue: TextView?
    private val btnSwapUnits: View?
    private val btnCopyConverted: View?
    private val tvConverterPickHint: TextView?
    private val layoutUnitGrid: LinearLayout?

    private var converterSourceValue: Double = 0.0
    private var converterCategory: QuantityCategory = QuantityCategory.LENGTH
    private var fromUnit: QuantityUnit = QuantityConverter.defaultFromUnit(QuantityCategory.LENGTH)
    private var toUnit: QuantityUnit = QuantityConverter.defaultToUnit(QuantityCategory.LENGTH)
    private var selectingFromUnit: Boolean = true
    private var currentConvertedNumberText: String = "0"

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        LayoutInflater.from(context).inflate(R.layout.page_calculator, this, true)
        tvExpression = findViewById(R.id.tv_expression)
        tvResult = findViewById(R.id.tv_result)
        scrollExpression = findViewById(R.id.scroll_expression)
        scrollResult = findViewById(R.id.scroll_result)
        layoutExtendedDrawer = findViewById(R.id.layout_extended_drawer)
        ivDrawerArrow = findViewById(R.id.iv_drawer_arrow)
        btnCopyResult = findViewById(R.id.btn_copy_result)
        btnConvertQuantity = findViewById(R.id.btn_convert_quantity)
        layoutCalculatorMain = findViewById(R.id.layout_calculator_main)
        layoutConverterOverlay = findViewById(R.id.layout_converter_overlay)
        tvConverterHeader = findViewById(R.id.tv_converter_header)
        btnConverterClose = findViewById(R.id.btn_converter_close)
        layoutConverterCategories = findViewById(R.id.layout_converter_categories)
        layoutConverterPairCard = findViewById(R.id.layout_converter_pair_card)
        layoutFromSlot = findViewById(R.id.layout_from_slot)
        layoutToSlot = findViewById(R.id.layout_to_slot)
        tvFromBadge = findViewById(R.id.tv_from_badge)
        tvToBadge = findViewById(R.id.tv_to_badge)
        tvFromUnit = findViewById(R.id.tv_from_unit)
        tvToUnit = findViewById(R.id.tv_to_unit)
        tvFromValue = findViewById(R.id.tv_from_value)
        tvToValue = findViewById(R.id.tv_to_value)
        btnSwapUnits = findViewById(R.id.btn_swap_units)
        btnCopyConverted = findViewById(R.id.btn_copy_converted)
        tvConverterPickHint = findViewById(R.id.tv_converter_pick_hint)
        layoutUnitGrid = findViewById(R.id.layout_unit_grid)

        // Ensure display elements cannot hold focus or display any cursor
        tvExpression.isCursorVisible = false
        tvExpression.isFocusable = false
        tvExpression.isFocusableInTouchMode = false
        tvResult.isCursorVisible = false
        tvResult.isFocusable = false
        tvResult.isFocusableInTouchMode = false
        scrollExpression.isFocusable = false
        scrollExpression.isFocusableInTouchMode = false
        scrollResult.isFocusable = false
        scrollResult.isFocusableInTouchMode = false

        btnCopyResult?.setOnClickListener {
            copyResultToClipboard()
        }

        restoreConverterPrefs()
        btnConvertQuantity?.setOnClickListener {
            openConverterOverlay()
        }
        btnConverterClose?.setOnClickListener {
            closeConverterOverlay()
        }
        layoutFromSlot?.setOnClickListener {
            selectingFromUnit = true
            renderConverterOverlay()
        }
        layoutToSlot?.setOnClickListener {
            selectingFromUnit = false
            renderConverterOverlay()
        }
        btnSwapUnits?.setOnClickListener {
            swapConverterUnits()
        }
        btnCopyConverted?.setOnClickListener {
            copyConvertedValueToClipboard()
        }
        tvToValue?.apply {
            isFocusable = false
            isFocusableInTouchMode = false
            setOnClickListener { copyConvertedValueToClipboard() }
        }

        // Drawer Toggle
        findViewById<View>(R.id.btn_toggle_drawer).setOnClickListener {
            if (layoutExtendedDrawer.visibility == View.VISIBLE) {
                layoutExtendedDrawer.visibility = View.GONE
                ivDrawerArrow.rotation = 180f
            } else {
                layoutExtendedDrawer.visibility = View.VISIBLE
                ivDrawerArrow.rotation = 0f
            }
            notifyHeight()
        }

        // Extended drawer buttons
        findViewById<View>(R.id.btn_cursor_left)?.setOnClickListener { moveCursor(-1) }
        findViewById<View>(R.id.btn_cursor_right)?.setOnClickListener { moveCursor(1) }
        findViewById<View>(R.id.btn_paren_open)?.setOnClickListener { insertAtCursor("(") }
        findViewById<View>(R.id.btn_paren_close)?.setOnClickListener { insertAtCursor(")") }
        findViewById<View>(R.id.btn_power)?.setOnClickListener { insertAtCursor("^") }
        findViewById<View>(R.id.btn_sqrt)?.setOnClickListener { insertAtCursor("√") }

        // Standard keypad grid
        val tableLayout = findViewById<TableLayout>(R.id.tableLayout)
        tableLayout?.let { tbl ->
            for (j in 0 until tbl.childCount) {
                val row = tbl.getChildAt(j) as? TableRow ?: continue
                for (k in 0 until row.childCount) {
                    val btn = row.getChildAt(k) as? TextView ?: continue
                    btn.setOnClickListener { onBtnClick(btn.text.toString()) }
                }
            }
        }
        applyThemeBackground()
        updateUI()
        notifyHeight()
    }

    /**
     * Applies the Sidebar container background styling (color, opacity, stroke, corner radii)
     * so that the embedded surface does not appear transparent over wallpaper.
     */
    fun applyThemeBackground(
        bgColor: Int = 0,
        isRight: Boolean = true
    ) {
        val finalColor = if (bgColor != 0) {
            bgColor
        } else {
            val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
            val opacity = prefs.getFloat("sidebar_transparency", 0.9f)
            val alphaInt = (opacity * 255).toInt().coerceIn(0, 255)
            val colorHex = prefs.getString("sidebar_color", "#1E1E2E") ?: "#1E1E2E"
            val baseColor = try { Color.parseColor(colorHex) } catch (e: Exception) { Color.parseColor("#1E1E2E") }
            val r = Color.red(baseColor)
            val g = Color.green(baseColor)
            val b = Color.blue(baseColor)
            Color.argb(alphaInt, r, g, b)
        }

        val density = resources.displayMetrics.density
        val radius = 16f * density
        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(finalColor)
            setStroke((1 * density).toInt(), Color.argb(80, 255, 255, 255))
            cornerRadii = if (isRight) {
                floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, radius, radius)
            } else {
                floatArrayOf(0f, 0f, 0f, 0f, radius, radius, 0f, 0f)
            }
        }
        background = bgDrawable
    }

    private fun notifyHeight() {
        post {
            measure(
                MeasureSpec.makeMeasureSpec(width.takeIf { it > 0 } ?: (320 * resources.displayMetrics.density).toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            if (measuredHeight > 0) {
                onHeightChanged?.invoke(measuredHeight)
            }
        }
    }

    override fun onPageSelected() {
        notifyHeight()
    }

    private fun moveCursor(offset: Int) {
        if (expressionCompleted) {
            expressionCompleted = false
        }
        val newPos = (cursorIndex + offset).coerceIn(0, expression.length)
        cursorIndex = newPos
        updateUI()
    }

    private fun insertAtCursor(input: String) {
        if (expressionCompleted) {
            expression = if (input in listOf("+", "-", "x", "÷", "%", "^")) {
                resultText.removePrefix("=") + input
            } else {
                input
            }
            cursorIndex = expression.length
            expressionCompleted = false
        } else {
            val left = expression.substring(0, cursorIndex)
            val right = expression.substring(cursorIndex)
            expression = left + input + right
            cursorIndex += input.length
        }
        evaluateAndSetResult()
        updateUI()
    }

    private fun copyResultToClipboard() {
        val rawResult = when {
            resultText.isNotEmpty() -> resultText.removePrefix("=")
            expression.isNotEmpty() -> {
                val evaluated = evaluateExpression(expression)
                if (evaluated.isNotEmpty() && evaluated != "Error") evaluated else expression
            }
            else -> "0"
        }
        val textToCopy = rawResult.trim()
        if (textToCopy == "Error") {
            Toast.makeText(context, "Cannot copy error", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("Calculator Result", textToCopy)
            clipboard?.setPrimaryClip(clip)
            Toast.makeText(context, context.getString(R.string.calculator_result_copied), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to copy", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onBtnClick(btn: String) {
        when (btn) {
            "C" -> onClear()
            "DEL" -> onDelete()
            "=" -> onEqual()
            else -> insertAtCursor(btn)
        }
    }

    private fun onClear() {
        expression = ""
        cursorIndex = 0
        resultText = ""
        expressionCompleted = false
        updateUI()
    }

    private fun onDelete() {
        if (expressionCompleted) {
            expression = ""
            cursorIndex = 0
            resultText = ""
            expressionCompleted = false
        } else if (expression.isNotEmpty() && cursorIndex > 0) {
            val left = expression.substring(0, cursorIndex - 1)
            val right = expression.substring(cursorIndex)
            expression = left + right
            cursorIndex--
            evaluateAndSetResult()
        }
        updateUI()
    }

    private fun onEqual() {
        if (expression.isNotEmpty()) {
            val res = evaluateExpression(expression)
            if (res != "Error" && res.isNotEmpty()) {
                resultText = "=$res"
                expressionCompleted = true
            }
        }
        updateUI()
    }

    private fun evaluateAndSetResult() {
        val res = evaluateExpression(expression)
        if (res != "Error" && res.isNotEmpty() && expression.any { it in listOf('+', '-', 'x', '÷', '%', '^', '√', '(', ')') }) {
            resultText = "=$res"
        } else {
            resultText = ""
        }
    }

    private fun updateUI() {
        // Auto-scale font sizes based on string length to prevent clipping/overflow
        val exprLength = expression.length
        val dynamicExprSize = when {
            exprLength > 30 -> 13f
            exprLength > 20 -> 15f
            else -> 18f
        }
        tvExpression.textSize = dynamicExprSize

        val resLength = if (resultText.isNotEmpty()) resultText.length else expression.length
        val dynamicResultSize = when {
            resLength > 20 -> 22f
            resLength > 15 -> 26f
            resLength > 10 -> 30f
            else -> 36f
        }
        tvResult.textSize = dynamicResultSize

        // Top expression: display expression cleanly without synthetic caret characters
        tvExpression.text = expression

        // Bottom display: standard default Android calculator display
        val bottomDisplay = when {
            expression.isEmpty() -> "0"
            resultText.isNotEmpty() -> resultText
            else -> expression
        }
        tvResult.text = bottomDisplay

        // Auto-scroll to end to follow input without requesting/stealing focus
        post {
            scrollExpression.fullScroll(View.FOCUS_RIGHT)
            scrollResult.fullScroll(View.FOCUS_RIGHT)
        }
        updateConvertButtonState()
    }

    private fun resolveCurrentConvertibleValue(): Double? {
        return QuantityConverter.resolveConvertibleValue(
            expression = expression,
            resultText = resultText,
            expressionCompleted = expressionCompleted,
            evalFn = { expr -> evalAdvanced(expr.replace("x", "*").replace("÷", "/")) }
        )
    }

    private fun updateConvertButtonState() {
        val eligible = resolveCurrentConvertibleValue() != null
        btnConvertQuantity?.apply {
            isEnabled = eligible
            alpha = if (eligible) 1.0f else 0.3f
        }
    }

    private fun getReaderPrefs() = try {
        context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
    } catch (_: Exception) {
        null
    }

    private fun restoreConverterPrefs() {
        val prefs = getReaderPrefs()
        val savedCatId = prefs?.getString("calc_conv_last_category", null)
        converterCategory = QuantityCategory.fromId(savedCatId)
        restoreConverterPairForCategory(converterCategory)
    }

    private fun restoreConverterPairForCategory(category: QuantityCategory) {
        val prefs = getReaderPrefs()
        val savedFrom = prefs?.getString("calc_conv_from_${category.id}", null)
        val savedTo = prefs?.getString("calc_conv_to_${category.id}", null)
        fromUnit = if (savedFrom != null) {
            QuantityConverter.findUnit(category, savedFrom)
        } else {
            QuantityConverter.defaultFromUnit(category)
        }
        toUnit = if (savedTo != null) {
            QuantityConverter.findUnit(category, savedTo)
        } else {
            QuantityConverter.defaultToUnit(category)
        }
    }

    private fun saveConverterPrefs() {
        val prefs = getReaderPrefs() ?: return
        prefs.edit()
            .putString("calc_conv_last_category", converterCategory.id)
            .putString("calc_conv_from_${converterCategory.id}", fromUnit.id)
            .putString("calc_conv_to_${converterCategory.id}", toUnit.id)
            .apply()
    }

    private fun openConverterOverlay() {
        val value = resolveCurrentConvertibleValue() ?: return
        converterSourceValue = value
        selectingFromUnit = true
        restoreConverterPairForCategory(converterCategory)
        // Keep calculator layout INVISIBLE (not GONE) so measured height stays identical
        layoutCalculatorMain?.visibility = View.INVISIBLE
        layoutConverterOverlay?.visibility = View.VISIBLE
        renderConverterOverlay()
        notifyHeight()
    }

    private fun closeConverterOverlay() {
        layoutConverterOverlay?.visibility = View.GONE
        layoutCalculatorMain?.visibility = View.VISIBLE
        notifyHeight()
    }

    private fun swapConverterUnits() {
        val tmp = fromUnit
        fromUnit = toUnit
        toUnit = tmp
        saveConverterPrefs()
        renderConverterOverlay()
    }

    private fun copyConvertedValueToClipboard() {
        val textToCopy = currentConvertedNumberText.trim()
        if (textToCopy.isEmpty() || textToCopy == "Error") {
            try {
                Toast.makeText(context, "Cannot copy error", Toast.LENGTH_SHORT)?.show()
            } catch (_: Exception) {}
            return
        }
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("Converted Quantity", textToCopy)
            clipboard?.setPrimaryClip(clip)
            Toast.makeText(context, context.getString(R.string.calculator_converted_copied), Toast.LENGTH_SHORT)?.show()
        } catch (_: Exception) {
            try {
                Toast.makeText(context, "Failed to copy", Toast.LENGTH_SHORT)?.show()
            } catch (_: Exception) {}
        }
    }

    private fun renderConverterOverlay() {
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val sourceFormatted = QuantityConverter.formatConvertedValue(converterSourceValue)
        val converted = QuantityConverter.convert(converterSourceValue, fromUnit, toUnit)
        currentConvertedNumberText = QuantityConverter.formatConvertedValue(converted)

        tvConverterHeader?.text = "Convert $sourceFormatted"
        tvFromUnit?.text = "${fromUnit.symbol} · ${fromUnit.name}"
        tvFromValue?.text = sourceFormatted
        tvToUnit?.text = "${toUnit.symbol} · ${toUnit.name}"
        tvToValue?.text = currentConvertedNumberText

        tvFromBadge?.setTextColor(if (selectingFromUnit) Color.parseColor("#80D8FF") else Color.parseColor("#90A4AE"))
        tvToBadge?.setTextColor(if (!selectingFromUnit) Color.parseColor("#80D8FF") else Color.parseColor("#90A4AE"))
        tvConverterPickHint?.text = if (selectingFromUnit) {
            "Select From unit (${converterCategory.label})"
        } else {
            "Select To unit (${converterCategory.label})"
        }

        layoutConverterPairCard?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(Color.argb(28, 255, 255, 255))
            setStroke(dp(1), Color.argb(45, 255, 255, 255))
        }

        layoutFromSlot?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(if (selectingFromUnit) Color.argb(40, 128, 216, 255) else Color.argb(14, 255, 255, 255))
            if (selectingFromUnit) setStroke(dp(1), Color.parseColor("#80D8FF"))
        }

        layoutToSlot?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(if (!selectingFromUnit) Color.argb(40, 128, 216, 255) else Color.argb(14, 255, 255, 255))
            if (!selectingFromUnit) setStroke(dp(1), Color.parseColor("#80D8FF"))
        }

        // Render category chips
        layoutConverterCategories?.removeAllViews()
        for (cat in QuantityCategory.entries) {
            val isSelected = cat == converterCategory
            val chip = TextView(context).apply {
                text = cat.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (isSelected) Color.parseColor("#0D1B2A") else Color.parseColor("#E0E0E0"))
                gravity = Gravity.CENTER
                isFocusable = false
                isFocusableInTouchMode = false
                setPadding(dp(10), dp(5), dp(10), dp(5))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(14).toFloat()
                    setColor(if (isSelected) Color.parseColor("#80D8FF") else Color.argb(36, 255, 255, 255))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dp(6)
                }
                setOnClickListener {
                    if (converterCategory != cat) {
                        converterCategory = cat
                        restoreConverterPairForCategory(cat)
                        saveConverterPrefs()
                        renderConverterOverlay()
                    }
                }
            }
            layoutConverterCategories?.addView(chip)
        }

        // Render unit selector grid (3 columns)
        val grid = layoutUnitGrid ?: return
        grid.removeAllViews()
        val units = QuantityConverter.unitsFor(converterCategory)
        val activeUnitId = if (selectingFromUnit) fromUnit.id else toUnit.id
        val columns = 3
        val rows = (units.size + columns - 1) / columns
        for (r in 0 until rows) {
            val rowLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(4)
                }
            }
            for (c in 0 until columns) {
                val idx = r * columns + c
                if (idx < units.size) {
                    val unit = units[idx]
                    val isSelected = unit.id == activeUnitId
                    val chipBtn = TextView(context).apply {
                        text = unit.symbol
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                        setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                        setTextColor(if (isSelected) Color.parseColor("#0D1B2A") else Color.WHITE)
                        gravity = Gravity.CENTER
                        maxLines = 1
                        isFocusable = false
                        isFocusableInTouchMode = false
                        setPadding(dp(4), dp(7), dp(4), dp(7))
                        contentDescription = "${unit.name} (${unit.symbol})"
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dp(8).toFloat()
                            setColor(if (isSelected) Color.parseColor("#80D8FF") else Color.argb(30, 255, 255, 255))
                            if (!isSelected) setStroke(dp(1), Color.argb(32, 255, 255, 255))
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f
                        ).apply {
                            if (c < columns - 1) marginEnd = dp(4)
                        }
                        setOnClickListener {
                            if (selectingFromUnit) {
                                fromUnit = unit
                            } else {
                                toUnit = unit
                            }
                            saveConverterPrefs()
                            renderConverterOverlay()
                        }
                    }
                    rowLayout.addView(chipBtn)
                } else {
                    val spacer = View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply {
                            if (c < columns - 1) marginEnd = dp(4)
                        }
                    }
                    rowLayout.addView(spacer)
                }
            }
            grid.addView(rowLayout)
        }
    }

    private fun evaluateExpression(expr: String): String {
        if (expr.isEmpty()) return ""
        val cleanExpr = expr.replace("x", "*").replace("÷", "/")
        return try {
            val result = evalAdvanced(cleanExpr)
            val format = DecimalFormat("0.######")
            format.format(result)
        } catch (e: Exception) {
            "Error"
        }
    }

    private fun evalAdvanced(str: String): Double {
        return object : Any() {
            var pos = -1
            var ch = 0
            fun nextChar() {
                ch = if (++pos < str.length) str[pos].code else -1
            }

            fun eat(charToEat: Int): Boolean {
                while (ch == ' '.code) nextChar()
                if (ch == charToEat) {
                    nextChar()
                    return true
                }
                return false
            }

            fun parse(): Double {
                nextChar()
                val x = parseExpression()
                if (pos < str.length) throw RuntimeException("Unexpected: " + ch.toChar())
                return x
            }

            fun parseExpression(): Double {
                var x = parseTerm()
                while (true) {
                    if (eat('+'.code)) x += parseTerm()
                    else if (eat('-'.code)) x -= parseTerm()
                    else return x
                }
            }

            fun parseTerm(): Double {
                var x = parseFactor()
                while (true) {
                    if (eat('*'.code)) x *= parseFactor()
                    else if (eat('/'.code)) x /= parseFactor()
                    else return x
                }
            }

            fun parseFactor(): Double {
                if (eat('+'.code)) return parseFactor()
                if (eat('-'.code)) return -parseFactor()
                var x: Double
                val startPos = pos
                if (eat('('.code)) {
                    x = parseExpression()
                    eat(')'.code)
                } else if (eat('√'.code)) {
                    x = sqrt(parseFactor())
                } else if (ch >= '0'.code && ch <= '9'.code || ch == '.'.code) {
                    while (ch >= '0'.code && ch <= '9'.code || ch == '.'.code) nextChar()
                    x = str.substring(startPos, pos).toDouble()
                } else {
                    throw RuntimeException("Unexpected: " + ch.toChar())
                }
                if (eat('^'.code)) x = x.pow(parseFactor())
                while (eat('%'.code)) {
                    x /= 100.0
                }
                return x
            }
        }.parse()
    }
}
