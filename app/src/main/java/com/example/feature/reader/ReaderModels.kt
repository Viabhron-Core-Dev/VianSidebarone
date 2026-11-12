package com.example.feature.reader

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Screen modes within the floating Reader mini-app.
 */
enum class ReaderScreenMode {
    LIBRARY,
    READING
}

/**
 * Safe coroutine dispatcher helper ensuring JVM test execution without Main looper crashes.
 */
object ReaderDispatchers {
    val main: CoroutineDispatcher by lazy {
        try {
            val d = Dispatchers.Main
            d.isDispatchNeeded(EmptyCoroutineContext)
            d
        } catch (_: Throwable) {
            Dispatchers.Default
        }
    }
}

/**
 * Event broadcast interface for library updates (e.g. when a book is imported via SAF).
 */
object ReaderEventManager {
    private val listeners = mutableListOf<() -> Unit>()

    fun registerListener(listener: () -> Unit) {
        synchronized(listeners) {
            listeners.add(listener)
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
}
