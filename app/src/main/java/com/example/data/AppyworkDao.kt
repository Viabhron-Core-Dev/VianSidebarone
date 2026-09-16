package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Delete
import kotlinx.coroutines.flow.Flow

@Dao
interface AppyworkDao {
    @Query("SELECT * FROM appywork_projects ORDER BY lastUpdated DESC")
    fun getAllProjectsFlow(): Flow<List<AppyworkProject>>

    @Query("SELECT * FROM appywork_projects ORDER BY lastUpdated DESC")
    suspend fun getAllProjects(): List<AppyworkProject>

    @Query("SELECT * FROM appywork_projects WHERE id = :id LIMIT 1")
    suspend fun getProjectById(id: Int): AppyworkProject?

    @Insert
    suspend fun insertProject(project: AppyworkProject): Long

    @Update
    suspend fun updateProject(project: AppyworkProject)

    @Delete
    suspend fun deleteProject(project: AppyworkProject)

    @Query("SELECT * FROM appywork_file_nodes WHERE projectId = :projectId ORDER BY path ASC")
    fun getFilesForProjectFlow(projectId: Int): Flow<List<AppyworkFileNode>>

    @Query("SELECT * FROM appywork_file_nodes WHERE projectId = :projectId")
    suspend fun getFilesForProject(projectId: Int): List<AppyworkFileNode>

    @Query("SELECT * FROM appywork_file_nodes WHERE projectId = :projectId AND path = :path LIMIT 1")
    suspend fun getFileNode(projectId: Int, path: String): AppyworkFileNode?

    @Insert
    suspend fun insertFileNode(fileNode: AppyworkFileNode): Long

    @Update
    suspend fun updateFileNode(fileNode: AppyworkFileNode)

    @Delete
    suspend fun deleteFileNode(fileNode: AppyworkFileNode)
    
    @Query("DELETE FROM appywork_file_nodes WHERE projectId = :projectId")
    suspend fun deleteAllFilesForProject(projectId: Int)
}
