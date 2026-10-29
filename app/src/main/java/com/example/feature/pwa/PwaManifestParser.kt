package com.example.feature.pwa

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Extracted manifest data from web app manifest (manifest.json / manifest.webmanifest).
 */
data class PwaManifestData(
    val name: String,
    val shortName: String = "",
    val description: String = "",
    val startUrl: String = "index.html",
    val scope: String = "/",
    val displayMode: String = "standalone",
    val orientation: String = "any",
    val themeColor: String = "#1E1E2E",
    val backgroundColor: String = "#121212",
    val iconRelativePath: String? = null
)

/**
 * PwaManifestParser: Parses web app manifest JSON files.
 *
 * Extracts application metadata, start URLs, display modes, colors,
 * and identifies the optimal application icon for offline runtime hosting.
 */
object PwaManifestParser {

    fun parse(manifestFile: File, fallbackName: String = "Offline PWA"): PwaManifestData {
        if (!manifestFile.exists()) {
            return PwaManifestData(name = fallbackName)
        }
        return try {
            val content = manifestFile.readText(Charsets.UTF_8)
            parseJson(content, fallbackName)
        } catch (e: Throwable) {
            com.example.core.LogKeeper.writeLog("PWA", "Manifest file read/parse failed for '${manifestFile.name}', using fallback '$fallbackName': ${e.message}")
            PwaManifestData(name = fallbackName)
        }
    }

    fun parseJson(jsonString: String, fallbackName: String = "Offline PWA"): PwaManifestData {
        return try {
            val json = JSONObject(jsonString)
            val name = json.optString("name").takeIf { it.isNotBlank() }
                ?: json.optString("short_name").takeIf { it.isNotBlank() }
                ?: fallbackName

            val shortName = json.optString("short_name", "")
            val description = json.optString("description", "")
            var startUrl = json.optString("start_url", "index.html").trim()
            if (startUrl.startsWith("/")) {
                startUrl = startUrl.removePrefix("/")
            }
            if (startUrl.startsWith("./")) {
                startUrl = startUrl.removePrefix("./")
            }
            if (startUrl.isBlank()) {
                startUrl = "index.html"
            }

            val scope = json.optString("scope", "/")
            val displayMode = json.optString("display", "standalone")
            val orientation = json.optString("orientation", "any")
            val themeColor = json.optString("theme_color", "#1E1E2E")
            val backgroundColor = json.optString("background_color", "#121212")

            val iconRelPath = extractBestIcon(json.optJSONArray("icons"))

            PwaManifestData(
                name = name,
                shortName = shortName,
                description = description,
                startUrl = startUrl,
                scope = scope,
                displayMode = displayMode,
                orientation = orientation,
                themeColor = themeColor,
                backgroundColor = backgroundColor,
                iconRelativePath = iconRelPath
            )
        } catch (e: Throwable) {
            com.example.core.LogKeeper.writeLog("PWA", "Manifest JSON parsing failed, using fallback '$fallbackName': ${e.message}")
            PwaManifestData(name = fallbackName)
        }
    }

    private fun extractBestIcon(iconsArray: JSONArray?): String? {
        if (iconsArray == null || iconsArray.length() == 0) return null

        var bestPath: String? = null
        var bestSize = -1

        for (i in 0 until iconsArray.length()) {
            val iconObj = iconsArray.optJSONObject(i) ?: continue
            val src = iconObj.optString("src").trim()
            if (src.isBlank()) continue

            val cleanSrc = src.removePrefix("/").removePrefix("./")
            val sizes = iconObj.optString("sizes", "0x0")
            val size = parseIconSize(sizes)

            if (size > bestSize || bestPath == null) {
                bestSize = size
                bestPath = cleanSrc
            }
        }
        return bestPath
    }

    private fun parseIconSize(sizesStr: String): Int {
        return try {
            val first = sizesStr.split(" ").firstOrNull() ?: return 0
            val parts = first.split("x", "X")
            if (parts.size == 2) {
                parts[0].toIntOrNull() ?: 0
            } else 0
        } catch (_: Throwable) {
            0
        }
    }
}
