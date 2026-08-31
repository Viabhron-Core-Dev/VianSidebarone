package com.example.feature.system_hub

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import com.example.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.LogKeeper
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class QRCropActivity : ComponentActivity() {
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
            val cacheFiles = cacheDir.listFiles { _, name -> name.startsWith("shared_crop_") }
            cacheFiles?.forEach { file ->
                if (file.lastModified() < System.currentTimeMillis() - 60 * 60 * 1000) { // 1 hour old
                    file.delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Cleanup old shared crop images
        Thread {
            try {
                cacheDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("shared_crop_") && file.name.endsWith(".jpg")) {
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
        
        val imagePath = intent.getStringExtra("IMAGE_PATH")
        tempImagePath = imagePath
        if (imagePath == null) {
            Toast.makeText(this, "No image provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val file = File(imagePath)
        if (!file.exists()) {
            Toast.makeText(this, "Image file not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (bitmap == null) {
            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    QRCropScreen(
                        bitmap = bitmap,
                        onAction = { action, x, y, width, height, shape, points ->
                            if (action == "scan") {
                                scanCroppedArea(bitmap, x, y, width, height, shape, points)
                            } else if (action == "ocr") {
                                ocrCroppedArea(bitmap, x, y, width, height, shape, points)
                            } else if (action == "share") {
                                shareCroppedArea(bitmap, x, y, width, height, shape, points)
                            }
                        },
                        onClose = { finish() }
                    )
                }
            }
        }
    }

    private fun ocrCroppedArea(bitmap: Bitmap, x: Float, y: Float, w: Float, h: Float, shape: String, points: List<Offset>) {
        val cropX = maxOf(0, x.toInt())
        val cropY = maxOf(0, y.toInt())
        val cropW = minOf(bitmap.width - cropX, w.toInt())
        val cropH = minOf(bitmap.height - cropY, h.toInt())
        if (cropW <= 0 || cropH <= 0) {
            Toast.makeText(this, "Invalid crop area", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            var croppedBitmap = Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)
            
            if (shape == "circle") {
                val output = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(output)
                canvas.drawColor(android.graphics.Color.WHITE)
                val path = android.graphics.Path()
                path.addOval(android.graphics.RectF(0f, 0f, cropW.toFloat(), cropH.toFloat()), android.graphics.Path.Direction.CW)
                canvas.clipPath(path)
                canvas.drawBitmap(croppedBitmap, 0f, 0f, null)
                croppedBitmap = output
            } else if (shape == "polygon") {
                val output = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(output)
                canvas.drawColor(android.graphics.Color.WHITE)
                if (points.isNotEmpty()) {
                    val path = android.graphics.Path()
                    path.moveTo(points.first().x - cropX, points.first().y - cropY)
                    for (i in 1 until points.size) {
                        path.lineTo(points[i].x - cropX, points[i].y - cropY)
                    }
                    path.close()
                    canvas.clipPath(path)
                }
                canvas.drawBitmap(croppedBitmap, 0f, 0f, null)
                croppedBitmap = output
            }
            
            val image = InputImage.fromBitmap(croppedBitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val text = visionText.text
                    if (text.isNotEmpty()) {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("OCR Text", text))
                        Toast.makeText(this, "Copied: $text", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "No text found", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener { e ->
                    val msg = if (e.message?.contains("optional module") == true || e.message?.contains("Waiting for") == true || e.message?.contains("unavailable") == true) {
                        "OCR engine not downloaded. Please download it from Record Settings."
                    } else {
                        "OCR failed: ${e.message}"
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
                .addOnCompleteListener {
                    recognizer.close()
                }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to crop for OCR", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareCroppedArea(bitmap: Bitmap, x: Float, y: Float, w: Float, h: Float, shape: String, points: List<Offset>) {
        val cropX = maxOf(0, x.toInt())
        val cropY = maxOf(0, y.toInt())
        val cropW = minOf(bitmap.width - cropX, w.toInt())
        val cropH = minOf(bitmap.height - cropY, h.toInt())
        if (cropW <= 0 || cropH <= 0) {
            Toast.makeText(this, "Invalid crop area", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            var croppedBitmap = Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)
            
            if (shape == "circle") {
                val output = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(output)
                canvas.drawColor(android.graphics.Color.WHITE)
                val path = android.graphics.Path()
                path.addOval(android.graphics.RectF(0f, 0f, cropW.toFloat(), cropH.toFloat()), android.graphics.Path.Direction.CW)
                canvas.clipPath(path)
                canvas.drawBitmap(croppedBitmap, 0f, 0f, null)
                croppedBitmap = output
            } else if (shape == "polygon") {
                val output = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(output)
                canvas.drawColor(android.graphics.Color.WHITE)
                if (points.isNotEmpty()) {
                    val path = android.graphics.Path()
                    path.moveTo(points.first().x - cropX, points.first().y - cropY)
                    for (i in 1 until points.size) {
                        path.lineTo(points[i].x - cropX, points[i].y - cropY)
                    }
                    path.close()
                    canvas.clipPath(path)
                }
                canvas.drawBitmap(croppedBitmap, 0f, 0f, null)
                croppedBitmap = output
            }

            val cacheFile = java.io.File(cacheDir, "shared_crop_${System.currentTimeMillis()}.jpg")
            java.io.FileOutputStream(cacheFile).use { out ->
                croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.provider", cacheFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, "Share Image")
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(chooser)
            finish()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error sharing image", Toast.LENGTH_SHORT).show()
        }
    }

    private fun scanCroppedArea(bitmap: Bitmap, x: Float, y: Float, w: Float, h: Float, shape: String, points: List<Offset>) {
        val cropX = maxOf(0, x.toInt())
        val cropY = maxOf(0, y.toInt())
        val cropW = minOf(bitmap.width - cropX, w.toInt())
        val cropH = minOf(bitmap.height - cropY, h.toInt())

        if (cropW <= 0 || cropH <= 0) {
            Toast.makeText(this, "Invalid crop area", Toast.LENGTH_SHORT).show()
            return
        }

        val croppedBitmap = Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)
        
        Thread {
            try {
                val pixels = IntArray(cropW * cropH)
                croppedBitmap.getPixels(pixels, 0, cropW, 0, 0, cropW, cropH)
                val source = RGBLuminanceSource(cropW, cropH, pixels)
                val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
                val reader = MultiFormatReader()
                val result = reader.decode(binaryBitmap)
                val text = result.text
                
                runOnUiThread {
                    if (text.isNullOrEmpty()) {
                        Toast.makeText(this, "No QR Code found in this area", Toast.LENGTH_SHORT).show()
                    } else {
                        com.example.core.LogKeeper.writeLog("QRCropActivity", "Successfully scanned QR Code: ${text.take(20)}...")
                        showResultDialog(text)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "No QR Code found", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun showResultDialog(text: String) {
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text("Scanned QR Code (Secure)") },
                    text = { 
                        Column {
                            Text("Data treated with caution. Do not open unknown links.", style = MaterialTheme.typography.labelSmall, color = Color.Yellow)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text) 
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("QR Code", text)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(this@QRCropActivity, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        }) {
                            Text("Copy Data")
                        }
                    },
                    dismissButton = {
                        if (text.startsWith("http://") || text.startsWith("https://")) {
                            TextButton(onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(text))
                                startActivity(intent)
                                finish()
                            }) {
                                Text("Open Link", color = Color.Red)
                            }
                        } else {
                            TextButton(onClick = { finish() }) {
                                Text("Close")
                            }
                        }
                    }
                )
            }
        }
    }
}

private fun isPointInPoly(point: Offset, vertices: List<Offset>): Boolean {
    if (vertices.size < 3) return false
    var inside = false
    var j = vertices.size - 1
    for (i in vertices.indices) {
        val vi = vertices[i]
        val vj = vertices[j]
        if ((vi.y > point.y) != (vj.y > point.y) &&
            point.x < (vj.x - vi.x) * (point.y - vi.y) / (vj.y - vi.y) + vi.x
        ) {
            inside = !inside
        }
        j = i
    }
    return inside
}

@Composable
fun QRCropScreen(
    bitmap: Bitmap,
    onAction: (String, Float, Float, Float, Float, String, List<Offset>) -> Unit,
    onClose: () -> Unit
) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var cropRect by remember { mutableStateOf(Rect.Zero) }
    var cropShape by remember { mutableStateOf("square") } // "square", "circle", "polygon"
    val polygonPoints = remember { mutableStateListOf<Offset>() }
    var isPolygonClosed by remember { mutableStateOf(false) }
    var isMoveMode by remember { mutableStateOf(false) }
    
    var draggedPointIndex by remember { mutableStateOf<Int?>(null) }
    var isDraggingPolygonBody by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                viewSize = size
                if (cropRect == Rect.Zero && size.width > 0 && size.height > 0) {
                    val boxSize = size.width * 0.65f
                    cropRect = Rect(
                        offset = Offset((size.width - boxSize) / 2f, (size.height - boxSize) / 2f),
                        size = Size(boxSize, boxSize)
                    )
                }
            }
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Screenshot",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )

        if (viewSize.width > 0) {
            val handleSize = 36.dp

            Canvas(
                modifier = Modifier
                    .graphicsLayer { alpha = 0.99f }
                    .fillMaxSize()
                    .pointerInput(cropShape, isPolygonClosed, isMoveMode) {
                        if (cropShape == "polygon") {
                            val touchRadius = 44.dp.toPx()
                            val snapRadius = 48.dp.toPx()

                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val downOffset = down.position

                                val idx = polygonPoints.indexOfFirst { pt ->
                                    (pt - downOffset).getDistance() <= touchRadius
                                }

                                var dragAmountTotal = Offset.Zero
                                var isDrag = false
                                val dragSlop = viewConfiguration.touchSlop

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (change.changedToUp()) {
                                        change.consume()
                                        break
                                    }
                                    val drag = change.positionChange()
                                    dragAmountTotal += drag
                                    if (!isDrag && dragAmountTotal.getDistance() > dragSlop) {
                                        isDrag = true
                                        if (idx != -1) {
                                            draggedPointIndex = idx
                                        } else if (isPolygonClosed && (isPointInPoly(downOffset, polygonPoints) || isMoveMode)) {
                                            isDraggingPolygonBody = true
                                        }
                                    }
                                    if (isDrag) {
                                        change.consume()
                                        val targetIdx = draggedPointIndex
                                        if (targetIdx != null && targetIdx in polygonPoints.indices) {
                                            polygonPoints[targetIdx] = polygonPoints[targetIdx] + drag
                                        } else if (isDraggingPolygonBody) {
                                            for (i in polygonPoints.indices) {
                                                polygonPoints[i] = polygonPoints[i] + drag
                                            }
                                        }
                                    }
                                }

                                if (isDrag) {
                                    draggedPointIndex = null
                                    isDraggingPolygonBody = false
                                } else {
                                    // User performed a TAP!
                                    if (!isPolygonClosed) {
                                        if (polygonPoints.size >= 3 && (polygonPoints.first() - downOffset).getDistance() <= snapRadius) {
                                            isPolygonClosed = true
                                            isMoveMode = true
                                        } else {
                                            val existingIdx = polygonPoints.indexOfFirst { pt ->
                                                (pt - downOffset).getDistance() <= touchRadius
                                            }
                                            if (existingIdx == -1) {
                                                polygonPoints.add(downOffset)
                                                if (polygonPoints.size == 4) {
                                                    isPolygonClosed = true
                                                    isMoveMode = true
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            var dragHandle: String? = null
                            val touchRadius = 50.dp.toPx()

                            detectDragGestures(
                                onDragStart = { offset ->
                                    val left = cropRect.left
                                    val right = cropRect.right
                                    val top = cropRect.top
                                    val bottom = cropRect.bottom

                                    dragHandle = when {
                                        offset.x in (left - touchRadius)..(left + touchRadius) && offset.y in (top - touchRadius)..(top + touchRadius) -> "topLeft"
                                        offset.x in (right - touchRadius)..(right + touchRadius) && offset.y in (top - touchRadius)..(top + touchRadius) -> "topRight"
                                        offset.x in (left - touchRadius)..(left + touchRadius) && offset.y in (bottom - touchRadius)..(bottom + touchRadius) -> "bottomLeft"
                                        offset.x in (right - touchRadius)..(right + touchRadius) && offset.y in (bottom - touchRadius)..(bottom + touchRadius) -> "bottomRight"
                                        offset.x in left..right && offset.y in top..bottom -> "center"
                                        else -> null
                                    }
                                },
                                onDragEnd = { dragHandle = null },
                                onDragCancel = { dragHandle = null },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    when (dragHandle) {
                                        "topLeft" -> cropRect = Rect(cropRect.left + dragAmount.x, cropRect.top + dragAmount.y, cropRect.right, cropRect.bottom)
                                        "topRight" -> cropRect = Rect(cropRect.left, cropRect.top + dragAmount.y, cropRect.right + dragAmount.x, cropRect.bottom)
                                        "bottomLeft" -> cropRect = Rect(cropRect.left + dragAmount.x, cropRect.top, cropRect.right, cropRect.bottom + dragAmount.y)
                                        "bottomRight" -> cropRect = Rect(cropRect.left, cropRect.top, cropRect.right + dragAmount.x, cropRect.bottom + dragAmount.y)
                                        "center" -> cropRect = cropRect.translate(dragAmount.x, dragAmount.y)
                                    }
                                    if (cropRect.width < 50f) cropRect = Rect(cropRect.left, cropRect.top, cropRect.left + 50f, cropRect.bottom)
                                    if (cropRect.height < 50f) cropRect = Rect(cropRect.left, cropRect.top, cropRect.right, cropRect.top + 50f)
                                }
                            )
                        }
                    }
            ) {
                val dimColor = Color.Black.copy(alpha = 0.6f)
                val accentColor = Color(0xFF00E676) // Vibrant Emerald/Green
                val dashedEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 14f), 0f)

                // 1. Draw outer dimming mask
                val maskPath = Path().apply {
                    addRect(Rect(0f, 0f, size.width, size.height))
                    if (cropShape == "circle") {
                        addOval(cropRect)
                    } else if (cropShape == "polygon") {
                        if (isPolygonClosed && polygonPoints.size >= 3) {
                            moveTo(polygonPoints.first().x, polygonPoints.first().y)
                            for (i in 1 until polygonPoints.size) {
                                lineTo(polygonPoints[i].x, polygonPoints[i].y)
                            }
                            close()
                        }
                    } else {
                        addRect(cropRect)
                    }
                    fillType = PathFillType.EvenOdd
                }
                drawPath(maskPath, dimColor)

                val hs = handleSize.toPx() / 2

                // 2. Draw selection boundaries & handles
                if (cropShape == "circle") {
                    drawOval(accentColor, cropRect.topLeft, cropRect.size, style = Stroke(width = 3.5.dp.toPx()))
                    listOf(cropRect.topLeft, cropRect.topRight, cropRect.bottomLeft, cropRect.bottomRight).forEach { pt ->
                        drawCircle(Color.White, radius = hs * 0.85f, center = pt)
                        drawCircle(accentColor, radius = hs * 0.85f, center = pt, style = Stroke(width = 3.dp.toPx()))
                    }
                } else if (cropShape == "polygon") {
                    if (polygonPoints.isNotEmpty()) {
                        // Draw lines between points
                        if (isPolygonClosed && polygonPoints.size >= 3) {
                            val polyPath = Path().apply {
                                moveTo(polygonPoints.first().x, polygonPoints.first().y)
                                for (i in 1 until polygonPoints.size) {
                                    lineTo(polygonPoints[i].x, polygonPoints[i].y)
                                }
                                close()
                            }
                            // Light tint covering the enclosed area
                            drawPath(polyPath, accentColor.copy(alpha = 0.15f))
                            drawPath(polyPath, accentColor, style = Stroke(width = 3.5.dp.toPx()))
                        } else {
                            // Open path connecting points sequentially
                            val openPath = Path().apply {
                                moveTo(polygonPoints.first().x, polygonPoints.first().y)
                                for (i in 1 until polygonPoints.size) {
                                    lineTo(polygonPoints[i].x, polygonPoints[i].y)
                                }
                            }
                            drawPath(openPath, accentColor, style = Stroke(width = 3.5.dp.toPx()))

                            // Subtle dashed preview line to first point
                            if (polygonPoints.size >= 2) {
                                val previewPath = Path().apply {
                                    moveTo(polygonPoints.last().x, polygonPoints.last().y)
                                    lineTo(polygonPoints.first().x, polygonPoints.first().y)
                                }
                                drawPath(previewPath, accentColor.copy(alpha = 0.6f), style = Stroke(width = 2.dp.toPx(), pathEffect = dashedEffect))
                            }
                        }

                        // Draw vertex pins
                        polygonPoints.forEachIndexed { index, pt ->
                            val isFirst = index == 0
                            val isSnapTarget = !isPolygonClosed && isFirst && polygonPoints.size >= 3
                            
                            // Pulse/snap target circle on Point 1 when ready to connect
                            if (isSnapTarget) {
                                drawCircle(accentColor.copy(alpha = 0.35f), radius = hs * 1.5f, center = pt)
                                drawCircle(Color.White, radius = hs * 1.5f, center = pt, style = Stroke(width = 2.dp.toPx(), pathEffect = dashedEffect))
                            }

                            // Vertex circle
                            drawCircle(Color.White, radius = hs * 0.9f, center = pt)
                            drawCircle(accentColor, radius = hs * 0.9f, center = pt, style = Stroke(width = 3.5.dp.toPx()))
                        }
                    }
                } else {
                    // Square
                    drawRect(accentColor, cropRect.topLeft, cropRect.size, style = Stroke(width = 3.5.dp.toPx()))
                    listOf(cropRect.topLeft, cropRect.topRight, cropRect.bottomLeft, cropRect.bottomRight).forEach { pt ->
                        drawCircle(Color.White, radius = hs * 0.85f, center = pt)
                        drawCircle(accentColor, radius = hs * 0.85f, center = pt, style = Stroke(width = 3.dp.toPx()))
                    }
                }
            }
        }

        // Top Compact Pills Floating Toolbar for Tap-to-Tap Polygon
        if (cropShape == "polygon") {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp),
                shape = RoundedCornerShape(28.dp),
                color = Color(0xDD181818),
                border = BorderStroke(1.dp, Color(0x44FFFFFF))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Status Badge
                    val statusText = when {
                        isPolygonClosed -> "Connected • Drag to adjust"
                        polygonPoints.isEmpty() -> "Tap 1st point"
                        polygonPoints.size == 1 -> "Tap 2nd point"
                        polygonPoints.size == 2 -> "Tap 3rd point"
                        else -> "Tap 4th or 1st to connect"
                    }
                    Text(
                        text = statusText,
                        color = if (isPolygonClosed) Color(0xFF00E676) else Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    // Undo Point Pill
                    IconButton(
                        onClick = {
                            if (isPolygonClosed) {
                                isPolygonClosed = false
                                isMoveMode = false
                            } else if (polygonPoints.isNotEmpty()) {
                                polygonPoints.removeAt(polygonPoints.size - 1)
                            }
                        },
                        enabled = polygonPoints.isNotEmpty(),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_undo),
                            contentDescription = "Undo Point",
                            tint = if (polygonPoints.isNotEmpty()) Color.White else Color.Gray,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Reset Pill
                    IconButton(
                        onClick = {
                            polygonPoints.clear()
                            isPolygonClosed = false
                            isMoveMode = false
                        },
                        enabled = polygonPoints.isNotEmpty(),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_restart_alt),
                            contentDescription = "Reset Points",
                            tint = if (polygonPoints.isNotEmpty()) Color(0xFFFF5252) else Color.Gray,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Move Points Toggle Pill
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isMoveMode) Color(0xFF00E676) else Color(0x33FFFFFF),
                        modifier = Modifier.clickable {
                            isMoveMode = !isMoveMode
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                painter = painterResource(if (isMoveMode) R.drawable.ic_open_with else R.drawable.ic_pan_tool),
                                contentDescription = "Toggle Move",
                                tint = if (isMoveMode) Color.Black else Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = if (isMoveMode) "Move" else "Tap",
                                color = if (isMoveMode) Color.Black else Color.White,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        // Bottom Controls Container
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            color = Color.Black.copy(alpha = 0.85f),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Color(0x33FFFFFF))
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Shape selector chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    FilterChip(
                        selected = cropShape == "square",
                        onClick = { cropShape = "square" },
                        label = { Text("Box") }
                    )
                    FilterChip(
                        selected = cropShape == "circle",
                        onClick = { cropShape = "circle" },
                        label = { Text("Oval") }
                    )
                    FilterChip(
                        selected = cropShape == "polygon",
                        onClick = {
                            cropShape = "polygon"
                            if (polygonPoints.size >= 4) {
                                isPolygonClosed = true
                                isMoveMode = true
                            }
                        },
                        label = { Text("Tap-to-Tap") }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    OutlinedButton(
                        onClick = onClose,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            val imgRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                            val viewRatio = viewSize.width.toFloat() / viewSize.height.toFloat()
                            var renderedW = viewSize.width.toFloat()
                            var renderedH = viewSize.height.toFloat()
                            var offsetX = 0f
                            var offsetY = 0f
                            if (imgRatio > viewRatio) {
                                renderedH = viewSize.width / imgRatio
                                offsetY = (viewSize.height - renderedH) / 2f
                            } else {
                                renderedW = viewSize.height * imgRatio
                                offsetX = (viewSize.width - renderedW) / 2f
                            }
                            val scale = bitmap.width / renderedW
                            val minX = polygonPoints.minOfOrNull { it.x } ?: 0f
                            val maxX = polygonPoints.maxOfOrNull { it.x } ?: 0f
                            val minY = polygonPoints.minOfOrNull { it.y } ?: 0f
                            val maxY = polygonPoints.maxOfOrNull { it.y } ?: 0f
                            val rectToUse = if (cropShape == "polygon" && polygonPoints.isNotEmpty()) Rect(minX, minY, maxX, maxY) else cropRect

                            val realX = (rectToUse.left - offsetX) * scale
                            val realY = (rectToUse.top - offsetY) * scale
                            val realW = rectToUse.width * scale
                            val realH = rectToUse.height * scale
                            val mappedPoints = polygonPoints.map { Offset((it.x - offsetX) * scale, (it.y - offsetY) * scale) }
                            onAction("ocr", realX, realY, realW, realH, cropShape, mappedPoints)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("OCR")
                    }

                    Button(
                        onClick = {
                            val imgRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                            val viewRatio = viewSize.width.toFloat() / viewSize.height.toFloat()
                            var renderedW = viewSize.width.toFloat()
                            var renderedH = viewSize.height.toFloat()
                            var offsetX = 0f
                            var offsetY = 0f
                            if (imgRatio > viewRatio) {
                                renderedH = viewSize.width / imgRatio
                                offsetY = (viewSize.height - renderedH) / 2f
                            } else {
                                renderedW = viewSize.height * imgRatio
                                offsetX = (viewSize.width - renderedW) / 2f
                            }
                            val scale = bitmap.width / renderedW
                            val minX = polygonPoints.minOfOrNull { it.x } ?: 0f
                            val maxX = polygonPoints.maxOfOrNull { it.x } ?: 0f
                            val minY = polygonPoints.minOfOrNull { it.y } ?: 0f
                            val maxY = polygonPoints.maxOfOrNull { it.y } ?: 0f
                            val rectToUse = if (cropShape == "polygon" && polygonPoints.isNotEmpty()) Rect(minX, minY, maxX, maxY) else cropRect

                            val realX = (rectToUse.left - offsetX) * scale
                            val realY = (rectToUse.top - offsetY) * scale
                            val realW = rectToUse.width * scale
                            val realH = rectToUse.height * scale
                            val mappedPoints = polygonPoints.map { Offset((it.x - offsetX) * scale, (it.y - offsetY) * scale) }
                            onAction("scan", realX, realY, realW, realH, cropShape, mappedPoints)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text("Scan QR")
                    }

                    OutlinedButton(
                        onClick = {
                            val imgRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                            val viewRatio = viewSize.width.toFloat() / viewSize.height.toFloat()
                            var renderedW = viewSize.width.toFloat()
                            var renderedH = viewSize.height.toFloat()
                            var offsetX = 0f
                            var offsetY = 0f
                            if (imgRatio > viewRatio) {
                                renderedH = viewSize.width / imgRatio
                                offsetY = (viewSize.height - renderedH) / 2f
                            } else {
                                renderedW = viewSize.height * imgRatio
                                offsetX = (viewSize.width - renderedW) / 2f
                            }
                            val scale = bitmap.width / renderedW
                            val minX = polygonPoints.minOfOrNull { it.x } ?: 0f
                            val maxX = polygonPoints.maxOfOrNull { it.x } ?: 0f
                            val minY = polygonPoints.minOfOrNull { it.y } ?: 0f
                            val maxY = polygonPoints.maxOfOrNull { it.y } ?: 0f
                            val rectToUse = if (cropShape == "polygon" && polygonPoints.isNotEmpty()) Rect(minX, minY, maxX, maxY) else cropRect

                            val realX = (rectToUse.left - offsetX) * scale
                            val realY = (rectToUse.top - offsetY) * scale
                            val realW = rectToUse.width * scale
                            val realH = rectToUse.height * scale
                            val mappedPoints = polygonPoints.map { Offset((it.x - offsetX) * scale, (it.y - offsetY) * scale) }
                            onAction("share", realX, realY, realW, realH, cropShape, mappedPoints)
                        }
                    ) {
                        Text("Share")
                    }
                }
            }
        }
    }
}

