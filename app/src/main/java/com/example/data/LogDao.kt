package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {
    @Insert
    suspend fun insert(log: LogEntry)

    @Query("SELECT * FROM logs WHERE timestamp >= :minTimestamp ORDER BY timestamp DESC")
    fun getLogsFlow(minTimestamp: Long): Flow<List<LogEntry>>

    @Query("SELECT * FROM logs ORDER BY timestamp DESC")
    suspend fun getAllLogsSync(): List<LogEntry>

    @Query("DELETE FROM logs")
    suspend fun clearLogs()
}
