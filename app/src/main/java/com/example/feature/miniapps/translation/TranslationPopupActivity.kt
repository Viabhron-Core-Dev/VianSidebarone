package com.example.feature.miniapps.translation


import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.SettingsActivity
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

class TranslationPopupActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        var textToTranslate = ""
        
        if (intent?.action == Intent.ACTION_PROCESS_TEXT) {
            textToTranslate = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() ?: ""
        }
        
        tts = TextToSpeech(this, this)
        
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .background(Color.Transparent),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    TranslationPopupContent(
                        initialText = textToTranslate,
                        onClose = { finish() },
                        onReadAloud = { text, lang ->
                            if (tts != null && text.isNotEmpty()) {
                                tts?.language = Locale(lang)
                                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
                            }
                        },
                        onOpenSettings = {
                            startActivity(Intent(this, TranslationManagementActivity::class.java))
                        },
                        context = this
                    )
                }
            }
        }
    }
    
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.ENGLISH
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
    }
}

@Composable
fun TranslationPopupContent(
    initialText: String,
    onClose: () -> Unit,
    onReadAloud: (String, String) -> Unit,
    onOpenSettings: () -> Unit,
    context: Context
) {
    val prefs = context.getSharedPreferences("TranslationPrefs", Context.MODE_PRIVATE)
    var targetLanguage by remember { mutableStateOf(prefs.getString("default_target_lang", TranslateLanguage.ENGLISH) ?: TranslateLanguage.ENGLISH) }
    var sourceLanguage by remember { mutableStateOf<String?>(null) }
    var translatedText by remember { mutableStateOf("") }
    var isTranslating by remember { mutableStateOf(true) }
    
    val allLanguages = TranslateLanguage.getAllLanguages()

    LaunchedEffect(initialText, targetLanguage) {
        if (initialText.isEmpty()) {
            isTranslating = false
            return@LaunchedEffect
        }
        
        isTranslating = true
        var activeTranslator: com.google.mlkit.nl.translate.Translator? = null
        val languageIdentifier = LanguageIdentification.getClient()
        try {
            languageIdentifier.identifyLanguage(initialText)
                .addOnSuccessListener { languageCode ->
                    val srcLang = if (languageCode == "und") TranslateLanguage.ENGLISH else languageCode
                    sourceLanguage = srcLang
                    
                    val options = TranslatorOptions.Builder()
                        .setSourceLanguage(srcLang)
                        .setTargetLanguage(targetLanguage)
                        .build()
                    val translator = Translation.getClient(options)
                    activeTranslator = translator
                    
                    val conditions = DownloadConditions.Builder().build()
                    translator.downloadModelIfNeeded(conditions)
                        .addOnSuccessListener {
                            translator.translate(initialText)
                                .addOnSuccessListener { translated ->
                                    translatedText = translated
                                    isTranslating = false
                                }
                                .addOnFailureListener {
                                    translatedText = "Error translating text."
                                    isTranslating = false
                                }
                        }
                        .addOnFailureListener {
                            translatedText = "Error downloading language model."
                            isTranslating = false
                        }
                }
                .addOnFailureListener {
                    translatedText = "Error identifying language."
                    isTranslating = false
                }
                .addOnCompleteListener {
                    languageIdentifier.close()
                }
        } catch (e: Exception) {
            languageIdentifier.close()
            isTranslating = false
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Translation",
                style = MaterialTheme.typography.titleLarge
            )
            Row {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Source Text Area
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (sourceLanguage != null) Locale(sourceLanguage!!).displayLanguage else "Detecting...",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    IconButton(onClick = { onReadAloud(initialText, sourceLanguage ?: TranslateLanguage.ENGLISH) }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Read Aloud")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = initialText,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Target Text Area
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Target Language Selector
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(0.dp)) {
                            Text(
                                text = Locale(targetLanguage).displayLanguage,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            allLanguages.forEach { langCode ->
                                DropdownMenuItem(
                                    text = { Text(Locale(langCode).displayLanguage) },
                                    onClick = {
                                        targetLanguage = langCode
                                        prefs.edit().putString("default_target_lang", langCode).apply()
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                    
                    if (!isTranslating && translatedText.isNotEmpty()) {
                        IconButton(onClick = { onReadAloud(translatedText, targetLanguage) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Read Aloud")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (isTranslating) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp).align(Alignment.CenterHorizontally))
                } else {
                    Text(
                        text = translatedText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}
