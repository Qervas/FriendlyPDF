package tech.ohao.friendlypdf

import android.net.Uri
import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromString(value: String?): Uri? {
        return value?.let { Uri.parse(it) }
    }

    @TypeConverter
    fun uriToString(uri: Uri?): String? {
        return uri?.toString()
    }
}
