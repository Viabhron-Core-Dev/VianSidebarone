package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "logs")
data class LogEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long,
    val tag: String,
    val message: String
)
