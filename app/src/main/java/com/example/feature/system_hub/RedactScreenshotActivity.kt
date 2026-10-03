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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.core.LogKeeper
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

enum class RedactTool(val displayName: String) {
    BRUSH("Brush"),
    BLOCK("Block")
}

sealed class RedactItem {
    data class Stroke(val stroke: RedactStroke) : RedactItem()
    data class Box(val box: RedactBox) : RedactItem()
}

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
 * Allows drawing freehand strokes or dragging rectangular blocks to blur or black out sensitive data on captured screenshots,
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
    val history = remember { mutableStateListOf<RedactItem>() }
    var selectedTool by remember { mutableStateOf(RedactTool.BRUSH) }
    var isBlurMode by remember { mutableStateOf(false) }
    var selectedStrokeWidthDp by remember { mutableStateOf(32f) }

    var currentStrokePoints by remember { mutableStateOf<List<RedactPoint>>(emptyList()) }
    var currentDragBox by remember { mutableStateOf<RedactBox?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Redact Screenshot", color = Color.White) },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close", tint = Color.White)
                }
            },
            actions = {
                if (history.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            if (history.isNotEmpty()) {
                                history.removeAt(history.lastIndex)
                            }
                        },
                        modifier = Modifier.testTag("redact_undo_button")
                    ) {
                        Text("Undo", color = Color(0xFFFFCC00))
                    }
                    IconButton(
                        onClick = { history.clear() },
                        modifier = Modifier.testTag("redact_clear_button")
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear All", tint = Color.White)
                    }
                }
                Button(
                    onClick = {
                        val finalBitmap = renderRedactedBitmap(originalBitmap, history)
                        onSaveAndShare(finalBitmap)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .testTag("redact_save_share_button")
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Save & Share")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E1E1E))
        )

        // Mode and Tool Control Toolbar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF2A2A2A))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Tool Toggle (Brush vs Blocks)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = { selectedTool = RedactTool.BRUSH },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedTool == RedactTool.BRUSH) MaterialTheme.colorScheme.primary else Color(0xFF383838),
                            contentColor = if (selectedTool == RedactTool.BRUSH) Color.White else Color.LightGray
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("redact_tool_brush")
                    ) {
                        Text("Brush", style = MaterialTheme.typography.bodySmall)
                    }

                    Button(
                        onClick = { selectedTool = RedactTool.BLOCK },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedTool == RedactTool.BLOCK) MaterialTheme.colorScheme.primary else Color(0xFF383838),
                            contentColor = if (selectedTool == RedactTool.BLOCK) Color.White else Color.LightGray
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("redact_tool_block")
                    ) {
                        Text("Blocks", style = MaterialTheme.typography.bodySmall)
                    }
                }

                // Style Toggle (Blackout vs Blur)
                Button(
                    onClick = { isBlurMode = !isBlurMode },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isBlurMode) Color(0xFF4CAF50) else Color(0xFF3A3A3A)
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier
                        .height(34.dp)
                        .testTag(if (isBlurMode) "redact_mode_blur" else "redact_mode_blackout")
                ) {
                    Text(
                        text = if (isBlurMode) "Blur / Mosaic" else "Blackout",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // Brush size buttons (only if Brush tool is active)
                if (selectedTool == RedactTool.BRUSH) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(16f to "S", 32f to "M", 56f to "L").forEach { (sizeDp, label) ->
                            val isSelected = (selectedStrokeWidthDp == sizeDp)
                            Button(
                                onClick = { selectedStrokeWidthDp = sizeDp },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.secondary else Color(0xFF383838),
                                    contentColor = if (isSelected) Color.White else Color.LightGray
                                ),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .height(28.dp)
                                    .testTag("redact_brush_size_$label")
                            ) {
                                Text(label, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            // Instructional Guidance Banner
            Text(
                text = if (selectedTool == RedactTool.BLOCK) {
                    "Mode: Blocks (Rectangle). Drag on screen to redact area with a ${if (isBlurMode) "blur mosaic" else "blackout"} block."
                } else {
                    "Mode: Brush (Freehand). Drag across sensitive items to paint ${if (isBlurMode) "blur mosaic" else "blackout"} strokes."
                },
                color = Color.LightGray,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF222222))
                    .padding(horizontal = 14.dp, vertical = 4.dp)
            )
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
                    .pointerInput(selectedTool, isBlurMode, selectedStrokeWidthDp) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val cW = size.width.toFloat()
                            val cH = size.height.toFloat()
                            if (cW <= 0f || cH <= 0f) return@awaitEachGesture

                            if (selectedTool == RedactTool.BRUSH) {
                                val points = mutableListOf(
                                    RedactPoint(down.position.x / cW, down.position.y / cH)
                                )
                                currentStrokePoints = points.toList()

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    if (change.pressed) {
                                        change.consume()
                                        points.add(
                                            RedactPoint(change.position.x / cW, change.position.y / cH)
                                        )
                                        currentStrokePoints = points.toList()
                                    } else {
                                        break
                                    }
                                }

                                if (points.isNotEmpty()) {
                                    history.add(
                                        RedactItem.Stroke(
                                            RedactStroke(
                                                points = points.toList(),
                                                isBlur = isBlurMode,
                                                strokeWidthDp = selectedStrokeWidthDp
                                            )
                                        )
                                    )
                                }
                                currentStrokePoints = emptyList()
                            } else {
                                // Block mode (bounding box drag)
                                val downPos = down.position
                                var curPos = down.position

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    if (change.pressed) {
                                        change.consume()
                                        curPos = change.position
                                        val l = min(downPos.x, curPos.x) / cW
                                        val t = min(downPos.y, curPos.y) / cH
                                        val r = max(downPos.x, curPos.x) / cW
                                        val b = max(downPos.y, curPos.y) / cH
                                        currentDragBox = RedactBox(
                                            normalizedLeft = l.coerceIn(0f, 1f),
                                            normalizedTop = t.coerceIn(0f, 1f),
                                            normalizedRight = r.coerceIn(0f, 1f),
                                            normalizedBottom = b.coerceIn(0f, 1f),
                                            isBlur = isBlurMode
                                        )
                                    } else {
                                        break
                                    }
                                }

                                currentDragBox?.let { box ->
                                    val boxW = box.normalizedRight - box.normalizedLeft
                                    val boxH = box.normalizedBottom - box.normalizedTop
                                    if (boxW > 0.005f && boxH > 0.005f) {
                                        history.add(RedactItem.Box(box))
                                    }
                                }
                                currentDragBox = null
                            }
                        }
                    }
            ) {
                // Draw background screenshot image scaled to canvas
                drawImage(
                    image = imageBitmap,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt())
                )

                // Draw confirmed history items (strokes and blocks) in order
                for (item in history) {
                    when (item) {
                        is RedactItem.Stroke -> drawStrokeOnCanvas(item.stroke, size.width, size.height)
                        is RedactItem.Box -> drawBoxOnCanvas(item.box, size.width, size.height)
                    }
                }

                // Draw active in-progress stroke preview
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

                // Draw active in-progress block preview
                currentDragBox?.let { box ->
                    drawBoxOnCanvas(box, size.width, size.height, isPreview = true)
                }
            }
        }
    }
}

private fun DrawScope.drawBoxOnCanvas(box: RedactBox, canvasW: Float, canvasH: Float, isPreview: Boolean = false) {
    val l = box.normalizedLeft * canvasW
    val t = box.normalizedTop * canvasH
    val r = box.normalizedRight * canvasW
    val b = box.normalizedBottom * canvasH
    val w = (r - l).coerceAtLeast(1f)
    val h = (b - t).coerceAtLeast(1f)

    if (box.isBlur) {
        drawRect(
            color = Color(0xDD333333),
            topLeft = Offset(l, t),
            size = Size(w, h)
        )
        drawRect(
            color = Color(0x88FFFFFF),
            topLeft = Offset(l, t),
            size = Size(w, h),
            style = Stroke(width = if (isPreview) 2.dp.toPx() else 1.dp.toPx())
        )
    } else {
        drawRect(
            color = Color.Black,
            topLeft = Offset(l, t),
            size = Size(w, h)
        )
        if (isPreview) {
            drawRect(
                color = Color(0xFF00E676),
                topLeft = Offset(l, t),
                size = Size(w, h),
                style = Stroke(width = 1.5.dp.toPx())
            )
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
 * Creates a clean copy of the original bitmap and draws all redaction items (strokes & blocks) onto it.
 */
fun renderRedactedBitmap(original: Bitmap, items: List<RedactItem>): Bitmap {
    val result = original.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(result)
    val w = original.width.toFloat()
    val h = original.height.toFloat()

    val blurItems = items.filter { (it is RedactItem.Stroke && it.stroke.isBlur) || (it is RedactItem.Box && it.box.isBlur) }
    val blackoutItems = items.filter { (it is RedactItem.Stroke && !it.stroke.isBlur) || (it is RedactItem.Box && !it.box.isBlur) }

    // Render blur items via authentic pixelated mosaic
    if (blurItems.isNotEmpty()) {
        val downscaleFactor = 24
        val smallW = (original.width / downscaleFactor).coerceAtLeast(1)
        val smallH = (original.height / downscaleFactor).coerceAtLeast(1)
        val smallBitmap = Bitmap.createScaledBitmap(original, smallW, smallH, false)
        val mosaicBitmap = Bitmap.createScaledBitmap(smallBitmap, original.width, original.height, false)
        smallBitmap.recycle()

        for (item in blurItems) {
            when (item) {
                is RedactItem.Box -> {
                    val box = item.box
                    val rect = RectF(
                        box.normalizedLeft * w,
                        box.normalizedTop * h,
                        box.normalizedRight * w,
                        box.normalizedBottom * h
                    )
                    canvas.save()
                    canvas.clipRect(rect)
                    canvas.drawBitmap(mosaicBitmap, 0f, 0f, null)
                    canvas.restore()
                }
                is RedactItem.Stroke -> {
                    val stroke = item.stroke
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
            }
        }

        mosaicBitmap.recycle()
    }

    // Render blackout items in solid black
    val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        style = Paint.Style.FILL
    }
    val blackStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    for (item in blackoutItems) {
        when (item) {
            is RedactItem.Box -> {
                val box = item.box
                val rect = RectF(
                    box.normalizedLeft * w,
                    box.normalizedTop * h,
                    box.normalizedRight * w,
                    box.normalizedBottom * h
                )
                canvas.drawRect(rect, blackPaint)
            }
            is RedactItem.Stroke -> {
                val stroke = item.stroke
                if (stroke.points.isEmpty()) continue
                val strokePx = (stroke.strokeWidthDp * (w / 360f)).coerceAtLeast(10f)

                if (stroke.points.size == 1) {
                    val p = stroke.points[0]
                    canvas.drawCircle(p.normalizedX * w, p.normalizedY * h, strokePx / 2f, blackPaint)
                    continue
                }

                val strokePath = android.graphics.Path().apply {
                    moveTo(stroke.points[0].normalizedX * w, stroke.points[0].normalizedY * h)
                    for (i in 1 until stroke.points.size) {
                        lineTo(stroke.points[i].normalizedX * w, stroke.points[i].normalizedY * h)
                    }
                }

                blackStrokePaint.strokeWidth = strokePx
                canvas.drawPath(strokePath, blackStrokePaint)
            }
        }
    }

    return result
}

/**
 * Backwards-compatibility overload for stroke list.
 */
@JvmName("renderRedactedBitmapFromStrokes")
fun renderRedactedBitmap(original: Bitmap, strokes: List<RedactStroke>): Bitmap {
    return renderRedactedBitmap(original, strokes.map { RedactItem.Stroke(it) })
}

/**
 * Backwards-compatibility overload for rectangular box redaction.
 */
@JvmName("renderRedactedBitmapFromBoxes")
fun renderRedactedBitmap(original: Bitmap, boxes: List<RedactBox>): Bitmap {
    return renderRedactedBitmap(original, boxes.map { RedactItem.Box(it) })
}

