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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
    var cropLeft by remember { mutableStateOf(0.1f) }
    var cropTop by remember { mutableStateOf(0.2f) }
    var cropRight by remember { mutableStateOf(0.9f) }
    var cropBottom by remember { mutableStateOf(0.6f) }

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
                    cropLeft = 0.1f
                    cropTop = 0.2f
                    cropRight = 0.9f
                    cropBottom = 0.6f
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reset Crop", tint = Color.White)
                }
                Button(
                    onClick = {
                        val bw = originalBitmap.width
                        val bh = originalBitmap.height
                        val x = (cropLeft * bw).roundToInt().coerceIn(0, bw - 1)
                        val y = (cropTop * bh).roundToInt().coerceIn(0, bh - 1)
                        val w = ((cropRight - cropLeft) * bw).roundToInt().coerceIn(1, bw - x)
                        val h = ((cropBottom - cropTop) * bh).roundToInt().coerceIn(1, bh - y)

                        val cropped = Bitmap.createBitmap(originalBitmap, x, y, w, h)
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
            text = "Drag across the screen to select the region to scan and inspect.",
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
                // Background image
                drawImage(
                    image = imageBitmap,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt())
                )

                // Semi-transparent darkened overlay outside crop box
                val cX = cropLeft * size.width
                val cY = cropTop * size.height
                val cW = (cropRight - cropLeft) * size.width
                val cH = (cropBottom - cropTop) * size.height

                // Dim areas around crop
                drawRect(Color(0x88000000), Offset.Zero, Size(size.width, cY))
                drawRect(Color(0x88000000), Offset(0f, cY + cH), Size(size.width, size.height - (cY + cH)))
                drawRect(Color(0x88000000), Offset(0f, cY), Size(cX, cH))
                drawRect(Color(0x88000000), Offset(cX + cW, cY), Size(size.width - (cX + cW), cH))

                // Crop border & corner accents
                drawRect(
                    color = Color(0xFF00E676),
                    topLeft = Offset(cX, cY),
                    size = Size(cW, cH),
                    style = Stroke(width = 2.dp.toPx())
                )
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
