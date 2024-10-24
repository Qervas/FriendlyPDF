package tech.ohao.friendlypdf

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle
import com.google.android.material.floatingactionbutton.FloatingActionButton
import tech.ohao.friendlypdf.databinding.ActivityMainBinding
import com.github.barteksc.pdfviewer.util.FitPolicy 
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.SeekBar
import tech.ohao.friendlypdf.databinding.LayoutMediaControlsBinding

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var pdfView: PDFView
    private lateinit var tts: TextToSpeech
    private var isSpeaking = false
    private var currentPage = 0
    private val PICK_PDF_FILE = 2
    private var currentUri: Uri? = null  // Add this to track current PDF
    
    // View mode states
    private enum class ViewMode {
        FIT_WIDTH, FIT_PAGE, CROP_MARGINS
    }
    private var currentViewMode = ViewMode.FIT_WIDTH
    private var isMediaControlsVisible = false
    private var speechRate = 1.0f
    private var currentSentence = ""
    private var sentences = listOf<String>()
    private lateinit var mediaControlsBinding: LayoutMediaControlsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Initialize PDFBox
        PDFBoxResourceLoader.init(applicationContext)
        
        // Initialize Text-to-Speech
        tts = TextToSpeech(this, this)
        
        pdfView = binding.pdfView
        
        // Handle incoming PDF intent
        handleIntent(intent)
        
        // Add FAB for file picking
        binding.fabAdd.setOnClickListener { openFilePicker() }
        
        // Setup view mode FAB to toggle reading
        binding.fabViewMode.setOnClickListener { toggleReading() }
        
        // Setup media controls
        setupMediaControls()
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                // Handle PDF opened from external app
                intent.data?.let { uri ->
                    displayPdf(uri)
                }
            }
            else -> {
                // Default PDF if no external intent
                try {
                    assets.open("sample.pdf").use { stream ->
                        pdfView.fromStream(stream)
                            .defaultPage(0)
                            .enableSwipe(true)
                            .swipeHorizontal(false)
                            .enableDoubletap(true)
                            .enableAnnotationRendering(true)
                            .scrollHandle(DefaultScrollHandle(this))
                            .spacing(10)
                            .load()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "No default PDF found", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
        }
        startActivityForResult(intent, PICK_PDF_FILE)
    }

    private fun displayPdf(uri: Uri) {
        currentUri = uri  // Store the URI
        try {
            pdfView.fromUri(uri)
                .defaultPage(0)
                .enableSwipe(true)
                .swipeHorizontal(false)
                .enableDoubletap(true)
                .enableAnnotationRendering(true)
                .scrollHandle(DefaultScrollHandle(this))
                .spacing(10)
                .load()
        } catch (e: Exception) {
            Toast.makeText(this, "Error loading PDF: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (requestCode == PICK_PDF_FILE && resultCode == Activity.RESULT_OK) {
            resultData?.data?.also { uri ->
                displayPdf(uri)
            }
        }
    }

    private fun toggleViewMode() {
        currentViewMode = when (currentViewMode) {
            ViewMode.FIT_WIDTH -> ViewMode.FIT_PAGE
            ViewMode.FIT_PAGE -> ViewMode.CROP_MARGINS
            ViewMode.CROP_MARGINS -> ViewMode.FIT_WIDTH
        }
        
        // Reload PDF with new view mode
        pdfView.let {
            val currentPage = it.currentPage
            currentUri?.let { uri ->
                loadPdfWithCurrentSettings(uri, currentPage)
            }
        }
        
        // Show current mode to user
        Toast.makeText(this, "Mode: ${currentViewMode.name}", Toast.LENGTH_SHORT).show()
    }

    private fun loadPdfWithCurrentSettings(uri: Uri, page: Int = 0) {
        try {
            pdfView.fromUri(uri)
                .defaultPage(page)
                .enableSwipe(true)
                .swipeHorizontal(false)
                .enableDoubletap(true)
                .enableAnnotationRendering(true)
                .scrollHandle(DefaultScrollHandle(this))
                .spacing(10)
                .apply {
                    when (currentViewMode) {
                        ViewMode.FIT_WIDTH -> {
                            fitEachPage(true)  // Changed to use available methods
                            pageFling(true)
                            pageSnap(true)
                            // Add extra spacing for better readability
                            spacing(50)
                        }
                        ViewMode.FIT_PAGE -> {
                            fitEachPage(true)
                            pageFling(true)
                            pageSnap(true)
                            spacing(10)
                        }
                        ViewMode.CROP_MARGINS -> {
                            fitEachPage(true)
                            // Auto-crop margins
                            autoSpacing(true)
                            pageSnap(true)
                            pageFling(true)
                            // Add margin cropping if available
                            enableAntialiasing(true)
                        }
                    }
                }
                .onPageChange { page, pageCount ->
                    title = getString(R.string.page_of_total, page + 1, pageCount)
                }
                .onPageError { page, t ->
                    Toast.makeText(this, "Error loading page ${page + 1}", Toast.LENGTH_SHORT).show()
                }
                .load()
        } catch (e: Exception) {
            Toast.makeText(this, "Error loading PDF: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_zoom_in -> {
                pdfView.zoomWithAnimation(pdfView.zoom * 1.25f)
                true
            }
            R.id.action_zoom_out -> {
                pdfView.zoomWithAnimation(pdfView.zoom * 0.75f)
                true
            }
            R.id.action_toggle_scroll -> {
                // Store current page before reloading
                val currentPage = pdfView.currentPage
                
                // Reload the PDF with new orientation
                currentUri?.let { uri ->
                    pdfView.fromUri(uri)
                        .defaultPage(currentPage)
                        .enableSwipe(true)
                        .swipeHorizontal(!pdfView.isSwipeEnabled)  // Toggle between vertical and horizontal
                        .enableDoubletap(true)
                        .enableAnnotationRendering(true)
                        .scrollHandle(DefaultScrollHandle(this))
                        .spacing(10)
                        .load()
                }
                
                // Show current orientation to user
                Toast.makeText(this, 
                    if (pdfView.isSwipeEnabled) "Horizontal Scrolling" else "Vertical Scrolling", 
                    Toast.LENGTH_SHORT
                ).show()
                true
            }
            R.id.action_lang_en -> {
                setAppLanguage(Locale.ENGLISH)
                true
            }
            R.id.action_lang_de -> {
                setAppLanguage(Locale.GERMAN)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setAppLanguage(locale: Locale) {
        // Update TTS language without recreating activity
        val result = tts.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Toast.makeText(this, getString(R.string.language_not_supported), Toast.LENGTH_SHORT).show()
            return
        }
        
        // Update app locale without recreation
        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
        
        // Update UI text
        updateUIText()
    }

    private fun updateUIText() {
        // Update all text in the UI that uses string resources
        binding.mediaControls.apply {
            btnPrevious.contentDescription = getString(R.string.previous_page)
            btnPlayPause.contentDescription = 
                if (isSpeaking) getString(R.string.pause) 
                else getString(R.string.play)
            btnNext.contentDescription = getString(R.string.next_page)
            btnClose.contentDescription = getString(R.string.hide_controls)
        }
        // ... update other UI elements ...
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Set default language based on system locale
            val locale = Locale.getDefault()
            val result = tts.setLanguage(locale)
            
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, getString(R.string.language_not_supported), Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, getString(R.string.tts_init_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleReading() {
        if (isSpeaking) {
            stopReading()
            return
        }

        currentUri?.let { uri ->
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val inputStream = contentResolver.openInputStream(uri)
                    val document = PDDocument.load(inputStream)
                    val stripper = PDFTextStripper()
                    
                    stripper.startPage = pdfView.currentPage + 1
                    stripper.endPage = pdfView.currentPage + 1
                    val text = stripper.getText(document)
                    
                    // Split text into sentences
                    sentences = text.split(Regex("[.!?]+\\s+"))
                    
                    withContext(Dispatchers.Main) {
                        startReading()
                    }
                    
                    document.close()
                    inputStream?.close()
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.error_reading_pdf, e.message),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private fun startReading() {
        if (!::tts.isInitialized || tts.engines.isEmpty()) {
            Toast.makeText(this, "Text-to-speech not available", Toast.LENGTH_SHORT).show()
            return
        }

        isSpeaking = true
        binding.mediaControls.btnPlayPause.setImageResource(R.drawable.baseline_pause_24)

        // Setup TTS listener for sentence highlighting
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                runOnUiThread {
                    if (utteranceId.toIntOrNull() != null) {
                        currentSentence = sentences[utteranceId.toInt()]
                        highlightCurrentSentence()
                    }
                }
            }

            override fun onDone(utteranceId: String) {
                utteranceId.toIntOrNull()?.let { index ->
                    val nextIndex = index + 1
                    if (nextIndex < sentences.size) {
                        tts.speak(
                            sentences[nextIndex],
                            TextToSpeech.QUEUE_ADD,
                            null,
                            nextIndex.toString()
                        )
                    } else {
                        runOnUiThread { stopReading() }
                    }
                }
            }

            override fun onError(utteranceId: String) {
                runOnUiThread { 
                    stopReading()
                    Toast.makeText(this@MainActivity, "Error reading text", Toast.LENGTH_SHORT).show()
                }
            }
        })

        // Start reading first sentence
        tts.setSpeechRate(speechRate)
        if (sentences.isNotEmpty()) {
            tts.speak(sentences[0], TextToSpeech.QUEUE_FLUSH, null, "0")
        }
    }

    private fun stopReading() {
        tts.stop()
        isSpeaking = false
        binding.mediaControls.btnPlayPause.setImageResource(R.drawable.baseline_play_arrow_24)
        binding.mediaControls.btnPlayPause.contentDescription = getString(R.string.play)
        Toast.makeText(this, getString(R.string.stopped_reading), Toast.LENGTH_SHORT).show()
        clearHighlight()
    }

    private fun highlightCurrentSentence() {
        // Implementation depends on your PDF viewer library
        // You might need to use a custom view or overlay
        // This is a placeholder for the highlighting logic
    }

    private fun clearHighlight() {
        // Clear any existing highlights
    }

    private fun setupMediaControls() {
        // Remove this line since we're using data binding
        // val mediaControlsBinding = binding.mediaControls

        binding.mediaControls.apply {
            btnPlayPause.setOnClickListener { 
                if (isSpeaking) {
                    stopReading()
                } else {
                    toggleReading()
                }
            }
            
            btnPrevious.setOnClickListener {
                if (pdfView.currentPage > 0) {
                    pdfView.jumpTo(pdfView.currentPage - 1)
                    if (isSpeaking) {
                        stopReading()
                        toggleReading()
                    }
                }
            }
            
            btnNext.setOnClickListener {
                if (pdfView.currentPage < pdfView.pageCount - 1) {
                    pdfView.jumpTo(pdfView.currentPage + 1)
                    if (isSpeaking) {
                        stopReading()
                        toggleReading()
                    }
                }
            }
            
            btnClose.setOnClickListener {
                toggleMediaControlsVisibility(false)
            }

            // Setup speed control
            speedSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    speechRate = (progress + 5) / 10f  // This gives a range of 0.5x to 2.5x
                    speedText.text = String.format("%.1fx", speechRate)
                    if (isSpeaking) {
                        tts.setSpeechRate(speechRate)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }

        // Update FAB to show/hide media controls
        binding.fabViewMode.setOnClickListener {
            toggleMediaControlsVisibility(!isMediaControlsVisible)
        }
    }

    private fun toggleMediaControlsVisibility(show: Boolean) {
        isMediaControlsVisible = show
        binding.mediaControls.root.visibility = if (show) View.VISIBLE else View.GONE
        
        // Update FAB icon
        binding.fabViewMode.setImageResource(
            if (show) R.drawable.baseline_close_24
            else R.drawable.baseline_play_arrow_24
        )
    }

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }
}
