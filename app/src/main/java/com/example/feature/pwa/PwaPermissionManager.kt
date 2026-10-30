package com.example.feature.pwa

import android.content.Context
import android.net.Uri
import com.example.core.LogKeeper
import com.example.core.OverlaySyncManager
import org.json.JSONArray

/**
 * PwaPermissionPolicy: Represents the enforced capability policy for an Offline PWA Mini App.
 * All permissions default strictly to false (OFF) for zero-trust security.
 */
data class PwaPermissionPolicy(
    val internetAllowed: Boolean = false,
    val filesAllowed: Boolean = false,
    val cameraAllowed: Boolean = false,
    val microphoneAllowed: Boolean = false,
    val locationAllowed: Boolean = false,
    val soundAllowed: Boolean = false
)

/**
 * PwaPermissionManager: Manages persistence, multi-process synchronization,
 * and SAF file grants for per-PWA Mini App capability policies.
 */
object PwaPermissionManager {

    private const val TAG = "PWA"
    private const val PREFS_NAME = "FloatingReaderPrefs"

    fun getPolicy(context: Context, pwaId: String): PwaPermissionPolicy {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return PwaPermissionPolicy(
            internetAllowed = prefs.getBoolean("pwa_perm_${pwaId}_internet", false),
            filesAllowed = prefs.getBoolean("pwa_perm_${pwaId}_files", false),
            cameraAllowed = prefs.getBoolean("pwa_perm_${pwaId}_camera", false),
            microphoneAllowed = prefs.getBoolean("pwa_perm_${pwaId}_microphone", false),
            locationAllowed = prefs.getBoolean("pwa_perm_${pwaId}_location", false),
            soundAllowed = prefs.getBoolean("pwa_perm_${pwaId}_sound", false)
        )
    }

    fun savePolicy(context: Context, pwaId: String, policy: PwaPermissionPolicy) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("pwa_perm_${pwaId}_internet", policy.internetAllowed)
            .putBoolean("pwa_perm_${pwaId}_files", policy.filesAllowed)
            .putBoolean("pwa_perm_${pwaId}_camera", policy.cameraAllowed)
            .putBoolean("pwa_perm_${pwaId}_microphone", policy.microphoneAllowed)
            .putBoolean("pwa_perm_${pwaId}_location", policy.locationAllowed)
            .putBoolean("pwa_perm_${pwaId}_sound", policy.soundAllowed)
            .commit()

        // Synchronize across Main and :heavy processes via OverlaySyncManager
        OverlaySyncManager.syncBoolean(context, "pwa_perm_${pwaId}_internet", policy.internetAllowed)
        OverlaySyncManager.syncBoolean(context, "pwa_perm_${pwaId}_files", policy.filesAllowed)
        OverlaySyncManager.syncBoolean(context, "pwa_perm_${pwaId}_camera", policy.cameraAllowed)
        OverlaySyncManager.syncBoolean(context, "pwa_perm_${pwaId}_microphone", policy.microphoneAllowed)
        OverlaySyncManager.syncBoolean(context, "pwa_perm_${pwaId}_location", policy.locationAllowed)
        OverlaySyncManager.syncBoolean(context, "pwa_perm_${pwaId}_sound", policy.soundAllowed)

        LogKeeper.log(
            context,
            TAG,
            "PWA_PERMISSION_CHANGED id=$pwaId internet=${policy.internetAllowed} files=${policy.filesAllowed} camera=${policy.cameraAllowed} mic=${policy.microphoneAllowed} location=${policy.locationAllowed} sound=${policy.soundAllowed}"
        )
    }

    /**
     * Retrieves all persisted SAF URIs granted to this specific pwaId.
     */
    fun getGrantedUris(context: Context, pwaId: String): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString("pwa_saf_grants_$pwaId", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Associates a newly user-selected SAF URI with this pwaId.
     */
    fun addGrantedUri(context: Context, pwaId: String, uri: Uri) {
        addGrantedUri(context, pwaId, uri.toString())
    }

    /**
     * Associates a newly user-selected SAF URI string with this pwaId.
     */
    fun addGrantedUri(context: Context, pwaId: String, uriStr: String) {
        val current = getGrantedUris(context, pwaId).toMutableList()
        if (!current.contains(uriStr)) {
            current.add(uriStr)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString("pwa_saf_grants_$pwaId", JSONArray(current).toString()).commit()
            OverlaySyncManager.syncString(context, "pwa_saf_grants_$pwaId", JSONArray(current).toString())
            LogKeeper.log(context, TAG, "PWA_SAF_GRANT_ACQUIRED id=$pwaId uri=$uriStr")
        }
    }

    /**
     * Revokes a granted SAF URI from this pwaId and releases persisted Android permissions.
     */
    fun revokeGrantedUri(context: Context, pwaId: String, uriStr: String) {
        val current = getGrantedUris(context, pwaId).toMutableList()
        if (current.remove(uriStr)) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString("pwa_saf_grants_$pwaId", JSONArray(current).toString()).commit()
            OverlaySyncManager.syncString(context, "pwa_saf_grants_$pwaId", JSONArray(current).toString())
            try {
                val resolver = context.contentResolver
                if (resolver != null) {
                    val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    val parsed = Uri.parse(uriStr)
                    if (parsed != null) {
                        resolver.releasePersistableUriPermission(parsed, flags)
                    }
                }
            } catch (_: Throwable) {}
            LogKeeper.log(context, TAG, "PWA_SAF_GRANT_REVOKED id=$pwaId uri=$uriStr")
        }
    }
}
