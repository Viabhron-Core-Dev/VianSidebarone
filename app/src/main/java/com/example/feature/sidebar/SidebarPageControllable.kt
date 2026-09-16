package com.example.feature.sidebar

interface SidebarPageControllable {
    fun onEditClicked() {}
    fun onPageSelected() {}
    fun onPageUnselected() {}
    fun onTrimMemory(level: Int) {}
}
