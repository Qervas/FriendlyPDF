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
                        binding.mediaControls.btnPlayPause.setImageResource(R.drawable.baseline_pause_24)
                        
                        tts.setSpeechRate(speechRate)
                        tts.speak(sentences[0], TextToSpeech.QUEUE_FLUSH, null, "0")
                        
                        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String) {
                                utteranceId.toIntOrNull()?.let { index ->
                                    currentSentence = sentences[index]
                                    runOnUiThread {
                                        val progress = (index.toFloat() / sentences.size * 100).toInt()
                                        binding.mediaControls.audioProgress.progress = progress
                                        updateTimeRemaining(index)
                                    }
                                }
                            }
                            
                            override fun onDone(utteranceId: String) {
                                utteranceId.toIntOrNull()?.let { index ->
                                    val nextIndex = index + 1
                                    if (nextIndex < sentences.size) {
                                        tts.speak(sentences[nextIndex], TextToSpeech.QUEUE_ADD, null, nextIndex.toString())
                                    } else {
                                        // Move to next page if available
                                        if (pdfView.currentPage < pdfView.pageCount - 1) {
                                            runOnUiThread {
                                                pdfView.jumpTo(pdfView.currentPage + 1)
                                                readNextPage()
                                            }
                                        } else {
                                            runOnUiThread { stopReading() }
                                        }
                                    }
                                }
                            }
                            
                            override fun onError(utteranceId: String) {
                                runOnUiThread { stopReading() }
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
                        Toast.makeText(this@MainActivity, 
                            getString(R.string.reading_page, pdfView.currentPage + 1), 
                            Toast.LENGTH_SHORT).show()
                        tts.speak(sentences[0], TextToSpeech.QUEUE_FLUSH, null, "0")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { stopReading() }
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
        
        sentences = pageText.split("""[.!?]\s+""".toRegex())
            .filter { it.isNotBlank() }
            .map { it.trim() }
    }

    private fun stopReading() {
        if (::tts.isInitialized) {
            tts.stop()
            isSpeaking = false
            currentSentence = ""
            sentences = listOf()
            // Update UI
            binding.mediaControls.btnPlayPause.setImageResource(R.drawable.baseline_play_arrow_24)
            binding.mediaControls.audioProgress.progress = 0
        }
    }

    private fun highlightCurrentSentence() {
        // Remove previous highlight if it exists
        clearHighlight()
        
        // Get the current page text location
        val pageText = getCurrentPageText()
        if (pageText.isNullOrEmpty() || currentSentence.isEmpty()) return

        // Find the sentence position in the page
        val startIndex = pageText.indexOf(currentSentence)
        if (startIndex == -1) return

        // Calculate the highlight bounds
        val textBounds = calculateTextBounds(startIndex, currentSentence.length)
        if (textBounds != null) {
            // Create and add highlight overlay
            createHighlightOverlay(textBounds)
        }
    }

    private fun calculateTextBounds(startIndex: Int, length: Int): RectF? {
        // This is a simplified example. You'll need to adjust based on your PDF viewer
        val pageWidth = pdfView.width.toFloat()
        val pageHeight = pdfView.height.toFloat()
        
        // Calculate the relative position (this is approximate and needs to be adjusted)
        val lineHeight = 20f // Approximate line height
        val charsPerLine = 50 // Approximate characters per line
        
        val line = startIndex / charsPerLine
        val yPosition = line * lineHeight
        
        return RectF(
            20f, // Left margin
            yPosition,
            pageWidth - 20f, // Right margin
            yPosition + lineHeight
        )
    }

    private fun createHighlightOverlay(bounds: RectF) {
        // Create a new View for highlighting
        val highlight = View(this).apply {
            background = ContextCompat.getDrawable(context, R.drawable.text_highlight_background)
            elevation = 4f
        }
        
        // Add the highlight view to the layout
        val container = binding.pdfView.parent as ViewGroup
        container.addView(highlight)
        
        // Position the highlight
        highlight.layoutParams = FrameLayout.LayoutParams(
            bounds.width().toInt(),
            bounds.height().toInt()
        ).apply {
            leftMargin = bounds.left.toInt()
            topMargin = bounds.top.toInt()
        }
        
        highlightView = highlight
    }

    private fun clearHighlight() {
        highlightView?.let { highlight ->
            (highlight.parent as? ViewGroup)?.removeView(highlight)
            highlightView = null
        }
    }

    // Helper function to get current page text
    private fun getCurrentPageText(): String? {
        return try {
            val document = PDDocument.load(contentResolver.openInputStream(currentUri!!))
            val stripper = PDFTextStripper()
            stripper.startPage = pdfView.currentPage + 1
            stripper.endPage = pdfView.currentPage + 1
            val text = stripper.getText(document)
            document.close()
            text
        } catch (e: Exception) {
            e.printStackTrace()
            null
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
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser && sentences.isNotEmpty()) {
                        val newIndex = (progress * sentences.size / 100f).toInt()
                        if (newIndex < sentences.size) {
                            currentSentence = sentences[newIndex]
                            if (isSpeaking) {
                                tts.stop()
                                tts.speak(currentSentence, TextToSpeech.QUEUE_FLUSH, null, newIndex.toString())
                            }
                            updateTimeRemaining(newIndex)
                        }
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }
    }

    private fun updateTimeRemaining(currentIndex: Int) {
        if (sentences.isEmpty()) return
        
        // Calculate remaining text
        val remainingText = sentences.subList(currentIndex, sentences.size).joinToString(" ")
        
        // More accurate time calculation:
        // - Base rate: ~175 characters per minute at 1.0x speed
        // - Adjust for current speech rate
        val charsPerMinute = 175.0 * speechRate
        val remainingChars = remainingText.length
        val remainingMinutes = remainingChars / charsPerMinute
        
        // Convert to minutes and seconds
        val minutes = remainingMinutes.toInt()
        val seconds = ((remainingMinutes - minutes) * 60).toInt()
        
        // Update UI
        binding.mediaControls.timeRemaining.text = String.format(
            getString(R.string.time_remaining),
            minutes,
            seconds
        )
    }

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

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }
}
