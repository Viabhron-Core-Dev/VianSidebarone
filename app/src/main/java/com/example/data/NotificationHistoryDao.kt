package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationHistoryDao {
    @Insert
    suspend fun insert(notification: NotificationHistory): Long

    @Update
    suspend fun update(notification: NotificationHistory)

    @Query("SELECT * FROM notification_history WHERE packageName = :packageName AND title = :title ORDER BY timestamp DESC LIMIT 1")
    suspend fun findLatestByPackageAndTitle(packageName: String, title: String): NotificationHistory?

    @Query("SELECT * FROM notification_history ORDER BY timestamp DESC")
    fun getAll(): Flow<List<NotificationHistory>>
    
    @Query("SELECT * FROM notification_history WHERE packageName NOT IN (:excludedPackages) ORDER BY timestamp DESC")
    fun getFiltered(excludedPackages: List<String>): Flow<List<NotificationHistory>>

    @Query("SELECT * FROM notification_history WHERE (title LIKE '%' || :query || '%' OR text LIKE '%' || :query || '%' OR appName LIKE '%' || :query || '%') ORDER BY timestamp DESC")
    fun searchAll(query: String): Flow<List<NotificationHistory>>

    @Query("SELECT * FROM notification_history WHERE (title LIKE '%' || :query || '%' OR text LIKE '%' || :query || '%' OR appName LIKE '%' || :query || '%') AND packageName NOT IN (:excludedPackages) ORDER BY timestamp DESC")
    fun search(query: String, excludedPackages: List<String>): Flow<List<NotificationHistory>>

    @Query("DELETE FROM notification_history")
    suspend fun deleteAll()
}
