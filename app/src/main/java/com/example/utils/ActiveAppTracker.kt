package com.example.utils

import kotlinx.coroutines.flow.MutableStateFlow

data class ActiveAppInfo(
    val id: String,
    val name: String,
    val type: String,
    val estimatedMemoryMb: Int
)

object ActiveAppTracker {
    val activeApps = MutableStateFlow<List<ActiveAppInfo>>(emptyList())
    
    fun addApp(id: String, name: String, type: String, estMb: Int = 10) {
        val current = activeApps.value.toMutableList()
        current.removeAll { it.id == id } // avoid duplicates
        current.add(ActiveAppInfo(id, name, type, estMb))
        activeApps.value = current
    }
    
    fun removeApp(id: String) {
        val current = activeApps.value.toMutableList()
        current.removeAll { it.id == id }
        activeApps.value = current
    }
}
