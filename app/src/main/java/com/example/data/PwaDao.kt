package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * PwaDao: Data access object for imported Offline PWAs.
 */
@Dao
interface PwaDao {

    @Query("SELECT * FROM pwa_apps ORDER BY name COLLATE NOCASE ASC")
    fun getAllFlow(): Flow<List<PwaEntity>>

    @Query("SELECT * FROM pwa_apps ORDER BY name COLLATE NOCASE ASC")
    fun getAllSync(): List<PwaEntity>

    @Query("SELECT * FROM pwa_apps WHERE id = :id LIMIT 1")
    fun getById(id: String): PwaEntity?

    @Query("SELECT * FROM pwa_apps WHERE id = :id LIMIT 1")
    fun getByIdFlow(id: String): Flow<PwaEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(entity: PwaEntity)

    @Update
    fun update(entity: PwaEntity)

    @Delete
    fun delete(entity: PwaEntity)

    @Query("DELETE FROM pwa_apps WHERE id = :id")
    fun deleteById(id: String)

    @Query("UPDATE pwa_apps SET lastLaunchedAt = :time WHERE id = :id")
    fun updateLastLaunched(id: String, time: Long)

    @Query("UPDATE pwa_apps SET name = :name, iconPath = :iconPath, updatedAt = :updatedAt WHERE id = :id")
    fun updateMetadata(id: String, name: String, iconPath: String?, updatedAt: Long)
}
