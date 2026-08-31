package com.example.feature.miniapps.translation


import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.res.painterResource
import com.example.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import java.util.Locale
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel

@OptIn(ExperimentalMaterial3Api::class)
class TranslationManagementActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Manage Translation Models") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.Filled.ArrowBack, "Back")
                                }
                            }
                        )
                    }
                ) { padding ->
                    TranslationModelsScreen(Modifier.padding(padding))
                }
            }
        }
    }
    
    @Composable
    fun TranslationModelsScreen(modifier: Modifier = Modifier) {
        val modelManager = RemoteModelManager.getInstance()
        var downloadedModels by remember { mutableStateOf<Set<TranslateRemoteModel>>(emptySet()) }
        var isRefreshing by remember { mutableStateOf(true) }

        val refreshModels = {
            isRefreshing = true
            modelManager.getDownloadedModels(TranslateRemoteModel::class.java)
                .addOnSuccessListener { models ->
                    downloadedModels = models
                    isRefreshing = false
                }
                .addOnFailureListener {
                    isRefreshing = false
                }
        }

        LaunchedEffect(Unit) {
            refreshModels()
        }

        val allLanguages = TranslateLanguage.getAllLanguages()

        Column(modifier = modifier.fillMaxSize()) {
            Text(
                "Tap a language to download or delete its offline translation model.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp)
            )

            if (isRefreshing) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(allLanguages) { langCode ->
                        val model = TranslateRemoteModel.Builder(langCode).build()
                        val isDownloaded = downloadedModels.any { it.language == langCode }
                        var isDownloading by remember { mutableStateOf(false) }

                        ListItem(
                            headlineContent = { Text(Locale(langCode).displayLanguage) },
                            supportingContent = { Text(langCode) },
                            trailingContent = {
                                if (isDownloading) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                } else if (isDownloaded) {
                                    IconButton(onClick = {
                                        modelManager.deleteDownloadedModel(model)
                                            .addOnSuccessListener {
                                                Toast.makeText(this@TranslationManagementActivity, "Deleted $langCode", Toast.LENGTH_SHORT).show()
                                                refreshModels()
                                            }
                                    }) {
                                        Icon(Icons.Filled.Delete, "Delete")
                                    }
                                } else {
                                    IconButton(onClick = {
                                        isDownloading = true
                                        val conditions = DownloadConditions.Builder().build()
                                        modelManager.download(model, conditions)
                                            .addOnSuccessListener {
                                                isDownloading = false
                                                Toast.makeText(this@TranslationManagementActivity, "Downloaded $langCode", Toast.LENGTH_SHORT).show()
                                                refreshModels()
                                            }
                                            .addOnFailureListener {
                                                isDownloading = false
                                                Toast.makeText(this@TranslationManagementActivity, "Failed to download $langCode", Toast.LENGTH_SHORT).show()
                                            }
                                    }) {
                                        Icon(painterResource(R.drawable.ic_download), "Download")
                                    }
                                }
                            }
                        )
                        Divider()
                    }
                }
            }
        }
    }
}
