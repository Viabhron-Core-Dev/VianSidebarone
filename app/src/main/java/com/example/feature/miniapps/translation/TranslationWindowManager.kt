package com.example.feature.miniapps.translation


import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import com.example.R
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

class TranslationWindowManager(private val context: Context) : TextToSpeech.OnInitListener {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = context.getSharedPreferences("TranslationPrefs", Context.MODE_PRIVATE)
    
    private var floatingView: View? = null
    private var isShowing = false
    private var tts: TextToSpeech? = null
    
    private lateinit var sourceTextEdit: EditText
    private lateinit var targetTextOut: TextView
    private lateinit var sourceLangSpinner: Spinner
    private lateinit var targetLangSpinner: Spinner
    private lateinit var readSourceBtn: ImageView
    private lateinit var readTargetBtn: ImageView
    
    private val allLanguages = TranslateLanguage.getAllLanguages()
    private val languageNames = allLanguages.map { Locale(it).displayLanguage }
    
    private var isTranslating = false
    private var activeTranslator: com.google.mlkit.nl.translate.Translator? = null
    private var activeLanguageIdentifier: com.google.mlkit.nl.languageid.LanguageIdentifier? = null
    
    fun show() {
        if (isShowing) return
        isShowing = true
        
        tts = TextToSpeech(context, this)
        
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        layoutParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        layoutParams.y = 100
        
        val inflater = LayoutInflater.from(context)
        floatingView = inflater.inflate(R.layout.layout_floating_translation, null)
        
        setupViews()
        setupDrag(layoutParams)
        
        windowManager.addView(floatingView, layoutParams)
    }
    
    private fun setupViews() {
        val view = floatingView ?: return
        
        val closeBtn = view.findViewById<ImageView>(R.id.btn_close)
        val expandBtn = view.findViewById<ImageView>(R.id.btn_expand)
        val manageBtn = view.findViewById<ImageView>(R.id.btn_manage)
        val contentArea = view.findViewById<LinearLayout>(R.id.content_area)
        
        sourceTextEdit = view.findViewById(R.id.edit_source)
        targetTextOut = view.findViewById(R.id.text_target)
        sourceLangSpinner = view.findViewById(R.id.spinner_source)
        targetLangSpinner = view.findViewById(R.id.spinner_target)
        readSourceBtn = view.findViewById(R.id.btn_read_source)
        readTargetBtn = view.findViewById(R.id.btn_read_target)
        
        closeBtn.setOnClickListener { hide() }
        
        var isExpanded = true
        expandBtn.setOnClickListener {
            isExpanded = !isExpanded
            contentArea.visibility = if (isExpanded) View.VISIBLE else View.GONE
        }
        
        manageBtn.setOnClickListener {
            val intent = Intent(context, TranslationManagementActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            hide()
        }
        
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, languageNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        
        val sourceOptions = listOf("Auto Detect") + languageNames
        val sourceAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, sourceOptions)
        sourceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        
        sourceLangSpinner.adapter = sourceAdapter
        targetLangSpinner.adapter = adapter
        
        val defaultTarget = prefs.getString("default_target_lang", TranslateLanguage.ENGLISH) ?: TranslateLanguage.ENGLISH
        val targetIndex = allLanguages.indexOf(defaultTarget)
        if (targetIndex >= 0) {
            targetLangSpinner.setSelection(targetIndex)
        }
        
        sourceTextEdit.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val params = floatingView?.layoutParams as? WindowManager.LayoutParams
                params?.let {
                    it.flags = it.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                    windowManager.updateViewLayout(floatingView, it)
                }
            }
            false
        }
        
        floatingView?.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                val params = floatingView?.layoutParams as? WindowManager.LayoutParams
                params?.let {
                    it.flags = it.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    windowManager.updateViewLayout(floatingView, it)
                }
            }
            false
        }
        
        val textWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                translateText()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }
        
        sourceTextEdit.addTextChangedListener(textWatcher)
        
        val onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (parent == targetLangSpinner) {
                    val lang = allLanguages[position]
                    prefs.edit().putString("default_target_lang", lang).apply()
                }
                translateText()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        sourceLangSpinner.onItemSelectedListener = onItemSelectedListener
        targetLangSpinner.onItemSelectedListener = onItemSelectedListener
        
        readSourceBtn.setOnClickListener {
            val text = sourceTextEdit.text.toString()
            if (text.isNotEmpty() && tts != null) {
                val srcPos = sourceLangSpinner.selectedItemPosition
                val lang = if (srcPos == 0) TranslateLanguage.ENGLISH else allLanguages[srcPos - 1] // Best effort for auto detect
                tts?.language = Locale(lang)
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }
        
        readTargetBtn.setOnClickListener {
            val text = targetTextOut.text.toString()
            if (text.isNotEmpty() && tts != null) {
                val lang = allLanguages[targetLangSpinner.selectedItemPosition]
                tts?.language = Locale(lang)
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }
    }
    
    private fun translateText() {
        val text = sourceTextEdit.text.toString()
        if (text.isEmpty()) {
            targetTextOut.text = ""
            return
        }
        
        val srcPos = sourceLangSpinner.selectedItemPosition
        val targetPos = targetLangSpinner.selectedItemPosition
        val targetLang = allLanguages[targetPos]
        
        if (srcPos == 0) { // Auto Detect
            activeLanguageIdentifier?.close()
            val languageIdentifier = LanguageIdentification.getClient()
            activeLanguageIdentifier = languageIdentifier
            languageIdentifier.identifyLanguage(text)
                .addOnSuccessListener { languageCode ->
                    val srcLang = if (languageCode == "und") TranslateLanguage.ENGLISH else languageCode
                    performTranslation(text, srcLang, targetLang)
                }
                .addOnCompleteListener {
                    languageIdentifier.close()
                    if (activeLanguageIdentifier == languageIdentifier) {
                        activeLanguageIdentifier = null
                    }
                }
        } else {
            val srcLang = allLanguages[srcPos - 1]
            performTranslation(text, srcLang, targetLang)
        }
    }
    
    private fun performTranslation(text: String, srcLang: String, targetLang: String) {
        activeTranslator?.close()
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(srcLang)
            .setTargetLanguage(targetLang)
            .build()
        val translator = Translation.getClient(options)
        activeTranslator = translator
        
        val conditions = DownloadConditions.Builder().build()
        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                translator.translate(text)
                    .addOnSuccessListener { translated ->
                        targetTextOut.text = translated
                    }
                    .addOnFailureListener {
                        targetTextOut.text = "Error translating text."
                    }
            }
            .addOnFailureListener {
                targetTextOut.text = "Error downloading model."
            }
    }
    
    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag(layoutParams: WindowManager.LayoutParams) {
        val handle = floatingView?.findViewById<View>(R.id.drag_handle)
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        handle?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingView, layoutParams)
                    true
                }
                else -> false
            }
        }
    }
    
    fun hide() {
        if (!isShowing) return
        isShowing = false
        activeTranslator?.close()
        activeTranslator = null
        activeLanguageIdentifier?.close()
        activeLanguageIdentifier = null
        floatingView?.let { windowManager.removeView(it) }
        floatingView = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.ENGLISH
        }
    }
}
