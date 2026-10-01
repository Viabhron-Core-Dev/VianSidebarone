package com.example.feature.system_hub

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.core.LogKeeper
import com.example.feature.system_hub.accessibility.ScannerCapabilityBridge
import com.example.feature.system_hub.accessibility.ScannerResult
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * SecureScreenScannerActivity: Heavy-process screen scanner providing interactive screenshot cropping
 * and modular scanner execution against the selected crop.
 */
class SecureScreenScannerActivity : ComponentActivity() {

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
                        SecureScreenScannerContent(
                            originalBitmap = loadedBitmap,
                            onClose = { finish() }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
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

    companion object {
        const val EXTRA_IMAGE_PATH = "EXTRA_IMAGE_PATH"
        private const val TAG = "SecureScreenScannerActivity"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecureScreenScannerContent(
    originalBitmap: Bitmap,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var selectedShape by remember { mutableStateOf(SelectionShape.RECTANGLE) }
    var startPoint by remember { mutableStateOf<Offset?>(null) }
    var endPoint by remember { mutableStateOf<Offset?>(null) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    var cropBounds by remember {
        mutableStateOf(NormalizedCropBounds(0.1f, 0.2f, 0.9f, 0.6f))
    }

    var scanResultDialog by remember { mutableStateOf<ScannerResult?>(null) }
    var lastCroppedDimensions by remember { mutableStateOf("") }

    val imageBitmap = remember(originalBitmap) { originalBitmap.asImageBitmap() }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Secure Screen Scanner", color = Color.White) },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close", tint = Color.White)
                }
            },
            actions = {
                IconButton(onClick = {
                    startPoint = null
                    endPoint = null
                    cropBounds = NormalizedCropBounds(0.1f, 0.2f, 0.9f, 0.6f)
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reset Selection", tint = Color.White)
                }
                Button(
                    onClick = {
                        val cropped = ScannerSelectionHelper.createCroppedBitmap(originalBitmap, cropBounds, selectedShape)
                        lastCroppedDimensions = "${cropped.width}x${cropped.height} px (${selectedShape.displayName})"
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
                        val sp = startPoint
                        val ep = endPoint
                        if (sp != null && ep != null && canvasSize.width > 0f && canvasSize.height > 0f) {
                            cropBounds = ScannerSelectionHelper.calculateBounds(
                                startX = sp.x,
                                startY = sp.y,
                                endX = ep.x,
                                endY = ep.y,
                                canvasWidth = canvasSize.width,
                                canvasHeight = canvasSize.height,
                                shape = shape
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF333333),
                        contentColor = if (isSelected) Color.White else Color.LightGray
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text(shape.displayName, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Instructions
        val instructionText = when {
            startPoint == null -> "Tap anywhere to set start point, or drag across the screen."
            endPoint == null -> "Start point set. Tap second point or drag to complete selection."
            else -> "Mode: ${selectedShape.displayName}. Tap to start new selection or drag to reselect."
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
                    .pointerInput(selectedShape) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val downPos = down.position
                            var isDragging = false
                            var currentPos = downPos

                            // If startPoint is already set and awaiting second tap, initialStart is that anchor.
                            // If a previous selection was already completed (endPoint != null), tapping starts fresh.
                            val initialStart = if (endPoint == null) startPoint else null

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (change.pressed) {
                                    val dist = (change.position - downPos).getDistance()
                                    if (dist > 15f) {
                                        isDragging = true
                                        change.consume()
                                        currentPos = change.position

                                        val activeStart = initialStart ?: downPos
                                        startPoint = activeStart
                                        endPoint = currentPos

                                        cropBounds = ScannerSelectionHelper.calculateBounds(
                                            startX = activeStart.x,
                                            startY = activeStart.y,
                                            endX = currentPos.x,
                                            endY = currentPos.y,
                                            canvasWidth = size.width.toFloat(),
                                            canvasHeight = size.height.toFloat(),
                                            shape = selectedShape
                                        )
                                    }
                                } else {
                                    // Pointer released
                                    if (!isDragging) {
                                        // Tap gesture
                                        if (initialStart == null) {
                                            // First tap establishes starting point
                                            startPoint = downPos
                                            endPoint = null
                                        } else {
                                            // Second tap completes the selection
                                            endPoint = downPos
                                            cropBounds = ScannerSelectionHelper.calculateBounds(
                                                startX = initialStart.x,
                                                startY = initialStart.y,
                                                endX = downPos.x,
                                                endY = downPos.y,
                                                canvasWidth = size.width.toFloat(),
                                                canvasHeight = size.height.toFloat(),
                                                shape = selectedShape
                                            )
                                        }
                                    }
                                    break
                                }
                            }
                        }
                    }
            ) {
                // Background image
                drawImage(
                    image = imageBitmap,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt())
                )

                val cX = cropBounds.left * size.width
                val cY = cropBounds.top * size.height
                val cW = (cropBounds.right - cropBounds.left) * size.width
                val cH = (cropBounds.bottom - cropBounds.top) * size.height

                if (selectedShape == SelectionShape.CIRCLE) {
                    val radius = min(cW, cH) / 2f
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
                }

                // If first tap is active and awaiting second tap, render clear visual anchor target
                val firstTap = startPoint
                if (firstTap != null && endPoint == null) {
                    drawCircle(
                        color = Color(0xFF00E676),
                        radius = 16.dp.toPx(),
                        center = firstTap,
                        style = Stroke(width = 2.dp.toPx())
                    )
                    drawCircle(
                        color = Color(0xFF00E676),
                        radius = 4.dp.toPx(),
                        center = firstTap
                    )
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
                        val cropped = ScannerSelectionHelper.createCroppedBitmap(originalBitmap, cropBounds, selectedShape)
                        ScannerSelectionHelper.shareBitmap(context, cropped)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))
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

    // Result Dialog
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
                                "The crop area was successfully captured and measured. Optional OCR / barcode models can be installed separately without affecting normal Sidebar operation.",
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
                    Text("Scan Another Area")
                }
            }
        )
    }
}
