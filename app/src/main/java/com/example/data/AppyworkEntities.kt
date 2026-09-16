package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "appywork_projects")
data class AppyworkProject(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String,
    val remoteUrl: String,
    val authType: String, // PAT, GITHUB_APP, MCP
    val authToken: String,
    val lastUpdated: Long
)

@Entity(
    tableName = "appywork_file_nodes",
    foreignKeys = [
        ForeignKey(
            entity = AppyworkProject::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["projectId", "path"], unique = true)]
)
data class AppyworkFileNode(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val projectId: Int,
    val path: String, // Relative path inside the project e.g. "src/main.kt"
    val localHash: String,
    val syncState: String // NEW, MODIFIED, DELETED, SYNCED
)
