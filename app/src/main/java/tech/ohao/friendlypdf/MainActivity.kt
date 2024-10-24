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

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var pdfView: PDFView
    private val PICK_PDF_FILE = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        pdfView = binding.pdfView
        
        // Handle incoming PDF intent
        handleIntent(intent)
        
        // Add FAB for file picking
        binding.fabAdd.setOnClickListener { openFilePicker() }
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
            else -> super.onOptionsItemSelected(item)
        }
    }
}
