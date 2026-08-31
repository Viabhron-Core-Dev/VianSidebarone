package com.example.feature.system_hub

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Rect as AndroidRect
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.res.painterResource
import com.example.R
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.min

enum class RedactTool {
    BOX,
    BRUSH
}

enum class RedactStyle {
    BLACK,
    GREY,
    BLUR
}

sealed class RedactItem {
    data class Box(
        val rect: Rect,
        val style: RedactStyle
    ) : RedactItem()

    data class Stroke(
        val points: List<Offset>,
        val strokeWidth: Float,
        val style: RedactStyle
    ) : RedactItem()
}

class RedactScreenshotActivity : ComponentActivity() {
    private var tempImagePath: String? = null

    override fun onDestroy() {
        super.onDestroy()
        tempImagePath?.let { path ->
            try {
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        try {
            val cacheFiles = cacheDir.listFiles { _, name -> name.startsWith("shared_redact_") }
            cacheFiles?.forEach { file ->
                if (file.lastModified() < System.currentTimeMillis() - 60 * 60 * 1000) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val imagePath = intent.getStringExtra("IMAGE_PATH")
        tempImagePath = imagePath
        if (imagePath == null) {
            Toast.makeText(this, "No screenshot provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val file = File(imagePath)
        if (!file.exists()) {
            Toast.makeText(this, "Screenshot file not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (bitmap == null) {
            Toast.makeText(this, "Failed to load screenshot", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val prefs = getSharedPreferences("ScreenCapPrefs", Context.MODE_PRIVATE)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF121212)
                ) {
                    RedactScreenshotScreen(
                        originalBitmap = bitmap,
                        prefs = prefs,
                        onClose = { finish() },
                        onSave = { redactedBitmap ->
                            saveRedactedImage(redactedBitmap, prefs)
                        },
                        onShare = { redactedBitmap ->
                            shareRedactedImage(redactedBitmap)
                        },
                        onCopy = { redactedBitmap ->
                            copyRedactedImage(redactedBitmap)
                        }
                    )
                }
            }
        }
    }

    private fun saveRedactedImage(bitmap: Bitmap, prefs: SharedPreferences) {
        val saveLocation = prefs.getString("save_location", "Default (Pictures/Screenshots)") ?: "Default (Pictures/Screenshots)"
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "Screenshot_Redacted_$timestamp.png"

        Thread {
            if (saveLocation != "Default (Pictures/Screenshots)") {
                try {
                    val uri = Uri.parse(saveLocation)
                    val dir = DocumentFile.fromTreeUri(this, uri)
                    if (dir != null && dir.isDirectory) {
                        val file = dir.createFile("image/png", fileName)
                        if (file != null) {
                            val out: OutputStream? = contentResolver.openOutputStream(file.uri)
                            if (out != null) {
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                                out.flush()
                                out.close()
                                runOnUiThread {
                                    Toast.makeText(this, "Saved to custom location", Toast.LENGTH_SHORT).show()
                                    finish()
                                }
                                return@Thread
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            try {
                val resolver = contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Screenshots")
                }
                val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (imageUri != null) {
                    resolver.openOutputStream(imageUri).use { out ->
                        if (out != null) {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                    }
                    runOnUiThread {
                        Toast.makeText(this, "Saved to Pictures/Screenshots", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "Failed to save screenshot", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Error saving screenshot: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun shareRedactedImage(bitmap: Bitmap) {
        Thread {
            try {
                val cacheFile = File(cacheDir, "shared_redact_${System.currentTimeMillis()}.png")
                FileOutputStream(cacheFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                val uri = FileProvider.getUriForFile(this, "${packageName}.provider", cacheFile)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(shareIntent, "Share Redacted Screenshot")
                chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(chooser)
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Failed to share image", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun copyRedactedImage(bitmap: Bitmap) {
        Thread {
            try {
                val cacheFile = File(cacheDir, "shared_redact_${System.currentTimeMillis()}.png")
                FileOutputStream(cacheFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                val uri = FileProvider.getUriForFile(this, "${packageName}.provider", cacheFile)
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newUri(contentResolver, "Redacted Screenshot", uri)
                clipboard.setPrimaryClip(clip)
                runOnUiThread {
                    Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Failed to copy image", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedactScreenshotScreen(
    originalBitmap: Bitmap,
    prefs: SharedPreferences,
    onClose: () -> Unit,
    onSave: (Bitmap) -> Unit,
    onShare: (Bitmap) -> Unit,
    onCopy: (Bitmap) -> Unit
) {
    var selectedTool by remember { mutableStateOf(RedactTool.BOX) }
    var selectedStyle by remember { mutableStateOf(RedactStyle.BLACK) }
    var brushSizeDp by remember { mutableFloatStateOf(24f) }

    val items = remember { mutableStateListOf<RedactItem>() }
    val redoStack = remember { mutableStateListOf<RedactItem>() }

    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // Touch states
    var currentBoxStart by remember { mutableStateOf<Offset?>(null) }
    var currentBoxEnd by remember { mutableStateOf<Offset?>(null) }
    var currentStrokePoints by remember { mutableStateOf<List<Offset>>(emptyList()) }

    // Pre-calculated mosaic/blur bitmap for blur effect
    val mosaicBitmap = remember(originalBitmap) {
        createMosaicBitmap(originalBitmap, pixelBlockSize = 24)
    }

    // Helper to generate composite redacted bitmap
    fun buildRedactedBitmap(): Bitmap {
        val resultBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = AndroidCanvas(resultBitmap)

        val blackPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            style = AndroidPaint.Style.FILL
        }
        val greyPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#2B2B2B")
            style = AndroidPaint.Style.FILL
        }

        // Calculate scaling from displayed image container to actual original bitmap
        val scaleX = if (containerSize.width > 0) originalBitmap.width.toFloat() / containerSize.width else 1f
        val scaleY = if (containerSize.height > 0) originalBitmap.height.toFloat() / containerSize.height else 1f

        for (item in items) {
            when (item) {
                is RedactItem.Box -> {
                    val left = item.rect.left * scaleX
                    val top = item.rect.top * scaleY
                    val right = item.rect.right * scaleX
                    val bottom = item.rect.bottom * scaleY
                    val targetRect = AndroidRect(
                        left.toInt().coerceIn(0, originalBitmap.width),
                        top.toInt().coerceIn(0, originalBitmap.height),
                        right.toInt().coerceIn(0, originalBitmap.width),
                        bottom.toInt().coerceIn(0, originalBitmap.height)
                    )

                    when (item.style) {
                        RedactStyle.BLACK -> canvas.drawRect(
                            targetRect.left.toFloat(), targetRect.top.toFloat(),
                            targetRect.right.toFloat(), targetRect.bottom.toFloat(), blackPaint
                        )
                        RedactStyle.GREY -> canvas.drawRect(
                            targetRect.left.toFloat(), targetRect.top.toFloat(),
                            targetRect.right.toFloat(), targetRect.bottom.toFloat(), greyPaint
                        )
                        RedactStyle.BLUR -> {
                            if (targetRect.width() > 0 && targetRect.height() > 0) {
                                canvas.drawBitmap(mosaicBitmap, targetRect, targetRect, null)
                            }
                        }
                    }
                }
                is RedactItem.Stroke -> {
                    if (item.points.size >= 2) {
                        val strokePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                            style = AndroidPaint.Style.STROKE
                            strokeCap = AndroidPaint.Cap.ROUND
                            strokeJoin = AndroidPaint.Join.ROUND
                            strokeWidth = item.strokeWidth * ((scaleX + scaleY) / 2f)
                            color = when (item.style) {
                                RedactStyle.BLACK -> android.graphics.Color.BLACK
                                RedactStyle.GREY -> android.graphics.Color.parseColor("#2B2B2B")
                                RedactStyle.BLUR -> android.graphics.Color.parseColor("#80555555")
                            }
                        }
                        val path = android.graphics.Path()
                        path.moveTo(item.points[0].x * scaleX, item.points[0].y * scaleY)
                        for (i in 1 until item.points.size) {
                            path.lineTo(item.points[i].x * scaleX, item.points[i].y * scaleY)
                        }
                        canvas.drawPath(path, strokePaint)
                    }
                }
            }
        }
        return resultBitmap
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Redact Screenshot",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (items.isNotEmpty()) {
                                val popped = items.removeAt(items.size - 1)
                                redoStack.add(popped)
                            }
                        },
                        enabled = items.isNotEmpty()
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_undo),
                            contentDescription = "Undo",
                            tint = if (items.isNotEmpty()) Color.White else Color.Gray
                        )
                    }

                    IconButton(
                        onClick = {
                            if (redoStack.isNotEmpty()) {
                                val popped = redoStack.removeAt(redoStack.size - 1)
                                items.add(popped)
                            }
                        },
                        enabled = redoStack.isNotEmpty()
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_redo),
                            contentDescription = "Redo",
                            tint = if (redoStack.isNotEmpty()) Color.White else Color.Gray
                        )
                    }

                    IconButton(
                        onClick = {
                            if (items.isNotEmpty()) {
                                redoStack.clear()
                                items.clear()
                            }
                        },
                        enabled = items.isNotEmpty()
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Reset All",
                            tint = if (items.isNotEmpty()) Color.White else Color.Gray
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E1E1E))
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E1E1E))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                // Secondary row: Size options (for Brush) or quick help for box
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Style selection chips: Blackout, Grey, Blur
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = selectedStyle == RedactStyle.BLACK,
                            onClick = { selectedStyle = RedactStyle.BLACK },
                            label = { Text("Blackout", fontSize = 12.sp) },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .background(Color.Black, CircleShape)
                                        .border(1.dp, Color.White, CircleShape)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF333333),
                                selectedLabelColor = Color.White
                            )
                        )

                        FilterChip(
                            selected = selectedStyle == RedactStyle.GREY,
                            onClick = { selectedStyle = RedactStyle.GREY },
                            label = { Text("Grey", fontSize = 12.sp) },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .background(Color(0xFF424242), CircleShape)
                                        .border(1.dp, Color.White, CircleShape)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF333333),
                                selectedLabelColor = Color.White
                            )
                        )

                        FilterChip(
                            selected = selectedStyle == RedactStyle.BLUR,
                            onClick = { selectedStyle = RedactStyle.BLUR },
                            label = { Text("Mosaic", fontSize = 12.sp) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_grain),
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = Color.Cyan
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF333333),
                                selectedLabelColor = Color.White
                            )
                        )
                    }

                    // Brush size toggles
                    if (selectedTool == RedactTool.BRUSH) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(12f to "S", 24f to "M", 48f to "L").forEach { (size, label) ->
                                val isSelected = brushSizeDp == size
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF2C2C2C))
                                        .clickable { brushSizeDp = size },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color.Black else Color.White
                                    )
                                }
                            }
                        }
                    }
                }

                // Primary row: Tool selector and Export actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Tool toggle buttons
                    Row(
                        modifier = Modifier
                            .background(Color(0xFF2A2A2A), RoundedCornerShape(12.dp))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Box Mode Button
                        IconButton(
                            onClick = { selectedTool = RedactTool.BOX },
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    if (selectedTool == RedactTool.BOX) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    RoundedCornerShape(8.dp)
                                )
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_crop_square),
                                contentDescription = "Box Tool",
                                tint = if (selectedTool == RedactTool.BOX) Color.Black else Color.White
                            )
                        }

                        // Brush Mode Button
                        IconButton(
                            onClick = { selectedTool = RedactTool.BRUSH },
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    if (selectedTool == RedactTool.BRUSH) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    RoundedCornerShape(8.dp)
                                )
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Brush Tool",
                                tint = if (selectedTool == RedactTool.BRUSH) Color.Black else Color.White
                            )
                        }
                    }

                    // Action buttons: Copy, Share, Save
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val result = buildRedactedBitmap()
                                onCopy(result)
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(painterResource(R.drawable.ic_content_copy), contentDescription = "Copy", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Copy", fontSize = 13.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                val result = buildRedactedBitmap()
                                onShare(result)
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Share", fontSize = 13.sp)
                        }

                        Button(
                            onClick = {
                                val result = buildRedactedBitmap()
                                onSave(result)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = "Save", tint = Color.Black, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Save", fontSize = 13.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF101010)),
            contentAlignment = Alignment.Center
        ) {
            val composeImage = remember(originalBitmap) { originalBitmap.asImageBitmap() }
            val composeMosaicImage = remember(mosaicBitmap) { mosaicBitmap.asImageBitmap() }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { containerSize = it }
                    .pointerInput(selectedTool, selectedStyle, brushSizeDp) {
                        if (selectedTool == RedactTool.BOX) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    currentBoxStart = offset
                                    currentBoxEnd = offset
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    currentBoxEnd = change.position
                                },
                                onDragEnd = {
                                    val start = currentBoxStart
                                    val end = currentBoxEnd
                                    if (start != null && end != null) {
                                        val left = min(start.x, end.x)
                                        val top = min(start.y, end.y)
                                        val right = max(start.x, end.x)
                                        val bottom = max(start.y, end.y)
                                        if (right - left > 8 && bottom - top > 8) {
                                            redoStack.clear()
                                            items.add(
                                                RedactItem.Box(
                                                    rect = Rect(left, top, right, bottom),
                                                    style = selectedStyle
                                                )
                                            )
                                        }
                                    }
                                    currentBoxStart = null
                                    currentBoxEnd = null
                                },
                                onDragCancel = {
                                    currentBoxStart = null
                                    currentBoxEnd = null
                                }
                            )
                        } else {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    currentStrokePoints = listOf(offset)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    currentStrokePoints = currentStrokePoints + change.position
                                },
                                onDragEnd = {
                                    if (currentStrokePoints.size >= 2) {
                                        redoStack.clear()
                                        items.add(
                                            RedactItem.Stroke(
                                                points = currentStrokePoints,
                                                strokeWidth = brushSizeDp,
                                                style = selectedStyle
                                            )
                                        )
                                    }
                                    currentStrokePoints = emptyList()
                                },
                                onDragCancel = {
                                    currentStrokePoints = emptyList()
                                }
                            )
                        }
                    }
            ) {
                // Drawing Canvas
                Canvas(modifier = Modifier.fillMaxSize()) {
                    if (size.width <= 0 || size.height <= 0) return@Canvas

                    // Draw base original screenshot
                    drawImage(
                        image = composeImage,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt())
                    )

                    // Draw completed redactions
                    for (item in items) {
                        when (item) {
                            is RedactItem.Box -> {
                                when (item.style) {
                                    RedactStyle.BLACK -> {
                                        drawRect(
                                            color = Color.Black,
                                            topLeft = item.rect.topLeft,
                                            size = item.rect.size
                                        )
                                    }
                                    RedactStyle.GREY -> {
                                        drawRect(
                                            color = Color(0xFF2B2B2B),
                                            topLeft = item.rect.topLeft,
                                            size = item.rect.size
                                        )
                                    }
                                    RedactStyle.BLUR -> {
                                        val srcRect = androidx.compose.ui.unit.IntRect(
                                            (item.rect.left * (mosaicBitmap.width / size.width)).toInt().coerceIn(0, mosaicBitmap.width),
                                            (item.rect.top * (mosaicBitmap.height / size.height)).toInt().coerceIn(0, mosaicBitmap.height),
                                            (item.rect.right * (mosaicBitmap.width / size.width)).toInt().coerceIn(0, mosaicBitmap.width),
                                            (item.rect.bottom * (mosaicBitmap.height / size.height)).toInt().coerceIn(0, mosaicBitmap.height)
                                        )
                                        drawImage(
                                            image = composeMosaicImage,
                                            srcOffset = srcRect.topLeft,
                                            srcSize = srcRect.size,
                                            dstOffset = androidx.compose.ui.unit.IntOffset(item.rect.left.toInt(), item.rect.top.toInt()),
                                            dstSize = IntSize(item.rect.width.toInt(), item.rect.height.toInt())
                                        )
                                    }
                                }
                            }
                            is RedactItem.Stroke -> {
                                if (item.points.size >= 2) {
                                    val path = Path().apply {
                                        moveTo(item.points[0].x, item.points[0].y)
                                        for (i in 1 until item.points.size) {
                                            lineTo(item.points[i].x, item.points[i].y)
                                        }
                                    }
                                    val strokeColor = when (item.style) {
                                        RedactStyle.BLACK -> Color.Black
                                        RedactStyle.GREY -> Color(0xFF2B2B2B)
                                        RedactStyle.BLUR -> Color(0xAA555555)
                                    }
                                    drawPath(
                                        path = path,
                                        color = strokeColor,
                                        style = Stroke(
                                            width = item.strokeWidth,
                                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                            join = androidx.compose.ui.graphics.StrokeJoin.Round
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // Draw in-progress Box
                    val start = currentBoxStart
                    val end = currentBoxEnd
                    if (start != null && end != null) {
                        val left = min(start.x, end.x)
                        val top = min(start.y, end.y)
                        val right = max(start.x, end.x)
                        val bottom = max(start.y, end.y)
                        val previewRect = Rect(left, top, right, bottom)

                        when (selectedStyle) {
                            RedactStyle.BLACK -> drawRect(color = Color.Black.copy(alpha = 0.85f), topLeft = previewRect.topLeft, size = previewRect.size)
                            RedactStyle.GREY -> drawRect(color = Color(0xFF2B2B2B).copy(alpha = 0.85f), topLeft = previewRect.topLeft, size = previewRect.size)
                            RedactStyle.BLUR -> {
                                val srcRect = androidx.compose.ui.unit.IntRect(
                                    (previewRect.left * (mosaicBitmap.width / size.width)).toInt().coerceIn(0, mosaicBitmap.width),
                                    (previewRect.top * (mosaicBitmap.height / size.height)).toInt().coerceIn(0, mosaicBitmap.height),
                                    (previewRect.right * (mosaicBitmap.width / size.width)).toInt().coerceIn(0, mosaicBitmap.width),
                                    (previewRect.bottom * (mosaicBitmap.height / size.height)).toInt().coerceIn(0, mosaicBitmap.height)
                                )
                                if (srcRect.width > 0 && srcRect.height > 0) {
                                    drawImage(
                                        image = composeMosaicImage,
                                        srcOffset = srcRect.topLeft,
                                        srcSize = srcRect.size,
                                        dstOffset = androidx.compose.ui.unit.IntOffset(previewRect.left.toInt(), previewRect.top.toInt()),
                                        dstSize = IntSize(previewRect.width.toInt(), previewRect.height.toInt())
                                    )
                                }
                            }
                        }
                        // Guide border
                        drawRect(
                            color = Color(0xFF00E676),
                            topLeft = previewRect.topLeft,
                            size = previewRect.size,
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }

                    // Draw in-progress Stroke
                    if (currentStrokePoints.size >= 2) {
                        val path = Path().apply {
                            moveTo(currentStrokePoints[0].x, currentStrokePoints[0].y)
                            for (i in 1 until currentStrokePoints.size) {
                                lineTo(currentStrokePoints[i].x, currentStrokePoints[i].y)
                            }
                        }
                        val strokeColor = when (selectedStyle) {
                            RedactStyle.BLACK -> Color.Black
                            RedactStyle.GREY -> Color(0xFF2B2B2B)
                            RedactStyle.BLUR -> Color(0xAA555555)
                        }
                        drawPath(
                            path = path,
                            color = strokeColor,
                            style = Stroke(
                                width = brushSizeDp,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                join = androidx.compose.ui.graphics.StrokeJoin.Round
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Creates an ultra-fast pixelated mosaic bitmap of the source screenshot for the blur tool.
 */
private fun createMosaicBitmap(src: Bitmap, pixelBlockSize: Int = 24): Bitmap {
    val downW = (src.width / pixelBlockSize).coerceAtLeast(1)
    val downH = (src.height / pixelBlockSize).coerceAtLeast(1)
    val smallBitmap = Bitmap.createScaledBitmap(src, downW, downH, false)
    val result = Bitmap.createScaledBitmap(smallBitmap, src.width, src.height, false)
    smallBitmap.recycle()
    return result
}
