package com.example.feature.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandleEditScreen(handleId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE) }
    
    val prefix = "handle_${handleId}_"
    
    var yPos by remember { mutableFloatStateOf(prefs.getInt("${prefix}y", 50).toFloat()) }
    var sizeWidth by remember { mutableFloatStateOf(prefs.getInt("${prefix}width", if (handleId == "reader") 16 else 12).toFloat()) }
    var sizeHeight by remember { mutableFloatStateOf(prefs.getInt("${prefix}height", if (handleId == "reader") 60 else 120).toFloat()) }
    var colorHex by remember { mutableStateOf(prefs.getString("${prefix}color", if (handleId == "reader") "#44102d42" else "#242962ff") ?: "#242962ff") }
    var shape by remember { mutableStateOf(prefs.getString("${prefix}shape", if (handleId == "reader") "half_oval" else "slanted_block") ?: "slanted_block") }
    var edge by remember { mutableStateOf(prefs.getString("${prefix}edge", "right") ?: "right") }
    
    

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit ${if (handleId == "sidebar") "Sidebar" else "Reader"} Handle") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("Appearance (Applies Instantly)", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            
            Text("Edge Position:")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("left", "right").forEach { s ->
                    FilterChip(
                        selected = edge == s,
                        onClick = { 
                            edge = s
                            prefs.edit().putString("${prefix}edge", s).commit()
                            com.example.core.OverlaySyncManager.syncString(context, "${prefix}edge", s)
                        },
                        label = { Text(s.replaceFirstChar { it.uppercase() }) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            
            Text("Y Position: ${yPos.toInt()}")
            Slider(value = yPos, onValueChange = { 
                yPos = it
                prefs.edit().putInt("${prefix}y", it.toInt()).commit()
                com.example.core.OverlaySyncManager.syncInt(context, "${prefix}y", it.toInt())
            }, valueRange = 0f..100f)
            
            Text("Width (Thickness): ${sizeWidth.toInt()}dp")
            Slider(value = sizeWidth, onValueChange = { 
                sizeWidth = it
                prefs.edit().putInt("${prefix}width", it.toInt()).commit()
                com.example.core.OverlaySyncManager.syncInt(context, "${prefix}width", it.toInt())
            }, valueRange = 2f..50f)
            
            Text("Height (Length): ${sizeHeight.toInt()}dp")
            Slider(value = sizeHeight, onValueChange = { 
                sizeHeight = it
                prefs.edit().putInt("${prefix}height", it.toInt()).commit()
                com.example.core.OverlaySyncManager.syncInt(context, "${prefix}height", it.toInt())
            }, valueRange = 20f..300f)
            
            Text("Handle Color:")
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val presetColors = listOf(
                    "#44102d42", "#3318304A", "#66000000", "#66FFFFFF", 
                    "#80FF5252", "#804CAF50", "#802196F3", "#80FFEB3B", "#8087CEEB", "#1d2962ff"
                )
                presetColors.forEach { colorString ->
                    val parsedColor = try {
                        Color(android.graphics.Color.parseColor(colorString))
                    } catch (e: Exception) {
                        Color.Gray
                    }
                    val baseColorStr = if (colorString.length >= 7) colorString.substring(colorString.length - 6) else colorString
                    val currentBaseStr = if (colorHex.length >= 7) colorHex.substring(colorHex.length - 6) else colorHex
                    val isSelected = baseColorStr.equals(currentBaseStr, ignoreCase = true)
                    
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(parsedColor, CircleShape)
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
                                shape = CircleShape
                            )
                            .clickable {
                                // Keep the current alpha if a color is selected
                                val currentAlpha = if (colorHex.length == 9) colorHex.substring(1, 3) else "FF"
                                val newBase = if (colorString.length >= 7) colorString.substring(colorString.length - 6) else colorString
                                val newColorHex = "#$currentAlpha$newBase"
                                colorHex = newColorHex
                                prefs.edit().putString("${prefix}color", newColorHex).commit()
                                com.example.core.OverlaySyncManager.syncString(context, "${prefix}color", newColorHex)
                            }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            var alphaValue by remember(colorHex) {
                mutableFloatStateOf(
                    try {
                        val hex = colorHex.removePrefix("#")
                        if (hex.length == 8) {
                            hex.substring(0, 2).toInt(16) / 255f
                        } else {
                            1f
                        }
                    } catch(e: Exception) { 1f }
                )
            }
            Text("Transparency: ${(alphaValue * 100).toInt()}%")
            Slider(
                value = alphaValue,
                onValueChange = { newAlpha ->
                    alphaValue = newAlpha
                    val hex = colorHex.removePrefix("#")
                    val newAlphaHex = String.format("%02X", (newAlpha * 255).toInt())
                    val newColorHex = if (hex.length == 8) {
                        "#" + newAlphaHex + hex.substring(2)
                    } else if (hex.length == 6) {
                        "#" + newAlphaHex + hex
                    } else {
                        colorHex
                    }
                    colorHex = newColorHex
                    prefs.edit().putString("${prefix}color", newColorHex).commit()
                    com.example.core.OverlaySyncManager.syncString(context, "${prefix}color", newColorHex)
                },
                valueRange = 0f..1f
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            Text("Shape:")
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("triangle", "rectangle", "half_oval", "rounded_rect", "slanted_block").forEach { s ->
                    FilterChip(
                        selected = shape == s,
                        onClick = { 
                            shape = s
                            prefs.edit().putString("${prefix}shape", s).commit()
                            com.example.core.OverlaySyncManager.syncString(context, "${prefix}shape", s)
                        },
                        label = { Text(s.replace("_", " ").capitalize()) }
                    )
                }
            }
            
            }
    }
    
    
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionDropdown(label: String, selected: String, actions: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        OutlinedTextField(
            value = actions.find { it.first == selected }?.second ?: selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.exposedDropdownSize(matchTextFieldWidth = true)
        ) {
            actions.forEach { (id, title) ->
                DropdownMenuItem(
                    text = { Text(title) },
                    onClick = {
                        onSelect(id)
                        expanded = false
                    }
                )
            }
        }
    }
}

