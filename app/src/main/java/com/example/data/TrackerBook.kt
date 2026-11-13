package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "tracker_books",
    foreignKeys = [
        ForeignKey(
            entity = EpubBook::class,
            parentColumns = ["id"],
            childColumns = ["linkedEpubBookId"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["linkedEpubBookId"])
    ]
)
data class TrackerBook(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val author: String = "",
    val totalChapters: Int = 0,
    val readChapters: Int = 0,
    val isFinished: Boolean = false,
    val isWebNovel: Boolean = false,
    val genres: String = "",
    val rating: Float = 0f,
    val comment: String = "",
    val addedTimestamp: Long = System.currentTimeMillis(),
    val lastUpdatedTimestamp: Long = System.currentTimeMillis(),
    val linkedEpubBookId: Int? = null,
    val lastReadingActivityTimestamp: Long = 0L,
    val lastMetadataEditTimestamp: Long = System.currentTimeMillis(),
    val backupOriginKey: String = ""
)

@Dao
interface TrackerDao {
    @Query("SELECT * FROM tracker_books ORDER BY lastUpdatedTimestamp DESC")
    suspend fun getAllBooks(): List<TrackerBook>

    @Query("SELECT * FROM tracker_books ORDER BY lastUpdatedTimestamp DESC")
    fun observeAllBooks(): Flow<List<TrackerBook>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: TrackerBook): Long

    @Update
    suspend fun updateBook(book: TrackerBook)

    @Delete
    suspend fun deleteBook(book: TrackerBook)

    @Query("SELECT * FROM tracker_books WHERE id = :id LIMIT 1")
    suspend fun getBookById(id: Int): TrackerBook?

    @Query("SELECT * FROM tracker_books WHERE linkedEpubBookId = :epubBookId LIMIT 1")
    suspend fun getByLinkedEpubId(epubBookId: Int): TrackerBook?

    @Query("SELECT * FROM tracker_books WHERE backupOriginKey = :originKey AND backupOriginKey != '' LIMIT 1")
    suspend fun getByBackupOriginKey(originKey: String): TrackerBook?

    @Query("UPDATE tracker_books SET linkedEpubBookId = NULL WHERE linkedEpubBookId = :epubBookId")
    suspend fun unlinkEpubBook(epubBookId: Int)
}

