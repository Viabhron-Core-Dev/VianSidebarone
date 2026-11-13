package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "epubs")
data class EpubBook(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val filePath: String,
    val isParsed: Boolean = false,
    val totalChapters: Int = 0,
    val lastReadChapter: Int = 0,
    val lastReadScrollY: Int = 0,
    val addedTimestamp: Long = System.currentTimeMillis(),
    val lastOpenedTimestamp: Long = 0L,
    val author: String = "",
    val lastActivityTimestamp: Long = 0L
)

@Dao
interface EpubDao {
    @Query("SELECT * FROM epubs ORDER BY lastOpenedTimestamp DESC, addedTimestamp DESC, id DESC")
    suspend fun getAllBooks(): List<EpubBook>

    @Query("SELECT * FROM epubs ORDER BY addedTimestamp DESC, id DESC")
    suspend fun getAllBooksByAddedDesc(): List<EpubBook>

    @Query("SELECT * FROM epubs ORDER BY addedTimestamp DESC, id DESC")
    fun observeAllBooks(): Flow<List<EpubBook>>

    @Query("SELECT * FROM epubs WHERE lastOpenedTimestamp > 0 ORDER BY lastOpenedTimestamp DESC, id DESC LIMIT 10")
    suspend fun getRecentBooks(): List<EpubBook>

    @Query("SELECT * FROM epubs WHERE lastOpenedTimestamp > 0 ORDER BY lastOpenedTimestamp DESC, id DESC LIMIT 10")
    fun observeRecentBooks(): Flow<List<EpubBook>>

    @Query("SELECT * FROM epubs WHERE filePath = :filePath LIMIT 1")
    suspend fun getBookByFilePath(filePath: String): EpubBook?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBook(book: EpubBook): Long

    @Update
    suspend fun updateBook(book: EpubBook)

    @Query("SELECT * FROM epubs WHERE id = :id LIMIT 1")
    suspend fun getBookById(id: Int): EpubBook?

    @Delete
    suspend fun deleteBook(book: EpubBook)
}

