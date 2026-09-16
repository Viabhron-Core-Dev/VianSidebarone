package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [NotificationHistory::class, EpubBook::class, TrackerBook::class, QuickNote::class, LogEntry::class, SchedulerTask::class, AppyworkProject::class, AppyworkFileNode::class], version = 9, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun epubDao(): EpubDao
    abstract fun trackerDao(): TrackerDao
    abstract fun quickNoteDao(): QuickNoteDao
    abstract fun logDao(): LogDao
    abstract fun schedulerTaskDao(): SchedulerTaskDao
    abstract fun appyworkDao(): AppyworkDao
    abstract fun notificationHistoryDao(): NotificationHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `quick_notes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `text` TEXT NOT NULL, `timestamp` INTEGER NOT NULL)")
            }
        }
        
        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `tag` TEXT NOT NULL, `message` TEXT NOT NULL)")
            }
        }

        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `scheduler_tasks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `note` TEXT NOT NULL, `timeMillis` INTEGER NOT NULL)")
            }
        }

        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `scheduler_tasks` ADD COLUMN `tags` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `scheduler_tasks` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'PENDING'")
            }
        }

        
        private val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `appywork_projects` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `remoteUrl` TEXT NOT NULL, `authType` TEXT NOT NULL, `authToken` TEXT NOT NULL, `lastUpdated` INTEGER NOT NULL)")
                database.execSQL("CREATE TABLE IF NOT EXISTS `appywork_file_nodes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `projectId` INTEGER NOT NULL, `path` TEXT NOT NULL, `localHash` TEXT NOT NULL, `syncState` TEXT NOT NULL, FOREIGN KEY(`projectId`) REFERENCES `appywork_projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_appywork_file_nodes_projectId_path` ON `appywork_file_nodes` (`projectId`, `path`)")
            }
        }

        private val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `notification_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `packageName` TEXT NOT NULL, `appName` TEXT NOT NULL, `title` TEXT NOT NULL, `text` TEXT NOT NULL, `timestamp` INTEGER NOT NULL)")
            }
        }
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "litereader_db"
                ).addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
