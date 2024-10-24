package tech.ohao.friendlypdf

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val uri: String,
    val lastPageRead: Int,
    val lastReadTimestamp: Long,
    val thumbnailPath: String? = null // Add this field
)
