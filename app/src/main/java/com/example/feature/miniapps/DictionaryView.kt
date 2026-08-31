package com.example.feature.miniapps

import android.content.Context
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class DictionaryView(context: Context) : FrameLayout(context) {

    private val db = DictionaryDatabase.getInstance(context)
    private var tts: TextToSpeech? = null
    private var selectedEntry: DictionaryEntry? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var adapter: DictionaryAdapter

    init {
        LayoutInflater.from(context).inflate(R.layout.layout_dictionary, this, true)
        
        // Hide window controls since FloatingWindow handles them
        findViewById<LinearLayout>(R.id.bottom_window_controls)?.visibility = View.GONE
        findViewById<ImageView>(R.id.bubble_icon)?.visibility = View.GONE
        
        setupUI()
        setupTTS()
    }

    private fun setupUI() {
        val etSearch = findViewById<EditText>(R.id.et_search)
        val rvResults = findViewById<RecyclerView>(R.id.rv_results)
        val btnBack = findViewById<ImageView>(R.id.btn_back)
        val btnSpeak = findViewById<ImageView>(R.id.btn_speak_word)
        val searchLayout = findViewById<LinearLayout>(R.id.search_layout)
        val detailLayout = findViewById<LinearLayout>(R.id.detail_layout)

        adapter = DictionaryAdapter { word, entry ->
            if (entry != null) {
                showDetail(entry)
            } else {
                searchWord(word)
            }
        }
        rvResults.layoutManager = LinearLayoutManager(context)
        rvResults.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                searchWord(s.toString())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnBack.setOnClickListener {
            detailLayout.visibility = View.GONE
            searchLayout.visibility = View.VISIBLE
        }

        btnSpeak.setOnClickListener {
            selectedEntry?.let { entry ->
                tts?.speak(entry.word, TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }
    }

    private fun setupTTS() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
            }
        }
    }

    private fun searchWord(query: String) {
        if (query.isBlank()) {
            adapter.submitList(emptyList())
            return
        }
        scope.launch {
            val results: List<DictionaryEntry> = withContext(Dispatchers.IO) {
                db.dictionaryDao().searchWords("$query%", "English")
            }
            val mapped = results.map { Pair(it.word, it) }
            adapter.submitList(mapped)
        }
    }

    private fun showDetail(entry: DictionaryEntry) {
        selectedEntry = entry
        findViewById<LinearLayout>(R.id.search_layout).visibility = View.GONE
        findViewById<LinearLayout>(R.id.detail_layout).visibility = View.VISIBLE
        
        val tvWord = findViewById<TextView>(R.id.tv_word)
        val tvDefinition = findViewById<TextView>(R.id.tv_definition)
        
        tvWord.text = entry.word
        tvDefinition.text = androidx.core.text.HtmlCompat.fromHtml(
            entry.definition, 
            androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT
        )
    }

    private class DictionaryAdapter(private val onItemClick: (String, DictionaryEntry?) -> Unit) : 
        RecyclerView.Adapter<DictionaryAdapter.ViewHolder>() {
        
        private var items = emptyList<Pair<String, DictionaryEntry?>>()
        
        fun submitList(newItems: List<Pair<String, DictionaryEntry?>>) {
            items = newItems
            notifyDataSetChanged()
        }
        
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textView: TextView = view.findViewById(R.id.tv_item_text)
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.layout_dictionary_item, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.textView.text = item.first
            holder.itemView.setOnClickListener { onItemClick(item.first, item.second) }
        }
        
        override fun getItemCount() = items.size
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        tts?.stop()
        tts?.shutdown()
    }
}
