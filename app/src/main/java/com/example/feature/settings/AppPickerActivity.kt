package com.example.feature.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.example.feature.sidebar.SidebarAppsManager
import com.example.feature.sidebar.AppInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppPickerActivity : ComponentActivity() {
    private lateinit var manager: SidebarAppsManager
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
        
        manager = SidebarAppsManager(this, getSharedPreferences("prefs", Context.MODE_PRIVATE), CoroutineScope(Dispatchers.IO), "dummy") {

            scope.launch {

                val apps = manager.allInstalledApps
                list.adapter = object : ArrayAdapter<AppInfo>(this@AppPickerActivity, android.R.layout.simple_list_item_1, apps) {
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
                        val bmp = manager.getIconBitmap("app:${appInfo.packageName}")
                        if (bmp != null) {
                            view.findViewById<ImageView>(1).setImageBitmap(bmp)
                        } else {
                            view.findViewById<ImageView>(1).setImageResource(android.R.drawable.sym_def_app_icon)
                            val imgView = view.findViewById<ImageView>(1)
                            imgView.tag = appInfo.packageName
                            scope.launch {
                                val loaded = manager.loadIcon(appInfo.packageName)
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
                    val app = apps[position]
                    val resultIntent = Intent().apply { putExtra("ELEMENT_ID", "app:${app.packageName}") }
                    setResult(Activity.RESULT_OK, resultIntent)
                    finish()
                }

            }

        }

        manager.ensureLoaded()
    }
}
