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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
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

data class RedactPoint(
    val normalizedX: Float,
    val normalizedY: Float
)

data class RedactStroke(
    val points: List<RedactPoint>,
    val isBlur: Boolean,
    val strokeWidthDp: Float
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
    val strokes = remember { mutableStateListOf<RedactStroke>() }
    var currentStrokePoints by remember { mutableStateOf<List<RedactPoint>>(emptyList()) }
    var isBlurMode by remember { mutableStateOf(false) }
    var selectedStrokeWidthDp by remember { mutableStateOf(32f) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Redact Screenshot", color = Color.White) },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close", tint = Color.White)
                }
            },
            actions = {
                if (strokes.isNotEmpty()) {
                    TextButton(onClick = {
                        if (strokes.isNotEmpty()) {
                            strokes.removeAt(strokes.lastIndex)
                        }
                    }) {
                        Text("Undo", color = Color(0xFFFFCC00))
                    }
                    IconButton(onClick = { strokes.clear() }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear All", tint = Color.White)
                    }
                }
                Button(
                    onClick = {
                        val finalBitmap = renderRedactedBitmap(originalBitmap, strokes)
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

        // Mode and Brush Size Control Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF2A2A2A))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { isBlurMode = !isBlurMode },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isBlurMode) Color(0xFF4CAF50) else Color(0xFF333333)
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Text(
                    text = if (isBlurMode) "Brush: Blur / Mosaic" else "Brush: Blackout",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Size:", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                listOf(16f to "S", 32f to "M", 56f to "L").forEach { (sizeDp, label) ->
                    val isSelected = (selectedStrokeWidthDp == sizeDp)
                    Button(
                        onClick = { selectedStrokeWidthDp = sizeDp },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF3A3A3A),
                            contentColor = if (isSelected) Color.White else Color.LightGray
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text(label, style = MaterialTheme.typography.labelSmall)
                    }
                }
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
                    .pointerInput(isBlurMode, selectedStrokeWidthDp) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val points = mutableListOf(
                                RedactPoint(down.position.x / size.width, down.position.y / size.height)
                            )
                            currentStrokePoints = points.toList()

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (change.pressed) {
                                    change.consume()
                                    points.add(
                                        RedactPoint(change.position.x / size.width, change.position.y / size.height)
                                    )
                                    currentStrokePoints = points.toList()
                                } else {
                                    break
                                }
                            }

                            if (points.isNotEmpty()) {
                                strokes.add(
                                    RedactStroke(
                                        points = points.toList(),
                                        isBlur = isBlurMode,
                                        strokeWidthDp = selectedStrokeWidthDp
                                    )
                                )
                            }
                            currentStrokePoints = emptyList()
                        }
                    }
            ) {
                // Draw background screenshot image scaled to canvas
                drawImage(
                    image = imageBitmap,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt())
                )

                // Draw confirmed strokes
                for (stroke in strokes) {
                    drawStrokeOnCanvas(stroke, size.width, size.height)
                }

                // Draw active stroke in progress
                if (currentStrokePoints.isNotEmpty()) {
                    drawStrokeOnCanvas(
                        RedactStroke(
                            points = currentStrokePoints,
                            isBlur = isBlurMode,
                            strokeWidthDp = selectedStrokeWidthDp
                        ),
                        size.width,
                        size.height
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawStrokeOnCanvas(stroke: RedactStroke, canvasW: Float, canvasH: Float) {
    if (stroke.points.isEmpty()) return
    val strokePx = (stroke.strokeWidthDp * (canvasW / 360f)).coerceAtLeast(8f)

    if (stroke.points.size == 1) {
        val p = stroke.points[0]
        val center = Offset(p.normalizedX * canvasW, p.normalizedY * canvasH)
        if (stroke.isBlur) {
            drawCircle(
                color = Color(0xDD333333),
                radius = strokePx / 2f,
                center = center
            )
            drawCircle(
                color = Color(0x88FFFFFF),
                radius = strokePx / 2f,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )
        } else {
            drawCircle(
                color = Color.Black,
                radius = strokePx / 2f,
                center = center
            )
        }
        return
    }

    val path = Path().apply {
        moveTo(stroke.points[0].normalizedX * canvasW, stroke.points[0].normalizedY * canvasH)
        for (i in 1 until stroke.points.size) {
            lineTo(stroke.points[i].normalizedX * canvasW, stroke.points[i].normalizedY * canvasH)
        }
    }

    if (stroke.isBlur) {
        drawPath(
            path = path,
            color = Color(0xEE2A2A2A),
            style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawPath(
            path = path,
            color = Color(0x66FFFFFF),
            style = Stroke(width = strokePx * 0.45f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    } else {
        drawPath(
            path = path,
            color = Color.Black,
            style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

/**
 * Creates a clean copy of the original bitmap and draws all brush strokes onto it.
 */
fun renderRedactedBitmap(original: Bitmap, strokes: List<RedactStroke>): Bitmap {
    val result = original.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(result)
    val w = original.width.toFloat()
    val h = original.height.toFloat()

    val blurStrokes = strokes.filter { it.isBlur }
    val blackoutStrokes = strokes.filter { !it.isBlur }

    // Render blur strokes via authentic pixelated mosaic
    if (blurStrokes.isNotEmpty()) {
        val downscaleFactor = 24
        val smallW = (original.width / downscaleFactor).coerceAtLeast(1)
        val smallH = (original.height / downscaleFactor).coerceAtLeast(1)
        val smallBitmap = Bitmap.createScaledBitmap(original, smallW, smallH, false)
        val mosaicBitmap = Bitmap.createScaledBitmap(smallBitmap, original.width, original.height, false)
        smallBitmap.recycle()

        for (stroke in blurStrokes) {
            if (stroke.points.isEmpty()) continue
            val strokePx = (stroke.strokeWidthDp * (w / 360f)).coerceAtLeast(10f)

            if (stroke.points.size == 1) {
                val p = stroke.points[0]
                val path = android.graphics.Path().apply {
                    addCircle(p.normalizedX * w, p.normalizedY * h, strokePx / 2f, android.graphics.Path.Direction.CW)
                }
                canvas.save()
                canvas.clipPath(path)
                canvas.drawBitmap(mosaicBitmap, 0f, 0f, null)
                canvas.restore()
                continue
            }

            val strokePath = android.graphics.Path().apply {
                moveTo(stroke.points[0].normalizedX * w, stroke.points[0].normalizedY * h)
                for (i in 1 until stroke.points.size) {
                    lineTo(stroke.points[i].normalizedX * w, stroke.points[i].normalizedY * h)
                }
            }

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = strokePx
            }

            val fillPath = android.graphics.Path()
            paint.getFillPath(strokePath, fillPath)

            canvas.save()
            canvas.clipPath(fillPath)
            canvas.drawBitmap(mosaicBitmap, 0f, 0f, null)
            canvas.restore()
        }

        mosaicBitmap.recycle()
    }

    // Render blackout strokes in solid black
    for (stroke in blackoutStrokes) {
        if (stroke.points.isEmpty()) continue
        val strokePx = (stroke.strokeWidthDp * (w / 360f)).coerceAtLeast(10f)

        if (stroke.points.size == 1) {
            val p = stroke.points[0]
            val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.BLACK
                style = Paint.Style.FILL
            }
            canvas.drawCircle(p.normalizedX * w, p.normalizedY * h, strokePx / 2f, circlePaint)
            continue
        }

        val strokePath = android.graphics.Path().apply {
            moveTo(stroke.points[0].normalizedX * w, stroke.points[0].normalizedY * h)
            for (i in 1 until stroke.points.size) {
                lineTo(stroke.points[i].normalizedX * w, stroke.points[i].normalizedY * h)
            }
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = strokePx
        }
        canvas.drawPath(strokePath, paint)
    }

    return result
}

/**
 * Backwards-compatibility overload for rectangular box redaction.
 */
@JvmName("renderRedactedBitmapFromBoxes")
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
