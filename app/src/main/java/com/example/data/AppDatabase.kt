package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        NotificationHistory::class,
        EpubBook::class,
        TrackerBook::class,
        QuickNote::class,
        LogEntry::class,
        SchedulerTask::class,
        AppyworkProject::class,
        AppyworkFileNode::class,
        PwaEntity::class
    ],
    version = 11,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun epubDao(): EpubDao
    abstract fun trackerDao(): TrackerDao
    abstract fun quickNoteDao(): QuickNoteDao
    abstract fun logDao(): LogDao
    abstract fun schedulerTaskDao(): SchedulerTaskDao
    abstract fun appyworkDao(): AppyworkDao
    abstract fun notificationHistoryDao(): NotificationHistoryDao
    abstract fun pwaDao(): PwaDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `quick_notes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `text` TEXT NOT NULL, `timestamp` INTEGER NOT NULL)")
            }
        }
        
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `tag` TEXT NOT NULL, `message` TEXT NOT NULL)")
            }
        }

        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `scheduler_tasks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `note` TEXT NOT NULL, `timeMillis` INTEGER NOT NULL)")
            }
        }

        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `scheduler_tasks` ADD COLUMN `tags` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `scheduler_tasks` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'PENDING'")
            }
        }

        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `appywork_projects` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `remoteUrl` TEXT NOT NULL, `authType` TEXT NOT NULL, `authToken` TEXT NOT NULL, `lastUpdated` INTEGER NOT NULL)")
                database.execSQL("CREATE TABLE IF NOT EXISTS `appywork_file_nodes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `projectId` INTEGER NOT NULL, `path` TEXT NOT NULL, `localHash` TEXT NOT NULL, `syncState` TEXT NOT NULL, FOREIGN KEY(`projectId`) REFERENCES `appywork_projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_appywork_file_nodes_projectId_path` ON `appywork_file_nodes` (`projectId`, `path`)")
            }
        }

        val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `notification_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `packageName` TEXT NOT NULL, `appName` TEXT NOT NULL, `title` TEXT NOT NULL, `text` TEXT NOT NULL, `timestamp` INTEGER NOT NULL)")
            }
        }

        val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `pwa_apps` (
                        `id` TEXT PRIMARY KEY NOT NULL,
                        `name` TEXT NOT NULL,
                        `shortName` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `startUrl` TEXT NOT NULL,
                        `scope` TEXT NOT NULL,
                        `displayMode` TEXT NOT NULL,
                        `orientation` TEXT NOT NULL,
                        `themeColor` TEXT NOT NULL,
                        `backgroundColor` TEXT NOT NULL,
                        `iconPath` TEXT,
                        `zipPath` TEXT NOT NULL,
                        `unpackedPath` TEXT NOT NULL,
                        `packageSize` INTEGER NOT NULL,
                        `version` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `lastLaunchedAt` INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `epubs` ADD COLUMN `author` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `epubs` ADD COLUMN `lastActivityTimestamp` INTEGER NOT NULL DEFAULT 0")

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `tracker_books_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `author` TEXT NOT NULL,
                        `totalChapters` INTEGER NOT NULL,
                        `readChapters` INTEGER NOT NULL,
                        `isFinished` INTEGER NOT NULL,
                        `isWebNovel` INTEGER NOT NULL,
                        `genres` TEXT NOT NULL,
                        `rating` REAL NOT NULL,
                        `comment` TEXT NOT NULL,
                        `addedTimestamp` INTEGER NOT NULL,
                        `lastUpdatedTimestamp` INTEGER NOT NULL,
                        `linkedEpubBookId` INTEGER DEFAULT NULL,
                        `lastReadingActivityTimestamp` INTEGER NOT NULL DEFAULT 0,
                        `lastMetadataEditTimestamp` INTEGER NOT NULL DEFAULT 0,
                        `backupOriginKey` TEXT NOT NULL DEFAULT '',
                        FOREIGN KEY(`linkedEpubBookId`) REFERENCES `epubs`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    INSERT INTO `tracker_books_new` (
                        `id`, `title`, `author`, `totalChapters`, `readChapters`,
                        `isFinished`, `isWebNovel`, `genres`, `rating`, `comment`,
                        `addedTimestamp`, `lastUpdatedTimestamp`, `linkedEpubBookId`,
                        `lastReadingActivityTimestamp`, `lastMetadataEditTimestamp`, `backupOriginKey`
                    )
                    SELECT
                        `id`, `title`, `author`, `totalChapters`, `readChapters`,
                        `isFinished`, `isWebNovel`, `genres`, `rating`, `comment`,
                        `addedTimestamp`, `lastUpdatedTimestamp`, NULL,
                        `lastUpdatedTimestamp`, `lastUpdatedTimestamp`, ''
                    FROM `tracker_books`
                    """.trimIndent()
                )
                database.execSQL("DROP TABLE `tracker_books`")
                database.execSQL("ALTER TABLE `tracker_books_new` RENAME TO `tracker_books`")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_tracker_books_linkedEpubBookId` ON `tracker_books` (`linkedEpubBookId`)")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "litereader_db"
                )
                    .addMigrations(
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11
                    )
                    .fallbackToDestructiveMigrationFrom(1, 2)
                    .enableMultiInstanceInvalidation()
                    .build()
                INSTANCE = instance
                instance
            }
        }

        internal fun setTestDatabase(db: AppDatabase?) {
            synchronized(this) {
                INSTANCE = db
            }
        }
    }
}

