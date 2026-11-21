package com.h0lymarc.eside

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import java.io.File

internal data class CreateRequest(val parent: File, val folder: Boolean)

internal sealed interface PendingCloseAction {
    data class SingleTab(val path: String) : PendingCloseAction
    data class BatchTabs(val pathsToClose: List<String>, val keepPath: String? = null) : PendingCloseAction
    data object Project : PendingCloseAction
}

/** Tabs of the navigation drawer. */
internal enum class DrawerTab(val label: String, val icon: ImageVector) {
    FILES("Files", Icons.Outlined.Folder),
    LIBRARIES("Libraries", Icons.Outlined.Extension),
    BOARDS("Boards", Icons.Outlined.DeveloperBoard)
}

internal enum class BoardFilter(val label: String) {
    ALL("All"),
    INSTALLED("Installed"),
    ARDUINO("Arduino"),
    THIRD_PARTY("3rd-Party")
}

internal enum class LibraryFilter(val label: String) {
    ALL("All"),
    INSTALLED("Installed"),
    COMPATIBLE("Compatible")
}

/** Tabs of the bottom panel. */
internal enum class PanelTab(val label: String) {
    COMPILE("Compile Logs"),
    IDE("IDE Logs"),
    TERMINAL("Terminal Logs"),
    SEARCH("Search Results"),
    PROBLEMS("Problems")
}

internal enum class LogLevel { INFO, WARN, ERROR }

internal data class LogLine(val time: String, val level: LogLevel, val text: String)

internal data class SearchHit(
    val path: String,
    val line: Int,          // 0-based, same as the editor
    val col: Int,           // 0-based
    val length: Int,
    val preview: String,    // trimmed line shown in the results list
    val previewStart: Int   // where the match starts inside preview (can be out of range)
)

internal const val MAX_HITS = 1000
internal const val MAX_LOG_LINES = 500
internal const val MAX_SEARCH_FILE_BYTES = 1_000_000L
internal const val PREVIEW_CHARS = 200

internal val SEARCH_EXTENSIONS = setOf(
    "ino", "cpp", "cc", "c", "h", "hpp", "txt", "md",
    "json", "xml", "yaml", "yml", "properties", "ini", "cfg", "csv"
)

internal data class FileStyle(val icon: ImageVector, val color: Color)

internal fun styleFor(file: File): FileStyle = when (file.extension.lowercase()) {
    "ino" -> FileStyle(Icons.Outlined.Memory, Color(0xFF00979D))
    "cpp", "cc", "c" -> FileStyle(Icons.Outlined.Code, Color(0xFF4F8CFF))
    "h", "hpp" -> FileStyle(Icons.Outlined.DataObject, Color(0xFFB07CFF))
    "json", "xml", "yaml", "yml", "properties" -> FileStyle(Icons.Outlined.Settings, Color(0xFFFFB454))
    "png", "jpg", "jpeg", "webp" -> FileStyle(Icons.Outlined.Image, Color(0xFFFF7A90))
    else -> FileStyle(Icons.Outlined.Description, Color(0xFF9AA0A6))
}

internal fun formatSize(bytes: Long): String =
    if (bytes < 1024) "$bytes B" else "%.1f KB".format(bytes / 1024.0)
