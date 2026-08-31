package com.example

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.AppWidgetHelper
import org.json.JSONObject

class WidgetPickerActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var appWidgetManager: AppWidgetManager
    private var selectedProvider: AppWidgetProviderInfo? = null

    override fun onDestroy() {
        super.onDestroy()
        val actionType = intent.getStringExtra("ACTION_TYPE") ?: ""
        val pageId = intent.getStringExtra("PAGE_ID") ?: ""
        val broadcastIntent = Intent("WIDGET_PICKER_CLOSED")
        broadcastIntent.putExtra("ACTION_TYPE", actionType)
        broadcastIntent.putExtra("PAGE_ID", pageId)
        broadcastIntent.setPackage(packageName); sendBroadcast(broadcastIntent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sendBroadcast(Intent("WIDGET_PICKER_OPENED"))
        
        appWidgetManager = AppWidgetManager.getInstance(this)
        val allProviders = mutableListOf<AppWidgetProviderInfo>()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val userManager = getSystemService(android.content.Context.USER_SERVICE) as android.os.UserManager
            for (profile in userManager.userProfiles) {
                allProviders.addAll(appWidgetManager.getInstalledProvidersForProfile(profile))
            }
        } else {
            allProviders.addAll(appWidgetManager.installedProviders)
        }
        
        val host = AppWidgetHelper.getHost(this)
        appWidgetId = host.allocateAppWidgetId()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                WidgetPickerScreen(
                    providers = allProviders,
                    actionType = intent.getStringExtra("ACTION_TYPE") ?: "",
                    onWidgetSelected = { provider ->
                        handleWidgetSelected(provider)
                    },
                    onCancel = {
                        AppWidgetHelper.getHost(this).deleteAppWidgetId(appWidgetId)
                        finish()
                    }
                )
            }
        }
    }

    private fun handleWidgetSelected(provider: AppWidgetProviderInfo) {
        selectedProvider = provider
        val allowed = appWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, provider.provider)
        if (allowed) {
            proceedToConfigureOrFinish()
        } else {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider)
            try {
                startActivityForResult(intent, 200)
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to bind widget", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun proceedToConfigureOrFinish() {
        val provider = selectedProvider ?: return
        if (provider.configure != null) {
            val configIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
            configIntent.component = provider.configure
            configIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            try {
                startActivityForResult(configIntent, 101)
            } catch (e: Exception) {
                finishWithSuccess(appWidgetId)
            }
        } else {
            finishWithSuccess(appWidgetId)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == 200) {
            if (resultCode == RESULT_OK) {
                proceedToConfigureOrFinish()
            } else {
                Toast.makeText(this, "Widget binding denied", Toast.LENGTH_SHORT).show()
                AppWidgetHelper.getHost(this).deleteAppWidgetId(appWidgetId)
                finish()
            }
        } else if (requestCode == 101) {
            if (resultCode == RESULT_OK) {
                finishWithSuccess(appWidgetId)
            } else {
                AppWidgetHelper.getHost(this).deleteAppWidgetId(appWidgetId)
                finish()
            }
        }
    }
    
    private fun finishWithSuccess(widgetId: Int) {
        val actionType = intent.getStringExtra("ACTION_TYPE") ?: "ADD_ELEMENT"
        
        if (actionType == "ADD_ELEMENT") {
            val info = appWidgetManager.getAppWidgetInfo(widgetId)
            val label = info?.loadLabel(packageManager) ?: "Widget"
            
            val json = JSONObject()
            json.put("widgetId", widgetId)
            json.put("label", label)
            val id = "widget:${widgetId}:${json.toString()}"
            
            val serviceIntent = Intent(this, com.example.feature.miniapps.reader.FloatingReaderService::class.java)
            serviceIntent.action = "ADD_ELEMENT"
            serviceIntent.putExtra("element_id", id)
            
            val folderUuid = intent.getStringExtra("FOLDER_UUID")
            val isElementCallback = intent.getBooleanExtra("IS_ELEMENT_CALLBACK", false)
            
            if (folderUuid != null) {
                serviceIntent.putExtra("FOLDER_UUID", folderUuid)
            }
            if (isElementCallback) {
                serviceIntent.putExtra("IS_ELEMENT_CALLBACK", true)
            }
            startService(serviceIntent)
        } else if (actionType == "ADD_TO_WIDGETS_GRID") {
            val pageId = intent.getStringExtra("PAGE_ID") ?: ""
            val prefs = getSharedPreferences("FloatingReaderPrefs", android.content.Context.MODE_PRIVATE)
            val currentStr = prefs.getString("widgets_grid_$pageId", "[]") ?: "[]"
            val current = org.json.JSONArray(currentStr)
            current.put(widgetId)
            prefs.edit().putString("widgets_grid_$pageId", current.toString()).apply()
            
            val broadcastIntent = Intent("WIDGET_ADDED_TO_GRID")
            broadcastIntent.putExtra("WIDGET_ID", widgetId)
            broadcastIntent.putExtra("PAGE_ID", pageId)
            broadcastIntent.setPackage(packageName); sendBroadcast(broadcastIntent)
        } else if (actionType == "CREATE_PAGE") {
            val broadcastIntent = Intent("WIDGET_PAGE_CREATED")
            broadcastIntent.putExtra("WIDGET_ID", widgetId)
            broadcastIntent.setPackage(packageName); sendBroadcast(broadcastIntent)
        } else if (actionType == "RETURN_ID") {
            val info = appWidgetManager.getAppWidgetInfo(widgetId)
            val label = info?.loadLabel(packageManager) ?: "Widget"
            
            val spanX = if (info != null) {
                val cw = if (android.os.Build.VERSION.SDK_INT >= 31) info.targetCellWidth else 0
                if (cw > 0) cw else Math.max(1, Math.round(info.minWidth / 70.0).toInt())
            } else 2
            val spanY = if (info != null) {
                val ch = if (android.os.Build.VERSION.SDK_INT >= 31) info.targetCellHeight else 0
                if (ch > 0) ch else Math.max(1, Math.round(info.minHeight / 70.0).toInt())
            } else 2
            
            val json = JSONObject()
            json.put("widgetId", widgetId)
            json.put("label", label)
            json.put("cols", spanX)
            json.put("rows", spanY)
            val id = "widget:${widgetId}:${json.toString()}"
            
            val resultIntent = Intent().apply { putExtra("ELEMENT_ID", id) }
            setResult(android.app.Activity.RESULT_OK, resultIntent)
        }
        
        finish()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetPickerScreen(
    providers: List<AppWidgetProviderInfo>,
    actionType: String,
    onWidgetSelected: (AppWidgetProviderInfo) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val pm = context.packageManager
    
    data class AppGroup(
        val appName: String,
        val appIcon: Drawable?,
        val widgets: List<AppWidgetProviderInfo>
    )

    // Group by actual Application Name instead of individual widget label
    val grouped = remember(providers) {
        providers.groupBy { provider ->
            provider.provider.packageName
        }.mapNotNull { (pkg, widgetList) ->
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val appName = pm.getApplicationLabel(appInfo).toString()
                val appIcon = pm.getApplicationIcon(appInfo)
                AppGroup(
                    appName = appName,
                    appIcon = appIcon,
                    widgets = widgetList.sortedBy { it.loadLabel(pm).toString() }
                )
            } catch (e: Exception) {
                val fallbackName = widgetList.firstOrNull()?.loadLabel(pm)?.toString() ?: pkg
                AppGroup(
                    appName = fallbackName,
                    appIcon = null,
                    widgets = widgetList.sortedBy { it.loadLabel(pm).toString() }
                )
            }
        }.sortedBy { it.appName.lowercase() }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xCC000000)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().clickable(onClick = onCancel)) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.85f)
                    .clickable(enabled = false, onClick = {}), // consume clicks
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Choose a Widget",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        grouped.forEach { appGroup ->
                            item(key = appGroup.appName + "_" + (appGroup.widgets.firstOrNull()?.provider?.packageName ?: "")) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                        .padding(vertical = 8.dp, horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (appGroup.appIcon != null) {
                                        val iconBitmap = drawableToBitmap(appGroup.appIcon)
                                        Image(
                                            bitmap = iconBitmap.asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text(
                                        text = appGroup.appName,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "(${appGroup.widgets.size})",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                            items(appGroup.widgets, key = { it.provider.flattenToString() + "_" + it.loadLabel(pm) }) { provider ->
                                val spanX = if (android.os.Build.VERSION.SDK_INT >= 31) provider.targetCellWidth else Math.max(1, Math.round(provider.minWidth / 70.0).toInt())
                                val spanY = if (android.os.Build.VERSION.SDK_INT >= 31) provider.targetCellHeight else Math.max(1, Math.round(provider.minHeight / 70.0).toInt())
                                val enabled = true
                                
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = enabled) { onWidgetSelected(provider) }
                                        .padding(vertical = 10.dp, horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Try to load preview image first, then fallback to icon
                                    val previewDrawable = provider.loadPreviewImage(context, context.resources.displayMetrics.densityDpi)
                                        ?: provider.loadIcon(context, context.resources.displayMetrics.densityDpi)
                                    if (previewDrawable != null) {
                                        val bitmap = drawableToBitmap(previewDrawable)
                                        Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier.size(48.dp),
                                            alpha = if (enabled) 1f else 0.3f
                                        )
                                        Spacer(modifier = Modifier.width(16.dp))
                                    } else {
                                        Box(modifier = Modifier.size(48.dp).background(Color.Gray, RoundedCornerShape(8.dp)))
                                        Spacer(modifier = Modifier.width(16.dp))
                                    }
                                    
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = provider.loadLabel(pm).toString().ifEmpty { appGroup.appName },
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${spanX}x${spanY}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                        )
                                    }
                                }
                            }
                            item {
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onCancel) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}

fun drawableToBitmap(drawable: Drawable): Bitmap {
    if (drawable is BitmapDrawable) {
        if (drawable.bitmap != null) {
            return drawable.bitmap
        }
    }
    val bitmap = if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    } else {
        Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
    }
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}
