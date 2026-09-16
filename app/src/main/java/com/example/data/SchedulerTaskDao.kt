package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SchedulerTaskDao {
    @Query("SELECT * FROM scheduler_tasks ORDER BY timeMillis ASC")
    fun getAllTasks(): Flow<List<SchedulerTask>>

    @Insert
    suspend fun insert(task: SchedulerTask)

    @Update
    suspend fun update(task: SchedulerTask)

    @Delete
    suspend fun delete(task: SchedulerTask)
}
