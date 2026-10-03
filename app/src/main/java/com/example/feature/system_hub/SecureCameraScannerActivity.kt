package com.example.feature.system_hub

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.core.LogKeeper
import com.example.feature.system_hub.accessibility.ScannerCapabilityBridge
import com.example.feature.system_hub.accessibility.ScannerResult
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * SecureCameraScannerActivity: Dedicated Camera scanner running in the :heavy process.
 * Modular and loaded on-demand: opens camera, captures an image, lets the user crop/select
 * the region of interest, and runs the scanner engine against the crop.
 */
class SecureCameraScannerActivity : ComponentActivity() {

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var imageCapture: ImageCapture? = null

    // For JVM / Robolectric tests
    companion object {
        private const val TAG = "SecureCameraScanner"
        var testBitmapProvider: (() -> Bitmap?)? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    SecureCameraScannerHost(
                        onClose = { finish() },
                        onBindCamera = { previewView, capture ->
                            imageCapture = capture
                            bindCameraUseCases(previewView, capture)
                        },
                        onCapturePhoto = { onPhotoCaptured ->
                            testBitmapProvider?.invoke()?.let {
                                onPhotoCaptured(it)
                                return@SecureCameraScannerHost
                            }
                            captureImage(onPhotoCaptured)
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun bindCameraUseCases(previewView: PreviewView, capture: ImageCapture) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, capture)
                LogKeeper.writeLog(TAG, "Camera bound to lifecycle successfully")
            } catch (e: Exception) {
                LogKeeper.writeLog(TAG, "Camera binding failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureImage(onPhotoCaptured: (Bitmap) -> Unit) {
        val capture = imageCapture ?: return
        val photoFile = File(cacheDir, "camera_capture_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                    photoFile.delete()
                    if (bitmap != null) {
                        runOnUiThread { onPhotoCaptured(bitmap) }
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    LogKeeper.writeLog(TAG, "Capture failed: ${exc.message}")
                    runOnUiThread {
                        Toast.makeText(this@SecureCameraScannerActivity, "Failed to capture image", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecureCameraScannerHost(
    onClose: () -> Unit,
    onBindCamera: (PreviewView, ImageCapture) -> Unit,
    onCapturePhoto: ((Bitmap) -> Unit) -> Unit
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) {
            Toast.makeText(context, "Camera permission is required to scan", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }

    if (!hasCameraPermission) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Camera permission required", color = Color.White)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("Grant Permission")
            }
        }
        return
    }

    if (capturedBitmap == null) {
        // Camera View Mode
        Box(modifier = Modifier.fillMaxSize()) {
            val previewView = remember { PreviewView(context) }
            val imageCapture = remember { ImageCapture.Builder().build() }

            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )

            LaunchedEffect(previewView) {
                onBindCamera(previewView, imageCapture)
            }

            // Top Bar
            TopAppBar(
                title = { Text("Secure Camera Scanner", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0x66000000)),
                modifier = Modifier.align(Alignment.TopCenter)
            )

            // Capture Shutter Button
            Button(
                onClick = {
                    onCapturePhoto { bmp ->
                        capturedBitmap = bmp
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 40.dp)
                    .size(72.dp),
                shape = androidx.compose.foundation.shape.CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White)
            ) {
                Icon(painterResource(android.R.drawable.ic_menu_camera), contentDescription = "Capture", tint = Color.Black, modifier = Modifier.size(36.dp))
            }
        }
    } else {
        // Captured Frame Crop & Inspect Mode
        val currentBitmap = capturedBitmap!!
        CameraCropInspectView(
            bitmap = currentBitmap,
            onRetake = { capturedBitmap = null },
            onClose = onClose
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraCropInspectView(
    bitmap: Bitmap,
    onRetake: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var selectedShape by remember { mutableStateOf(SelectionShape.RECTANGLE) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    var cropBounds by remember {
        mutableStateOf(NormalizedCropBounds(0.15f, 0.25f, 0.85f, 0.65f))
    }

    var customVertices by remember {
        mutableStateOf<List<NormalizedPoint>>(emptyList())
    }
    var isPolygonClosed by remember { mutableStateOf(false) }

    var scanResultDialog by remember { mutableStateOf<ScannerResult?>(null) }
    var lastCroppedDimensions by remember { mutableStateOf("") }

    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Inspect & Crop Frame", color = Color.White) },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close", tint = Color.White)
                }
            },
            actions = {
                IconButton(onClick = {
                    if (selectedShape == SelectionShape.CUSTOM) {
                        customVertices = emptyList()
                        isPolygonClosed = false
                    } else {
                        onRetake()
                    }
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Retake", tint = Color.White)
                }
                Button(
                    onClick = {
                        val cropped = ScannerSelectionHelper.createCroppedBitmap(
                            bitmap,
                            cropBounds,
                            selectedShape,
                            if (selectedShape == SelectionShape.CUSTOM && isPolygonClosed && customVertices.size >= 3) customVertices else null
                        )
                        lastCroppedDimensions = "${cropped.width}x${cropped.height} px (${selectedShape.displayName})"
                        val result = ScannerCapabilityBridge.scanBitmap(cropped)
                        scanResultDialog = result
                    },
                    enabled = if (selectedShape == SelectionShape.CUSTOM) (isPolygonClosed && customVertices.size >= 3) else true,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .testTag("scanner_inspect_crop_button")
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Inspect Crop")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E1E1E))
        )

        // Shape Selection Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF222222))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Shape:", color = Color.LightGray, style = MaterialTheme.typography.labelMedium)
            SelectionShape.values().forEach { shape ->
                val isSelected = (shape == selectedShape)
                Button(
                    onClick = {
                        selectedShape = shape
                        if (shape == SelectionShape.CUSTOM) {
                            customVertices = emptyList()
                            isPolygonClosed = false
                        } else if (shape == SelectionShape.SQUARE || shape == SelectionShape.CIRCLE) {
                            val w = cropBounds.right - cropBounds.left
                            val h = cropBounds.bottom - cropBounds.top
                            val side = kotlin.math.max(w, h).coerceAtLeast(0.05f)
                            val r = (cropBounds.left + side).coerceAtMost(1f)
                            val b = (cropBounds.top + side).coerceAtMost(1f)
                            cropBounds = NormalizedCropBounds(cropBounds.left, cropBounds.top, r, b)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF333333),
                        contentColor = if (isSelected) Color.White else Color.LightGray
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier
                        .height(34.dp)
                        .testTag("scanner_shape_${shape.name.lowercase()}")
                ) {
                    Text(shape.displayName, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Instructions
        val instructionText = when (selectedShape) {
            SelectionShape.CUSTOM -> {
                if (!isPolygonClosed) {
                    if (customVertices.isEmpty()) {
                        "Mode: Custom Polygon. Tap screen to place Point 1."
                    } else if (customVertices.size < 3) {
                        "Tap to place next point (${customVertices.size} placed)."
                    } else {
                        "Tap first point (yellow ring) or 'Join Shape' to close area."
                    }
                } else {
                    "Mode: Custom Polygon. Drag corner handles to reshape, or drag inside to move."
                }
            }
            SelectionShape.CIRCLE -> "Mode: Circle. Drag cardinal handles to resize radius, drag inside to move."
            SelectionShape.SQUARE -> "Mode: Square. Drag corner/edge handles to resize, drag inside to move."
            SelectionShape.RECTANGLE -> "Mode: Rectangle. Drag handles to resize, drag inside to move, or drag outside to redraw."
        }
        Text(
            text = instructionText,
            color = Color.LightGray,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF2A2A2A))
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .background(Color.Black)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged {
                        canvasSize = Size(it.width.toFloat(), it.height.toFloat())
                    }
                    .pointerInput(selectedShape, cropBounds, isPolygonClosed) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val downPos = down.position
                            val cW = size.width.toFloat()
                            val cH = size.height.toFloat()
                            if (cW <= 0f || cH <= 0f) return@awaitEachGesture

                            if (selectedShape == SelectionShape.CUSTOM) {
                                if (!isPolygonClosed) {
                                    // Tap detection for vertex creation
                                    var isDrag = false
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: break
                                        if (change.pressed) {
                                            if (kotlin.math.hypot(change.position.x - downPos.x, change.position.y - downPos.y) > 28.dp.toPx()) {
                                                isDrag = true
                                            }
                                            change.consume()
                                        } else {
                                            break
                                        }
                                    }

                                    if (!isDrag) {
                                        // If >= 3 points and tap is close to Point 0 -> close polygon
                                        if (customVertices.size >= 3) {
                                            val p0 = Offset(customVertices[0].x * cW, customVertices[0].y * cH)
                                            if (kotlin.math.hypot(downPos.x - p0.x, downPos.y - p0.y) <= 44.dp.toPx()) {
                                                isPolygonClosed = true
                                                return@awaitEachGesture
                                            }
                                        }
                                        customVertices = customVertices + NormalizedPoint(
                                            (downPos.x / cW).coerceIn(0f, 1f),
                                            (downPos.y / cH).coerceIn(0f, 1f)
                                        )
                                    }
                                    return@awaitEachGesture
                                } else {
                                    // Polygon is closed: drag handles or move polygon
                                    val hitThreshold = 32.dp.toPx()
                                    var activeHandle = -1
                                    for (i in customVertices.indices) {
                                        val vx = customVertices[i].x * cW
                                        val vy = customVertices[i].y * cH
                                        if (kotlin.math.hypot(downPos.x - vx, downPos.y - vy) <= hitThreshold) {
                                            activeHandle = i
                                            break
                                        }
                                    }

                                    var isMovingEntireSelection = false
                                    if (activeHandle == -1) {
                                        val minX = customVertices.minOf { it.x } * cW
                                        val maxX = customVertices.maxOf { it.x } * cW
                                        val minY = customVertices.minOf { it.y } * cH
                                        val maxY = customVertices.maxOf { it.y } * cH
                                        if (downPos.x in minX..maxX && downPos.y in minY..maxY) {
                                            isMovingEntireSelection = true
                                        }
                                    }

                                    var lastTouchPos = downPos
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: break
                                        if (change.pressed) {
                                            change.consume()
                                            val curPos = change.position

                                            if (activeHandle in customVertices.indices) {
                                                val updated = customVertices.toMutableList()
                                                updated[activeHandle] = NormalizedPoint(
                                                    (curPos.x / cW).coerceIn(0f, 1f),
                                                    (curPos.y / cH).coerceIn(0f, 1f)
                                                )
                                                customVertices = updated
                                            } else if (isMovingEntireSelection) {
                                                val deltaX = (curPos.x - lastTouchPos.x) / cW
                                                val deltaY = (curPos.y - lastTouchPos.y) / cH
                                                customVertices = customVertices.map {
                                                    NormalizedPoint(
                                                        (it.x + deltaX).coerceIn(0f, 1f),
                                                        (it.y + deltaY).coerceIn(0f, 1f)
                                                    )
                                                }
                                            }
                                            lastTouchPos = curPos
                                        } else {
                                            break
                                        }
                                    }
                                    return@awaitEachGesture
                                }
                            }

                            // Standard shapes (Rectangle, Square, Circle)
                            val hitThreshold = 32.dp.toPx()
                            var activeHandle = -1
                            var isMovingEntireSelection = false
                            var isNewSelection = false

                            val leftPx = cropBounds.left * cW
                            val topPx = cropBounds.top * cH
                            val rightPx = cropBounds.right * cW
                            val bottomPx = cropBounds.bottom * cH
                            val midX = (leftPx + rightPx) / 2f
                            val midY = (topPx + bottomPx) / 2f

                            val handleMap = listOf(
                                10 to Offset(leftPx, topPx), // Top-Left
                                11 to Offset(rightPx, topPx), // Top-Right
                                12 to Offset(rightPx, bottomPx), // Bottom-Right
                                13 to Offset(leftPx, bottomPx), // Bottom-Left
                                14 to Offset(midX, topPx), // Top
                                15 to Offset(rightPx, midY), // Right
                                16 to Offset(midX, bottomPx), // Bottom
                                17 to Offset(leftPx, midY) // Left
                            )

                            for ((id, pos) in handleMap) {
                                if (kotlin.math.hypot(downPos.x - pos.x, downPos.y - pos.y) <= hitThreshold) {
                                    activeHandle = id
                                    break
                                }
                            }

                            if (activeHandle == -1) {
                                if (downPos.x in leftPx..rightPx && downPos.y in topPx..bottomPx) {
                                    isMovingEntireSelection = true
                                } else {
                                    isNewSelection = true
                                }
                            }

                            var lastTouchPos = downPos

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (change.pressed) {
                                    change.consume()
                                    val curPos = change.position

                                    if (isNewSelection) {
                                        val newBounds = ScannerSelectionHelper.calculateBounds(
                                            startX = downPos.x,
                                            startY = downPos.y,
                                            endX = curPos.x,
                                            endY = curPos.y,
                                            canvasWidth = cW,
                                            canvasHeight = cH,
                                            shape = selectedShape
                                        )
                                        cropBounds = newBounds
                                    } else if (isMovingEntireSelection) {
                                        val deltaX = (curPos.x - lastTouchPos.x) / cW
                                        val deltaY = (curPos.y - lastTouchPos.y) / cH
                                        val bw = cropBounds.right - cropBounds.left
                                        val bh = cropBounds.bottom - cropBounds.top
                                        val newL = (cropBounds.left + deltaX).coerceIn(0f, 1f - bw)
                                        val newT = (cropBounds.top + deltaY).coerceIn(0f, 1f - bh)
                                        cropBounds = NormalizedCropBounds(newL, newT, newL + bw, newT + bh)
                                    } else if (activeHandle >= 10) {
                                        var l = cropBounds.left
                                        var t = cropBounds.top
                                        var r = cropBounds.right
                                        var b = cropBounds.bottom

                                        val curNormX = (curPos.x / cW).coerceIn(0f, 1f)
                                        val curNormY = (curPos.y / cH).coerceIn(0f, 1f)

                                        when (activeHandle) {
                                            10 -> { l = kotlin.math.min(curNormX, r - 0.05f); t = kotlin.math.min(curNormY, b - 0.05f) }
                                            11 -> { r = kotlin.math.max(curNormX, l + 0.05f); t = kotlin.math.min(curNormY, b - 0.05f) }
                                            12 -> { r = kotlin.math.max(curNormX, l + 0.05f); b = kotlin.math.max(curNormY, t + 0.05f) }
                                            13 -> { l = kotlin.math.min(curNormX, r - 0.05f); b = kotlin.math.max(curNormY, t + 0.05f) }
                                            14 -> { t = kotlin.math.min(curNormY, b - 0.05f) }
                                            15 -> { r = kotlin.math.max(curNormX, l + 0.05f) }
                                            16 -> { b = kotlin.math.max(curNormY, t + 0.05f) }
                                            17 -> { l = kotlin.math.min(curNormX, r - 0.05f) }
                                        }

                                        if (selectedShape == SelectionShape.SQUARE || selectedShape == SelectionShape.CIRCLE) {
                                            val wPx = (r - l) * cW
                                            val hPx = (b - t) * cH
                                            val sidePx = kotlin.math.max(wPx, hPx)
                                            val normSideX = sidePx / cW
                                            val normSideY = sidePx / cH
                                            r = (l + normSideX).coerceAtMost(1f)
                                            b = (t + normSideY).coerceAtMost(1f)
                                        }
                                        cropBounds = NormalizedCropBounds(l, t, r, b)
                                    }
                                    lastTouchPos = curPos
                                } else {
                                    break
                                }
                            }
                        }
                    }
            ) {
                drawImage(
                    image = imageBitmap,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt())
                )

                val cX = cropBounds.left * size.width
                val cY = cropBounds.top * size.height
                val cW = (cropBounds.right - cropBounds.left) * size.width
                val cH = (cropBounds.bottom - cropBounds.top) * size.height

                fun drawAnchorHandle(pos: Offset) {
                    drawCircle(color = Color.White, radius = 9.dp.toPx(), center = pos)
                    drawCircle(color = Color(0xFF00E676), radius = 6.5.dp.toPx(), center = pos)
                }

                if (selectedShape == SelectionShape.CUSTOM) {
                    if (!isPolygonClosed) {
                        // Draw unclosed path connecting points
                        if (customVertices.size >= 2) {
                            val unclosedPath = Path().apply {
                                val p0 = Offset(customVertices[0].x * size.width, customVertices[0].y * size.height)
                                moveTo(p0.x, p0.y)
                                for (i in 1 until customVertices.size) {
                                    val pi = Offset(customVertices[i].x * size.width, customVertices[i].y * size.height)
                                    lineTo(pi.x, pi.y)
                                }
                            }
                            drawPath(
                                path = unclosedPath,
                                color = Color(0xFF00E676),
                                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }

                        // Draw vertex dots
                        for (i in customVertices.indices) {
                            val pos = Offset(customVertices[i].x * size.width, customVertices[i].y * size.height)
                            if (i == 0) {
                                drawCircle(color = Color(0xFF00E676), radius = 10.dp.toPx(), center = pos)
                                drawCircle(color = Color.White, radius = 6.dp.toPx(), center = pos)
                                if (customVertices.size >= 3) {
                                    drawCircle(
                                        color = Color(0xFFFFCC00),
                                        radius = 16.dp.toPx(),
                                        center = pos,
                                        style = Stroke(width = 2.dp.toPx())
                                    )
                                }
                            } else {
                                drawCircle(color = Color.White, radius = 6.dp.toPx(), center = pos)
                                drawCircle(color = Color(0xFF00E676), radius = 4.dp.toPx(), center = pos)
                            }
                        }
                    } else if (customVertices.size >= 3) {
                        val polyPath = Path().apply {
                            val p0 = Offset(customVertices[0].x * size.width, customVertices[0].y * size.height)
                            moveTo(p0.x, p0.y)
                            for (i in 1 until customVertices.size) {
                                val pi = Offset(customVertices[i].x * size.width, customVertices[i].y * size.height)
                                lineTo(pi.x, pi.y)
                            }
                            close()
                        }

                        val cutout = Path().apply {
                            fillType = PathFillType.EvenOdd
                            addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                            addPath(polyPath)
                        }
                        drawPath(cutout, color = Color(0x88000000))
                        drawPath(polyPath, color = Color(0xFF00E676), style = Stroke(width = 2.dp.toPx()))

                        for (v in customVertices) {
                            drawAnchorHandle(Offset(v.x * size.width, v.y * size.height))
                        }
                    }
                } else if (selectedShape == SelectionShape.CIRCLE) {
                    val radius = kotlin.math.min(cW, cH) / 2f
                    val centerX = cX + radius
                    val centerY = cY + radius

                    val cutoutPath = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                        addOval(androidx.compose.ui.geometry.Rect(centerX - radius, centerY - radius, centerX + radius, centerY + radius))
                    }
                    drawPath(cutoutPath, color = Color(0x88000000))

                    drawCircle(
                        color = Color(0xFF00E676),
                        radius = radius,
                        center = Offset(centerX, centerY),
                        style = Stroke(width = 2.dp.toPx())
                    )

                    // Cardinal handles
                    drawAnchorHandle(Offset(centerX, centerY - radius))
                    drawAnchorHandle(Offset(centerX + radius, centerY))
                    drawAnchorHandle(Offset(centerX, centerY + radius))
                    drawAnchorHandle(Offset(centerX - radius, centerY))
                } else {
                    // Dim areas around crop rectangle / square
                    drawRect(Color(0x88000000), Offset.Zero, Size(size.width, cY))
                    drawRect(Color(0x88000000), Offset(0f, cY + cH), Size(size.width, size.height - (cY + cH)))
                    drawRect(Color(0x88000000), Offset(0f, cY), Size(cX, cH))
                    drawRect(Color(0x88000000), Offset(cX + cW, cY), Size(size.width - (cX + cW), cH))

                    // Crop border
                    drawRect(
                        color = Color(0xFF00E676),
                        topLeft = Offset(cX, cY),
                        size = Size(cW, cH),
                        style = Stroke(width = 2.dp.toPx())
                    )

                    // 4 corner handles
                    drawAnchorHandle(Offset(cX, cY))
                    drawAnchorHandle(Offset(cX + cW, cY))
                    drawAnchorHandle(Offset(cX + cW, cY + cH))
                    drawAnchorHandle(Offset(cX, cY + cH))

                    // 4 edge handles
                    drawAnchorHandle(Offset(cX + cW / 2f, cY))
                    drawAnchorHandle(Offset(cX + cW, cY + cH / 2f))
                    drawAnchorHandle(Offset(cX + cW / 2f, cY + cH))
                    drawAnchorHandle(Offset(cX, cY + cH / 2f))
                }
            }

            // Custom polygon action buttons pill overlaid on canvas
            if (selectedShape == SelectionShape.CUSTOM) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp)
                        .background(Color(0xDD1E1E1E), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isPolygonClosed) {
                        Text(
                            text = if (customVertices.isEmpty()) "Tap screen to mark area" else "${customVertices.size} points placed",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
                        if (customVertices.size >= 3) {
                            Button(
                                onClick = { isPolygonClosed = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676), contentColor = Color.Black),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .height(28.dp)
                                    .testTag("scanner_custom_join_button")
                            ) {
                                Text("Join & Close", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (customVertices.isNotEmpty()) {
                            Button(
                                onClick = {
                                    customVertices = emptyList()
                                    isPolygonClosed = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF444444), contentColor = Color.White),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .height(28.dp)
                                    .testTag("scanner_custom_reset_button")
                            ) {
                                Text("Reset", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    } else {
                        Text(
                            text = "Area Marked (${customVertices.size} corners)",
                            color = Color(0xFF00E676),
                            style = MaterialTheme.typography.labelSmall
                        )
                        Button(
                            onClick = {
                                customVertices = emptyList()
                                isPolygonClosed = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF444444), contentColor = Color.White),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier
                                .height(28.dp)
                                .testTag("scanner_custom_redraw_button")
                        ) {
                            Text("Redraw", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        // Dedicated Post-Selection Action Bar (Share, QR Code, OCR)
        Surface(
            color = Color(0xFF1E1E1E),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        val cropped = ScannerSelectionHelper.createCroppedBitmap(
                            bitmap,
                            cropBounds,
                            selectedShape,
                            if (selectedShape == SelectionShape.CUSTOM && isPolygonClosed && customVertices.size >= 3) customVertices else null
                        )
                        ScannerSelectionHelper.shareBitmap(context, cropped)
                    },
                    enabled = if (selectedShape == SelectionShape.CUSTOM) (isPolygonClosed && customVertices.size >= 3) else true,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                    modifier = Modifier.testTag("scanner_share_button")
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share")
                }

                Button(
                    onClick = {
                        Toast.makeText(context, "QR Code scanning coming soon", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("QR Code")
                }

                Button(
                    onClick = {
                        Toast.makeText(context, "OCR coming soon", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("OCR")
                }
            }
        }
    }

    scanResultDialog?.let { result ->
        AlertDialog(
            onDismissRequest = { scanResultDialog = null },
            title = {
                Text(
                    when (result) {
                        is ScannerResult.Success -> "Scan Inspection Result"
                        is ScannerResult.Unavailable -> "Scanner Engine Unavailable"
                        is ScannerResult.Failed -> "Scan Failed"
                    }
                )
            },
            text = {
                Column {
                    Text("Selected Region: $lastCroppedDimensions", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                    Spacer(modifier = Modifier.height(8.dp))
                    when (result) {
                        is ScannerResult.Success -> {
                            Text(result.text, style = MaterialTheme.typography.bodyLarge)
                            if (result.details.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(result.details, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                        }
                        is ScannerResult.Unavailable -> {
                            Text(result.reason, style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Camera frame captured and cropped successfully. Optional barcode/OCR engine can be downloaded separately.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                        is ScannerResult.Failed -> {
                            Text(result.error, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = {
                if (result is ScannerResult.Success) {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Scan Result", result.text))
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        scanResultDialog = null
                    }) {
                        Text("Copy")
                    }
                } else {
                    TextButton(onClick = { scanResultDialog = null }) {
                        Text("OK")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { scanResultDialog = null }) {
                    Text("Dismiss")
                }
            }
        )
    }
}
