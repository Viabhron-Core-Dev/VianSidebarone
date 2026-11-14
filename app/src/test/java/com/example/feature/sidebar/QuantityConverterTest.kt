package com.example.feature.sidebar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantityConverterTest {

    @Test
    fun testSingleNumberOrTotalEligibilityGating() {
        // Default empty calculator ("0" placeholder) -> Disabled
        assertNull(QuantityConverter.resolveConvertibleValue("", "", false))

        // Single finite number literals (without '=') -> Enabled
        assertEquals(42.0, QuantityConverter.resolveConvertibleValue("42", "", false)!!, 1e-9)
        assertEquals(0.0, QuantityConverter.resolveConvertibleValue("0", "", false)!!, 1e-9)
        assertEquals(-12.5, QuantityConverter.resolveConvertibleValue("-12.5", "", false)!!, 1e-9)
        assertEquals(0.75, QuantityConverter.resolveConvertibleValue(".75", "", false)!!, 1e-9)
        assertEquals(5.0, QuantityConverter.resolveConvertibleValue("5.", "", false)!!, 1e-9)

        // Incomplete punctuation-only tokens -> Disabled
        assertNull(QuantityConverter.resolveConvertibleValue("-", "", false))
        assertNull(QuantityConverter.resolveConvertibleValue(".", "", false))
        assertNull(QuantityConverter.resolveConvertibleValue("+.", "", false))

        // Unfinalized expressions / operators -> Disabled
        assertNull(QuantityConverter.resolveConvertibleValue("12+8", "=20", false))
        assertNull(QuantityConverter.resolveConvertibleValue("12+", "", false))
        assertNull(QuantityConverter.resolveConvertibleValue("√9", "=3", false))
        assertNull(QuantityConverter.resolveConvertibleValue("50%", "=0.5", false))
        assertNull(QuantityConverter.resolveConvertibleValue("(10+2)", "=12", false))

        // Finalized expression with '=' (expressionCompleted = true) -> Enabled
        assertEquals(
            20.0,
            QuantityConverter.resolveConvertibleValue("12+8", "=20", true) { 20.0 }!!,
            1e-9
        )
        assertEquals(
            3.0,
            QuantityConverter.resolveConvertibleValue("√9", "=3", true) { 3.0 }!!,
            1e-9
        )

        // Infinity / NaN / Error results after '=' -> Strictly Disabled
        assertNull(QuantityConverter.resolveConvertibleValue("1÷0", "=∞", true) { Double.POSITIVE_INFINITY })
        assertNull(QuantityConverter.resolveConvertibleValue("√(-1)", "=NaN", true) { Double.NaN })
        assertNull(QuantityConverter.resolveConvertibleValue("5++", "Error", true) { 0.0 })
        assertNull(QuantityConverter.resolveConvertibleValue("1÷0", "Infinity", true))
    }

    @Test
    fun testDataBinary1024Conversions() {
        val b = QuantityConverter.findUnit(QuantityCategory.DATA, "b")
        val kb = QuantityConverter.findUnit(QuantityCategory.DATA, "kb")
        val mb = QuantityConverter.findUnit(QuantityCategory.DATA, "mb")
        val gb = QuantityConverter.findUnit(QuantityCategory.DATA, "gb")
        val tb = QuantityConverter.findUnit(QuantityCategory.DATA, "tb")

        assertEquals(1024.0, QuantityConverter.convert(1.0, kb, b), 1e-9)
        assertEquals(1024.0, QuantityConverter.convert(1.0, mb, kb), 1e-9)
        assertEquals(1024.0, QuantityConverter.convert(1.0, gb, mb), 1e-9)
        assertEquals(1024.0, QuantityConverter.convert(1.0, tb, gb), 1e-9)
        assertEquals(1024.0 * 1024.0, QuantityConverter.convert(1.0, gb, kb), 1e-6)
        assertEquals(0.5, QuantityConverter.convert(512.0, mb, gb), 1e-9)
    }

    @Test
    fun testTemperatureConversionsAndRoundTrips() {
        val c = QuantityConverter.findUnit(QuantityCategory.TEMPERATURE, "c")
        val f = QuantityConverter.findUnit(QuantityCategory.TEMPERATURE, "f")
        val k = QuantityConverter.findUnit(QuantityCategory.TEMPERATURE, "k")

        assertEquals(32.0, QuantityConverter.convert(0.0, c, f), 1e-9)
        assertEquals(212.0, QuantityConverter.convert(100.0, c, f), 1e-9)
        assertEquals(-40.0, QuantityConverter.convert(-40.0, c, f), 1e-9)
        assertEquals(-40.0, QuantityConverter.convert(-40.0, f, c), 1e-9)
        assertEquals(0.0, QuantityConverter.convert(32.0, f, c), 1e-9)
        assertEquals(273.15, QuantityConverter.convert(0.0, c, k), 1e-9)
        assertEquals(0.0, QuantityConverter.convert(273.15, k, c), 1e-9)
        assertEquals(373.15, QuantityConverter.convert(212.0, f, k), 1e-9)

        // Same-unit identity
        assertEquals(98.6, QuantityConverter.convert(98.6, f, f), 1e-9)
    }

    @Test
    fun testAllCategoriesConversionRoundTripsAndSwapSymmetry() {
        for (cat in QuantityCategory.entries) {
            val units = QuantityConverter.unitsFor(cat)
            assertTrue("Category ${cat.label} must have >= 3 units", units.size >= 3)
            val fromDef = QuantityConverter.defaultFromUnit(cat)
            val toDef = QuantityConverter.defaultToUnit(cat)
            assertNotNull(fromDef)
            assertNotNull(toDef)
            assertFalse("Default pair must have distinct units for $cat", fromDef.id == toDef.id)

            val forward = QuantityConverter.convert(25.0, fromDef, toDef)
            val backward = QuantityConverter.convert(forward, toDef, fromDef)
            assertEquals("Round-trip failed for $cat", 25.0, backward, 1e-6)
        }
    }

    @Test
    fun testLengthWeightVolumeAreaTimeSpeedSpotChecks() {
        // Length
        val km = QuantityConverter.findUnit(QuantityCategory.LENGTH, "km")
        val m = QuantityConverter.findUnit(QuantityCategory.LENGTH, "m")
        val inch = QuantityConverter.findUnit(QuantityCategory.LENGTH, "in")
        val cm = QuantityConverter.findUnit(QuantityCategory.LENGTH, "cm")
        assertEquals(1000.0, QuantityConverter.convert(1.0, km, m), 1e-9)
        assertEquals(2.54, QuantityConverter.convert(1.0, inch, cm), 1e-9)

        // Weight
        val kg = QuantityConverter.findUnit(QuantityCategory.WEIGHT, "kg")
        val g = QuantityConverter.findUnit(QuantityCategory.WEIGHT, "g")
        val lb = QuantityConverter.findUnit(QuantityCategory.WEIGHT, "lb")
        assertEquals(1000.0, QuantityConverter.convert(1.0, kg, g), 1e-9)
        assertEquals(2.20462262185, QuantityConverter.convert(1.0, kg, lb), 1e-6)

        // Volume
        val gal = QuantityConverter.findUnit(QuantityCategory.VOLUME, "gal")
        val l = QuantityConverter.findUnit(QuantityCategory.VOLUME, "l")
        assertEquals(3.785411784, QuantityConverter.convert(1.0, gal, l), 1e-9)

        // Area
        val ha = QuantityConverter.findUnit(QuantityCategory.AREA, "ha")
        val m2 = QuantityConverter.findUnit(QuantityCategory.AREA, "m2")
        assertEquals(10_000.0, QuantityConverter.convert(1.0, ha, m2), 1e-6)

        // Time
        val d = QuantityConverter.findUnit(QuantityCategory.TIME, "d")
        val h = QuantityConverter.findUnit(QuantityCategory.TIME, "h")
        assertEquals(24.0, QuantityConverter.convert(1.0, d, h), 1e-9)

        // Speed
        val mps = QuantityConverter.findUnit(QuantityCategory.SPEED, "mps")
        val kmh = QuantityConverter.findUnit(QuantityCategory.SPEED, "kmh")
        assertEquals(36.0, QuantityConverter.convert(10.0, mps, kmh), 1e-9)
    }

    @Test
    fun testCleanNumericFormattingForClipboardCopy() {
        assertEquals("0", QuantityConverter.formatConvertedValue(0.0))
        assertEquals("0", QuantityConverter.formatConvertedValue(-0.0))
        assertEquals("0", QuantityConverter.formatConvertedValue(1e-15))
        assertEquals("1024", QuantityConverter.formatConvertedValue(1024.0))
        assertEquals("98.6", QuantityConverter.formatConvertedValue(98.6))
        assertEquals("Error", QuantityConverter.formatConvertedValue(Double.NaN))
        assertEquals("Error", QuantityConverter.formatConvertedValue(Double.POSITIVE_INFINITY))

        val sciLarge = QuantityConverter.formatConvertedValue(1.5e14)
        assertTrue("Expected scientific notation for extreme magnitude, got $sciLarge", sciLarge.contains("E"))
        val sciSmall = QuantityConverter.formatConvertedValue(2.5e-8)
        assertTrue("Expected scientific notation for tiny magnitude, got $sciSmall", sciSmall.contains("E"))
    }
}
