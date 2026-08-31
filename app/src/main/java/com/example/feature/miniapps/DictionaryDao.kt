package com.example.feature.miniapps
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface DictionaryDao {
    @Query("SELECT * FROM dictionary_entries WHERE word = :word AND dictName = :dictName LIMIT 1")
    fun getDefinition(word: String, dictName: String): DictionaryEntry?

    @Query("SELECT * FROM dictionary_entries WHERE word LIKE :query AND dictName = :dictName LIMIT 50")
    fun searchWords(query: String, dictName: String): List<DictionaryEntry>

    @Query("SELECT DISTINCT dictName FROM dictionary_entries")
    fun getAvailableDictionaries(): List<String>

    @Insert
    fun insertAll(entries: List<DictionaryEntry>)

    @Query("DELETE FROM dictionary_entries WHERE dictName = :dictName")
    fun clearDictionary(dictName: String)

    @Query("DELETE FROM dictionary_entries")
    fun clearAll()
}
