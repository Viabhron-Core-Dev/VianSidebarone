package com.example.feature.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Color
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.example.core.IconCacheManager
import com.example.feature.sidebar.AppInfo
import com.example.feature.sidebar.ElementMetadataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppPickerActivity : ComponentActivity() {
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.BLACK)
            setPadding(16, 16, 16, 16)
        }
        
        val title = TextView(this).apply {
            text = "Select App"
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }

        layout.addView(title)
        
        val list = ListView(this).apply {
            setBackgroundColor(Color.parseColor("#222222"))
        }

        layout.addView(list)
        setContentView(layout)

        scope.launch(Dispatchers.IO) {
            val appList = mutableListOf<AppInfo>()
            val pm = packageManager

            // 1. Query LauncherApps across all user profiles
            try {
                val launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
                val userManager = getSystemService(Context.USER_SERVICE) as? android.os.UserManager
                val profiles = userManager?.userProfiles ?: listOf(Process.myUserHandle())
                if (launcherApps != null) {
                    for (profile in profiles) {
                        try {
                            val activities = launcherApps.getActivityList(null, profile)
                            for (activityInfo in activities) {
                                val pkg = activityInfo.applicationInfo.packageName
                                if (pkg == this@AppPickerActivity.packageName) continue
                                val label = activityInfo.label?.toString() ?: pkg
                                appList.add(AppInfo(pkg, label))
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 2. Query PackageManager launcher activities as well to ensure full discovery
            try {
                val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val resolveInfos = pm.queryIntentActivities(launcherIntent, 0)
                for (ri in resolveInfos) {
                    val pkg = ri.activityInfo?.packageName ?: continue
                    if (pkg == this@AppPickerActivity.packageName) continue
                    val label = ri.loadLabel(pm)?.toString() ?: pkg
                    appList.add(AppInfo(pkg, label))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val distinctApps = appList.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }

            withContext(Dispatchers.Main) {
                list.adapter = object : ArrayAdapter<AppInfo>(this@AppPickerActivity, android.R.layout.simple_list_item_1, distinctApps) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val view = (convertView as? LinearLayout) ?: LinearLayout(this@AppPickerActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(16, 16, 16, 16)
                            gravity = Gravity.CENTER_VERTICAL
                            
                            addView(ImageView(this@AppPickerActivity).apply {
                                id = 1
                                layoutParams = LinearLayout.LayoutParams(96, 96)
                            })

                            addView(TextView(this@AppPickerActivity).apply {
                                id = 2
                                setTextColor(Color.WHITE)
                                textSize = 16f
                                setPadding(16, 0, 0, 0)
                            })
                        }

                        val appInfo = getItem(position)!!
                        val imgView = view.findViewById<ImageView>(1)
                        val cached = IconCacheManager.getCachedBitmap(this@AppPickerActivity, appInfo.packageName)
                        if (cached != null) {
                            imgView.setImageBitmap(cached)
                        } else {
                            imgView.setImageResource(android.R.drawable.sym_def_app_icon)
                            imgView.tag = appInfo.packageName
                            scope.launch(Dispatchers.IO) {
                                val loaded = IconCacheManager.getOrLoadBitmap(this@AppPickerActivity, appInfo.packageName)
                                withContext(Dispatchers.Main) {
                                    if (loaded != null && imgView.tag == appInfo.packageName) {
                                        imgView.setImageBitmap(loaded)
                                    }
                                }
                            }
                        }

                        view.findViewById<TextView>(2).text = appInfo.label
                        return view
                    }
                }

                list.setOnItemClickListener { _, _, position, _ ->
                    val app = distinctApps[position]
                    // Capture app label and compact WebP icon on disk once
                    ElementMetadataStore.saveAppElement(
                        this@AppPickerActivity,
                        app.packageName,
                        app.label
                    )
                    val resultIntent = Intent().apply { putExtra("ELEMENT_ID", "app:${app.packageName}") }
                    setResult(Activity.RESULT_OK, resultIntent)
                    finish()
                }
            }
        }
    }
}
