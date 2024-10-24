package tech.ohao.friendlypdf

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import tech.ohao.friendlypdf.databinding.ItemBookBinding
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import android.widget.ImageView
import android.net.Uri
import android.graphics.Matrix
import androidx.lifecycle.LifecycleOwner
import android.graphics.BitmapFactory
import java.io.File

class BookAdapter(
    private var books: List<Book>,
    private val onBookClick: (Book) -> Unit,
    private val onBookDelete: (Book) -> Unit,
    private val context: Context
) : RecyclerView.Adapter<BookAdapter.BookViewHolder>() {

    private val thumbnailCache = mutableMapOf<String, Bitmap>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder {
        val binding = ItemBookBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return BookViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
        holder.bind(books[position])
    }

    override fun getItemCount() = books.size

    fun updateBooks(newBooks: List<Book>) {
        books = newBooks
        notifyDataSetChanged()
    }

    inner class BookViewHolder(private val binding: ItemBookBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(book: Book) {
            binding.apply {
                titleTextView.text = book.title
                
                // Set default thumbnail
                thumbnailView.setImageResource(R.drawable.ic_pdf)
                
                // Load thumbnail if available
                book.thumbnailPath?.let { path ->
                    val thumbnailFile = File(path)
                    if (thumbnailFile.exists()) {
                        try {
                            val bitmap = BitmapFactory.decodeFile(path)
                            thumbnailView.setImageBitmap(bitmap)
                        } catch (e: Exception) {
                            Log.e("BookAdapter", "Error loading thumbnail", e)
                        }
                    }
                }
                
                // Set click listeners
                root.setOnClickListener { onBookClick(book) }
                deleteButton.setOnClickListener { 
                    AlertDialog.Builder(context)
                        .setTitle("Delete Book")
                        .setMessage("Are you sure you want to delete '${book.title}'?")
                        .setPositiveButton("Delete") { _, _ -> 
                            // Delete thumbnail file if it exists
                            book.thumbnailPath?.let { path ->
                                File(path).delete()
                            }
                            onBookDelete(book)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }

        private fun loadThumbnail(uriString: String, imageView: ImageView) {
            // Check cache first
            thumbnailCache[uriString]?.let {
                imageView.setImageBitmap(it)
                return
            }

            try {
                val uri = Uri.parse(uriString)
                context.contentResolver.openFileDescriptor(uri, "r")?.use { parcelFileDescriptor ->
                    (context as? LifecycleOwner)?.lifecycleScope?.launch(Dispatchers.IO) {
                        try {
                            val thumbnail = generateThumbnail(parcelFileDescriptor)
                            withContext(Dispatchers.Main) {
                                thumbnail?.let {
                                    thumbnailCache[uriString] = it
                                    imageView.setImageBitmap(it)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("BookAdapter", "Error generating thumbnail", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("BookAdapter", "Error loading thumbnail", e)
            }
        }

        private fun generateThumbnail(fileDescriptor: ParcelFileDescriptor): Bitmap? {
            return try {
                val renderer = PdfRenderer(fileDescriptor)
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
                            bitmap
                        }
                    } else null
                }
            } catch (e: Exception) {
                Log.e("BookAdapter", "Error rendering PDF page", e)
                null
            }
        }
    }
}
