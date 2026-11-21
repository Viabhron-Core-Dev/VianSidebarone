package com.example.feature.language.dictionary

/**
 * Parsed metadata from a StarDict `.ifo` file (versions 2.4.2 and 3.0.0).
 */
data class StarDictIfo(
    val version: String,
    val bookName: String,
    val wordCount: Long,
    val synWordCount: Long = 0L,
    val idxFileSize: Long,
    val idxOffsetBits: Int = 32,
    val sameTypeSequence: String = "",
    val author: String = "",
    val email: String = "",
    val website: String = "",
    val description: String = "",
    val date: String = ""
)

/**
 * Persisted catalog entry describing one installed offline StarDict dictionary.
 */
data class InstalledDictionary(
    val id: String,
    val name: String,
    val wordCount: Long,
    val synonymCount: Long = 0L,
    val idxOffsetBits: Int = 32,
    val sameTypeSequence: String = "",
    val author: String = "",
    val description: String = "",
    val storageBytes: Long = 0L,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val sourceFingerprint: String = "",
    val installedAtMillis: Long = System.currentTimeMillis()
)

/**
 * One resolved definition entry returned by [DictionaryService.lookup].
 */
data class DictionaryDefinitionEntry(
    val dictionaryId: String,
    val dictionaryName: String,
    val headword: String,
    val matchedSynonym: String? = null,
    val definitionText: String,
    val phonetic: String? = null,
    val partOfSpeech: String? = null,
    val hasUnsupportedBinaryOnly: Boolean = false
)

/**
 * Structured outcome of an offline dictionary lookup.
 */
sealed class DictionaryLookupOutcome {
    data object EmptyQuery : DictionaryLookupOutcome()
    data object NoDictionariesInstalled : DictionaryLookupOutcome()
    data object AllDictionariesDisabled : DictionaryLookupOutcome()
    data class NotFound(
        val query: String,
        val searchedDictionaries: Int
    ) : DictionaryLookupOutcome()

    data class Found(
        val query: String,
        val entries: List<DictionaryDefinitionEntry>
    ) : DictionaryLookupOutcome()

    data class Error(
        val query: String,
        val message: String
    ) : DictionaryLookupOutcome()
}

/**
 * Progress snapshot emitted during off-main-thread StarDict ZIP import.
 */
data class DictionaryImportProgress(
    val stage: String,
    val detail: String,
    val fraction: Float? = null
)

/**
 * Outcome of importing a StarDict ZIP archive.
 */
sealed class DictionaryImportResult {
    data class Success(
        val installed: List<InstalledDictionary>,
        val skippedDuplicates: List<String> = emptyList()
    ) : DictionaryImportResult()

    data class Failure(
        val message: String
    ) : DictionaryImportResult()
}
