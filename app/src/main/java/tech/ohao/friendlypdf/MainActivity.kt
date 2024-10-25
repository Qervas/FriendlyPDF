package tech.ohao.friendlypdf

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle
import tech.ohao.friendlypdf.databinding.ActivityMainBinding
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
import android.graphics.RectF
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AlertDialog
import android.util.Log
import android.graphics.Color
import android.content.res.Configuration
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.content.ContentResolver
import android.provider.OpenableColumns
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import java.io.File
import java.io.FileOutputStream
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import android.view.animation.AnticipateInterpolator
import android.speech.tts.Voice
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import java.util.Timer
import java.util.TimerTask
import android.os.Build

// Make PageSize public by moving it outside the file-level scope
data class PageSize(val width: Float, val height: Float)

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var pdfView: PDFView
    private var ttsService: TTSService? = null
    private var isSpeaking = false
    private var currentPage = 0
    private val PICK_PDF_FILE = 2
    private var currentUri: Uri? = null 
    
    // View mode states
    private enum class ViewMode {
        FIT_WIDTH, FIT_PAGE, CROP_MARGINS
    }
    private var currentViewMode = ViewMode.FIT_WIDTH
    private var isMediaControlsVisible = false
    private var speechRate = 1.0f
    private var currentSentence = ""
    private var sentences = listOf<String>()
    private var highlightView: View? = null
    private var currentHighlightColor = R.color.highlight_blue
    private var isFabMenuExpanded = false

    private var currentReadingIndex = 0

    private var lastSentenceStartTime: Long = 0 

    private var isDarkMode = false

    private var isTTSServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TTSService.TTSBinder
            ttsService = binder.getService()
            isTTSServiceBound = true
            initializeTTSWithSavedVoice()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            ttsService = null
            isTTSServiceBound = false
        }
    }

    private lateinit var database: AppDatabase

    private var volume = 1.0f
    private var pitch = 1.0f
    private val ssmlEnabled = true
    private var currentVoice: Voice? = null
    private var sleepTimeInMinutes: Int = 0
    private var timeUpdateTimer: Timer? = null
    private var sleepTimer: Timer? = null

    companion object {
        const val PREFS_NAME = "FriendlyPDFPrefs"
        private const val LAST_PDF_URI = "last_pdf_uri"
        private const val LAST_PAGE_NUMBER = "last_page_number"
        const val THEME_PREF = "theme_preference"  // Add this
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                123
            )
        }
        
        // Initialize database
        database = AppDatabase.getInstance(applicationContext)
        
        // Initialize PDFBox
        PDFBoxResourceLoader.init(applicationContext)
        
        // Check system dark mode setting
        val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        isDarkMode = currentNightMode == Configuration.UI_MODE_NIGHT_YES

        // Initialize PDF view with system theme
        pdfView = binding.pdfView
        pdfView.setNightMode(isDarkMode)
        pdfView.setBackgroundColor(if (isDarkMode) Color.BLACK else Color.WHITE)
        
        // Setup other UI components
        setupMediaControls()
        setupFabMenu()
        supportActionBar?.hide()
        setupThemeButton()

        // Start and bind to TTS Service
        Intent(this, TTSService::class.java).also { intent ->
            startService(intent)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        // Initialize saved voice preference after service binding
        initializeTTSWithSavedVoice()

        // Load last opened PDF or handle incoming intent
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            val bookId = intent.getLongExtra("BOOK_ID", -1L)
            if (bookId != -1L) {
                lifecycleScope.launch {
                    val book = withContext(Dispatchers.IO) {
                        database.bookDao().getBookById(bookId)
                    }
                    book?.let {
                        loadPdfWithCurrentSettings(Uri.parse(it.uri), it.lastPageRead, it)
                    }
                }
            } else {
                loadPDF(intent.data!!)
            }
        } else {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val lastPdfUri = prefs.getString(LAST_PDF_URI, null)
            val lastPageNumber = prefs.getInt(LAST_PAGE_NUMBER, 0)
            Log.d("PDF_READER", "Last saved URI: $lastPdfUri, Last saved page: $lastPageNumber")
            if (lastPdfUri != null) {
                val uri = Uri.parse(lastPdfUri)
                loadPdfWithCurrentSettings(uri, lastPageNumber)
            }
        }

        setupBookshelfFab()

        // Handle the intent to open a new book
        intent?.data?.let { uri ->
            lifecycleScope.launch {
                val book = handleBookOpening(uri)
                loadPdfWithCurrentSettings(uri, book.lastPageRead, book)
            }
        }
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
        
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.processing_pdf),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                // Handle book database entry
                val book = handleBookOpening(uri)

                // If in dark mode, force a theme cycle to ensure proper rendering
                if (isDarkMode) {
                    pdfView.post {
                        pdfView.setNightMode(false)
                        pdfView.setBackgroundColor(Color.WHITE)
                        
                        // Toggle back to dark after a short delay
                        pdfView.postDelayed({
                            pdfView.setNightMode(true)
                            pdfView.setBackgroundColor(Color.BLACK)
                            loadPdfWithCurrentSettings(uri, book.lastPageRead, book)
                        }, 100)
                    }
                } else {
                    loadPdfWithCurrentSettings(uri, book.lastPageRead, book)
                }

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
                loadPdfWithCurrentSettings(uri, 0)  // Start from the first page for newly opened PDFs
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

    private fun loadPdfWithCurrentSettings(uri: Uri, pageNumber: Int = 0, book: Book? = null) {
        currentUri = uri
        
        lifecycleScope.launch {
            val openedBook = book ?: handleBookOpening(uri)
            val startPage = book?.lastPageRead ?: pageNumber
            
            Log.d("PDF_READER", "Loading PDF with page number: $startPage")
            pdfView.fromUri(uri)
                .defaultPage(startPage)
                .onPageChange { page, pageCount ->
                    currentPage = page
                    updatePageNumber(page + 1, pageCount)
                    Log.d("PDF_READER", "Page changed to: ${page + 1}")
                    // Update the current page number
                    lifecycleScope.launch(Dispatchers.IO) {
                        val updatedBook = openedBook.copy(lastPageRead = page, lastReadTimestamp = System.currentTimeMillis())
                        database.bookDao().updateBook(updatedBook)
                    }
                }
                .enableSwipe(true)
                .swipeHorizontal(false)
                .pageSnap(true)
                .autoSpacing(true)
                .pageFling(true)
                .enableDoubletap(true)
                .enableAnnotationRendering(false)
                .scrollHandle(DefaultScrollHandle(this@MainActivity))  // Use this@MainActivity instead of this
                .spacing(10)
                .nightMode(isDarkMode)
                .onLoad { 
                    Log.d("PDF_READER", "PDF loaded, current page: ${pdfView.currentPage}")
                }
                .load()
        }
    }

    private fun setAppLanguage(locale: Locale) {
        // Update TTS language
        ttsService?.let { service ->
            val result = service.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, getString(R.string.language_not_supported), Toast.LENGTH_SHORT).show()
                return
            }
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

    private fun toggleReading() {
        if (isSpeaking) {
            stopReading()
        } else {
            // Update time estimate before starting reading
            if (sentences.isNotEmpty()) {
                updateTimeRemaining()
            }
            startReading()
        }
    }

    private fun startTimeUpdateTimer() {
        timeUpdateTimer?.cancel()
        timeUpdateTimer = Timer("TimeUpdateTimer").apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    runOnUiThread {
                        // Only update progress if we're actually speaking
                        if (isSpeaking && sentences.isNotEmpty()) {
                            val estimatedProgress = calculateProgressForCurrentSentence()
                            binding.mediaControls.audioProgress.progress = estimatedProgress
                        }
                    }
                }
            }, 0, 100)  // Update every 100ms for smoother progress
        }
    }

    private fun calculateProgressForCurrentSentence(): Int {
        if (sentences.isEmpty()) return 0

        val baseProgress = (currentReadingIndex.toFloat() / sentences.size * 100)

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

        val totalProgress = baseProgress + progressWithinSentence
        Log.d("Progress", "Base: $baseProgress, Within Sentence: $progressWithinSentence, Total: $totalProgress")

        return totalProgress.toInt().coerceIn(0, 100)
    }

    private fun estimateSentenceDuration(sentence: String): Long {
        val wordCount = sentence.split("\\s+".toRegex()).size
        val wordsPerSecond = speechRate * 2.5f // Approximate words per second at normal rate
        return (wordCount * 1000 / wordsPerSecond).toLong() // Duration in milliseconds
    }

    private fun startReading() {
        if (currentUri == null) return
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, R.string.processing_pdf, Toast.LENGTH_SHORT).show()
                }
                
                Log.d("PDF_READER", "Starting to read page ${pdfView.currentPage}")
                readPage(pdfView.currentPage)
                
                withContext(Dispatchers.Main) {
                    if (sentences.isNotEmpty()) {
                        Log.d("PDF_READER", "Sentences extracted: ${sentences.size}")
                        isSpeaking = true
                        currentReadingIndex = 0
                        lastSentenceStartTime = System.currentTimeMillis()
                        
                        binding.mediaControls.btnPlayPause.setImageResource(R.drawable.baseline_pause_24)
                        binding.mediaControls.audioProgress.max = 100
                        binding.mediaControls.audioProgress.progress = 0
                        
                        startTimeUpdateTimer()
                        
                        Log.d("PDF_READER", "Setting speech rate: $speechRate")
                        ttsService?.setSpeechRate(speechRate)
                        currentSentence = sentences[0]
                        updateTimeRemaining()
                        highlightCurrentSentence(currentSentence)
                        
                        // Enhanced speech parameters
                        val params = Bundle().apply {
                            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "0")
                            // Use the correct constant keys for TTS parameters
                            putFloat("volume", volume)  // Custom parameter for volume
                            putFloat("pitch", pitch)    // Custom parameter for pitch
                            
                            // Add breathing room between sentences
                            putString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS, "true")
                            putString(TextToSpeech.Engine.KEY_PARAM_STREAM, 
                                android.media.AudioManager.STREAM_MUSIC.toString())
                        }

                        // Format text with SSML if supported
                        val textToSpeak = if (ssmlEnabled) {
                            formatWithSSML(currentSentence)
                        } else {
                            currentSentence
                        }

                        ttsService?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, params, "0")
                        
                        ttsService?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String) {
                                Log.d("PDF_READER", "Started utterance: $utteranceId")
                                utteranceId.toIntOrNull()?.let { index ->
                                    currentReadingIndex = index
                                    currentSentence = sentences[index]
                                    lastSentenceStartTime = System.currentTimeMillis()
                                    runOnUiThread {
                                        updateTimeRemaining()
                                        highlightCurrentSentence(currentSentence)
                                    }
                                }
                            }

                            override fun onDone(utteranceId: String) {
                                Log.d("PDF_READER", "Finished utterance: $utteranceId")
                                if (!isSpeaking) return  // Don't continue if stopped manually
                                
                                utteranceId.toIntOrNull()?.let { index ->
                                    if (index < sentences.size - 1) {
                                        // Continue with next sentence on current page
                                        runOnUiThread {
                                            currentSentence = sentences[index + 1]
                                            highlightCurrentSentence(currentSentence)
                                            ttsService?.speak(
                                                currentSentence, 
                                                TextToSpeech.QUEUE_FLUSH, 
                                                null, 
                                                (index + 1).toString()
                                            )
                                        }
                                    } else {
                                        // Check for next page
                                        runOnUiThread {
                                            if (pdfView.currentPage < pdfView.pageCount - 1) {
                                                // Move to next page
                                                pdfView.jumpTo(pdfView.currentPage + 1)
                                                lifecycleScope.launch {
                                                    delay(500) // Wait for page to load
                                                    readNextPage()
                                                }
                                            } else {
                                                // End of document reached
                                                stopReading()
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    getString(R.string.reading_completed),
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }
                                }
                            }

                            override fun onError(utteranceId: String) {
                                Log.e("PDF_READER", "Error with utterance $utteranceId")
                                // Try to recover by moving to next sentence
                                utteranceId.toIntOrNull()?.let { index ->
                                    if (index < sentences.size - 1) {
                                        runOnUiThread {
                                            currentSentence = sentences[index + 1]
                                            ttsService?.speak(
                                                currentSentence,
                                                TextToSpeech.QUEUE_FLUSH,
                                                null,
                                                (index + 1).toString()
                                            )
                                        }
                                    }
                                }
                            }
                        })
                    } else {
                        Log.w("PDF_READER", "No sentences extracted from the current page")
                    }
                }
            } catch (e: Exception) {
                Log.e("PDF_READER", "Error in startReading", e)
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

    private fun updateTimeRemaining() {
        if (sentences.isEmpty()) return

        val remainingSentences = sentences.size - currentReadingIndex
        val remainingWords = sentences.subList(currentReadingIndex, sentences.size)
            .sumBy { it.split("\\s+".toRegex()).size }

        val wordsPerSecond = speechRate * 2.5f
        val estimatedSeconds = (remainingWords / wordsPerSecond).toLong()

        val minutes = estimatedSeconds / 60
        val seconds = estimatedSeconds % 60

        binding.mediaControls.timeRemaining.text = String.format(
            getString(R.string.time_remaining),
            minutes,
            seconds
        )
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
                        updateTimeRemaining()
                        highlightCurrentSentence(currentSentence)
                        
                        // Use the same speech parameters as in startReading
                        val params = Bundle().apply {
                            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "0")
                            putFloat("volume", volume)
                            putFloat("pitch", pitch)
                            putString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS, "true")
                            putString(TextToSpeech.Engine.KEY_PARAM_STREAM, 
                                android.media.AudioManager.STREAM_MUSIC.toString())
                        }

                        val textToSpeak = if (ssmlEnabled) {
                            formatWithSSML(currentSentence)
                        } else {
                            currentSentence
                        }

                        ttsService?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, params, "0")
                    }
                } else {
                    // If no sentences on this page, try next page
                    withContext(Dispatchers.Main) {
                        if (pdfView.currentPage < pdfView.pageCount - 1) {
                            pdfView.jumpTo(pdfView.currentPage + 1)
                            delay(500)
                            readNextPage()
                        } else {
                            stopReading()
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.reading_completed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
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
        
        ttsService?.stop()
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

    private fun highlightCurrentSentence(sentence: String) {
        clearHighlight()

        if (sentence.isBlank()) return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val document = PDDocument.load(contentResolver.openInputStream(currentUri!!))
                val stripper = object : PDFTextStripper() {
                    var foundPosition: RectF? = null

                    override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                        if (foundPosition != null) return

                        val normalizedText = text.replace("\n", " ").trim()
                        val normalizedSentence = sentence.replace("\n", " ").trim()

                        val index = normalizedText.indexOf(normalizedSentence, ignoreCase = true)
                        if (index >= 0) {
                            var startPositionIndex = 0
                            var accumulatedLength = 0
                            for ((i, tp) in textPositions.withIndex()) {
                                accumulatedLength += tp.unicode.length
                                if (accumulatedLength >= index + 1) {
                                    startPositionIndex = i
                                    break
                                }
                            }

                            var endPositionIndex = startPositionIndex
                            accumulatedLength = 0
                            for (i in startPositionIndex until textPositions.size) {
                                accumulatedLength += textPositions[i].unicode.length
                                if (accumulatedLength >= normalizedSentence.length) {
                                    endPositionIndex = i
                                    break
                                }
                            }

                            val sentencePositions = textPositions.subList(startPositionIndex, endPositionIndex + 1)
                            val x = sentencePositions.minOf { it.x }
                            val y = sentencePositions.minOf { it.y }
                            val maxX = sentencePositions.maxOf { it.x + it.width }
                            val maxY = sentencePositions.maxOf { it.y + it.height }
                            val width = maxX - x
                            val height = maxY - y

                            foundPosition = RectF(x, y, x + width, y + height)
                        }
                        super.writeString(text, textPositions)
                    }
                }.apply {
                    startPage = pdfView.currentPage + 1
                    endPage = pdfView.currentPage + 1
                }

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
            setBackgroundColor(ContextCompat.getColor(context, currentHighlightColor))
            alpha = 0.3f
        }

        val pageSize = getCurrentPageSize()
        val container = binding.pdfView

        // Calculate scaling factors
        val scaleX = container.width.toFloat() / pageSize.width
        val scaleY = container.height.toFloat() / pageSize.height

        // Adjust for coordinate systems (PDF vs Android)
        val left = position.left * scaleX
        val top = (pageSize.height - position.top - position.height()) * scaleY
        val width = position.width() * scaleX
        val height = position.height() * scaleY

        val layoutParams = FrameLayout.LayoutParams(
            width.toInt(),
            height.toInt()
        ).apply {
            leftMargin = left.toInt()
            topMargin = top.toInt()
        }

        highlight.layoutParams = layoutParams

        // Add the highlight view to the PDFView's parent
        (container.parent as ViewGroup).addView(highlight)
        highlightView = highlight
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
                toggleReading()
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
                        ttsService?.setSpeechRate(speechRate)
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
                        val newIndex = (progress * sentences.size / 100f).toInt().coerceIn(0, sentences.size - 1)
                        if (newIndex != currentReadingIndex) {
                            currentReadingIndex = newIndex
                            currentSentence = sentences[newIndex]
                            lastSentenceStartTime = System.currentTimeMillis()
                            updateTimeRemaining()
                            highlightCurrentSentence(currentSentence)
                        }
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    if (isSpeaking) {
                        ttsService?.stop()
                    }
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    if (isSpeaking) {
                        ttsService?.speak(currentSentence, TextToSpeech.QUEUE_FLUSH, null, currentReadingIndex.toString())
                    }
                }
            })

            btnVoice.setOnClickListener {
                showVoiceSelectionDialog()
            }

            // Add this with your other button handlers
            btnSleepTimer.setOnClickListener {
                showSleepTimerDialog()
            }
        }
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

        // Define all FABs in a single list
        val fabs = listOf(
            binding.fabBookshelf,
            binding.fabTheme,
            binding.fabLanguage,
            binding.fabRead,
            binding.fabAdd
        )
        
        // Animate FABs
        fabs.forEachIndexed { index, fab ->
            if (isFabMenuExpanded) {
                fab.alpha = 1f
                fab.visibility = View.VISIBLE
                fab.animate()
                    .translationY(-((index + 1) * 80).toFloat())
                    .setDuration(300)
                    .setInterpolator(FastOutSlowInInterpolator())
                    .withLayer()
                    .start()
            } else {
                fab.animate()
                    .translationY(0f)
                    .setDuration(200)
                    .setInterpolator(AnticipateInterpolator())
                    .withLayer()
                    .withEndAction {
                        if (!isFabMenuExpanded) {
                            fab.visibility = View.GONE
                        }
                    }
                    .start()
            }
        }
    }
    
    private fun toggleFabMenu() {
        isFabMenuExpanded = !isFabMenuExpanded
        
        val rotation = if (isFabMenuExpanded) 45f else 0f
        binding.fabMain.animate().rotation(rotation).setDuration(200)
        
        // Reverse the order of FABs
        val fabs = listOf(binding.fabBookshelf, binding.fabTheme, binding.fabLanguage, binding.fabRead, binding.fabAdd)
        
        fabs.forEachIndexed { index, fab ->
            if (isFabMenuExpanded) {
                fab.alpha = 1f
                fab.visibility = View.VISIBLE
                fab.animate()
                    .translationY(-((index + 1) * 80).toFloat()) // Increased spacing
                    .setDuration(300) // Slightly longer animation
                    .setInterpolator(FastOutSlowInInterpolator()) // Smooth opening animation
                    .withLayer()
                    .start()
            } else {
                fab.animate()
                    .translationY(0f)
                    .setDuration(200)
                    .setInterpolator(AnticipateInterpolator()) // Add anticipation when closing
                    .withLayer()
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
                setAppLanguage(locale)
            }
            .show()
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

    override fun onDestroy() {
        timeUpdateTimer?.cancel()
        sleepTimer?.cancel()
        ttsService?.stop()
        if (isTTSServiceBound) {
            unbindService(serviceConnection)
            isTTSServiceBound = false
        }
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

    private fun clearHighlight() {
        highlightView?.let { highlight ->
            (highlight.parent as? ViewGroup)?.removeView(highlight)
            highlightView = null
        }
    }

    private fun setupThemeButton() {
        binding.fabTheme.setOnClickListener {
            toggleFabMenu()
            toggleTheme()
        }
    }

    private fun toggleTheme() {
        isDarkMode = !isDarkMode
        
        // Save theme preference
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(THEME_PREF, isDarkMode)
            .apply()
            
        // Force theme change by toggling twice if in dark mode
        if (isDarkMode) {
            pdfView.setNightMode(false)
            pdfView.setBackgroundColor(Color.WHITE)
            currentUri?.let { uri ->
                val currentPage = pdfView.currentPage
                loadPdfWithCurrentSettings(uri, currentPage)
            }
            
            // Toggle back to dark after a short delay
            pdfView.postDelayed({
                pdfView.setNightMode(true)
                pdfView.setBackgroundColor(Color.BLACK)
                currentUri?.let { uri ->
                    val currentPage = pdfView.currentPage
                    loadPdfWithCurrentSettings(uri, currentPage)
                }
            }, 100)
        } else {
            updateTheme()
        }
    }

    private fun updateTheme() {
        // Hide the action bar
        supportActionBar?.hide()

        // Update PDF view night mode
        pdfView.setNightMode(isDarkMode)
        pdfView.setBackgroundColor(if (isDarkMode) Color.BLACK else Color.WHITE)
        
        // Force redraw the current page to apply night mode
        val currentPage = pdfView.currentPage
        pdfView.jumpTo(currentPage)

    }

    private fun checkSystemTheme() {
        val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        isDarkMode = currentNightMode == Configuration.UI_MODE_NIGHT_YES
        
        // Force a theme toggle if in dark mode to ensure proper PDF rendering
        if (isDarkMode) {
            isDarkMode = false  // Temporarily set to light mode
            toggleTheme()       // Toggle to dark mode properly
        } else {
            updateTheme()       // Just update for light mode
        }
    }

    override fun onPause() {
        super.onPause()
        currentUri?.let { uri ->
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val currentPage = pdfView.currentPage
            prefs.edit().apply {
                putString(LAST_PDF_URI, uri.toString())
                putInt(LAST_PAGE_NUMBER, currentPage)
                apply()
            }
            Log.d("PDF_READER", "Saved page number: $currentPage")
        }
    }

    private fun updatePageNumber(currentPage: Int, totalPages: Int) {
        binding.pageNumber.text = getString(R.string.page_number_format, currentPage, totalPages)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.data?.let { uri ->
            loadPdfWithCurrentSettings(uri, 0)  // Start from page 0 for new PDFs
        }
    }

    private fun setupBookshelfFab() {
        binding.fabBookshelf.setOnClickListener {
            val intent = Intent(this, BookshelfActivity::class.java)
            startActivity(intent)
        }
    }

    private suspend fun handleBookOpening(uri: Uri): Book {
        return withContext(Dispatchers.IO) {
            val existingBook = database.bookDao().getBookByUri(uri.toString())
            if (existingBook != null) {
                // Update the last read timestamp
                val updatedBook = existingBook.copy(lastReadTimestamp = System.currentTimeMillis())
                database.bookDao().updateBook(updatedBook)
                Log.d("BookshelfDebug", "Updated existing book: ${updatedBook.title}")
                updatedBook
            } else {
                // Add a new book
                val bookTitle = extractBookName(uri)
                val thumbnail = generateThumbnail(uri)
                val newBook = Book(
                    title = bookTitle,
                    uri = uri.toString(),
                    lastPageRead = 0,
                    lastReadTimestamp = System.currentTimeMillis(),
                    thumbnailPath = thumbnail
                )
                database.bookDao().insertBook(newBook)
                Log.d("BookshelfDebug", "Added new book: $bookTitle")
                newBook
            }
        }
    }

    private fun extractBookName(uri: Uri): String {
        return if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                cursor.getString(nameIndex)
            } ?: "Unknown Book"
        } else {
            uri.lastPathSegment?.substringAfterLast('/') ?: "Unknown Book"
        }
    }

    private fun generateThumbnail(uri: Uri): String? {
        return try {
            contentResolver.openFileDescriptor(uri, "r")?.use { parcelFileDescriptor ->
                val renderer = PdfRenderer(parcelFileDescriptor)
                renderer.use { pdfRenderer ->
                    if (pdfRenderer.pageCount > 0) {
                        pdfRenderer.openPage(0).use { page ->
                            // Define maximum dimensions
                            val maxWidth = 300
                            val maxHeight = 400
                            
                            // Calculate scale to fit within max dimensions
                            val scale = minOf(
                                maxWidth.toFloat() / page.width,
                                maxHeight.toFloat() / page.height
                            )
                            
                            // Create bitmap with scaled dimensions
                            val bitmap = Bitmap.createBitmap(
                                (page.width * scale).toInt(),
                                (page.height * scale).toInt(),
                                Bitmap.Config.ARGB_8888
                            )
                            
                            // Render the page onto the bitmap
                            page.render(
                                bitmap,
                                null,
                                Matrix().apply { postScale(scale, scale) },
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                            )

                            // Save bitmap to internal storage
                            val thumbnailFile = File(
                                filesDir,
                                "thumbnails/${uri.lastPathSegment}_thumb.jpg"
                            )
                            thumbnailFile.parentFile?.mkdirs()
                            
                            FileOutputStream(thumbnailFile).use { out ->
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                            }
                            
                            thumbnailFile.absolutePath
                        }
                    } else null
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error generating thumbnail", e)
            null
        }
    }

    private fun showVoiceSelectionDialog() {
        ttsService?.let { service ->
            val voices: List<Voice> = service.voices?.toList()?.let { voiceList: List<Voice> ->
                voiceList
                    .filter { voice: Voice ->
                        // Improved language filtering
                        voice.name.let { name ->
                            name.contains("-en-", ignoreCase = true) ||
                            name.contains("en-US", ignoreCase = true) ||
                            name.contains("en-GB", ignoreCase = true) ||
                            name.contains("en-AU", ignoreCase = true) ||
                            name.contains("en-IN", ignoreCase = true) ||
                            name.contains("en-CA", ignoreCase = true) ||
                            name.contains("-fr-", ignoreCase = true) ||
                            name.contains("fr-FR", ignoreCase = true) ||
                            name.contains("-de-", ignoreCase = true) ||
                            name.contains("de-DE", ignoreCase = true) ||
                            name.contains("-sv-", ignoreCase = true) ||
                            name.contains("sv-SE", ignoreCase = true) ||
                            name.contains("-zh-", ignoreCase = true) ||
                            name.contains("zh-CN", ignoreCase = true)
                        }
                    }
                    .sortedWith(compareBy { voice: Voice ->
                        if (voice.name.contains("network", ignoreCase = true)) 0 else 1
                    })
            } ?: return
            
            val voiceItems = voices.mapIndexed { index, voice: Voice ->
                val languageCode = voice.name.substringAfter("-").substringBefore("-")
                val (languageName, languageFlag) = when {
                    voice.name.contains("us", ignoreCase = true) -> Pair("US", "🇺🇸")
                    voice.name.contains("gb", ignoreCase = true) -> Pair("GB", "🇬🇧")
                    voice.name.contains("au", ignoreCase = true) -> Pair("AU", "🇦🇺")
                    voice.name.contains("in", ignoreCase = true) -> Pair("IN", "🇮🇳")
                    voice.name.contains("ca", ignoreCase = true) -> Pair("CA", "🇨🇦")
                    voice.name.contains("ie", ignoreCase = true) -> Pair("IE", "🇮🇪")
                    voice.name.contains("nz", ignoreCase = true) -> Pair("NZ", "🇳🇿")
                    voice.name.contains("za", ignoreCase = true) -> Pair("ZA", "🇿🇦")
                    voice.name.contains("de", ignoreCase = true) -> Pair("DE", "🇩🇪")
                    voice.name.contains("fr", ignoreCase = true) -> Pair("FR", "🇫🇷")
                    voice.name.contains("se", ignoreCase = true) -> Pair("SE", "🇸🇪")
                    voice.name.contains("cn", ignoreCase = true) -> Pair("CN", "🇨🇳")
                    else -> Pair("US", "🇺🇸") // Default
                }

                val type = if (voice.name.contains("network", ignoreCase = true)) {
                    "🌐"
                } else {
                    "📱"
                }
                buildString {
                    append("#")
                    append(index + 1)
                    append(" ")
                    append(type)
                    append(" ")
                    append(languageName)
                    append(" ")
                    append(languageFlag)
                }
            }.toTypedArray()
            
            // Log the available voices for debugging
            voices.forEach { voice ->
                Log.d("VoiceSelection", "Available voice: ${voice.name}")
            }
            
            val currentIndex = voices.indexOf(currentVoice) 
            
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.select_voice))
                .setSingleChoiceItems(
                    voiceItems,
                    currentIndex,
                    { dialog, which -> 
                        val selectedVoice = voices[which]
                        service.voice = selectedVoice
                        currentVoice = selectedVoice
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit()
                            .putString("preferred_voice", selectedVoice.name)
                            .apply()
                        dialog.dismiss()
                    }
                )
                .show()
        }
    }

    // Add new helper function for SSML formatting
    private fun formatWithSSML(text: String): String {
        return """
            <speak>
                <prosody rate="${speechRate}" pitch="medium" volume="loud">
                    <break time="500ms"/>
                    ${addEmphasis(text)}
                    <break time="500ms"/>
                </prosody>
            </speak>
        """.trimIndent()
    }

    // Add natural emphasis based on punctuation
    private fun addEmphasis(text: String): String {
        return text
            .replace(". ", ". <break time='500ms'/>")
            .replace("! ", "! <break time='700ms'/>")
            .replace("? ", "? <break time='700ms'/>")
            .replace(", ", ", <break time='300ms'/>")
            .replace(": ", ": <break time='400ms'/>")
            .replace("; ", "; <break time='400ms'/>")
            // Add emphasis to quoted text
            .replace(Regex("\"([^\"]*)\"")) { matchResult ->
                "<emphasis level='moderate'>${matchResult.groupValues[1]}</emphasis>"
            }
    }

    // Add method to initialize TTS with high-quality voice
    private fun initializeTTSWithBestVoice() {
        ttsService?.let { service ->
            // Get available voices
            val voices: Set<Voice>? = service.voices
            
            // Find the best available voice (prefer neural/high-quality voices)
            val bestVoice = voices?.firstOrNull { voice: Voice ->
                voice.name.contains("neural", ignoreCase = true) ||
                voice.name.contains("premium", ignoreCase = true) ||
                voice.name.contains("enhanced", ignoreCase = true)
            } ?: voices?.firstOrNull()
            
            // Set the best available voice
            bestVoice?.let { voice: Voice ->
                service.voice = voice
                currentVoice = voice  // Store the current voice
            }
        }
    }

    // Update the speech rate setting to be more natural
    private fun updateSpeechRate(rate: Float) {
        speechRate = when {
            rate < 0.8f -> 0.8f  // Minimum rate for natural speech
            rate > 2.0f -> 2.0f  // Maximum rate for comprehension
            else -> rate
        }
        ttsService?.setSpeechRate(speechRate)
    }

    // Add this method to initialize with saved voice preference
    private fun initializeTTSWithSavedVoice() {
        ttsService?.let { service ->
            val savedVoiceName = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString("preferred_voice", null)
                
            if (savedVoiceName != null) {
                service.voices?.find { it.name == savedVoiceName }?.let { voice: Voice ->
                    service.voice = voice
                    currentVoice = voice  // Store the current voice
                }
            } else {
                // If no saved preference, initialize with best available voice
                initializeTTSWithBestVoice()
            }
        }
    }

    // Add method to update pitch
    private fun updatePitch(newPitch: Float) {
        pitch = newPitch.coerceIn(0.5f, 2.0f)  // Keep pitch within reasonable bounds
        ttsService?.setPitch(pitch)
    }

    // Add method to update volume
    private fun updateVolume(newVolume: Float) {
        volume = newVolume.coerceIn(0.0f, 1.0f)  // Keep volume between 0 and 1
    }

    private fun showSleepTimerDialog() {
        val times = arrayOf("Off", "15 min", "30 min", "45 min", "60 min")
        val currentSelection = when (sleepTimeInMinutes) {
            15 -> 1
            30 -> 2
            45 -> 3
            60 -> 4
            else -> 0
        }
        
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.sleep_timer))
            .setSingleChoiceItems(times, currentSelection) { dialog, which ->
                when (which) {
                    0 -> cancelSleepTimer()
                    1 -> setSleepTimer(15)
                    2 -> setSleepTimer(30)
                    3 -> setSleepTimer(45)
                    4 -> setSleepTimer(60)
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun setSleepTimer(minutes: Int) {
        sleepTimeInMinutes = minutes
        sleepTimer?.cancel()
        ttsService?.updateCountdown(minutes)
        
        sleepTimer = Timer("SleepTimer").apply {
            schedule(object : TimerTask() {
                override fun run() {
                    if (sleepTimeInMinutes <= 0) {
                        runOnUiThread {
                            if (isSpeaking) {
                                stopReading()
                            }
                            sleepTimer?.cancel()
                            sleepTimer = null
                            ttsService?.updateCountdown(0)
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.sleep_timer_off),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        sleepTimeInMinutes--
                    }
                }
            }, 0, TimeUnit.MINUTES.toMillis(1))
        }
        
        // Only show toast for timer confirmation
        Toast.makeText(
            this,
            getString(R.string.sleep_timer_set, minutes),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun cancelSleepTimer() {
        sleepTimer?.cancel()
        sleepTimer = null
        sleepTimeInMinutes = 0
        ttsService?.updateCountdown(0)  // Clear the countdown
        Toast.makeText(
            this,
            getString(R.string.sleep_timer_off),
            Toast.LENGTH_SHORT
        ).show()
    }
}
    