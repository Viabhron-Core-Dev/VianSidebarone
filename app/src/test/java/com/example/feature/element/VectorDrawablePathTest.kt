package com.example.feature.element

import androidx.compose.ui.graphics.vector.PathParser
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VectorDrawablePathTest {

    @Test
    fun testIcLanguageVectorPathIsValid() {
        val drawableFile = File("src/main/res/drawable/ic_language.xml")
        assertTrue("ic_language.xml must exist", drawableFile.exists())
        val content = drawableFile.readText()

        val regex = Regex("""android:pathData="([^"]+)"""")
        val match = regex.find(content)
        assertNotNull("pathData attribute must be found in ic_language.xml", match)
        val pathData = match!!.groupValues[1]

        // Parse using Compose vector PathParser (the exact class that failed with "Unknown command for: W")
        val parser = PathParser()
        parser.parsePathString(pathData)
        val nodes = parser.toNodes()
        assertTrue("Parsed vector path nodes should not be empty", nodes.isNotEmpty())
    }
}
