package com.example

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.example.core.IconCacheManager
import com.example.core.LogKeeper
import kotlinx.coroutines.launch

class IconPickerActivity : ComponentActivity() {

    private var targetItemId: String? = null

    private val safImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null && !targetItemId.isNullOrEmpty()) {
            val itemId = targetItemId!!
            lifecycleScope.launch {
                val bitmap = IconCacheManager.saveCustomIconFromUri(this@IconPickerActivity, itemId, uri)
                if (bitmap != null) {
                    Toast.makeText(this@IconPickerActivity, "Icon updated & compressed successfully", Toast.LENGTH_SHORT).show()
                    val intent = Intent("com.example.UPDATE_SIDEBAR_ICONS").apply {
                        putExtra("item_id", itemId)
                    }
                    sendBroadcast(intent)
                    setResult(Activity.RESULT_OK)
                    finish()
                } else {
                    Toast.makeText(this@IconPickerActivity, "Failed to process image", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetItemId = intent.getStringExtra("item_id")
        if (targetItemId.isNullOrEmpty()) {
            finish()
            return
        }

        val density = resources.displayMetrics.density
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E2E"))
            val pad = (20 * density).toInt()
            setPadding(pad, pad, pad, pad)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val titleView = TextView(this).apply {
            text = "Change Icon"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (16 * density).toInt())
        }
        rootLayout.addView(titleView)

        fun createOptionButton(label: String, iconRes: Int, onClick: () -> Unit): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val pad = (14 * density).toInt()
                setPadding(pad, pad, pad, pad)
                val bg = GradientDrawable().apply {
                    setColor(Color.parseColor("#2A2A3C"))
                    cornerRadius = 12 * density
                    setStroke(1, Color.parseColor("#44445A"))
                }
                background = bg
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, (12 * density).toInt())
                }

                val iv = ImageView(this@IconPickerActivity).apply {
                    setImageResource(iconRes)
                    setColorFilter(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt()).apply {
                        marginEnd = (12 * density).toInt()
                    }
                }
                val tv = TextView(this@IconPickerActivity).apply {
                    text = label
                    setTextColor(Color.WHITE)
                    textSize = 15f
                }
                addView(iv)
                addView(tv)
                setOnClickListener { onClick() }
            }
        }

        // Option 1: Gallery / SAF (Storage Access Framework)
        val galleryBtn = createOptionButton("Select Image from Storage (SAF)", android.R.drawable.ic_menu_gallery) {
            safImageLauncher.launch("image/*")
        }
        rootLayout.addView(galleryBtn)

        // Option 2: Reset Icon
        val resetBtn = createOptionButton("Reset to Default Icon", android.R.drawable.ic_menu_revert) {
            targetItemId?.let { id ->
                IconCacheManager.evictIcon(this, id)
                val intent = Intent("com.example.UPDATE_SIDEBAR_ICONS").apply {
                    putExtra("item_id", id)
                }
                sendBroadcast(intent)
            }
            Toast.makeText(this, "Icon reset to default", Toast.LENGTH_SHORT).show()
            setResult(Activity.RESULT_OK)
            finish()
        }
        rootLayout.addView(resetBtn)

        setContentView(rootLayout)
    }
}
