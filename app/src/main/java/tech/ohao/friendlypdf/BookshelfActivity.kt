package tech.ohao.friendlypdf

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.ohao.friendlypdf.databinding.ActivityBookshelfBinding
import android.net.Uri
import android.util.Log

class BookshelfActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBookshelfBinding
    private lateinit var bookAdapter: BookAdapter
    private lateinit var database: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBookshelfBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getInstance(applicationContext)

        lifecycleScope.launch {
            val bookCount = withContext(Dispatchers.IO) {
                database.bookDao().getBookCount()
            }
            Log.d("BookshelfDebug", "Total books in database: $bookCount")
        }

        setupRecyclerView()
        loadBooks()

        // Remove or comment out this block if not needed
        /*
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (database.bookDao().getAllBooks().isEmpty()) {
                    database.bookDao().insertBook(
                        Book(
                            title = "Sample Book",
                            uri = "file:///android_asset/sample.pdf",
                            lastPageRead = 0,
                            lastReadTimestamp = System.currentTimeMillis()
                        )
                    )
                }
            }
            loadBooks() // Reload books after adding test data
        }
        */
    }

    private fun setupRecyclerView() {
        bookAdapter = BookAdapter(
            emptyList(),
            this::onBookClick,
            this::onBookDelete,
            this  // Pass the activity as context
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@BookshelfActivity)
            adapter = bookAdapter
        }
    }

    private fun loadBooks() {
        lifecycleScope.launch {
            val books = withContext(Dispatchers.IO) {
                val allBooks = database.bookDao().getAllBooks()
                Log.d("BookshelfDebug", "Loading books from database. Count: ${allBooks.size}")
                allBooks.forEach { book ->
                    Log.d("BookshelfDebug", "Book: ${book.title}, URI: ${book.uri}")
                }
                allBooks
            }
            bookAdapter.updateBooks(books)
            
            // Add visual feedback if no books are found
            if (books.isEmpty()) {
                Log.d("BookshelfDebug", "No books found in database")
            }
        }
    }

    private fun onBookClick(book: Book) {
        val intent = Intent(this, MainActivity::class.java).apply {
            data = Uri.parse(book.uri)
        }
        startActivity(intent)
    }

    private fun onBookDelete(book: Book) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                database.bookDao().deleteBook(book)
            }
            loadBooks()
        }
    }

    override fun onResume() {
        super.onResume()
        loadBooks() // Refresh the list of books when the activity resumes
    }
}
