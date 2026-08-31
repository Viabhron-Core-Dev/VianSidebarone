package com.example.feature.miniapps

import com.example.service.SidebarService
import android.content.Intent
import android.os.Bundle
import android.content.Context
import android.speech.tts.TextToSpeech
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import com.example.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class DictionaryPopupActivity : ComponentActivity() {

    private fun makeWordsClickable(htmlText: CharSequence, onWordClick: (String) -> Unit): CharSequence {
        val spannable = android.text.SpannableString(htmlText)
        val matcher = java.util.regex.Pattern.compile("[a-zA-Z]+").matcher(spannable)
        while (matcher.find()) {
            val word = matcher.group()
            if (word.length > 3) {
                val span = object : android.text.style.ClickableSpan() {
                    override fun onClick(widget: android.view.View) {
                        onWordClick(word)
                    }
                    override fun updateDrawState(ds: android.text.TextPaint) {
                        super.updateDrawState(ds)
                        ds.color = android.graphics.Color.parseColor("#4ea8de")
                        ds.isUnderlineText = false
                    }
                }
                spannable.setSpan(span, matcher.start(), matcher.end(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return spannable
    }

    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
            }
        }
        
        var query = ""
        if (intent.action == Intent.ACTION_PROCESS_TEXT) {
            query = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() ?: ""
        } else if (intent.action == Intent.ACTION_SEND) {
            query = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        }
        
        query = query.trim().split(Regex("\\s+")).firstOrNull()?.replace(Regex("[^a-zA-Z]"), "") ?: ""
        val db = DictionaryDatabase.getInstance(applicationContext)
        
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var definition by remember { mutableStateOf<String?>("Loading...") }
                
                LaunchedEffect(query) {
                    if (query.isNotEmpty()) {
                        withContext(Dispatchers.IO) {
                            val res = db.dictionaryDao().getDefinition(query, getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE).getString("active_dict", "English") ?: "English")
                            definition = res?.definition ?: "No definition found for '$query'"
                        }
                    } else {
                        definition = "No word selected."
                    }
                }
                
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x88000000))
                        .clickable { finish() },
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2C)),
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .heightIn(min = 100.dp, max = 400.dp)
                            .clickable(enabled = false) {}
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (query.isEmpty()) "Dictionary" else query,
                                    fontSize = 18.sp,
                                    color = Color.White
                                )
                                if (query.isNotEmpty()) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = {
                                                tts?.speak(query, TextToSpeech.QUEUE_FLUSH, null, "dict")
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = "Read Aloud",
                                                tint = Color.White
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                val i = Intent(this@DictionaryPopupActivity, SidebarService::class.java)
                                                i.action = "OPEN_DICTIONARY"
                                                i.putExtra("QUERY", query)
                                                startService(i)
                                                finish()
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_open_in_new),
                                                contentDescription = "Open in Floating Dictionary",
                                                tint = Color.White
                                            )
                                        }
                                    }
                                }
                            }
                            
                            rememberScrollState().let { scrollState ->
                                AndroidView(
                                    modifier = Modifier.verticalScroll(scrollState),
                                    factory = { ctx ->
                                        TextView(ctx).apply {
                                            setTextColor(android.graphics.Color.LTGRAY)
                                            textSize = 14f
                                        }
                                    },
                                    update = { textView ->
                                        val htmlContent = HtmlCompat.fromHtml(
                                            definition ?: "",
                                            HtmlCompat.FROM_HTML_MODE_COMPACT
                                        )
                                        textView.movementMethod = android.text.method.LinkMovementMethod.getInstance()
                                        textView.text = makeWordsClickable(htmlContent) { word ->
                                            val i = Intent(this@DictionaryPopupActivity, SidebarService::class.java)
                                            i.action = "OPEN_DICTIONARY"
                                            i.putExtra("QUERY", word)
                                            startService(i)
                                            finish()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
