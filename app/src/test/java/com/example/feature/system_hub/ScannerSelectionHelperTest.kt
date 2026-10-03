package com.example.feature.system_hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerSelectionHelperTest {

    @Test
    fun testCalculateBoundsForRectangle() {
        val bounds = ScannerSelectionHelper.calculateBounds(
            startX = 100f,
            startY = 200f,
            endX = 500f,
            endY = 600f,
            canvasWidth = 1000f,
            canvasHeight = 1000f,
            shape = SelectionShape.RECTANGLE
        )

        assertEquals(0.1f, bounds.left, 0.001f)
        assertEquals(0.2f, bounds.top, 0.001f)
        assertEquals(0.5f, bounds.right, 0.001f)
        assertEquals(0.6f, bounds.bottom, 0.001f)
    }

    @Test
    fun testCalculateBoundsForSquareMaintainsAspectRatio() {
        val bounds = ScannerSelectionHelper.calculateBounds(
            startX = 100f,
            startY = 100f,
            endX = 300f,
            endY = 500f,
            canvasWidth = 1000f,
            canvasHeight = 1000f,
            shape = SelectionShape.SQUARE
        )

        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top
        assertEquals(width, height, 0.001f)
    }

    @Test
    fun testCalculateBoundsForCircleMaintainsAspectRatio() {
        val bounds = ScannerSelectionHelper.calculateBounds(
            startX = 200f,
            startY = 200f,
            endX = 400f,
            endY = 600f,
            canvasWidth = 1000f,
            canvasHeight = 1000f,
            shape = SelectionShape.CIRCLE
        )

        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top
        assertEquals(width, height, 0.001f)
    }

    @Test
    fun testNormalizedPointModel() {
        val point = NormalizedPoint(0.25f, 0.75f)
        assertEquals(0.25f, point.x, 0.001f)
        assertEquals(0.75f, point.y, 0.001f)
    }

    @Test
    fun testRedactBoxAndStrokeDataModels() {
        val box = RedactBox(0.1f, 0.2f, 0.5f, 0.6f, isBlur = false)
        assertEquals(0.1f, box.normalizedLeft, 0.001f)
        assertEquals(0.2f, box.normalizedTop, 0.001f)
        assertEquals(0.5f, box.normalizedRight, 0.001f)
        assertEquals(0.6f, box.normalizedBottom, 0.001f)
        assertFalse(box.isBlur)

        val stroke = RedactStroke(
            points = listOf(RedactPoint(0.1f, 0.1f), RedactPoint(0.2f, 0.2f)),
            isBlur = true,
            strokeWidthDp = 32f
        )
        assertEquals(2, stroke.points.size)
        assertTrue(stroke.isBlur)
        assertEquals(32f, stroke.strokeWidthDp, 0.001f)
    }

    private fun assertFalse(condition: Boolean) {
        org.junit.Assert.assertFalse(condition)
    }
}
