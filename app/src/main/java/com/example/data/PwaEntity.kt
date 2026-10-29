package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * PwaEntity: Persistent database record for an imported Offline PWA.
 *
 * Retains package and runtime references:
 * - zipPath: Source dist.zip permanently retained on disk
 * - unpackedPath: Directory containing extracted offline assets
 * - manifest metadata: name, startUrl, scope, displayMode, theme/background colors
 */
@Entity(tableName = "pwa_apps")
data class PwaEntity(
    @PrimaryKey
    val id: String,                         // Unique ID (e.g., "pwa_uuid")
    val name: String,                       // App display name from manifest or zip filename
    val shortName: String = "",             // Short name from manifest
    val description: String = "",           // App description from manifest
    val startUrl: String = "index.html",    // Relative start URL / entrypoint from manifest
    val scope: String = "/",                // Scope from manifest
    val displayMode: String = "standalone", // standalone, fullscreen, minimal-ui, browser
    val orientation: String = "any",        // portrait, landscape, any
    val themeColor: String = "#1E1E2E",     // Theme color hex
    val backgroundColor: String = "#121212",// Background color hex
    val iconPath: String? = null,           // Path to extracted icon file
    val zipPath: String,                    // Permanent path to source dist.zip package
    val unpackedPath: String,               // Path to unpacked offline assets directory
    val packageSize: Long = 0L,             // Package file size in bytes
    val version: String = "1.0.0",          // Package / manifest version
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastLaunchedAt: Long = 0L
)
