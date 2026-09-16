package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scheduler_tasks")
data class SchedulerTask(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String = "",
    val note: String = "",
    val timeMillis: Long,
    val tags: String = "",
    val status: String = "PENDING"
)
