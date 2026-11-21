package com.h0lymarc.eside.manager

import android.content.Context
import java.io.File

object ProjectManager {

    private val NAME_REGEX = Regex("[A-Za-z0-9_-]{1,63}")
    private val ENTRY_REGEX = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
    private val SOURCE_EXTENSIONS = listOf(".ino", ".cpp", ".h")
    private const val PREFS = "projects"
    private const val KEY_LAST = "last_project"

    /** /data/data/<package>/files/projects */
    fun root(context: Context): File =
        File(context.filesDir, "projects").apply { mkdirs() }

    fun readAsset(context: Context, path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    /** Creates projects/<name>/<name>.ino (Arduino needs the folder and main .ino to share a name). */
    fun createProject(context: Context, name: String, templateAsset: String): Result<File> =
        runCatching {
            require(name.matches(NAME_REGEX)) {
                "Use 1-63 letters, numbers, _ or - (no spaces)"
            }
            val dir = File(root(context), name)
            require(!dir.exists()) { "A project named \"$name\" already exists" }
            check(dir.mkdirs()) { "Could not create the project folder" }

            context.assets.open(templateAsset).use { input ->
                File(dir, "$name.ino").outputStream().use { input.copyTo(it) }
            }
            dir
        }

    /** All files inside the project, including subfolders, sorted by relative path. */
    fun listFiles(dir: File): List<File> =
        dir.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.relativeTo(dir).path.lowercase() }
            .toList()

    // ---- file tree ----

    /** Folders first, then files (main sketch first), skipping the children of collapsed folders. */
    fun listTree(projectDir: File, collapsed: Set<String>): List<TreeEntry> {
        val out = mutableListOf<TreeEntry>()

        fun walk(dir: File, depth: Int) {
            val children = dir.listFiles().orEmpty().sortedWith(
                compareByDescending<File> { it.isDirectory }
                    .thenByDescending { isMainSketch(projectDir, it) }
                    .thenBy { it.name.lowercase() }
            )
            for (child in children) {
                out += TreeEntry(child, depth)
                if (child.isDirectory && child.absolutePath !in collapsed) {
                    walk(child, depth + 1)
                }
            }
        }

        walk(projectDir, 0)
        return out
    }

    fun isMainSketch(projectDir: File, file: File): Boolean =
        file.isFile &&
            file.parentFile?.canonicalPath == projectDir.canonicalPath &&
            file.name == "${projectDir.name}.ino"

    private fun isInside(projectDir: File, target: File, allowSelf: Boolean): Boolean {
        val root = projectDir.canonicalPath
        val path = target.canonicalPath
        return (allowSelf && path == root) || path.startsWith(root + File.separator)
    }

    private fun starterContent(fileName: String): String = when {
        fileName.endsWith(".cpp", ignoreCase = true) -> "#include <Arduino.h>\n\n"
        fileName.endsWith(".h", ignoreCase = true) -> "#pragma once\n\n"
        else -> ""
    }

    /**
     * Creates a source file. If [name] already ends in .ino, .cpp or .h that extension is kept,
     * otherwise [extension] is added.
     */
    fun createFile(projectDir: File, parent: File, name: String, extension: String): Result<File> =
        runCatching {
            require(name.isNotBlank()) { "Enter a name" }
            require(parent.isDirectory && isInside(projectDir, parent, allowSelf = true)) {
                "That folder is outside the project"
            }
            val fileName =
                if (SOURCE_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }) name
                else name + extension
            require(fileName.matches(ENTRY_REGEX)) {
                "Use letters, numbers, _ . and - only (max 64 characters)"
            }
            val file = File(parent, fileName)
            require(!file.exists()) { "\"$fileName\" already exists" }
            file.writeText(starterContent(fileName))
            file
        }

    fun createFolder(projectDir: File, parent: File, name: String): Result<File> =
        runCatching {
            require(name.isNotBlank()) { "Enter a name" }
            require(parent.isDirectory && isInside(projectDir, parent, allowSelf = true)) {
                "That folder is outside the project"
            }
            require(name.matches(ENTRY_REGEX)) {
                "Use letters, numbers, _ . and - only (max 64 characters)"
            }
            val dir = File(parent, name)
            require(!dir.exists()) { "\"$name\" already exists" }
            check(dir.mkdirs()) { "Could not create the folder" }
            dir
        }

    /** Deletes a file or folder inside the project. The main sketch is protected. */
    fun deleteEntry(projectDir: File, target: File): Result<Unit> =
        runCatching {
            require(isInside(projectDir, target, allowSelf = false)) {
                "That item is outside the project"
            }
            require(!isMainSketch(projectDir, target)) {
                "The main sketch can't be deleted"
            }
            check(target.deleteRecursively()) { "Could not delete ${target.name}" }
        }

    // ---- last opened project ----

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setLastProject(context: Context, dir: File) =
        prefs(context).edit().putString(KEY_LAST, dir.absolutePath).apply()

    fun clearLastProject(context: Context) =
        prefs(context).edit().remove(KEY_LAST).apply()

    /** The previous project, or null if none or its folder no longer exists. */
    fun lastProject(context: Context): File? =
        prefs(context).getString(KEY_LAST, null)?.let(::File)?.takeIf { it.isDirectory }

    // ---- project list ----

    fun listProjects(context: Context): List<ProjectInfo> =
        root(context).listFiles { f -> f.isDirectory }.orEmpty().map { dir ->
            val files = dir.walkTopDown().filter { it.isFile }.toList()
            ProjectInfo(
                dir = dir,
                fileCount = files.size,
                sizeBytes = files.sumOf { it.length() },
                modified = files.maxOfOrNull { it.lastModified() } ?: dir.lastModified()
            )
        }.sortedByDescending { it.modified }

    /** Deletes a project folder. Refuses anything outside the projects root. */
    fun deleteProject(context: Context, dir: File): Boolean {
        val rootPath = root(context).canonicalPath + File.separator
        if (!dir.canonicalPath.startsWith(rootPath)) return false

        val wasLast = prefs(context).getString(KEY_LAST, null) == dir.absolutePath
        val deleted = dir.deleteRecursively()
        if (deleted && wasLast) clearLastProject(context)
        return deleted
    }
}

data class ProjectInfo(
    val dir: File,
    val fileCount: Int,
    val sizeBytes: Long,
    val modified: Long
) {
    val name: String get() = dir.name
}

data class TreeEntry(val file: File, val depth: Int)