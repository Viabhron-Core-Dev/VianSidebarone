package com.example.feature.settings

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AppDatabase
import com.example.data.SchedulerTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TagManagementActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val prefs = getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
        val db = AppDatabase.getDatabase(this)
        
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val defaultW = (resources.displayMetrics.widthPixels * 0.85).toInt()
                    val defaultH = (resources.displayMetrics.heightPixels * 0.6).toInt()
                    
                    var width by remember { mutableFloatStateOf(prefs.getInt("page_scheduler_width", defaultW).toFloat()) }
                    var height by remember { mutableFloatStateOf(prefs.getInt("page_scheduler_height", defaultH).toFloat()) }
                    
                    var tagsStr by remember { mutableStateOf(prefs.getString("scheduler_tags", "Work,Personal,Urgent,Misc") ?: "") }
                    var newTag by remember { mutableStateOf("") }
                    
                    val tagsList = tagsStr.split(",").filter { it.isNotBlank() }
                    
                    var history by remember { mutableStateOf(emptyList<SchedulerTask>()) }
                    
                    val scope = rememberCoroutineScope()
                    
                    LaunchedEffect(Unit) {
                        db.schedulerTaskDao().getAllTasks().collect { tasks ->
                            history = tasks.filter { it.status != "PENDING" }.sortedByDescending { it.timeMillis }
                        }
                    }
                    
                    Column(modifier = Modifier.fillMaxSize()) {
                        TopAppBar(
                            title = { Text("Short Reminders Settings") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                                }
                            }
                        )
                        
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                        ) {
                            item {
                                Text("Window Size", fontSize = 20.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
                                
                                Text("Width: ${width.toInt()}")
                                Slider(
                                    value = width,
                                    onValueChange = { 
                                        width = it
                                        prefs.edit().putInt("page_scheduler_width", it.toInt()).apply()
                                    },
                                    valueRange = 300f..(resources.displayMetrics.widthPixels.toFloat())
                                )
                                
                                Text("Height: ${height.toInt()}")
                                Slider(
                                    value = height,
                                    onValueChange = { 
                                        height = it
                                        prefs.edit().putInt("page_scheduler_height", it.toInt()).apply()
                                    },
                                    valueRange = 300f..(resources.displayMetrics.heightPixels.toFloat())
                                )
                                
                                Spacer(modifier = Modifier.height(24.dp))
                                
                                Text("Tags Management", fontSize = 20.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = newTag,
                                        onValueChange = { newTag = it },
                                        modifier = Modifier.weight(1f),
                                        label = { Text("New Tag") },
                                        singleLine = true
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(onClick = {
                                        if (newTag.isNotBlank() && !tagsList.contains(newTag)) {
                                            val updatedTags = if (tagsStr.isEmpty()) newTag else "$tagsStr,$newTag"
                                            tagsStr = updatedTags
                                            prefs.edit().putString("scheduler_tags", updatedTags).apply()
                                            newTag = ""
                                        }
                                    }) {
                                        Text("Add")
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            
                            items(tagsList) { tag ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(tag, fontSize = 16.sp)
                                    IconButton(onClick = {
                                        val updatedList = tagsList.filter { it != tag }
                                        tagsStr = updatedList.joinToString(",")
                                        prefs.edit().putString("scheduler_tags", tagsStr).apply()
                                    }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete Tag", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                            
                            item {
                                Spacer(modifier = Modifier.height(24.dp))
                                Text("History Ledger", fontSize = 20.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
                                
                                if (history.isEmpty()) {
                                    Text("No history yet.", color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
                                }
                            }
                            
                            items(history) { task ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = if (task.tags.isNotBlank()) task.tags.replace(",", ", ") else "No Tags",
                                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                                fontSize = 16.sp
                                            )
                                            Text(
                                                text = task.status,
                                                color = if (task.status == "DONE") Color.Green else Color.Red,
                                                fontSize = 12.sp,
                                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                            )
                                        }
                                        
                                        Spacer(modifier = Modifier.height(4.dp))
                                        
                                        Text(
                                            text = SimpleDateFormat("MMM dd, yyyy hh:mm:ss a", Locale.getDefault()).format(Date(task.timeMillis)),
                                            color = MaterialTheme.colorScheme.primary,
                                            fontSize = 14.sp
                                        )
                                        
                                        Spacer(modifier = Modifier.height(8.dp))
                                        
                                        Button(
                                            onClick = {
                                                scope.launch(Dispatchers.IO) {
                                                    db.schedulerTaskDao().delete(task)
                                                }
                                            },
                                            modifier = Modifier.align(Alignment.End),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                        ) {
                                            Text("Delete")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
