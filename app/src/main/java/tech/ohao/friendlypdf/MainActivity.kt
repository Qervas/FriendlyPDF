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

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var pdfView: PDFView
    private val PICK_PDF_FILE = 2
    private var currentUri: Uri? = null  // Add this to track current PDF
    
    // View mode states
    private enum class ViewMode {
        FIT_WIDTH, FIT_PAGE, CROP_MARGINS
    }
    private var currentViewMode = ViewMode.FIT_WIDTH

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        pdfView = binding.pdfView
        
        // Handle incoming PDF intent
        handleIntent(intent)
        
        // Add FAB for file picking
        binding.fabAdd.setOnClickListener { openFilePicker() }
        
        // Setup view mode FAB
        binding.fabViewMode.setOnClickListener { toggleViewMode() }
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
            else -> super.onOptionsItemSelected(item)
        }
    }
}
