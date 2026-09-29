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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.res.painterResource
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
    var cropLeft by remember { mutableStateOf(0.15f) }
    var cropTop by remember { mutableStateOf(0.25f) }
    var cropRight by remember { mutableStateOf(0.85f) }
    var cropBottom by remember { mutableStateOf(0.65f) }

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
                IconButton(onClick = onRetake) {
                    Icon(Icons.Default.Refresh, contentDescription = "Retake", tint = Color.White)
                }
                Button(
                    onClick = {
                        val bw = bitmap.width
                        val bh = bitmap.height
                        val x = (cropLeft * bw).toInt().coerceIn(0, bw - 1)
                        val y = (cropTop * bh).toInt().coerceIn(0, bh - 1)
                        val w = ((cropRight - cropLeft) * bw).toInt().coerceIn(1, bw - x)
                        val h = ((cropBottom - cropTop) * bh).toInt().coerceIn(1, bh - y)

                        val cropped = Bitmap.createBitmap(bitmap, x, y, w, h)
                        lastCroppedDimensions = "${w}x${h} px"
                        val result = ScannerCapabilityBridge.scanBitmap(cropped)
                        scanResultDialog = result
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Inspect Crop")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E1E1E))
        )

        Text(
            text = "Select area containing QR / Barcode / Text to inspect.",
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
                    .pointerInput(Unit) {
                        var start = Offset.Zero
                        detectDragGestures(
                            onDragStart = { offset ->
                                start = offset
                                cropLeft = (offset.x / size.width).coerceIn(0f, 1f)
                                cropTop = (offset.y / size.height).coerceIn(0f, 1f)
                                cropRight = cropLeft
                                cropBottom = cropTop
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val curX = change.position.x
                                val curY = change.position.y
                                cropLeft = (min(start.x, curX) / size.width).coerceIn(0f, 1f)
                                cropTop = (min(start.y, curY) / size.height).coerceIn(0f, 1f)
                                cropRight = (max(start.x, curX) / size.width).coerceIn(0f, 1f)
                                cropBottom = (max(start.y, curY) / size.height).coerceIn(0f, 1f)
                            }
                        )
                    }
            ) {
                drawImage(
                    image = imageBitmap,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt())
                )

                val cX = cropLeft * size.width
                val cY = cropTop * size.height
                val cW = (cropRight - cropLeft) * size.width
                val cH = (cropBottom - cropTop) * size.height

                drawRect(Color(0x88000000), Offset.Zero, Size(size.width, cY))
                drawRect(Color(0x88000000), Offset(0f, cY + cH), Size(size.width, size.height - (cY + cH)))
                drawRect(Color(0x88000000), Offset(0f, cY), Size(cX, cH))
                drawRect(Color(0x88000000), Offset(cX + cW, cY), Size(size.width - (cX + cW), cH))

                drawRect(
                    color = Color(0xFF00E676),
                    topLeft = Offset(cX, cY),
                    size = Size(cW, cH),
                    style = Stroke(width = 2.dp.toPx())
                )
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
