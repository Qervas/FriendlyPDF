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
import android.graphics.RectF
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AlertDialog
import android.util.Log
import java.util.Timer
import java.util.TimerTask
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.delay

// Make PageSize public by moving it outside the file-level scope
data class PageSize(val width: Float, val height: Float)

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
    private var highlightView: View? = null
    private var currentHighlightColor = R.color.highlight_blue
    private var isFabMenuExpanded = false

    // Add timer related properties
    private var timeUpdateTimer: Timer? = null
    private var currentReadingIndex = 0

    companion object {
        private const val PREFS_NAME = "FriendlyPDFPrefs"
        private const val LAST_PDF_URI = "last_pdf_uri"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Initialize PDFBox
        PDFBoxResourceLoader.init(applicationContext)
        
        // Initialize Text-to-Speech
        tts = TextToSpeech(this, this)
        
        pdfView = binding.pdfView
        
        // Check for intent first
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            loadPDF(intent.data!!)
        } else {
            // Try to load last opened PDF
            loadLastOpenedPDF()
        }
        
        // Setup media controls
        setupMediaControls()
        
        // Setup FAB menu
        setupFabMenu()
    }

    private fun loadLastOpenedPDF() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val lastPdfUri = prefs.getString(LAST_PDF_URI, null)
        
        if (lastPdfUri != null) {
            try {
                val uri = Uri.parse(lastPdfUri)
                // Take persistable URI permission if needed
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: SecurityException) {
                    // Permission might already be taken or not available
                }
                loadPDF(uri)
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    getString(R.string.error_loading_last_pdf),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun loadPDF(uri: Uri) {
        currentUri = uri
        
        // Save this URI as the last opened PDF
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(LAST_PDF_URI, uri.toString())
            .apply()
        
        // Your existing PDF loading code...
        lifecycleScope.launch {
            try {
                // Show loading indicator
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.processing_pdf),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                pdfView.fromUri(uri)
                    .defaultPage(0)
                    .enableSwipe(true)
                    .swipeHorizontal(false)
                    .pageSnap(true)
                    .autoSpacing(true)
                    .pageFling(true)
                    .enableDoubletap(true)
                    .enableAnnotationRendering(false)
                    .scrollHandle(DefaultScrollHandle(this@MainActivity))
                    .spacing(10)
                    .onLoad { pageCount ->
                        // PDF loaded successfully
                    }
                    .onError { throwable ->
                        // Handle error
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.error_reading_pdf, throwable.message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    .load()

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.error_reading_pdf, e.message),
                        Toast.LENGTH_LONG
                    ).show()
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
                // Take persistable URI permission
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: SecurityException) {
                    // Permission might already be taken or not available
                }
                loadPDF(uri)
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
            // Set language to match system or default to English
            val result = tts.setLanguage(Locale.getDefault())
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, R.string.language_not_supported, Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, R.string.tts_init_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleReading() {
        if (isSpeaking) {
            stopReading()
        } else {
            startReading()
        }
    }

    private fun startReading() {
        if (!::tts.isInitialized || currentUri == null) return
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, R.string.processing_pdf, Toast.LENGTH_SHORT).show()
                }
                
                // Extract text from current page
                readPage(pdfView.currentPage)
                
                withContext(Dispatchers.Main) {
                    if (sentences.isNotEmpty()) {
                        isSpeaking = true
                        currentReadingIndex = 0
                        updateTimeRemaining(0)
                        startTimeUpdateTimer()
                        binding.mediaControls.btnPlayPause.setImageResource(R.drawable.baseline_pause_24)
                        
                        binding.mediaControls.audioProgress.max = 100
                        binding.mediaControls.audioProgress.progress = 0
                        
                        tts.setSpeechRate(speechRate)
                        currentSentence = sentences[0]
                        highlightCurrentSentence(currentSentence)
                        tts.speak(currentSentence, TextToSpeech.QUEUE_FLUSH, null, "0")
                        
                        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String) {
                                utteranceId.toIntOrNull()?.let { index ->
                                    currentReadingIndex = index
                                    currentSentence = sentences[index]
                                    lastSentenceStartTime = System.currentTimeMillis() // Record start time
                                    runOnUiThread {
                                        updateTimeRemaining(index)
                                        highlightCurrentSentence(currentSentence)
                                    }
                                }
                            }

                            override fun onDone(utteranceId: String) {
                                utteranceId.toIntOrNull()?.let { index ->
                                    if (index < sentences.size - 1) {
                                        // Continue reading current page
                                        currentSentence = sentences[index + 1]
                                        highlightCurrentSentence(currentSentence)
                                        tts.speak(currentSentence, TextToSpeech.QUEUE_FLUSH, null, (index + 1).toString())
                                    } else {
                                        // Check if there's a next page
                                        runOnUiThread {
                                            if (pdfView.currentPage < pdfView.pageCount - 1) {
                                                // Move to next page
                                                pdfView.jumpTo(pdfView.currentPage + 1)
                                                // Start reading the new page
                                                lifecycleScope.launch {
                                                    delay(500) // Small delay to let page load
                                                    readNextPage()
                                                }
                                            } else {
                                                // No more pages, stop reading
                                                stopReading()
                                            }
                                        }
                                    }
                                }
                            }

                            override fun onError(utteranceId: String) {
                                Log.e("TTS", "Error with utterance $utteranceId")
                            }
                        })
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.error_reading_pdf, e.message),
                        Toast.LENGTH_SHORT
                    ).show()
                    stopReading()
                }
            }
        }
    }

    private fun readNextPage() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                readPage(pdfView.currentPage)
                if (sentences.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        currentReadingIndex = 0
                        currentSentence = sentences[0]
                        binding.mediaControls.audioProgress.progress = 0
                        updateTimeRemaining(0)
                        highlightCurrentSentence(currentSentence)
                        tts.speak(currentSentence, TextToSpeech.QUEUE_FLUSH, null, "0")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { 
                    stopReading()
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.error_reading_pdf, e.message),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun readPage(pageNumber: Int) {
        val document = PDDocument.load(contentResolver.openInputStream(currentUri!!))
        val stripper = PDFTextStripper().apply {
            startPage = pageNumber + 1
            endPage = pageNumber + 1
        }
        val pageText = stripper.getText(document)
        document.close()
        
        // Split text into sentences, handling multiple types of sentence endings
        sentences = pageText.split("""[.!?]\s+""".toRegex())
            .filter { it.isNotBlank() }
            .map { it.trim() + "." } // Add period back to sentences
    }

    private fun stopReading() {
        timeUpdateTimer?.cancel()
        timeUpdateTimer = null
        lastSentenceStartTime = 0
        
        if (::tts.isInitialized) {
            tts.stop()
            isSpeaking = false
            currentSentence = ""
            clearHighlight()
            binding.mediaControls.btnPlayPause.setImageResource(R.drawable.baseline_play_arrow_24)
            binding.mediaControls.audioProgress.progress = 0
            binding.mediaControls.timeRemaining.text = String.format(
                getString(R.string.time_remaining),
                0,
                0
            )
        }
    }

    private fun highlightCurrentSentence(sentence: String) {
        // Remove previous highlight if exists
        clearHighlight()
        
        if (sentence.isBlank()) return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val document = PDDocument.load(contentResolver.openInputStream(currentUri!!))
                val stripper = object : PDFTextStripper() {
                    var foundPosition: RectF? = null
                    
                    override fun processTextPosition(text: TextPosition) {
                        if (foundPosition == null && text.unicode.contains(sentence.take(20), ignoreCase = true)) {
                            // Found the start of our sentence
                            val pageSize = getCurrentPageSize()
                            foundPosition = RectF(
                                text.x,
                                text.y,
                                text.x + text.width,
                                text.y + text.height
                            )
                        }
                        super.processTextPosition(text)
                    }
                }.apply {
                    startPage = pdfView.currentPage + 1
                    endPage = pdfView.currentPage + 1
                }
                
                // This will process the page and populate foundPosition
                stripper.getText(document)
                document.close()

                stripper.foundPosition?.let { position ->
                    withContext(Dispatchers.Main) {
                        createHighlightOverlay(position)
                    }
                }
            } catch (e: Exception) {
                Log.e("PDF", "Error highlighting text: ${e.message}")
            }
        }
    }

    private fun createHighlightOverlay(position: RectF) {
        val highlight = View(this).apply {
            background = ContextCompat.getDrawable(context, currentHighlightColor)
            alpha = 0.3f
        }

        val pageSize = getCurrentPageSize()
        val container = binding.pdfView
        
        // Calculate scaling factors
        val scaleX = container.width.toFloat() / pageSize.width
        val scaleY = container.height.toFloat() / pageSize.height

        val layoutParams = FrameLayout.LayoutParams(
            (position.width() * scaleX).toInt(),
            (position.height() * scaleY).toInt()
        ).apply {
            leftMargin = (position.left * scaleX).toInt()
            topMargin = (position.top * scaleY).toInt()
        }

        highlight.layoutParams = layoutParams

        // Add the highlight view to the PDFView's parent
        (container.parent as ViewGroup).addView(highlight)
        highlightView = highlight
    }

    private fun clearHighlight() {
        highlightView?.let { highlight ->
            (highlight.parent as? ViewGroup)?.removeView(highlight)
            highlightView = null
        }
    }

    // Data class to wrap TextPosition for easier handling
    data class TextPositionWrapper(val textPosition: TextPosition) {
        val x: Float = textPosition.x
        val y: Float = textPosition.y
        val width: Float = textPosition.width
        val height: Float = textPosition.height
        val text: String = textPosition.unicode
        val boundingBox: RectF = RectF(x, y, x + width, y + height)
        
        lateinit var pageSize: PageSize
    }

    // Extension function to find sentence position
    private fun List<TextPositionWrapper>.findSentencePosition(sentence: String): TextPositionWrapper? {
        return this.find { it.text.contains(sentence, ignoreCase = true) }
    }

    // Helper function to get current page text
    private fun getCurrentPageText(): String {
        return try {
            val document = PDDocument.load(contentResolver.openInputStream(currentUri!!))
            val stripper = PDFTextStripper()
            stripper.startPage = pdfView.currentPage + 1  // PDFBox pages start from 1
            stripper.endPage = pdfView.currentPage + 1
            val text = stripper.getText(document)
            document.close()
            text
        } catch (e: Exception) {
            Log.e("PDF", "Error getting page text: ${e.message}")
            ""
        }
    }

    private fun setupMediaControls() {
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

            // Speed button click handler
            btnSpeed.setOnClickListener {
                speedControlContainer.visibility = if (speedControlContainer.visibility == View.VISIBLE) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
            }

            // Speed control setup
            speedSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    speechRate = (progress + 5) / 10f  // Range of 0.5x to 2.5x
                    speedText.text = String.format("%.1fx", speechRate)
                    if (isSpeaking) {
                        tts.setSpeechRate(speechRate)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })

            // Add this with other button handlers
            btnStop.setOnClickListener {
                stopReading()
                // Reset progress
                audioProgress.progress = 0
                // Reset play button icon
                btnPlayPause.setImageResource(R.drawable.baseline_play_arrow_24)
            }

            audioProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                private var userDragging = false
                
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser && sentences.isNotEmpty()) {
                        val newIndex = (progress * sentences.size / 100f).toInt().coerceIn(0, sentences.size - 1)
                        currentReadingIndex = newIndex
                        currentSentence = sentences[newIndex]
                        updateTimeRemaining(newIndex)
                        
                        if (userDragging) {
                            // Preview highlight without changing audio
                            highlightCurrentSentence(currentSentence)
                        }
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    userDragging = true
                    if (isSpeaking) {
                        tts.stop()
                    }
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    userDragging = false
                    if (isSpeaking) {
                        // Resume speaking from new position
                        tts.speak(currentSentence, TextToSpeech.QUEUE_FLUSH, null, currentReadingIndex.toString())
                    }
                }
            })
        }
    }

    private fun updateTimeRemaining(currentIndex: Int) {
        if (sentences.isEmpty()) return

        // Calculate remaining sentences
        val remainingSentences = sentences.subList(currentIndex, sentences.size)
        
        // Calculate total estimated duration for remaining sentences
        var totalDurationMs = 0L
        remainingSentences.forEach { sentence ->
            totalDurationMs += estimateSentenceDuration(sentence)
        }
        
        // Add small pauses between sentences
        totalDurationMs += (remainingSentences.size - 1) * 300 // 300ms pause between sentences
        
        // Convert to minutes and seconds
        val totalSeconds = totalDurationMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60

        // Update UI
        binding.mediaControls.timeRemaining.text = String.format(
            getString(R.string.time_remaining),
            minutes,
            seconds
        )
    }

    private fun calculateProgressForCurrentSentence(): Int {
        if (sentences.isEmpty()) return 0
        
        // Calculate base progress from completed sentences
        val baseProgress = (currentReadingIndex.toFloat() / sentences.size * 100)
        
        // Calculate progress within current sentence
        val currentTime = System.currentTimeMillis()
        val sentenceStartTime = lastSentenceStartTime
        val estimatedSentenceDuration = estimateSentenceDuration(currentSentence)
        
        val progressWithinSentence = if (sentenceStartTime > 0) {
            val timeElapsed = currentTime - sentenceStartTime
            val progressPercent = (timeElapsed.toFloat() / estimatedSentenceDuration).coerceIn(0f, 1f)
            (progressPercent * (100f / sentences.size))
        } else {
            0f
        }
        
        return (baseProgress + progressWithinSentence).toInt().coerceIn(0, 100)
    }

    private fun estimateSentenceDuration(sentence: String): Long {
        // Estimate duration based on word count and speech rate
        val wordCount = sentence.split("\\s+".toRegex()).size
        val wordsPerSecond = speechRate * 2.5f // Approximate words per second at normal rate
        return (wordCount * 1000 / wordsPerSecond).toLong() // Duration in milliseconds
    }

    // Add these properties to the class
    private var lastSentenceStartTime: Long = 0
    
    private fun toggleMediaControlsVisibility(show: Boolean) {
        isMediaControlsVisible = show
        binding.mediaControls.root.visibility = if (show) View.VISIBLE else View.GONE
        
        // Update read FAB icon if needed
        binding.fabRead.setImageResource(
            if (show) R.drawable.baseline_close_24
            else R.drawable.baseline_play_arrow_24
        )
    }

    private fun changeHighlightColor(colorRes: Int) {
        currentHighlightColor = colorRes
        // Update the highlight if it exists
        highlightView?.background?.setTint(ContextCompat.getColor(this, colorRes))
    }

    // Optional: Add a menu item or button to change colors
    private fun addColorChangeOption() {
        // Add color change option to the FAB menu
        binding.fabRead.setOnLongClickListener {
            showColorPickerDialog()
            true
        }
    }

    private fun showColorPickerDialog() {
        val colors = listOf(
            R.color.highlight_blue to "Blue",
            R.color.highlight_yellow to "Yellow",
            R.color.highlight_green to "Green",
            R.color.highlight_orange to "Orange",
            R.color.highlight_high_contrast to "High Contrast",
            R.color.highlight_light_contrast to "Light"
        )
        
        val colorNames = colors.map { it.second }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("Select Highlight Color")
            .setItems(colorNames) { _, which ->
                changeHighlightColor(colors[which].first)
            }
            .show()
    }

    private fun setupFabMenu() {
        // Main FAB click listener
        binding.fabMain.setOnClickListener {
            toggleFabMenu()
        }
        
        // Add PDF FAB
        binding.fabAdd.setOnClickListener {
            toggleFabMenu()
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/pdf"
            }
            startActivityForResult(intent, PICK_PDF_FILE)
        }
        
        // Read FAB
        binding.fabRead.setOnClickListener {
            toggleFabMenu()
            toggleMediaControlsVisibility(true)
        }
        
        // Read FAB long press for color options
        binding.fabRead.setOnLongClickListener {
            toggleFabMenu()
            showColorPickerDialog()
            true
        }
        
        // Language FAB
        binding.fabLanguage.setOnClickListener {
            toggleFabMenu()
            showLanguageDialog()
        }
        
        // Click outside to close menu
        binding.pdfView.setOnClickListener {
            if (isFabMenuExpanded) {
                toggleFabMenu()
            }
        }
    }
    
    private fun toggleFabMenu() {
        isFabMenuExpanded = !isFabMenuExpanded
        
        val rotation = if (isFabMenuExpanded) 45f else 0f
        binding.fabMain.animate().rotation(rotation).setDuration(200)
        
        val fabs = listOf(binding.fabAdd, binding.fabRead, binding.fabLanguage)
        
        fabs.forEachIndexed { index, fab ->
            if (isFabMenuExpanded) {
                // Remove default animation
                fab.alpha = 1f
                fab.visibility = View.VISIBLE
                fab.animate()
                    .translationY(-((index + 1) * 16 + index * 56).toFloat())
                    .setDuration(200)
                    .withLayer()  // This helps prevent unwanted overlays
                    .start()
            } else {
                fab.animate()
                    .translationY(0f)
                    .setDuration(200)
                    .withLayer()  // This helps prevent unwanted overlays
                    .withEndAction {
                        if (!isFabMenuExpanded) {
                            fab.visibility = View.GONE
                        }
                    }
                    .start()
            }
        }
    }
    
    private fun showLanguageDialog() {
        val languages = arrayOf(
            "English", 
            "Deutsch", 
            "Français", 
            "简体中文", 
            "Svenska"
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.select_language))
            .setItems(languages) { _, which ->
                val locale = when (which) {
                    0 -> Locale.ENGLISH
                    1 -> Locale.GERMAN
                    2 -> Locale.FRENCH
                    3 -> Locale.SIMPLIFIED_CHINESE
                    4 -> Locale("sv") // Swedish
                    else -> Locale.getDefault()
                }
                tts.language = locale
                // Check if language is available
                val result = tts.setLanguage(locale)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Toast.makeText(this, getString(R.string.language_not_supported), Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun startTimeUpdateTimer() {
        timeUpdateTimer?.cancel()
        timeUpdateTimer = Timer().apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    runOnUiThread {
                        if (isSpeaking && sentences.isNotEmpty()) {
                            // Update time remaining
                            updateTimeRemaining(currentReadingIndex)
                            
                            // Update progress bar
                            val estimatedProgress = calculateProgressForCurrentSentence()
                            binding.mediaControls.audioProgress.progress = estimatedProgress
                        }
                    }
                }
            }, 0, 100) // Update every 100ms for smoother progress
        }
    }

    override fun onDestroy() {
        timeUpdateTimer?.cancel()
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }

    // Add this helper function to get page dimensions
    private fun getCurrentPageSize(): PageSize {
        return try {
            val document = PDDocument.load(contentResolver.openInputStream(currentUri!!))
            val page = document.getPage(pdfView.currentPage)
            val size = PageSize(
                page.cropBox.width.toFloat(),
                page.cropBox.height.toFloat()
            )
            document.close()
            size
        } catch (e: Exception) {
            Log.e("PDF", "Error getting page size: ${e.message}")
            PageSize(pdfView.width.toFloat(), pdfView.height.toFloat())
        }
    }
}
