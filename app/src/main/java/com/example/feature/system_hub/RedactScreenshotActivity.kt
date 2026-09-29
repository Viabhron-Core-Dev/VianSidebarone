package com.example.feature.system_hub

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.core.LogKeeper
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

data class RedactBox(
    val normalizedLeft: Float,
    val normalizedTop: Float,
    val normalizedRight: Float,
    val normalizedBottom: Float,
    val isBlur: Boolean = false
)

/**
 * RedactScreenshotActivity: Dedicated image redaction editor running strictly in the :heavy process.
 * Allows drawing redaction rectangles to blur or black out sensitive data on captured screenshots,
 * and saving or sharing the redacted result.
 */
class RedactScreenshotActivity : ComponentActivity() {

    private var sourceImagePath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sourceImagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)

        val imageFile = sourceImagePath?.let { File(it) }
        val loadedBitmap = if (imageFile != null && imageFile.exists()) {
            BitmapFactory.decodeFile(imageFile.absolutePath)
        } else {
            null
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    if (loadedBitmap == null) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No screenshot available", color = Color.White)
                        }
                    } else {
                        RedactScreenContent(
                            originalBitmap = loadedBitmap,
                            onSaveAndShare = { finalBitmap ->
                                saveAndShareBitmap(finalBitmap)
                            },
                            onClose = {
                                finish()
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up temporary screenshot source file
        sourceImagePath?.let { path ->
            try {
                val f = File(path)
                if (f.exists()) {
                    f.delete()
                    LogKeeper.writeLog(TAG, "Temporary screenshot deleted: $path")
                }
            } catch (e: Exception) {
                LogKeeper.writeLog(TAG, "Failed to delete temporary screenshot: ${e.message}")
            }
        }
    }

    private fun saveAndShareBitmap(bitmap: Bitmap) {
        try {
            val fileName = "REDACTED_${System.currentTimeMillis()}.png"
            var savedUri: Uri? = null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Redacted")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    savedUri = uri
                }
            }

            if (savedUri == null) {
                val cacheFile = File(cacheDir, fileName)
                FileOutputStream(cacheFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                savedUri = FileProvider.getUriForFile(this, "$packageName.provider", cacheFile)
            }

            Toast.makeText(this, "Redacted screenshot saved", Toast.LENGTH_SHORT).show()

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, savedUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share Redacted Screenshot"))
        } catch (e: Exception) {
            LogKeeper.writeLog(TAG, "Error saving/sharing redacted bitmap: ${e.message}")
            Toast.makeText(this, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_IMAGE_PATH = "EXTRA_IMAGE_PATH"
        private const val TAG = "RedactScreenshotActivity"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedactScreenContent(
    originalBitmap: Bitmap,
    onSaveAndShare: (Bitmap) -> Unit,
    onClose: () -> Unit
) {
    val boxes = remember { mutableStateListOf<RedactBox>() }
    var currentBox by remember { mutableStateOf<RedactBox?>(null) }
    var isBlurMode by remember { mutableStateOf(false) }

    var canvasSize by remember { mutableStateOf(IntSize(1, 1)) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Redact Screenshot", color = Color.White) },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close", tint = Color.White)
                }
            },
            actions = {
                if (boxes.isNotEmpty()) {
                    IconButton(onClick = { boxes.clear() }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear All", tint = Color.White)
                    }
                }
                Button(
                    onClick = {
                        val finalBitmap = renderRedactedBitmap(originalBitmap, boxes)
                        onSaveAndShare(finalBitmap)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Save & Share")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E1E1E))
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF2A2A2A))
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Mode: ${if (isBlurMode) "Blur / Mosaic" else "Blackout Box"}", color = Color.White, style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = { isBlurMode = !isBlurMode },
                colors = ButtonDefaults.buttonColors(containerColor = if (isBlurMode) Color(0xFF4CAF50) else Color(0xFF555555))
            ) {
                Text(if (isBlurMode) "Switch to Blackout" else "Switch to Blur")
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .background(Color.Black)
        ) {
            val imageBitmap = remember(originalBitmap) { originalBitmap.asImageBitmap() }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(isBlurMode) {
                        var startOffset = Offset.Zero
                        detectDragGestures(
                            onDragStart = { offset ->
                                startOffset = offset
                                currentBox = RedactBox(
                                    normalizedLeft = offset.x / size.width,
                                    normalizedTop = offset.y / size.height,
                                    normalizedRight = offset.x / size.width,
                                    normalizedBottom = offset.y / size.height,
                                    isBlur = isBlurMode
                                )
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val curX = change.position.x
                                val curY = change.position.y
                                val left = min(startOffset.x, curX) / size.width
                                val top = min(startOffset.y, curY) / size.height
                                val right = max(startOffset.x, curX) / size.width
                                val bottom = max(startOffset.y, curY) / size.height

                                currentBox = RedactBox(left, top, right, bottom, isBlur = isBlurMode)
                            },
                            onDragEnd = {
                                currentBox?.let { box ->
                                    if ((box.normalizedRight - box.normalizedLeft) > 0.01f &&
                                        (box.normalizedBottom - box.normalizedTop) > 0.01f
                                    ) {
                                        boxes.add(box)
                                    }
                                }
                                currentBox = null
                            },
                            onDragCancel = {
                                currentBox = null
                            }
                        )
                    }
            ) {
                canvasSize = IntSize(size.width.toInt(), size.height.toInt())

                // Draw background screenshot image scaled to canvas
                drawImage(
                    image = imageBitmap,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt())
                )

                // Draw confirmed redaction boxes
                for (box in boxes) {
                    val x = box.normalizedLeft * size.width
                    val y = box.normalizedTop * size.height
                    val w = (box.normalizedRight - box.normalizedLeft) * size.width
                    val h = (box.normalizedBottom - box.normalizedTop) * size.height

                    if (box.isBlur) {
                        drawRect(
                            color = Color(0xDD333333),
                            topLeft = Offset(x, y),
                            size = Size(w, h)
                        )
                        drawRect(
                            color = Color.White,
                            topLeft = Offset(x, y),
                            size = Size(w, h),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    } else {
                        drawRect(
                            color = Color.Black,
                            topLeft = Offset(x, y),
                            size = Size(w, h)
                        )
                    }
                }

                // Draw currently dragging box
                currentBox?.let { box ->
                    val x = box.normalizedLeft * size.width
                    val y = box.normalizedTop * size.height
                    val w = (box.normalizedRight - box.normalizedLeft) * size.width
                    val h = (box.normalizedBottom - box.normalizedTop) * size.height

                    drawRect(
                        color = if (box.isBlur) Color(0x99555555) else Color(0x99000000),
                        topLeft = Offset(x, y),
                        size = Size(w, h)
                    )
                    drawRect(
                        color = Color.Red,
                        topLeft = Offset(x, y),
                        size = Size(w, h),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }
        }
    }
}

/**
 * Creates a clean copy of the original bitmap and draws all redactions onto it.
 */
fun renderRedactedBitmap(original: Bitmap, boxes: List<RedactBox>): Bitmap {
    val result = original.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(result)

    val blackPaint = Paint().apply {
        color = android.graphics.Color.BLACK
        style = Paint.Style.FILL
    }

    val blurPaint = Paint().apply {
        color = android.graphics.Color.DKGRAY
        style = Paint.Style.FILL
    }

    val w = original.width.toFloat()
    val h = original.height.toFloat()

    for (box in boxes) {
        val rect = RectF(
            box.normalizedLeft * w,
            box.normalizedTop * h,
            box.normalizedRight * w,
            box.normalizedBottom * h
        )
        if (box.isBlur) {
            canvas.drawRect(rect, blurPaint)
        } else {
            canvas.drawRect(rect, blackPaint)
        }
    }

    return result
}
