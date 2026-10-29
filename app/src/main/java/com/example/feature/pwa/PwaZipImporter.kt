package com.example.feature.pwa

import android.content.Context
import android.net.Uri
import com.example.data.AppDatabase
import com.example.data.PwaEntity
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * PwaZipImporter: Handles persistent import of Offline PWA dist.zip packages.
 *
 * Requirements enforced:
 * 1. The original dist.zip is permanently retained on disk (not discarded).
 * 2. Assets are safely unpacked into an isolated directory.
 * 3. Manifest metadata (name, icons, start_url, colors) is extracted automatically.
 * 4. PwaEntity record is stored in Room database.
 */
object PwaZipImporter {

    fun importZipUri(context: Context, zipUri: Uri, originalFileName: String?): PwaEntity {
        val resolver = context.contentResolver
        val inputStream = resolver.openInputStream(zipUri)
            ?: throw IllegalStateException("Unable to open input stream for URI: $zipUri")
        val fileName = originalFileName ?: "pwa_bundle.zip"
        return inputStream.use { stream ->
            importZipStream(context, stream, fileName)
        }
    }

    fun unpackAndBuildEntity(
        context: Context,
        inputStream: InputStream,
        originalFileName: String,
        targetPwaId: String? = null
    ): PwaEntity {
        val pwaId = targetPwaId ?: ("pwa_" + UUID.randomUUID().toString().replace("-", "").take(16))
        val pwaDir = File(context.filesDir, "pwa/$pwaId")
        if (!pwaDir.exists()) pwaDir.mkdirs()

        // 1. Permanently retain original source dist.zip
        val retainedZipFile = File(pwaDir, "package.zip")
        val packageSize = copyStreamToFile(inputStream, retainedZipFile)

        // 2. Unpack to assets directory
        val rawUnpackDir = File(pwaDir, "unpacked")
        if (!rawUnpackDir.exists()) rawUnpackDir.mkdirs()
        unzip(retainedZipFile, rawUnpackDir)

        // 3. Locate web root (directory containing index.html or manifest.json)
        val effectiveWebRoot = locateWebRoot(rawUnpackDir)

        // 4. Locate and parse manifest
        val manifestFile = locateManifest(effectiveWebRoot)
        val baseFallbackName = originalFileName
            .substringBeforeLast(".zip")
            .substringBeforeLast(".dist")
            .replace("_", " ")
            .replace("-", " ")
            .trim()
            .ifBlank { "Offline PWA" }

        val manifestData = if (manifestFile != null) {
            PwaManifestParser.parse(manifestFile, baseFallbackName)
        } else {
            PwaManifestData(name = baseFallbackName)
        }

        // 5. Locate and extract application icon
        val iconFile = resolveAndCopyIcon(effectiveWebRoot, pwaDir, manifestData.iconRelativePath)

        // 6. Verify startUrl exists or fallback to index.html
        val resolvedStartUrl = verifyStartUrl(effectiveWebRoot, manifestData.startUrl)

        // 7. Create PwaEntity
        return PwaEntity(
            id = pwaId,
            name = manifestData.name,
            shortName = manifestData.shortName,
            description = manifestData.description,
            startUrl = resolvedStartUrl,
            scope = manifestData.scope,
            displayMode = manifestData.displayMode,
            orientation = manifestData.orientation,
            themeColor = manifestData.themeColor,
            backgroundColor = manifestData.backgroundColor,
            iconPath = iconFile?.absolutePath,
            zipPath = retainedZipFile.absolutePath,
            unpackedPath = effectiveWebRoot.absolutePath,
            packageSize = packageSize,
            version = "1.0.0",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            lastLaunchedAt = 0L
        )
    }

    fun importZipStream(context: Context, inputStream: InputStream, originalFileName: String): PwaEntity {
        val entity = unpackAndBuildEntity(context, inputStream, originalFileName)
        try {
            AppDatabase.getDatabase(context).pwaDao().insert(entity)
        } catch (_: Throwable) {}
        return entity
    }

    private fun copyStreamToFile(input: InputStream, output: File): Long {
        var totalBytes = 0L
        FileOutputStream(output).use { out ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                out.write(buffer, 0, read)
                totalBytes += read
            }
            out.flush()
        }
        return totalBytes
    }

    private fun unzip(zipFile: File, destinationDir: File) {
        val destCanonicalPath = destinationDir.canonicalPath
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry: ZipEntry?
            while (zis.nextEntry.also { entry = it } != null) {
                val currentEntry = entry ?: continue
                val outputFile = File(destinationDir, currentEntry.name)
                // Zip Slip vulnerability protection
                if (!outputFile.canonicalPath.startsWith(destCanonicalPath)) {
                    throw SecurityException("Zip entry is outside of destination directory: ${currentEntry.name}")
                }
                if (currentEntry.isDirectory) {
                    outputFile.mkdirs()
                } else {
                    outputFile.parentFile?.mkdirs()
                    FileOutputStream(outputFile).use { fos ->
                        val buffer = ByteArray(8192)
                        var count: Int
                        while (zis.read(buffer).also { count = it } != -1) {
                            fos.write(buffer, 0, count)
                        }
                    }
                }
                zis.closeEntry()
            }
        }
    }

    private fun locateWebRoot(unpackedDir: File): File {
        // If index.html is directly in unpackedDir, it's the root
        if (File(unpackedDir, "index.html").exists()) return unpackedDir

        // Check if there is a single subdirectory containing index.html (e.g. dist/index.html)
        val subDirs = unpackedDir.listFiles { f -> f.isDirectory } ?: return unpackedDir
        for (sub in subDirs) {
            if (File(sub, "index.html").exists()) {
                return sub
            }
        }

        // Search recursively for the first index.html
        val found = unpackedDir.walkTopDown().firstOrNull { it.isFile && it.name.equals("index.html", ignoreCase = true) }
        return found?.parentFile ?: unpackedDir
    }

    private fun locateManifest(webRoot: File): File? {
        val directManifest = File(webRoot, "manifest.json")
        if (directManifest.exists()) return directManifest

        val webManifest = File(webRoot, "manifest.webmanifest")
        if (webManifest.exists()) return webManifest

        // Search for any manifest file inside webRoot
        return webRoot.walkTopDown().maxDepth(3).firstOrNull {
            it.isFile && (it.name.equals("manifest.json", ignoreCase = true) || it.name.equals("manifest.webmanifest", ignoreCase = true))
        }
    }

    private fun resolveAndCopyIcon(webRoot: File, pwaDir: File, manifestIconRelPath: String?): File? {
        val targetIconFile = File(pwaDir, "app_icon.png")

        if (!manifestIconRelPath.isNullOrBlank()) {
            val candidate = File(webRoot, manifestIconRelPath)
            if (candidate.exists() && candidate.isFile) {
                try {
                    candidate.copyTo(targetIconFile, overwrite = true)
                    return targetIconFile
                } catch (_: Throwable) {}
            }
        }

        // Fallback checks for common icon names
        val commonNames = listOf(
            "icon.png", "icon-512.png", "icon-192.png", "logo.png",
            "apple-touch-icon.png", "favicon.png", "favicon.ico"
        )
        for (name in commonNames) {
            val candidate = File(webRoot, name)
            if (candidate.exists() && candidate.isFile) {
                try {
                    candidate.copyTo(targetIconFile, overwrite = true)
                    return targetIconFile
                } catch (_: Throwable) {}
            }
        }

        // Search for any PNG image in webRoot as fallback
        val anyPng = webRoot.walkTopDown().maxDepth(3).firstOrNull {
            it.isFile && (it.name.endsWith(".png", ignoreCase = true) || it.name.endsWith(".webp", ignoreCase = true))
        }
        if (anyPng != null) {
            try {
                anyPng.copyTo(targetIconFile, overwrite = true)
                return targetIconFile
            } catch (_: Throwable) {}
        }

        return null
    }

    private fun verifyStartUrl(webRoot: File, candidateUrl: String): String {
        val clean = candidateUrl.removePrefix("/").removePrefix("./")
        if (File(webRoot, clean).exists()) {
            return clean
        }
        if (File(webRoot, "index.html").exists()) {
            return "index.html"
        }
        return clean.ifBlank { "index.html" }
    }
}
