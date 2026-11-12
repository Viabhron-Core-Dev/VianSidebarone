package com.example.feature.reader

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Screen modes within the floating Reader mini-app.
 */
enum class ReaderScreenMode {
    LIBRARY,
    READING,
    FOLDER_BROWSER
}

/**
 * Safe coroutine dispatcher helper ensuring JVM test execution without Main looper crashes.
 */
object ReaderDispatchers {
    val main: CoroutineDispatcher by lazy {
        try {
            val d = Dispatchers.Main
            d.isDispatchNeeded(kotlin.coroutines.EmptyCoroutineContext)
            d
        } catch (_: Throwable) {
            Dispatchers.Default
        }
    }
}

/**
 * Persists and retrieves the user's selected SAF library folder tree URI across Main and :heavy.
 */
object ReaderFolderPrefs {
    private const val PREFS_NAME = "FloatingReaderPrefs"
    private const val KEY_TREE_URI = "reader_folder_tree_uri"

    fun getFolderUri(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_TREE_URI, null)?.takeIf { it.isNotBlank() }
    }

    fun saveFolderUri(context: Context, uriStr: String?) {
        val clean = uriStr?.takeIf { it.isNotBlank() }
        if (clean == null) {
            com.example.core.OverlaySyncManager.syncRemove(context, KEY_TREE_URI, PREFS_NAME)
        } else {
            com.example.core.OverlaySyncManager.syncString(context, KEY_TREE_URI, clean, PREFS_NAME)
        }
    }
}

/**
 * Event broadcast interface for library and SAF folder updates across Main and :heavy.
 */
object ReaderEventManager {
    private val listeners = mutableListOf<() -> Unit>()
    private val folderListeners = mutableListOf<(String?) -> Unit>()

    fun registerListener(listener: () -> Unit) {
        synchronized(listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }
    }

    fun unregisterListener(listener: () -> Unit) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    fun notifyLibraryChanged() {
        val copy = synchronized(listeners) { ArrayList(listeners) }
        for (listener in copy) {
            try {
                listener.invoke()
            } catch (_: Throwable) {}
        }
    }

    fun registerFolderListener(listener: (String?) -> Unit) {
        synchronized(folderListeners) {
            if (!folderListeners.contains(listener)) {
                folderListeners.add(listener)
            }
        }
    }

    fun unregisterFolderListener(listener: (String?) -> Unit) {
        synchronized(folderListeners) {
            folderListeners.remove(listener)
        }
    }

    fun notifyFolderGranted(folderUri: String?) {
        val copy = synchronized(folderListeners) { ArrayList(folderListeners) }
        for (listener in copy) {
            try {
                listener.invoke(folderUri)
            } catch (_: Throwable) {}
        }
    }
}
