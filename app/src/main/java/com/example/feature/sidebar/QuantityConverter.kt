package com.example.feature.sidebar

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

enum class QuantityCategory(val id: String, val label: String) {
    LENGTH("length", "Length"),
    WEIGHT("weight", "Weight"),
    TEMPERATURE("temperature", "Temp"),
    VOLUME("volume", "Volume"),
    AREA("area", "Area"),
    TIME("time", "Time"),
    DATA("data", "Data"),
    SPEED("speed", "Speed");

    companion object {
        fun fromId(id: String?): QuantityCategory {
            return entries.firstOrNull { it.id == id } ?: LENGTH
        }
    }
}

data class QuantityUnit(
    val id: String,
    val symbol: String,
    val name: String,
    val category: QuantityCategory,
    val factorToBase: Double
)

object QuantityConverter {

    private val SINGLE_NUMBER_REGEX = Regex("""^[+-]?(\d+(\.\d*)?|\.\d+)$""")
    private val US_SYMBOLS = DecimalFormatSymbols(Locale.US)

    val unitsByCategory: Map<QuantityCategory, List<QuantityUnit>> = mapOf(
        QuantityCategory.LENGTH to listOf(
            QuantityUnit("mm", "mm", "Millimeter", QuantityCategory.LENGTH, 0.001),
            QuantityUnit("cm", "cm", "Centimeter", QuantityCategory.LENGTH, 0.01),
            QuantityUnit("m", "m", "Meter", QuantityCategory.LENGTH, 1.0),
            QuantityUnit("km", "km", "Kilometer", QuantityCategory.LENGTH, 1000.0),
            QuantityUnit("in", "in", "Inch", QuantityCategory.LENGTH, 0.0254),
            QuantityUnit("ft", "ft", "Foot", QuantityCategory.LENGTH, 0.3048),
            QuantityUnit("yd", "yd", "Yard", QuantityCategory.LENGTH, 0.9144),
            QuantityUnit("mi", "mi", "Mile", QuantityCategory.LENGTH, 1609.344)
        ),
        QuantityCategory.WEIGHT to listOf(
            QuantityUnit("mg", "mg", "Milligram", QuantityCategory.WEIGHT, 1e-6),
            QuantityUnit("g", "g", "Gram", QuantityCategory.WEIGHT, 0.001),
            QuantityUnit("kg", "kg", "Kilogram", QuantityCategory.WEIGHT, 1.0),
            QuantityUnit("t", "t", "Metric Ton", QuantityCategory.WEIGHT, 1000.0),
            QuantityUnit("oz", "oz", "Ounce", QuantityCategory.WEIGHT, 0.028349523125),
            QuantityUnit("lb", "lb", "Pound", QuantityCategory.WEIGHT, 0.45359237)
        ),
        QuantityCategory.TEMPERATURE to listOf(
            QuantityUnit("c", "°C", "Celsius", QuantityCategory.TEMPERATURE, 1.0),
            QuantityUnit("f", "°F", "Fahrenheit", QuantityCategory.TEMPERATURE, 1.0),
            QuantityUnit("k", "K", "Kelvin", QuantityCategory.TEMPERATURE, 1.0)
        ),
        QuantityCategory.VOLUME to listOf(
            QuantityUnit("ml", "mL", "Milliliter", QuantityCategory.VOLUME, 0.001),
            QuantityUnit("l", "L", "Liter", QuantityCategory.VOLUME, 1.0),
            QuantityUnit("m3", "m³", "Cubic Meter", QuantityCategory.VOLUME, 1000.0),
            QuantityUnit("floz", "fl oz", "US Fluid Oz", QuantityCategory.VOLUME, 0.0295735295625),
            QuantityUnit("cup", "cup", "US Cup", QuantityCategory.VOLUME, 0.2365882365),
            QuantityUnit("gal", "gal", "US Gallon", QuantityCategory.VOLUME, 3.785411784)
        ),
        QuantityCategory.AREA to listOf(
            QuantityUnit("cm2", "cm²", "Sq Centimeter", QuantityCategory.AREA, 0.0001),
            QuantityUnit("m2", "m²", "Sq Meter", QuantityCategory.AREA, 1.0),
            QuantityUnit("km2", "km²", "Sq Kilometer", QuantityCategory.AREA, 1_000_000.0),
            QuantityUnit("ft2", "ft²", "Sq Foot", QuantityCategory.AREA, 0.09290304),
            QuantityUnit("ac", "ac", "Acre", QuantityCategory.AREA, 4046.8564224),
            QuantityUnit("ha", "ha", "Hectare", QuantityCategory.AREA, 10_000.0)
        ),
        QuantityCategory.TIME to listOf(
            QuantityUnit("ms", "ms", "Millisecond", QuantityCategory.TIME, 0.001),
            QuantityUnit("s", "s", "Second", QuantityCategory.TIME, 1.0),
            QuantityUnit("min", "min", "Minute", QuantityCategory.TIME, 60.0),
            QuantityUnit("h", "h", "Hour", QuantityCategory.TIME, 3600.0),
            QuantityUnit("d", "d", "Day", QuantityCategory.TIME, 86400.0),
            QuantityUnit("wk", "wk", "Week", QuantityCategory.TIME, 604800.0)
        ),
        // Binary 1024-based data units (1 KB = 1024 B, 1 MB = 1024 KB, ...)
        QuantityCategory.DATA to listOf(
            QuantityUnit("b", "B", "Byte", QuantityCategory.DATA, 1.0),
            QuantityUnit("kb", "KB", "Kilobyte", QuantityCategory.DATA, 1024.0),
            QuantityUnit("mb", "MB", "Megabyte", QuantityCategory.DATA, 1024.0 * 1024.0),
            QuantityUnit("gb", "GB", "Gigabyte", QuantityCategory.DATA, 1024.0 * 1024.0 * 1024.0),
            QuantityUnit("tb", "TB", "Terabyte", QuantityCategory.DATA, 1024.0 * 1024.0 * 1024.0 * 1024.0)
        ),
        QuantityCategory.SPEED to listOf(
            QuantityUnit("mps", "m/s", "Meters/sec", QuantityCategory.SPEED, 1.0),
            QuantityUnit("kmh", "km/h", "Km/hour", QuantityCategory.SPEED, 1.0 / 3.6),
            QuantityUnit("mph", "mph", "Miles/hour", QuantityCategory.SPEED, 0.44704),
            QuantityUnit("kn", "kn", "Knot", QuantityCategory.SPEED, 1852.0 / 3600.0)
        )
    )

    val defaultPairByCategory: Map<QuantityCategory, Pair<String, String>> = mapOf(
        QuantityCategory.LENGTH to ("m" to "ft"),
        QuantityCategory.WEIGHT to ("kg" to "lb"),
        QuantityCategory.TEMPERATURE to ("c" to "f"),
        QuantityCategory.VOLUME to ("l" to "gal"),
        QuantityCategory.AREA to ("m2" to "ft2"),
        QuantityCategory.TIME to ("h" to "min"),
        QuantityCategory.DATA to ("gb" to "mb"),
        QuantityCategory.SPEED to ("kmh" to "mph")
    )

    fun unitsFor(category: QuantityCategory): List<QuantityUnit> {
        return unitsByCategory[category].orEmpty()
    }

    fun defaultFromUnit(category: QuantityCategory): QuantityUnit {
        val defaultId = defaultPairByCategory[category]?.first
        return findUnit(category, defaultId)
    }

    fun defaultToUnit(category: QuantityCategory): QuantityUnit {
        val defaultId = defaultPairByCategory[category]?.second
        val units = unitsFor(category)
        return units.firstOrNull { it.id == defaultId } ?: units.getOrElse(1) { units.first() }
    }

    fun findUnit(category: QuantityCategory, unitId: String?): QuantityUnit {
        val units = unitsFor(category)
        return units.firstOrNull { it.id == unitId } ?: units.first()
    }

    fun convert(value: Double, from: QuantityUnit, to: QuantityUnit): Double {
        require(from.category == to.category) {
            "Cannot convert between ${from.category} and ${to.category}"
        }
        if (!value.isFinite()) return Double.NaN
        if (from.id == to.id) return value

        if (from.category == QuantityCategory.TEMPERATURE) {
            val celsius = when (from.id) {
                "c" -> value
                "f" -> (value - 32.0) * 5.0 / 9.0
                "k" -> value - 273.15
                else -> value
            }
            return when (to.id) {
                "c" -> celsius
                "f" -> celsius * 9.0 / 5.0 + 32.0
                "k" -> celsius + 273.15
                else -> celsius
            }
        }

        return (value * from.factorToBase) / to.factorToBase
    }

    fun formatConvertedValue(value: Double): String {
        if (!value.isFinite()) return "Error"
        val absVal = abs(value)
        if (absVal < 1e-12) return "0"
        val formatted = if (absVal >= 1e12 || absVal < 1e-6) {
            DecimalFormat("0.######E0", US_SYMBOLS).format(value)
        } else {
            DecimalFormat("0.########", US_SYMBOLS).format(value)
        }
        return if (formatted == "-0") "0" else formatted
    }

    fun isSingleNumberLiteral(expression: String): Boolean {
        val trimmed = expression.trim()
        if (trimmed.isEmpty()) return false
        return SINGLE_NUMBER_REGEX.matches(trimmed)
    }

    /**
     * Resolves a finite numeric value eligible for quantity conversion:
     * - Enabled ONLY when [expression] is a single finite numeric literal, OR
     * - [expressionCompleted] is true (user tapped '=') and the evaluated total is finite.
     */
    fun resolveConvertibleValue(
        expression: String,
        resultText: String,
        expressionCompleted: Boolean,
        evalFn: ((String) -> Double)? = null
    ): Double? {
        if (expression.isBlank()) return null

        if (isSingleNumberLiteral(expression)) {
            val parsed = expression.trim().toDoubleOrNull()
            return if (parsed != null && parsed.isFinite()) parsed else null
        }

        if (!expressionCompleted) return null

        val cleanedResult = resultText.trim()
        if (cleanedResult.isEmpty() ||
            cleanedResult.contains("Error", ignoreCase = true) ||
            cleanedResult.contains("∞") ||
            cleanedResult.contains("NaN", ignoreCase = true) ||
            cleanedResult.contains("Infinity", ignoreCase = true)
        ) {
            return null
        }

        if (evalFn != null) {
            val evaluated = try {
                evalFn(expression)
            } catch (_: Exception) {
                Double.NaN
            }
            return if (evaluated.isFinite()) evaluated else null
        }

        val parsed = cleanedResult.removePrefix("=").replace(",", "").trim().toDoubleOrNull()
        return if (parsed != null && parsed.isFinite()) parsed else null
    }
}
