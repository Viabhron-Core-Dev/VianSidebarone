package com.example.data

import android.content.Context
import java.io.File

class AppyworkFileSystem(private val context: Context) {

    private fun getProjectDir(projectId: Int): File {
        val dir = File(context.filesDir, "appywork_projects/$projectId")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun writeFile(projectId: Int, path: String, content: String) {
        val projectDir = getProjectDir(projectId)
        val file = File(projectDir, path)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    fun readFile(projectId: Int, path: String): String? {
        val projectDir = getProjectDir(projectId)
        val file = File(projectDir, path)
        return if (file.exists()) file.readText() else null
    }

    fun deleteFile(projectId: Int, path: String): Boolean {
        val projectDir = getProjectDir(projectId)
        val file = File(projectDir, path)
        return if (file.exists()) file.delete() else false
    }

    fun moveFile(projectId: Int, oldPath: String, newPath: String): Boolean {
        val projectDir = getProjectDir(projectId)
        val oldFile = File(projectDir, oldPath)
        val newFile = File(projectDir, newPath)
        newFile.parentFile?.mkdirs()
        return oldFile.renameTo(newFile)
    }

    fun getFile(projectId: Int, path: String): File {
        return File(getProjectDir(projectId), path)
    }
}
