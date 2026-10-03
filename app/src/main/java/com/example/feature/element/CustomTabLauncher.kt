package com.example.feature.element

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import com.example.core.LogKeeper

/**
 * Information describing an installed browser that supports Android Custom Tabs.
 */
data class CustomTabProviderInfo(
    val packageName: String,
    val label: String,
    val isDefault: Boolean = false
)

/**
 * CustomTabLauncher: Dedicated utility for discovering compatible Custom Tab browsers
 * and opening verified HTTPS URLs in Android Custom Tabs with standard browser controls.
 */
object CustomTabLauncher {

    private const val TAG = "CustomTabLauncher"
    const val ACTION_CUSTOM_TABS_CONNECTION = "android.support.customtabs.action.CustomTabsService"

    /**
     * In-memory test override for Custom Tab launching during unit testing.
     */
    var testCustomTabOpener: ((Context, String, String?) -> Boolean)? = null

    /**
     * Validates that the provided URL uses the secure HTTPS protocol.
     * Enforces that HTTP and other schemes are strictly rejected.
     */
    fun isValidHttpsUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://", ignoreCase = true)) {
            return false
        }
        return try {
            val uri = java.net.URI(trimmed)
            val scheme = uri.scheme
            val host = uri.host
            scheme != null && scheme.equals("https", ignoreCase = true) && !host.isNullOrBlank()
        } catch (_: Throwable) {
            val afterScheme = trimmed.substring(8)
            val hostPart = afterScheme.substringBefore('/').substringBefore(':')
            hostPart.isNotBlank() && (hostPart.contains('.') || hostPart.equals("localhost", ignoreCase = true))
        }
    }

    /**
     * Dynamically discovers all installed Android browsers that declare support for
     * the CustomTabsService connection action ("android.support.customtabs.action.CustomTabsService").
     * Supports installed Chrome, Firefox, Brave, Samsung Internet, Edge, etc.
     */
    fun getCustomTabProviders(context: Context): List<CustomTabProviderInfo> {
        val pm = context.packageManager
        val serviceIntent = Intent(ACTION_CUSTOM_TABS_CONNECTION)
        
        val resolveServices: List<ResolveInfo> = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentServices(serviceIntent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentServices(serviceIntent, 0)
            }
        } catch (e: Exception) {
            LogKeeper.log(context, TAG, "Failed querying CustomTabsService: ${e.message}")
            emptyList()
        }

        // Determine system default browser for https
        val defaultBrowserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
        val defaultResolveInfo: ResolveInfo? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.resolveActivity(defaultBrowserIntent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.resolveActivity(defaultBrowserIntent, PackageManager.MATCH_DEFAULT_ONLY)
            }
        } catch (_: Exception) {
            null
        }
        val defaultPackageName = defaultResolveInfo?.activityInfo?.packageName

        val providers = mutableListOf<CustomTabProviderInfo>()
        val seenPackages = mutableSetOf<String>()

        for (info in resolveServices) {
            val pkg = info.serviceInfo?.packageName ?: continue
            if (!seenPackages.contains(pkg)) {
                seenPackages.add(pkg)
                val label = try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationLabel(appInfo).toString()
                } catch (_: Exception) {
                    pkg
                }
                providers.add(
                    CustomTabProviderInfo(
                        packageName = pkg,
                        label = label,
                        isDefault = (pkg == defaultPackageName)
                    )
                )
            }
        }

        // Fallback check for well-known browsers if queryIntentServices returned empty (e.g. unit tests or minimal environments)
        if (providers.isEmpty()) {
            val knownBrowsers = listOf(
                "com.android.chrome" to "Google Chrome",
                "org.mozilla.firefox" to "Mozilla Firefox"
            )
            for ((pkg, label) in knownBrowsers) {
                try {
                    pm.getPackageInfo(pkg, 0)
                    providers.add(CustomTabProviderInfo(pkg, label, pkg == defaultPackageName))
                } catch (_: Exception) {}
            }
        }

        // Sort default browser first, then alphabetically
        return providers.sortedWith(
            compareByDescending<CustomTabProviderInfo> { it.isDefault }
                .thenBy { it.label.lowercase() }
        )
    }

    /**
     * Resolves the best available Custom Tab provider package.
     * Uses preferredPackage if installed and compatible; otherwise selects default or first provider.
     */
    fun resolveProviderPackage(context: Context, preferredPackage: String?): String? {
        val availableProviders = getCustomTabProviders(context)
        if (availableProviders.isEmpty()) return null

        if (!preferredPackage.isNullOrBlank()) {
            val match = availableProviders.firstOrNull { it.packageName == preferredPackage }
            if (match != null) {
                return match.packageName
            }
            LogKeeper.log(context, TAG, "Preferred browser '$preferredPackage' not available. Falling back to default provider.")
        }

        // Default browser first if compatible, otherwise first available
        return availableProviders.firstOrNull { it.isDefault }?.packageName
            ?: availableProviders.firstOrNull()?.packageName
    }

    /**
     * Opens an HTTPS URL in an Android Custom Tab through the selected browser provider.
     *
     * Constraints enforced:
     * - HTTPS URLs only.
     * - Browser selected per link.
     * - Never opens in Sidebar's own WebView/browser.
     * - Never forces a custom user agent.
     * - Preserves standard browser top-bar controls (share state on, 3-dot menu).
     */
    fun openLink(context: Context, url: String?, preferredPackage: String? = null): Boolean {
        testCustomTabOpener?.let { opener ->
            return opener(context, url ?: "", preferredPackage)
        }

        if (!isValidHttpsUrl(url)) {
            val errorMsg = if (url.isNullOrBlank()) {
                "Link URL cannot be empty"
            } else {
                "Invalid URL: HTTPS is required (e.g. https://...)"
            }
            LogKeeper.log(context, TAG, "Rejected opening non-HTTPS link: $url")
            try {
                Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {}
            return false
        }

        val targetUrl = url!!.trim()
        val targetPackage = resolveProviderPackage(context, preferredPackage)

        if (targetPackage == null) {
            val errorMsg = "No compatible Custom Tab browser found"
            LogKeeper.log(context, TAG, "$errorMsg. Cannot open $targetUrl")
            try {
                Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
            } catch (_: Throwable) {}
            return false
        }

        return try {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(false)
                .setShareState(CustomTabsIntent.SHARE_STATE_ON)
                .build()

            customTabsIntent.intent.setPackage(targetPackage)
            customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            LogKeeper.log(context, TAG, "Launching Custom Tab for '$targetUrl' via browser '$targetPackage'")
            customTabsIntent.launchUrl(context, Uri.parse(targetUrl))
            true
        } catch (e: Exception) {
            LogKeeper.logError(context, TAG, "Error launching Custom Tab for '$targetUrl' via '$targetPackage'", e)
            try {
                Toast.makeText(context, "Failed to launch Custom Tab: ${e.message}", Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {}
            false
        }
    }
}
