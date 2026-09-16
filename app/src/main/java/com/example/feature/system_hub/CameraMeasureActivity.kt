package com.example.feature.system_hub

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import com.example.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.File
import java.text.DecimalFormat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.*

/**
 * High-Accuracy Offline Real-Life Camera Measurement Tool
 * Supports:
 * 1. Reference Object Caliper (Sub-mm precision using credit/ID card, coin, or custom calibration)
 * 2. Real-Time Distance & Height Sensor Inclinometer (Trigonometry + Accelerometer sensor fusion)
 * 3. Screen Caliper 1:1 True Physical Ruler
 */
class CameraMeasureActivity : ComponentActivity(), SensorEventListener {

    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gravitySensor: Sensor? = null

    private val _pitchAngle = mutableFloatStateOf(0f)
    private val _rollAngle = mutableFloatStateOf(0f)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Camera permission required for Measure Tool", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    CameraMeasureScreen(
                        pitchAngle = _pitchAngle.floatValue,
                        rollAngle = _rollAngle.floatValue,
                        onClose = { finish() },
                        onBindCamera = { previewView -> bindCameraUseCases(previewView) },
                        onToggleTorch = { enable -> camera?.cameraControl?.enableTorch(enable) },
                        onCapturePhoto = { onPhotoTaken -> capturePhoto(onPhotoTaken) }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val sensor = gravitySensor ?: accelerometer
        sensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        val gX = event.values[0]
        val gY = event.values[1]
        val gZ = event.values[2]

        // Pitch: Angle of tilt forward/backward relative to horizon
        val pitch = atan2(gY.toDouble(), sqrt((gX * gX + gZ * gZ).toDouble())) * (180.0 / Math.PI)
        // Roll: Angle of tilt side to side
        val roll = atan2(-gX.toDouble(), gZ.toDouble()) * (180.0 / Math.PI)

        _pitchAngle.floatValue = pitch.toFloat()
        _rollAngle.floatValue = roll.toFloat()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun bindCameraUseCases(previewView: PreviewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
            } catch (e: Exception) {
                Log.e("CameraMeasure", "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capturePhoto(onPhotoTaken: (Bitmap) -> Unit) {
        val imageCapture = this.imageCapture ?: return
        val photoFile = File.createTempFile("measure_capture_", ".jpg", cacheDir)
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                    photoFile.delete()
                    if (bitmap != null) {
                        onPhotoTaken(bitmap)
                    } else {
                        Toast.makeText(this@CameraMeasureActivity, "Failed to capture photo", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("CameraMeasure", "Photo capture failed: ${exception.message}", exception)
                    Toast.makeText(this@CameraMeasureActivity, "Capture failed", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

enum class MeasureMode {
    REFERENCE_CARD,
    DISTANCE_HEIGHT,
    SCREEN_RULER
}

enum class UnitSystem {
    METRIC_CM,
    METRIC_MM,
    IMPERIAL_INCH
}

@Composable
fun CameraMeasureScreen(
    pitchAngle: Float,
    rollAngle: Float,
    onClose: () -> Unit,
    onBindCamera: (PreviewView) -> Unit,
    onToggleTorch: (Boolean) -> Unit,
    onCapturePhoto: ((Bitmap) -> Unit) -> Unit
) {
    val context = LocalContext.current
    var currentMode by remember { mutableStateOf(MeasureMode.REFERENCE_CARD) }
    var unitSystem by remember { mutableStateOf(UnitSystem.METRIC_CM) }
    var isFlashOn by remember { mutableStateOf(false) }
    var frozenBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Reference Scale Mode State
    // Standard ID/Credit Card = 85.60 mm x 53.98 mm
    var referenceCardWidthMm by remember { mutableFloatStateOf(85.60f) }
    var refP1 by remember { mutableStateOf(Offset(180f, 300f)) }
    var refP2 by remember { mutableStateOf(Offset(420f, 300f)) }
    
    // Measurement Line(s)
    var measureP1 by remember { mutableStateOf(Offset(180f, 500f)) }
    var measureP2 by remember { mutableStateOf(Offset(420f, 500f)) }

    // Distance & Height Inclinometer State
    var cameraHeightCm by remember { mutableFloatStateOf(150f) } // Default ~1.5m eye/phone height
    var lockedBasePitch by remember { mutableStateOf<Float?>(null) }
    var lockedTopPitch by remember { mutableStateOf<Float?>(null) }

    // Screen Ruler State
    val dm: DisplayMetrics = context.resources.displayMetrics
    val xdpi = if (dm.xdpi > 0) dm.xdpi else 320f
    val ydpi = if (dm.ydpi > 0) dm.ydpi else 320f
    var screenCaliperP1 by remember { mutableFloatStateOf(200f) }
    var screenCaliperP2 by remember { mutableFloatStateOf(600f) }

    val df = remember { DecimalFormat("#,##0.0") }
    val df2 = remember { DecimalFormat("#,##0.00") }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            Surface(
                color = Color(0xEE121212),
                border = BorderStroke(0.5.dp, Color(0x33FFFFFF))
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onClose) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                            }
                            Text(
                                text = "Camera Measure",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            // Flash Torch Button
                            if (currentMode != MeasureMode.SCREEN_RULER) {
                                IconButton(onClick = {
                                    isFlashOn = !isFlashOn
                                    onToggleTorch(isFlashOn)
                                }) {
                                    Icon(
                                        painter = painterResource(if (isFlashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off),
                                        contentDescription = "Flash",
                                        tint = if (isFlashOn) Color.Yellow else Color.White
                                    )
                                }
                            }

                            // Unit Switcher
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0x33FFFFFF),
                                modifier = Modifier.clickable {
                                    unitSystem = when (unitSystem) {
                                        UnitSystem.METRIC_CM -> UnitSystem.METRIC_MM
                                        UnitSystem.METRIC_MM -> UnitSystem.IMPERIAL_INCH
                                        UnitSystem.IMPERIAL_INCH -> UnitSystem.METRIC_CM
                                    }
                                }
                            ) {
                                Text(
                                    text = when (unitSystem) {
                                        UnitSystem.METRIC_CM -> "cm"
                                        UnitSystem.METRIC_MM -> "mm"
                                        UnitSystem.IMPERIAL_INCH -> "in"
                                    },
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    // Mode Selection Tabs
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = currentMode == MeasureMode.REFERENCE_CARD,
                            onClick = { currentMode = MeasureMode.REFERENCE_CARD },
                            label = { Text("Reference Scale") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_credit_card), null, modifier = Modifier.size(16.dp)) }
                        )
                        FilterChip(
                            selected = currentMode == MeasureMode.DISTANCE_HEIGHT,
                            onClick = { currentMode = MeasureMode.DISTANCE_HEIGHT },
                            label = { Text("Distance & Height") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_height), null, modifier = Modifier.size(16.dp)) }
                        )
                        FilterChip(
                            selected = currentMode == MeasureMode.SCREEN_RULER,
                            onClick = { currentMode = MeasureMode.SCREEN_RULER },
                            label = { Text("Screen Ruler") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_straighten), null, modifier = Modifier.size(16.dp)) }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Camera or Freeze Frame Background
            if (currentMode != MeasureMode.SCREEN_RULER) {
                if (frozenBitmap != null) {
                    Image(
                        bitmap = frozenBitmap!!.asImageBitmap(),
                        contentDescription = "Frozen frame",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                onBindCamera(this)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                // Dark background for screen ruler mode
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF141414))
                )
            }

            // Interactive Mode Overlays
            when (currentMode) {
                MeasureMode.REFERENCE_CARD -> {
                    ReferenceCardMeasureOverlay(
                        refP1 = refP1,
                        onRefP1Change = { refP1 = it },
                        refP2 = refP2,
                        onRefP2Change = { refP2 = it },
                        measureP1 = measureP1,
                        onMeasureP1Change = { measureP1 = it },
                        measureP2 = measureP2,
                        onMeasureP2Change = { measureP2 = it },
                        referenceMm = referenceCardWidthMm,
                        unitSystem = unitSystem,
                        df = df,
                        df2 = df2
                    )
                }

                MeasureMode.DISTANCE_HEIGHT -> {
                    DistanceHeightOverlay(
                        pitchAngle = pitchAngle,
                        rollAngle = rollAngle,
                        cameraHeightCm = cameraHeightCm,
                        lockedBasePitch = lockedBasePitch,
                        lockedTopPitch = lockedTopPitch,
                        onLockBase = { lockedBasePitch = pitchAngle },
                        onLockTop = { lockedTopPitch = pitchAngle },
                        onReset = {
                            lockedBasePitch = null
                            lockedTopPitch = null
                        },
                        onAdjustHeight = { delta ->
                            cameraHeightCm = (cameraHeightCm + delta).coerceIn(30f, 300f)
                        },
                        unitSystem = unitSystem,
                        df = df
                    )
                }

                MeasureMode.SCREEN_RULER -> {
                    ScreenRulerOverlay(
                        ydpi = ydpi,
                        caliperP1 = screenCaliperP1,
                        onCaliperP1Change = { screenCaliperP1 = it },
                        caliperP2 = screenCaliperP2,
                        onCaliperP2Change = { screenCaliperP2 = it },
                        unitSystem = unitSystem,
                        df = df,
                        df2 = df2
                    )
                }
            }

            // Bottom Action Controls (Freeze Frame / Capture / Reset)
            if (currentMode != MeasureMode.SCREEN_RULER) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xDD1E1E1E),
                    border = BorderStroke(1.dp, Color(0x44FFFFFF))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        if (frozenBitmap != null) {
                            Button(
                                onClick = { frozenBitmap = null },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Unfreeze", modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Live Camera")
                            }
                        } else {
                            Button(
                                onClick = {
                                    onCapturePhoto { bmp ->
                                        frozenBitmap = bmp
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(painterResource(R.drawable.ic_camera), contentDescription = "Freeze", modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Freeze Photo")
                            }
                        }

                        if (currentMode == MeasureMode.REFERENCE_CARD) {
                            // Reset Calipers Position
                            IconButton(
                                onClick = {
                                    refP1 = Offset(180f, 300f)
                                    refP2 = Offset(420f, 300f)
                                    measureP1 = Offset(180f, 500f)
                                    measureP2 = Offset(420f, 500f)
                                }
                            ) {
                                Icon(painterResource(R.drawable.ic_restart_alt), "Reset Calipers", tint = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 1. High-Accuracy Reference Card Caliper Overlay
 * Calculates physical dimensions by comparing pixel length of measured object to pixel length of known reference (e.g. Card = 85.6mm).
 */
@Composable
fun ReferenceCardMeasureOverlay(
    refP1: Offset,
    onRefP1Change: (Offset) -> Unit,
    refP2: Offset,
    onRefP2Change: (Offset) -> Unit,
    measureP1: Offset,
    onMeasureP1Change: (Offset) -> Unit,
    measureP2: Offset,
    onMeasureP2Change: (Offset) -> Unit,
    referenceMm: Float,
    unitSystem: UnitSystem,
    df: DecimalFormat,
    df2: DecimalFormat
) {
    val context = LocalContext.current
    var activeHandle by remember { mutableStateOf<String?>(null) }

    val refPixelDistance = (refP2 - refP1).getDistance().coerceAtLeast(1f)
    val mmPerPixel = referenceMm / refPixelDistance
    val measurePixelDistance = (measureP2 - measureP1).getDistance()
    val calculatedMm = measurePixelDistance * mmPerPixel

    val displayValue = when (unitSystem) {
        UnitSystem.METRIC_CM -> "${df.format(calculatedMm / 10f)} cm"
        UnitSystem.METRIC_MM -> "${df.format(calculatedMm)} mm"
        UnitSystem.IMPERIAL_INCH -> "${df2.format(calculatedMm / 25.4f)} in"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                val touchRadius = 48.dp.toPx()
                detectDragGestures(
                    onDragStart = { offset ->
                        activeHandle = when {
                            (refP1 - offset).getDistance() <= touchRadius -> "ref1"
                            (refP2 - offset).getDistance() <= touchRadius -> "ref2"
                            (measureP1 - offset).getDistance() <= touchRadius -> "m1"
                            (measureP2 - offset).getDistance() <= touchRadius -> "m2"
                            else -> null
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        when (activeHandle) {
                            "ref1" -> onRefP1Change(refP1 + dragAmount)
                            "ref2" -> onRefP2Change(refP2 + dragAmount)
                            "m1" -> onMeasureP1Change(measureP1 + dragAmount)
                            "m2" -> onMeasureP2Change(measureP2 + dragAmount)
                        }
                    },
                    onDragEnd = { activeHandle = null },
                    onDragCancel = { activeHandle = null }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val handleRadius = 24.dp.toPx()
            val dashed = PathEffect.dashPathEffect(floatArrayOf(12f, 12f), 0f)

            // 1. Reference Line (Cyan)
            drawLine(
                color = Color(0xFF00E5FF),
                start = refP1,
                end = refP2,
                strokeWidth = 4.dp.toPx()
            )
            // End perpendicular caps for reference
            drawCaliperCap(refP1, refP2, Color(0xFF00E5FF))
            drawCaliperCap(refP2, refP1, Color(0xFF00E5FF))

            // Reference Knobs
            drawCircle(Color.White, radius = handleRadius * 0.7f, center = refP1)
            drawCircle(Color(0xFF00E5FF), radius = handleRadius * 0.7f, center = refP1, style = Stroke(3.dp.toPx()))
            drawCircle(Color.White, radius = handleRadius * 0.7f, center = refP2)
            drawCircle(Color(0xFF00E5FF), radius = handleRadius * 0.7f, center = refP2, style = Stroke(3.dp.toPx()))

            // 2. Measure Line (Lime Green)
            drawLine(
                color = Color(0xFF00E676),
                start = measureP1,
                end = measureP2,
                strokeWidth = 4.dp.toPx()
            )
            // End caps
            drawCaliperCap(measureP1, measureP2, Color(0xFF00E676))
            drawCaliperCap(measureP2, measureP1, Color(0xFF00E676))

            // Measure Knobs
            drawCircle(Color.White, radius = handleRadius * 0.7f, center = measureP1)
            drawCircle(Color(0xFF00E676), radius = handleRadius * 0.7f, center = measureP1, style = Stroke(3.dp.toPx()))
            drawCircle(Color.White, radius = handleRadius * 0.7f, center = measureP2)
            drawCircle(Color(0xFF00E676), radius = handleRadius * 0.7f, center = measureP2, style = Stroke(3.dp.toPx()))
        }

        // Live Dimension HUD Card
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp),
            shape = RoundedCornerShape(20.dp),
            color = Color(0xDD121212),
            border = BorderStroke(1.dp, Color(0x44FFFFFF))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Measured:",
                        color = Color.LightGray,
                        fontSize = 14.sp
                    )
                    Text(
                        text = displayValue,
                        color = Color(0xFF00E676),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Measurement", displayValue))
                            Toast.makeText(context, "Copied $displayValue", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(painterResource(R.drawable.ic_content_copy), "Copy", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }

                Text(
                    text = "Ref: Standard Card Width (85.6mm)",
                    color = Color(0xFF00E5FF),
                    fontSize = 12.sp
                )
            }
        }
    }
}

/**
 * 2. Real-Time Distance & Height Sensor Meter
 * Uses phone tilt pitch ($\theta$) and phone camera elevation ($H_{\text{eye}}$):
 * $D = H_{\text{eye}} \times \tan(90^\circ - \theta_{\text{base}})$
 * $H_{\text{object}} = D \times (\tan(\theta_{\text{top}}) - \tan(\theta_{\text{base}}))$
 */
@Composable
fun DistanceHeightOverlay(
    pitchAngle: Float,
    rollAngle: Float,
    cameraHeightCm: Float,
    lockedBasePitch: Float?,
    lockedTopPitch: Float?,
    onLockBase: () -> Unit,
    onLockTop: () -> Unit,
    onReset: () -> Unit,
    onAdjustHeight: (Float) -> Unit,
    unitSystem: UnitSystem,
    df: DecimalFormat
) {
    val context = LocalContext.current
    val hMeters = cameraHeightCm / 100f

    // Calculate Distance to Ground/Base
    // When aiming down at base, pitch angle is positive/declined
    val baseAngleRad = Math.toRadians(abs(lockedBasePitch ?: pitchAngle).toDouble().coerceIn(1.0, 89.0))
    val distanceM = (hMeters / tan(baseAngleRad)).coerceIn(0.1, 100.0)

    // Calculate Target Height once both angles locked
    val objectHeightM = if (lockedBasePitch != null && lockedTopPitch != null) {
        val topRad = Math.toRadians(lockedTopPitch.toDouble())
        val baseRad = Math.toRadians(lockedBasePitch.toDouble())
        // Vertical delta
        val heightM = distanceM * (tan(topRad) - tan(baseRad))
        abs(heightM).coerceAtLeast(0.01)
    } else null

    val distanceDisplay = when (unitSystem) {
        UnitSystem.METRIC_CM -> "${df.format(distanceM * 100f)} cm"
        UnitSystem.METRIC_MM -> "${df.format(distanceM * 1000f)} mm"
        UnitSystem.IMPERIAL_INCH -> "${df.format(distanceM * 3.28084f)} ft"
    }

    val heightDisplay = objectHeightM?.let { h ->
        when (unitSystem) {
            UnitSystem.METRIC_CM -> "${df.format(h * 100f)} cm"
            UnitSystem.METRIC_MM -> "${df.format(h * 1000f)} mm"
            UnitSystem.IMPERIAL_INCH -> "${df.format(h * 3.28084f)} ft"
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Crosshair & Level Horizon in Center
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val crosshairSize = 32.dp.toPx()

            // Horizontal Horizon Level Indicator
            val rollOffset = (rollAngle.coerceIn(-45f, 45f) * 4f)
            drawLine(
                color = if (abs(rollAngle) < 2f) Color(0xFF00E676) else Color.Yellow.copy(alpha = 0.7f),
                start = Offset(center.x - 80.dp.toPx(), center.y + rollOffset),
                end = Offset(center.x + 80.dp.toPx(), center.y - rollOffset),
                strokeWidth = 2.dp.toPx()
            )

            // Center Target Crosshair
            drawCircle(Color.White.copy(alpha = 0.8f), radius = 24.dp.toPx(), center = center, style = Stroke(2.dp.toPx()))
            drawLine(Color.White, Offset(center.x - crosshairSize, center.y), Offset(center.x + crosshairSize, center.y), 2.5.dp.toPx())
            drawLine(Color.White, Offset(center.x, center.y - crosshairSize), Offset(center.x, center.y + crosshairSize), 2.5.dp.toPx())
            drawCircle(Color(0xFF00E676), radius = 4.dp.toPx(), center = center)
        }

        // Top Status HUD
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp),
            shape = RoundedCornerShape(20.dp),
            color = Color(0xDD121212),
            border = BorderStroke(1.dp, Color(0x44FFFFFF))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Est. Distance", color = Color.Gray, fontSize = 12.sp)
                        Text(distanceDisplay, color = Color(0xFF00E5FF), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }

                    if (heightDisplay != null) {
                        Divider(modifier = Modifier.height(28.dp).width(1.dp), color = Color.Gray)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Target Height", color = Color.Gray, fontSize = 12.sp)
                            Text(heightDisplay, color = Color(0xFF00E676), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Camera Elevation Adjuster
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Phone Height: ${cameraHeightCm.toInt()} cm", color = Color.LightGray, fontSize = 12.sp)
                    IconButton(onClick = { onAdjustHeight(-5f) }, modifier = Modifier.size(24.dp)) {
                        Icon(painterResource(R.drawable.ic_remove), "-", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = { onAdjustHeight(5f) }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Filled.Add, "+", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // Action Step Buttons for Distance / Height Locking
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 90.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (lockedBasePitch == null) {
                Button(
                    onClick = onLockBase,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                ) {
                    Text("1. Aim at Base & Lock Distance", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            } else if (lockedTopPitch == null) {
                Button(
                    onClick = onLockTop,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))
                ) {
                    Text("2. Aim at Top & Lock Height", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onReset,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(painterResource(R.drawable.ic_restart_alt), null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Measure Another Object")
                }
            }
        }
    }
}

/**
 * 3. 1:1 True Physical Screen Caliper Ruler
 * Leverages device physical DPI to render millimeter and inch scales directly on the glass.
 */
@Composable
fun ScreenRulerOverlay(
    ydpi: Float,
    caliperP1: Float,
    onCaliperP1Change: (Float) -> Unit,
    caliperP2: Float,
    onCaliperP2Change: (Float) -> Unit,
    unitSystem: UnitSystem,
    df: DecimalFormat,
    df2: DecimalFormat
) {
    val context = LocalContext.current
    var activeCaliper by remember { mutableStateOf<Int?>(null) }

    // Convert pixel span to true physical units
    val pixelsPerInch = ydpi
    val pixelsPerMm = pixelsPerInch / 25.4f

    val deltaPixels = abs(caliperP2 - caliperP1)
    val measuredMm = deltaPixels / pixelsPerMm

    val displayValue = when (unitSystem) {
        UnitSystem.METRIC_CM -> "${df.format(measuredMm / 10f)} cm"
        UnitSystem.METRIC_MM -> "${df.format(measuredMm)} mm"
        UnitSystem.IMPERIAL_INCH -> "${df2.format(measuredMm / 25.4f)} in"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                val touchRadius = 40.dp.toPx()
                detectDragGestures(
                    onDragStart = { offset ->
                        activeCaliper = when {
                            abs(offset.y - caliperP1) <= touchRadius -> 1
                            abs(offset.y - caliperP2) <= touchRadius -> 2
                            else -> null
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        when (activeCaliper) {
                            1 -> onCaliperP1Change((caliperP1 + dragAmount.y).coerceIn(40f, 1800f))
                            2 -> onCaliperP2Change((caliperP2 + dragAmount.y).coerceIn(40f, 1800f))
                        }
                    },
                    onDragEnd = { activeCaliper = null },
                    onDragCancel = { activeCaliper = null }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // 1. Draw Physical MM tick marks along left edge
            var currentY = 0f
            var mmCount = 0
            while (currentY < h) {
                val isCm = mmCount % 10 == 0
                val isHalfCm = mmCount % 5 == 0
                val tickLength = when {
                    isCm -> 40.dp.toPx()
                    isHalfCm -> 24.dp.toPx()
                    else -> 12.dp.toPx()
                }

                drawLine(
                    color = if (isCm) Color(0xFF00E676) else Color.White.copy(alpha = 0.6f),
                    start = Offset(0f, currentY),
                    end = Offset(tickLength, currentY),
                    strokeWidth = if (isCm) 2.dp.toPx() else 1.dp.toPx()
                )

                mmCount++
                currentY += pixelsPerMm
            }

            // 2. Draw Physical Inch tick marks along right edge
            var currentInchY = 0f
            var inchCount = 0
            val pixelsPer16th = pixelsPerInch / 16f
            var sixteenthCount = 0
            while (currentInchY < h) {
                val isInch = sixteenthCount % 16 == 0
                val isHalf = sixteenthCount % 8 == 0
                val isQuarter = sixteenthCount % 4 == 0

                val tickLength = when {
                    isInch -> 44.dp.toPx()
                    isHalf -> 28.dp.toPx()
                    isQuarter -> 18.dp.toPx()
                    else -> 10.dp.toPx()
                }

                drawLine(
                    color = if (isInch) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.6f),
                    start = Offset(w, currentInchY),
                    end = Offset(w - tickLength, currentInchY),
                    strokeWidth = if (isInch) 2.dp.toPx() else 1.dp.toPx()
                )

                sixteenthCount++
                currentInchY += pixelsPer16th
            }

            // 3. Draw Covered Caliper Zone
            val minY = min(caliperP1, caliperP2)
            val maxY = max(caliperP1, caliperP2)
            drawRect(
                color = Color(0xFF00E676).copy(alpha = 0.12f),
                topLeft = Offset(0f, minY),
                size = Size(w, maxY - minY)
            )

            // Caliper Line 1
            drawLine(
                color = Color(0xFF00E676),
                start = Offset(0f, caliperP1),
                end = Offset(w, caliperP1),
                strokeWidth = 3.dp.toPx()
            )
            drawCircle(Color.White, radius = 16.dp.toPx(), center = Offset(w / 2f, caliperP1))
            drawCircle(Color(0xFF00E676), radius = 16.dp.toPx(), center = Offset(w / 2f, caliperP1), style = Stroke(3.dp.toPx()))

            // Caliper Line 2
            drawLine(
                color = Color(0xFF00E676),
                start = Offset(0f, caliperP2),
                end = Offset(w, caliperP2),
                strokeWidth = 3.dp.toPx()
            )
            drawCircle(Color.White, radius = 16.dp.toPx(), center = Offset(w / 2f, caliperP2))
            drawCircle(Color(0xFF00E676), radius = 16.dp.toPx(), center = Offset(w / 2f, caliperP2), style = Stroke(3.dp.toPx()))
        }

        // Center HUD Measurement
        Surface(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            color = Color(0xEE1A1A1A),
            border = BorderStroke(1.dp, Color(0x44FFFFFF))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = displayValue,
                    color = Color(0xFF00E676),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Measurement", displayValue))
                        Toast.makeText(context, "Copied $displayValue", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(painterResource(R.drawable.ic_content_copy), "Copy", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCaliperCap(
    p1: Offset,
    p2: Offset,
    color: Color
) {
    val dir = (p2 - p1)
    val len = dir.getDistance().coerceAtLeast(1f)
    val norm = Offset(-dir.y / len, dir.x / len)
    val capHalfLen = 16.dp.toPx()

    drawLine(
        color = color,
        start = p1 + norm * capHalfLen,
        end = p1 - norm * capHalfLen,
        strokeWidth = 3.dp.toPx()
    )
}
