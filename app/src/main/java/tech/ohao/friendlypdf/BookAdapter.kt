package tech.ohao.friendlypdf

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import tech.ohao.friendlypdf.databinding.ItemBookBinding
import android.app.AlertDialog

class BookAdapter(
    private var books: List<Book>,
    private val onBookClick: (Book) -> Unit,
    private val onBookDelete: (Book) -> Unit
) : RecyclerView.Adapter<BookAdapter.BookViewHolder>() {

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
                
                // Set click listeners
                root.setOnClickListener { onBookClick(book) }
                deleteButton.setOnClickListener { 
                    // Add confirmation dialog
                    val context = itemView.context
                    AlertDialog.Builder(context)
                        .setTitle("Delete Book")
                        .setMessage("Are you sure you want to delete '${book.title}'?")
                        .setPositiveButton("Delete") { _, _ -> onBookDelete(book) }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                
                // Optional: Add thumbnail generation here if needed
                // For now, we're using a placeholder
                thumbnailView.setImageResource(R.drawable.ic_pdf)
            }
        }
    }
}
