package com.puretv.twitch.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [TextStore] backed by one file per name inside [dir]. Writes go to a temp
 * file and are then renamed over the old one, so a crash mid-write leaves the
 * previous copy intact. The folder is capped at [maxFiles]; the least recently
 * written files are deleted first. (Identical to the desktopMain copy: both
 * targets are JVM, but core has no shared JVM source set.)
 */
class FileTextStore(private val dir: File, private val maxFiles: Int = 64) : TextStore {

    override suspend fun read(name: String): String? = withContext(Dispatchers.IO) {
        runCatching { fileFor(name).takeIf { it.isFile }?.readText() }.getOrNull()
    }

    override suspend fun write(name: String, text: String) = withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()
            val target = fileFor(name)
            val temp = File(dir, target.name + ".tmp")
            temp.writeText(text)
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
            prune()
        }
        Unit
    }

    private fun fileFor(name: String): File = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    private fun prune() {
        val files = dir.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") } ?: return
        if (files.size <= maxFiles) return
        files.sortedBy { it.lastModified() }.take(files.size - maxFiles).forEach { it.delete() }
    }
}
