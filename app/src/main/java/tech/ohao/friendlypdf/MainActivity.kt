package tech.ohao.friendlypdf

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle


class MainActivity : AppCompatActivity() {
    private lateinit var pdfView: PDFView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        pdfView = findViewById(R.id.pdfView)
        setupPdfViewer()
    }
    
    private fun setupPdfViewer() {
        pdfView.fromAsset("sample.pdf")
            .enableSwipe(true)
            .swipeHorizontal(false)
            .enableDoubletap(true)
            .defaultPage(0)
            .enableAnnotationRendering(true)
            .scrollHandle(DefaultScrollHandle(this))
            .spacing(10)
            .autoSpacing(true)
            .pageSnap(true)
            .pageFling(true)
            .nightMode(false)
            .load()
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
