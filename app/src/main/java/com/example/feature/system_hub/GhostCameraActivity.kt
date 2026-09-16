package com.example.feature.system_hub

import android.Manifest
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
import android.util.Log
import android.view.Surface
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.*

/**
 * HD Arrangement Checker / Ghost Camera Utility
 *
 * Allows users to snap high-definition reference photos of any scene, arrangement, or workspace.
 * When returning, the user can overlay the reference photo with adjustable opacity, difference blending,
 * and live accelerometer/gravity tilt guidance to ensure exact matching and spot moved or missing items.
 *
 * Reference photos are saved privately in internal app storage and displayed in a bottom card reel.
 * Long pressing any card deletes it.
 */
class GhostCameraActivity : ComponentActivity(), SensorEventListener {

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
            Toast.makeText(this, "Camera permission required for Arrangement Checker", Toast.LENGTH_LONG).show()
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
                    GhostCameraScreen(
                        pitchAngle = _pitchAngle.floatValue,
                        rollAngle = _rollAngle.floatValue,
                        onClose = { finish() },
                        onBindCamera = { previewView -> bindCameraUseCases(previewView) },
                        onToggleTorch = { enable -> camera?.cameraControl?.enableTorch(enable) },
                        onCaptureHdPhoto = { onSaved -> captureHdPhoto(onSaved) }
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

        // Pitch & Roll in degrees
        val pitch = atan2(gY.toDouble(), sqrt((gX * gX + gZ * gZ).toDouble())) * (180.0 / Math.PI)
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
                // High Definition Image Capture
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
            } catch (e: Exception) {
                Log.e("GhostCamera", "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureHdPhoto(onSaved: (File, Float, Float) -> Unit) {
        val imageCapture = this.imageCapture ?: return
        val storageDir = File(filesDir, "arrangement_refs").apply { mkdirs() }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val photoFile = File(storageDir, "REF_$timeStamp.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        val capturedPitch = _pitchAngle.floatValue
        val capturedRoll = _rollAngle.floatValue

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    onSaved(photoFile, capturedPitch, capturedRoll)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("GhostCamera", "Photo capture failed: ${exception.message}", exception)
                    Toast.makeText(this@GhostCameraActivity, "Capture failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

data class ReferenceItem(
    val id: String,
    val file: File,
    val pitch: Float,
    val roll: Float,
    val timestamp: Long,
    val bitmap: Bitmap? = null
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GhostCameraScreen(
    pitchAngle: Float,
    rollAngle: Float,
    onClose: () -> Unit,
    onBindCamera: (PreviewView) -> Unit,
    onToggleTorch: (Boolean) -> Unit,
    onCaptureHdPhoto: ((File, Float, Float) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isFlashOn by remember { mutableStateOf(false) }

    val references = remember { mutableStateListOf<ReferenceItem>() }
    var selectedIndex by remember { mutableIntStateOf(-1) }
    var ghostOpacity by remember { mutableFloatStateOf(0.5f) }
    var isBlinkActive by remember { mutableStateOf(false) }
    var blinkVisible by remember { mutableStateOf(true) }
    var isCapturing by remember { mutableStateOf(false) }

    // Dialog state for long-press delete
    var itemToDelete by remember { mutableStateOf<ReferenceItem?>(null) }

    // Load saved references on startup
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val storageDir = File(context.filesDir, "arrangement_refs").apply { mkdirs() }
            val metaFile = File(storageDir, "metadata.json")
            val metaMap = mutableMapOf<String, Pair<Float, Float>>()

            if (metaFile.exists()) {
                try {
                    val jsonStr = metaFile.readText()
                    val array = JSONArray(jsonStr)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val name = obj.optString("fileName")
                        val p = obj.optDouble("pitch", 0.0).toFloat()
                        val r = obj.optDouble("roll", 0.0).toFloat()
                        if (name.isNotEmpty()) {
                            metaMap[name] = Pair(p, r)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("GhostCamera", "Error reading metadata", e)
                }
            }

            val files = storageDir.listFiles { file -> file.name.endsWith(".jpg") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()

            val loadedList = files.map { file ->
                val meta = metaMap[file.name]
                val p = meta?.first ?: 0f
                val r = meta?.second ?: 0f
                val bmp = decodeSampledBitmap(file.absolutePath, 400, 400)
                ReferenceItem(
                    id = file.name,
                    file = file,
                    pitch = p,
                    roll = r,
                    timestamp = file.lastModified(),
                    bitmap = bmp
                )
            }

            withContext(Dispatchers.Main) {
                references.clear()
                references.addAll(loadedList)
                if (references.isNotEmpty()) {
                    selectedIndex = 0
                }
            }
        }
    }

    // Function to persist metadata
    fun saveMetadata() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val storageDir = File(context.filesDir, "arrangement_refs").apply { mkdirs() }
                val metaFile = File(storageDir, "metadata.json")
                val array = JSONArray()
                references.forEach { item ->
                    val obj = JSONObject().apply {
                        put("fileName", item.file.name)
                        put("pitch", item.pitch.toDouble())
                        put("roll", item.roll.toDouble())
                        put("timestamp", item.timestamp)
                    }
                    array.put(obj)
                }
                metaFile.writeText(array.toString())
            } catch (e: Exception) {
                Log.e("GhostCamera", "Error saving metadata", e)
            }
        }
    }

    // A/B Blink toggle loop
    LaunchedEffect(isBlinkActive) {
        if (isBlinkActive) {
            while (isBlinkActive) {
                blinkVisible = !blinkVisible
                delay(380)
            }
            blinkVisible = true
        } else {
            blinkVisible = true
        }
    }

    val activeReference = references.getOrNull(selectedIndex)

    // Calculate angle diff to guide user
    val targetPitch = activeReference?.pitch
    val targetRoll = activeReference?.roll

    val pitchDiff = if (targetPitch != null) pitchAngle - targetPitch else 0f
    val rollDiff = if (targetRoll != null) rollAngle - targetRoll else 0f
    val isAngleMatched = activeReference != null && abs(pitchDiff) <= 3.5f && abs(rollDiff) <= 3.5f

    // Guidance text
    val guidanceMessage: String = when {
        activeReference == null -> "Snap a reference photo to begin comparing"
        isAngleMatched -> "✓ Alignment Matched! Angle is exact"
        abs(pitchDiff) > abs(rollDiff) -> {
            if (pitchDiff > 0) "Tilt phone forward ${abs(pitchDiff).roundToInt()}°"
            else "Tilt phone back ${abs(pitchDiff).roundToInt()}°"
        }
        else -> {
            if (rollDiff > 0) "Rotate phone left ${abs(rollDiff).roundToInt()}°"
            else "Rotate phone right ${abs(rollDiff).roundToInt()}°"
        }
    }

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
                                text = "Arrangement Checker",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            // Flash Torch Button
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

                            // A/B Blink Button
                            if (activeReference != null) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isBlinkActive) Color(0xFF00E676) else Color(0x33FFFFFF),
                                    modifier = Modifier.clickable { isBlinkActive = !isBlinkActive }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_visibility),
                                            contentDescription = "Blink",
                                            tint = if (isBlinkActive) Color.Black else Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = if (isBlinkActive) "Blinking" else "A/B Blink",
                                            color = if (isBlinkActive) Color.Black else Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Live Angle Guidance Banner
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = if (isAngleMatched) Color(0x3300E676) else Color(0x33FFA000),
                        border = BorderStroke(1.dp, if (isAngleMatched) Color(0xFF00E676) else Color(0xFFAAAAAA))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                painter = painterResource(if (isAngleMatched) R.drawable.ic_check_circle else R.drawable.ic_screen_rotation),
                                contentDescription = null,
                                tint = if (isAngleMatched) Color(0xFF00E676) else Color(0xFFFFCA28),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = guidanceMessage,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
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
            // 1. Live Camera Viewfinder
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        onBindCamera(this)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // 2. Ghost Overlay Image of Selected Reference
            if (activeReference?.file != null && activeReference.file.exists()) {
                val fullBmp = remember(activeReference.file.absolutePath) {
                    BitmapFactory.decodeFile(activeReference.file.absolutePath)
                }

                if (fullBmp != null && blinkVisible) {
                    Image(
                        bitmap = fullBmp.asImageBitmap(),
                        contentDescription = "Ghost Reference",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        alpha = if (isBlinkActive) 1f else ghostOpacity
                    )
                }
            }

            // 3. Center Crosshair & Level Alignment Target
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val ringRadius = 36.dp.toPx()

                // Target Ring
                drawCircle(
                    color = if (isAngleMatched) Color(0xFF00E676) else Color.White.copy(alpha = 0.5f),
                    radius = ringRadius,
                    center = center,
                    style = Stroke(width = 2.5.dp.toPx())
                )

                // Level Horizon Line
                val rollOffset = (rollDiff.coerceIn(-30f, 30f) * 3f)
                drawLine(
                    color = if (abs(rollDiff) <= 3f) Color(0xFF00E676) else Color(0xFFFFCA28),
                    start = Offset(center.x - 60.dp.toPx(), center.y + rollOffset),
                    end = Offset(center.x + 60.dp.toPx(), center.y - rollOffset),
                    strokeWidth = 2.dp.toPx()
                )

                // Pitch Indicator Dot
                val pitchOffset = (pitchDiff.coerceIn(-30f, 30f) * 3f)
                drawCircle(
                    color = if (isAngleMatched) Color(0xFF00E676) else Color(0xFFFF5252),
                    radius = 5.dp.toPx(),
                    center = Offset(center.x, center.y + pitchOffset)
                )
            }

            // 4. Bottom Controls: Opacity Slider + Saved Cards Reel
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xDD121212))
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Opacity Adjuster (shown when a reference is active and not blinking)
                if (activeReference != null && !isBlinkActive) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Ghost Opacity:",
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )
                        Slider(
                            value = ghostOpacity,
                            onValueChange = { ghostOpacity = it },
                            valueRange = 0.05f..0.95f,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF00E676),
                                activeTrackColor = Color(0xFF00E676)
                            )
                        )
                        Text(
                            text = "${(ghostOpacity * 100).roundToInt()}%",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // 5. Horizontal Reel of Mini Thumbnail Cards
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Item 0: "+ Snap HD Reference" Card
                    item {
                        Surface(
                            modifier = Modifier
                                .size(width = 90.dp, height = 90.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable(enabled = !isCapturing) {
                                    isCapturing = true
                                    onCaptureHdPhoto { savedFile, p, r ->
                                        isCapturing = false
                                        coroutineScope.launch(Dispatchers.IO) {
                                            val bmp = decodeSampledBitmap(savedFile.absolutePath, 400, 400)
                                            val newItem = ReferenceItem(
                                                id = savedFile.name,
                                                file = savedFile,
                                                pitch = p,
                                                roll = r,
                                                timestamp = savedFile.lastModified(),
                                                bitmap = bmp
                                            )
                                            withContext(Dispatchers.Main) {
                                                references.add(0, newItem)
                                                selectedIndex = 0
                                                saveMetadata()
                                                Toast.makeText(context, "HD Reference Saved!", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                },
                            color = Color(0xFF222222),
                            border = BorderStroke(1.5.dp, Color(0xFF00E676))
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                if (isCapturing) {
                                    CircularProgressIndicator(
                                        color = Color(0xFF00E676),
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.5.dp
                                    )
                                } else {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_add_a_photo),
                                        contentDescription = "Snap Reference",
                                        tint = Color(0xFF00E676),
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "+ Snap HD",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // Saved Thumbnail Cards
                    itemsIndexed(references, key = { _, item -> item.id }) { index, item ->
                        val isSelected = selectedIndex == index
                        val timeStr = remember(item.timestamp) {
                            SimpleDateFormat("HH:mm", Locale.US).format(Date(item.timestamp))
                        }

                        Surface(
                            modifier = Modifier
                                .size(width = 90.dp, height = 90.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .combinedClickable(
                                    onClick = {
                                        selectedIndex = if (isSelected) -1 else index
                                    },
                                    onLongClick = {
                                        itemToDelete = item
                                    }
                                ),
                            color = Color(0xFF1E1E1E),
                            border = BorderStroke(
                                width = if (isSelected) 2.5.dp else 1.dp,
                                color = if (isSelected) Color(0xFF00E676) else Color(0x44FFFFFF)
                            )
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                if (item.bitmap != null) {
                                    Image(
                                        bitmap = item.bitmap.asImageBitmap(),
                                        contentDescription = "Saved reference",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color(0xFF2A2A2A)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(painterResource(R.drawable.ic_image), null, tint = Color.Gray)
                                    }
                                }

                                // Card overlay badge (Time & Index)
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .background(Color.Black.copy(alpha = 0.7f))
                                        .padding(vertical = 2.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "#${references.size - index} • $timeStr",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                // Active selection checkmark
                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp)
                                            .size(18.dp)
                                            .background(Color(0xFF00E676), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Filled.Check, null, tint = Color.Black, modifier = Modifier.size(12.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Delete Confirmation Dialog
    if (itemToDelete != null) {
        val target = itemToDelete!!
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text("Delete Reference Photo?", color = Color.White) },
            text = { Text("Do you want to permanently delete this reference image?", color = Color.LightGray) },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            if (target.file.exists()) {
                                target.file.delete()
                            }
                            withContext(Dispatchers.Main) {
                                val idx = references.indexOf(target)
                                references.remove(target)
                                if (selectedIndex == idx) {
                                    selectedIndex = if (references.isNotEmpty()) 0 else -1
                                } else if (selectedIndex > idx) {
                                    selectedIndex--
                                }
                                saveMetadata()
                                Toast.makeText(context, "Reference deleted", Toast.LENGTH_SHORT).show()
                                itemToDelete = null
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = Color(0xFF1E1E1E)
        )
    }
}

private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(path, options)

    var inSampleSize = 1
    if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
        val halfHeight: Int = options.outHeight / 2
        val halfWidth: Int = options.outWidth / 2
        while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
            inSampleSize *= 2
        }
    }

    options.inJustDecodeBounds = false
    options.inSampleSize = inSampleSize
    return BitmapFactory.decodeFile(path, options)
}
