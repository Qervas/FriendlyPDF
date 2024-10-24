package tech.ohao.friendlypdf

import androidx.room.*

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY lastReadTimestamp DESC")
    suspend fun getAllBooks(): List<Book>

    @Query("SELECT * FROM books WHERE uri = :uri LIMIT 1")
    suspend fun getBookByUri(uri: String): Book?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: Book)

    @Delete
    suspend fun deleteBook(book: Book)

    // Add this debug function
    @Query("SELECT COUNT(*) FROM books")
    suspend fun getBookCount(): Int

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    suspend fun getBookById(id: Long): Book?
}
