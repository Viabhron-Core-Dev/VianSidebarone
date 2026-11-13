package com.example.feature.reader

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import com.example.core.FakeSharedPreferences
import com.example.data.AppDatabase
import com.example.data.EpubBook
import com.example.data.EpubDao
import com.example.data.LibraryRepository
import com.example.data.NotificationHistoryDao
import com.example.data.PwaDao
import com.example.data.QuickNoteDao
import com.example.data.SchedulerTaskDao
import com.example.data.TrackerBook
import com.example.data.TrackerDao
import com.example.data.AppyworkDao
import com.example.data.LogDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ReaderArchitectureAndBackupTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var fakeDb: FakeAppDatabase
    private lateinit var repo: LibraryRepository

    private class FakeEpubDao : EpubDao {
        private val idGen = AtomicInteger(1)
        val rows = LinkedHashMap<Int, EpubBook>()
        private val flow = MutableStateFlow<List<EpubBook>>(emptyList())

        private fun refreshFlow() {
            flow.value = rows.values.toList()
        }

        override suspend fun getAllBooks(): List<EpubBook> {
            return rows.values.sortedWith(
                compareByDescending<EpubBook> { it.lastOpenedTimestamp }
                    .thenByDescending { it.addedTimestamp }
                    .thenByDescending { it.id }
            )
        }

        override suspend fun getAllBooksByAddedDesc(): List<EpubBook> {
            return rows.values.sortedWith(
                compareByDescending<EpubBook> { it.addedTimestamp }
                    .thenByDescending { it.id }
            )
        }

        override fun observeAllBooks(): Flow<List<EpubBook>> = flow

        override suspend fun getRecentBooks(): List<EpubBook> {
            return rows.values
                .filter { it.lastOpenedTimestamp > 0L }
                .sortedWith(
                    compareByDescending<EpubBook> { it.lastOpenedTimestamp }
                        .thenByDescending { it.id }
                )
                .take(10)
        }

        override fun observeRecentBooks(): Flow<List<EpubBook>> = flow

        override suspend fun getBookByFilePath(filePath: String): EpubBook? {
            return rows.values.firstOrNull { it.filePath == filePath }
        }

        override suspend fun insertBook(book: EpubBook): Long {
            val assignedId = if (book.id > 0) book.id else idGen.getAndIncrement()
            val saved = book.copy(id = assignedId)
            rows[assignedId] = saved
            refreshFlow()
            return assignedId.toLong()
        }

        override suspend fun updateBook(book: EpubBook) {
            rows[book.id] = book
            refreshFlow()
        }

        override suspend fun getBookById(id: Int): EpubBook? = rows[id]

        override suspend fun deleteBook(book: EpubBook) {
            rows.remove(book.id)
            refreshFlow()
        }
    }

    private class FakeTrackerDao : TrackerDao {
        private val idGen = AtomicInteger(1)
        val rows = LinkedHashMap<Int, TrackerBook>()
        private val flow = MutableStateFlow<List<TrackerBook>>(emptyList())

        private fun refreshFlow() {
            flow.value = rows.values.toList()
        }

        override suspend fun getAllBooks(): List<TrackerBook> {
            return rows.values.sortedByDescending { it.lastUpdatedTimestamp }
        }

        override fun observeAllBooks(): Flow<List<TrackerBook>> = flow

        override suspend fun insertBook(book: TrackerBook): Long {
            val assignedId = if (book.id > 0) book.id else idGen.getAndIncrement()
            val saved = book.copy(id = assignedId)
            rows[assignedId] = saved
            refreshFlow()
            return assignedId.toLong()
        }

        override suspend fun updateBook(book: TrackerBook) {
            rows[book.id] = book
            refreshFlow()
        }

        override suspend fun deleteBook(book: TrackerBook) {
            rows.remove(book.id)
            refreshFlow()
        }

        override suspend fun getBookById(id: Int): TrackerBook? = rows[id]

        override suspend fun getByLinkedEpubId(epubBookId: Int): TrackerBook? {
            return rows.values.firstOrNull { it.linkedEpubBookId == epubBookId }
        }

        override suspend fun getByBackupOriginKey(originKey: String): TrackerBook? {
            if (originKey.isBlank()) return null
            return rows.values.firstOrNull { it.backupOriginKey == originKey }
        }

        override suspend fun unlinkEpubBook(epubBookId: Int) {
            for ((k, v) in ArrayList(rows.entries)) {
                if (v.linkedEpubBookId == epubBookId) {
                    rows[k] = v.copy(linkedEpubBookId = null)
                }
            }
            refreshFlow()
        }
    }

    private class FakeAppDatabase : AppDatabase() {
        val fakeEpubDao = FakeEpubDao()
        val fakeTrackerDao = FakeTrackerDao()

        override fun epubDao(): EpubDao = fakeEpubDao
        override fun trackerDao(): TrackerDao = fakeTrackerDao
        override fun quickNoteDao(): QuickNoteDao = throw UnsupportedOperationException()
        override fun logDao(): LogDao = throw UnsupportedOperationException()
        override fun schedulerTaskDao(): SchedulerTaskDao = throw UnsupportedOperationException()
        override fun appyworkDao(): AppyworkDao = throw UnsupportedOperationException()
        override fun notificationHistoryDao(): NotificationHistoryDao = throw UnsupportedOperationException()
        override fun pwaDao(): PwaDao = throw UnsupportedOperationException()

        override fun clearAllTables() {
            fakeEpubDao.rows.clear()
            fakeTrackerDao.rows.clear()
        }

        override fun createInvalidationTracker(): androidx.room.InvalidationTracker {
            return androidx.room.InvalidationTracker(this, "epubs", "tracker_books")
        }

        override fun createOpenHelper(config: androidx.room.DatabaseConfiguration): androidx.sqlite.db.SupportSQLiteOpenHelper {
            throw UnsupportedOperationException()
        }
    }

    private class TestReaderContext(
        private val prefs: SharedPreferences,
        private val baseDir: File
    ) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.example"
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        override fun getFilesDir(): File = baseDir
        override fun getCacheDir(): File = baseDir
    }

    @Before
    fun setUp() {
        val baseDir = tempFolder.newFolder("reader_arch_test")
        context = TestReaderContext(FakeSharedPreferences(), baseDir)
        fakeDb = FakeAppDatabase()
        AppDatabase.setTestDatabase(fakeDb)
        repo = LibraryRepository(context)
    }

    @After
    fun tearDown() {
        AppDatabase.setTestDatabase(null)
    }

    /**
     * Tests:
     * - Recent tab returns at most 10 opened books ordered strictly by lastOpenedTimestamp DESC.
     * - All Books returns all imported books (> 10 books) without any 10- or 20-book cap.
     * - Editing metadata in Book Tracker does not cause an unopened book to appear in Recent
     *   and does not reorder Recent.
     */
    @Test
    fun testRecentLast10VsAllBooksAndTrackerEditIsolation() = runBlocking {
        val epubDao = fakeDb.epubDao()

        // Insert 15 books: 12 opened at timestamps 1000..12000, and 3 unopened (lastOpenedTimestamp = 0)
        val insertedIds = mutableListOf<Int>()
        for (i in 1..15) {
            val openedTs = if (i <= 12) (i * 1000L) else 0L
            val id = epubDao.insertBook(
                EpubBook(
                    title = "Book $i",
                    author = "Author $i",
                    filePath = "/fake/path/book_$i.epub",
                    totalChapters = 30,
                    lastReadChapter = 0,
                    lastReadScrollY = 0,
                    addedTimestamp = i * 100L,
                    lastOpenedTimestamp = openedTs,
                    lastActivityTimestamp = openedTs
                )
            ).toInt()
            insertedIds.add(id)
            repo.getOrCreateLinkedTrackerBook(id)
        }

        val recent = repo.getRecentBooks()
        val allBooks = repo.getAllBooks()

        assertEquals("Recent tab must show at most 10 opened books", 10, recent.size)
        assertEquals("All Books tab must show all 15 imported books", 15, allBooks.size)

        // Recent must be ordered strictly by lastOpenedTimestamp DESC: Book 12 down to Book 3
        assertEquals("Book 12", recent.first().title)
        assertEquals("Book 3", recent.last().title)
        assertTrue("Unopened books (lastOpenedTimestamp == 0) must not appear in Recent", recent.none { it.lastOpenedTimestamp == 0L })

        // Edit an unopened book (Book 15) and an older opened book (Book 5) in Book Tracker
        val unopenedId = insertedIds[14] // Book 15
        val olderOpenedId = insertedIds[4] // Book 5 (lastOpenedTimestamp = 5000)

        val tracker15 = repo.getOrCreateLinkedTrackerBook(unopenedId)!!
        repo.saveTrackerMetadataEdit(tracker15.copy(title = "Book 15 Edited", comment = "Edited in Tracker"))

        val tracker5 = repo.getOrCreateLinkedTrackerBook(olderOpenedId)!!
        repo.saveTrackerMetadataEdit(tracker5.copy(rating = 4.5f, genres = "Sci-Fi"))

        val recentAfterEdit = repo.getRecentBooks()
        assertEquals("Recent count must remain 10 after Tracker metadata edits", 10, recentAfterEdit.size)
        assertTrue("Unopened Book 15 must still not appear in Recent after Tracker edit", recentAfterEdit.none { it.id == unopenedId })
        assertEquals("Recent top book must still be Book 12 (not Book 5)", "Book 12", recentAfterEdit.first().title)
    }

    /**
     * Tests:
     * - Duplicate titles remain distinct and are linked by exact linkedEpubBookId, never by title alone.
     * - Deleting an EpubBook sets linkedEpubBookId = null on its TrackerBook without deleting Tracker history.
     */
    @Test
    fun testDuplicateTitlesRemainDistinctAndDeletingEpubUnlinksTrackerBook() = runBlocking {
        val epubDao = fakeDb.epubDao()
        val trackerDao = fakeDb.trackerDao()

        val id1 = epubDao.insertBook(
            EpubBook(
                title = "Duplicate Title",
                author = "Author One",
                filePath = "/fake/dup1.epub",
                totalChapters = 10,
                lastReadChapter = 2,
                lastReadScrollY = 0,
                addedTimestamp = 1000L
            )
        ).toInt()
        val id2 = epubDao.insertBook(
            EpubBook(
                title = "Duplicate Title",
                author = "Author Two",
                filePath = "/fake/dup2.epub",
                totalChapters = 25,
                lastReadChapter = 7,
                lastReadScrollY = 0,
                addedTimestamp = 2000L
            )
        ).toInt()

        val tr1 = repo.getOrCreateLinkedTrackerBook(id1)!!
        val tr2 = repo.getOrCreateLinkedTrackerBook(id2)!!
        assertTrue("Duplicate titles must create distinct TrackerBook records", tr1.id != tr2.id)
        assertEquals(id1, tr1.linkedEpubBookId)
        assertEquals(id2, tr2.linkedEpubBookId)

        // Advance high-water progress on Book 1 to 5
        repo.recordGenuineReadingProgress(id1, 4) // 0-based ch 4 -> 5 readChapters

        // Delete EpubBook 1 from Reader library
        val epub1 = epubDao.getBookById(id1)!!
        repo.deleteBook(epub1)

        assertNull("EpubBook 1 must be deleted", epubDao.getBookById(id1))
        val survivingTracker1 = trackerDao.getAllBooks().find { it.id == tr1.id }
        assertNotNull("TrackerBook 1 must survive EpubBook deletion", survivingTracker1)
        assertNull("TrackerBook 1 linkedEpubBookId must be set to null on EpubBook deletion", survivingTracker1!!.linkedEpubBookId)
        assertEquals("TrackerBook 1 reading history must be preserved", 5, survivingTracker1.readChapters)
    }

    /**
     * Tests High-water progress vs Reader resume separation + Undo:
     * - Jumping backward via TOC updates resume position only and does not reduce TrackerBook.readChapters.
     * - Jumping forward via TOC or search does not increase TrackerBook.readChapters before genuine reading activity.
     * - Genuine reading past the previous high-water mark increases TrackerBook.readChapters.
     * - Undo restores the previous high-water mark.
     */
    @Test
    fun testHighWaterProgressVsReaderResumeSeparationAndUndo() = runBlocking {
        val epubId = fakeDb.epubDao().insertBook(
            EpubBook(
                title = "Progress Test Book",
                author = "Author",
                filePath = "/fake/progress.epub",
                totalChapters = 100,
                lastReadChapter = 9, // 0-based ch 9 -> Ch 10
                lastReadScrollY = 120,
                addedTimestamp = 1000L,
                lastOpenedTimestamp = 2000L
            )
        ).toInt()

        val initialTracker = repo.getOrCreateLinkedTrackerBook(epubId)!!
        // Record initial genuine reading at ch 9 (1-based 10)
        repo.recordGenuineReadingProgress(epubId, currentChapterZeroBased = 9)
        assertEquals("Initial high-water mark should be 10 (1-based)", 10, fakeDb.trackerDao().getByLinkedEpubId(epubId)!!.readChapters)

        // 1. Jump backward via TOC to chapter 2 (0-based index 1)
        repo.saveResumePosition(epubId, chapterIndex = 1, scrollY = 0)
        val epubAfterBackJump = fakeDb.epubDao().getBookById(epubId)!!
        val trackerAfterBackJump = fakeDb.trackerDao().getByLinkedEpubId(epubId)!!
        assertEquals("Resume chapter must update to 1 (0-based)", 1, epubAfterBackJump.lastReadChapter)
        assertEquals("High-water readChapters must remain 10 on backward TOC jump", 10, trackerAfterBackJump.readChapters)

        // 2. Jump forward via TOC/search to chapter 40 (0-based index 39) without genuine reading activity
        repo.saveResumePosition(epubId, chapterIndex = 39, scrollY = 250)
        val epubAfterForwardJump = fakeDb.epubDao().getBookById(epubId)!!
        val trackerAfterForwardJump = fakeDb.trackerDao().getByLinkedEpubId(epubId)!!
        assertEquals("Resume chapter must update to 39 (0-based)", 39, epubAfterForwardJump.lastReadChapter)
        assertEquals(250, epubAfterForwardJump.lastReadScrollY)
        assertEquals(
            "Jumping forward via TOC/search must NOT advance TrackerBook.readChapters before genuine reading activity",
            10,
            trackerAfterForwardJump.readChapters
        )

        // 3. Genuine reading activity at chapter 40 (0-based index 39 -> 1-based 40) advances high-water mark
        val progressUpdate = repo.recordGenuineReadingProgress(epubId, currentChapterZeroBased = 39)
        assertNotNull("Genuine reading past high-water mark must return HighWaterProgressUpdate", progressUpdate)
        assertEquals(10, progressUpdate!!.previousReadChapters)
        assertEquals(40, progressUpdate.newReadChapters)
        assertEquals(40, fakeDb.trackerDao().getByLinkedEpubId(epubId)!!.readChapters)

        // 4. Undo restores the previous high-water mark (10)
        val undone = repo.undoHighWaterProgress(progressUpdate)
        assertTrue("Undo must succeed", undone)
        assertEquals("High-water mark must revert to 10 after Undo", 10, fakeDb.trackerDao().getByLinkedEpubId(epubId)!!.readChapters)
    }

    /**
     * Tests Room MIGRATION_10_11 SQL statements and schema evolution.
     */
    @Test
    fun testRoomMigration10To11SqlStatementsAndTrackerFilterSort() {
        val executedSql = mutableListOf<String>()
        val mockSupportDb = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                executedSql.add(args[0].toString())
            }
            null
        } as SupportSQLiteDatabase

        AppDatabase.MIGRATION_10_11.migrate(mockSupportDb)

        assertTrue(
            "MIGRATION_10_11 must add author column to epubs",
            executedSql.any { it.contains("ALTER TABLE `epubs` ADD COLUMN `author`") }
        )
        assertTrue(
            "MIGRATION_10_11 must add lastActivityTimestamp column to epubs",
            executedSql.any { it.contains("ALTER TABLE `epubs` ADD COLUMN `lastActivityTimestamp`") }
        )
        assertTrue(
            "MIGRATION_10_11 must recreate tracker_books with linkedEpubBookId foreign key ON DELETE SET NULL",
            executedSql.any { it.contains("FOREIGN KEY(`linkedEpubBookId`) REFERENCES `epubs`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL") }
        )
        assertTrue(
            "MIGRATION_10_11 must copy existing tracker_books rows preserving history",
            executedSql.any { it.contains("INSERT INTO `tracker_books_new`") && it.contains("FROM `tracker_books`") }
        )
        assertTrue(
            "MIGRATION_10_11 must create index_tracker_books_linkedEpubBookId",
            executedSql.any { it.contains("CREATE INDEX IF NOT EXISTS `index_tracker_books_linkedEpubBookId`") }
        )

        // Also verify BookTrackerActivity search, genre filter, and sort options (Date, Most Read, Name)
        val sampleTrackers = listOf(
            TrackerBook(id = 1, title = "Zeta Chronicles", author = "Alice", readChapters = 15, genres = "Sci-Fi, Space", addedTimestamp = 1000L, lastReadingActivityTimestamp = 2000L),
            TrackerBook(id = 2, title = "Alpha Odyssey", author = "Bob", readChapters = 80, genres = "Fantasy, Magic", addedTimestamp = 3000L, lastReadingActivityTimestamp = 3000L),
            TrackerBook(id = 3, title = "Beta Legends", author = "Charlie", readChapters = 40, genres = "Sci-Fi", addedTimestamp = 5000L, lastReadingActivityTimestamp = 9000L)
        )

        val byDate = BookTrackerActivity.filterAndSortTrackerBooks(sampleTrackers, "", "All", TrackerSortOption.DATE)
        assertEquals(listOf(3, 2, 1), byDate.map { it.id })

        val byMostRead = BookTrackerActivity.filterAndSortTrackerBooks(sampleTrackers, "", "All", TrackerSortOption.MOST_READ)
        assertEquals(listOf(2, 3, 1), byMostRead.map { it.id })

        val byName = BookTrackerActivity.filterAndSortTrackerBooks(sampleTrackers, "", "All", TrackerSortOption.NAME)
        assertEquals(listOf(2, 3, 1), byName.map { it.id })

        val sciFiOnly = BookTrackerActivity.filterAndSortTrackerBooks(sampleTrackers, "", "Sci-Fi", TrackerSortOption.NAME)
        assertEquals(listOf(3, 1), sciFiOnly.map { it.id })

        val searchAuthor = BookTrackerActivity.filterAndSortTrackerBooks(sampleTrackers, "alice", "All", TrackerSortOption.NAME)
        assertEquals(listOf(1), searchAuthor.map { it.id })
    }

    /**
     * Tests Full ZIP, NoBooks ZIP, TSV, and Moon+ .mrstd backup export & idempotent import,
     * plus corrupt/zip-slip safety.
     */
    @Test
    fun testBackupImportExportFullNoBooksTsvMrstdAndCorruptSafety() = runBlocking {
        // 1. Seed initial book and tracker record
        val sampleTxt = File(tempFolder.newFolder("seed_books"), "seed_novel.txt")
        sampleTxt.writeText("Chapter 1: Seed Start\nHello world.\n\nChapter 2: Seed Next\nMore content.")
        val seedEpubId = fakeDb.epubDao().insertBook(
            EpubBook(
                title = "Seed Novel",
                author = "Seed Author",
                filePath = sampleTxt.absolutePath,
                totalChapters = 2,
                lastReadChapter = 1,
                lastReadScrollY = 80,
                addedTimestamp = 1700005000L,
                lastOpenedTimestamp = 1700006000L,
                lastActivityTimestamp = 1700006000L,
                isParsed = true
            )
        ).toInt()
        val seedTracker = repo.getOrCreateLinkedTrackerBook(seedEpubId)!!
        repo.saveTrackerMetadataEdit(seedTracker.copy(readChapters = 2, genres = "Adventure", rating = 5f, comment = "Seed comment"))

        val exportDir = tempFolder.newFolder("exports")
        val fullZip = File(exportDir, "full_backup.zip")
        val noBooksZip = File(exportDir, "nobooks_backup.zip")
        val tsvFile = File(exportDir, "tracker.tsv")

        assertTrue("Full ZIP export must succeed", LiteReaderBackupManager.exportFullZipBackup(context, fullZip))
        assertTrue("NoBooks ZIP export must succeed", LiteReaderBackupManager.exportNoBooksZipBackup(context, noBooksZip))
        assertTrue("TSV export must succeed", LiteReaderBackupManager.exportTrackerTsv(context, tsvFile))

        // 2. Clear DB and import Full ZIP twice (idempotency check)
        fakeDb.clearAllTables()
        assertEquals(0, fakeDb.epubDao().getAllBooks().size)
        assertEquals(0, fakeDb.trackerDao().getAllBooks().size)

        val fullReport1 = LiteReaderBackupManager.importZipBackup(context, fullZip)
        assertTrue("Full ZIP import 1 must succeed: ${fullReport1.errorMessage}", fullReport1.success)
        assertEquals("FULL_ZIP", fullReport1.backupType)
        assertEquals(1, fakeDb.epubDao().getAllBooks().size)
        assertEquals(1, fakeDb.trackerDao().getAllBooks().size)
        val importedTracker = fakeDb.trackerDao().getAllBooks().first()
        val importedEpub = fakeDb.epubDao().getAllBooks().first()
        assertEquals("Full ZIP must remap linkedEpubBookId to new EpubBook.id", importedEpub.id, importedTracker.linkedEpubBookId)

        // Repeat Full ZIP import -> must update idempotently without duplicating rows
        val fullReport2 = LiteReaderBackupManager.importZipBackup(context, fullZip)
        assertTrue(fullReport2.success)
        assertEquals("Repeating Full ZIP import must not duplicate EpubBook", 1, fakeDb.epubDao().getAllBooks().size)
        assertEquals("Repeating Full ZIP import must not duplicate TrackerBook", 1, fakeDb.trackerDao().getAllBooks().size)

        // 3. Clear DB and import NoBooks ZIP -> imports Tracker history without broken book binary
        fakeDb.clearAllTables()

        val noBooksReport = LiteReaderBackupManager.importZipBackup(context, noBooksZip)
        assertTrue("NoBooks ZIP import must succeed: ${noBooksReport.errorMessage}", noBooksReport.success)
        assertEquals("NOBOOKS_ZIP", noBooksReport.backupType)
        assertEquals("NoBooks ZIP must import TrackerBook history", 1, fakeDb.trackerDao().getAllBooks().size)

        // 4. TSV import idempotency
        val tsvReport1 = LiteReaderBackupManager.importTrackerTsv(context, tsvFile)
        assertTrue(tsvReport1.success)
        val countAfterTsv1 = fakeDb.trackerDao().getAllBooks().size
        val tsvReport2 = LiteReaderBackupManager.importTrackerTsv(context, tsvFile)
        assertTrue(tsvReport2.success)
        assertEquals("Repeated TSV import must not duplicate Tracker records", countAfterTsv1, fakeDb.trackerDao().getAllBooks().size)

        // 5. Moon+ .mrstd import idempotency
        val mrstdFile = File(exportDir, "sample.mrstd")
        ZipOutputStream(FileOutputStream(mrstdFile)).use { zos ->
            zos.putNextEntry(ZipEntry("books/Starlight_Chronicles.epub.po"))
            zos.write("1700099000*14@0#45%".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        val moonReport1 = LiteReaderBackupManager.importMoonPlusMrstd(context, mrstdFile)
        assertTrue("Moon+ .mrstd import must succeed", moonReport1.success)
        val countAfterMoon1 = fakeDb.trackerDao().getAllBooks().size
        val moonReport2 = LiteReaderBackupManager.importMoonPlusMrstd(context, mrstdFile)
        assertTrue(moonReport2.success)
        assertEquals("Repeated Moon+ .mrstd import must be idempotent", countAfterMoon1, fakeDb.trackerDao().getAllBooks().size)

        // 6. Corrupt / Zip-Slip safety -> fails cleanly without losing existing records
        val corruptZip = File(exportDir, "corrupt.zip")
        corruptZip.writeText("NOT_A_VALID_ZIP_OR_SQLITE")
        val corruptReport = LiteReaderBackupManager.importZipBackup(context, corruptZip)
        assertFalse("Corrupt ZIP must fail cleanly", corruptReport.success)
        assertEquals("Existing Tracker records must remain intact after failed import", countAfterMoon1, fakeDb.trackerDao().getAllBooks().size)
    }
}
