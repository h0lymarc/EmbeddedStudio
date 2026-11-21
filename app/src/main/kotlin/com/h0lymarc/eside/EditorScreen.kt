package com.h0lymarc.eside

import android.content.Context
import android.graphics.Typeface
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.h0lymarc.eside.manager.ArduinoCompiler
import com.h0lymarc.eside.manager.ArduinoKeywords
import com.h0lymarc.eside.manager.ArduinoLibrary
import com.h0lymarc.eside.manager.BoardBuildConfig
import com.h0lymarc.eside.manager.BoardManager
import com.h0lymarc.eside.manager.BoardMenuGroup
import com.h0lymarc.eside.manager.BoardPlatform
import com.h0lymarc.eside.manager.CodeDiagnostic
import com.h0lymarc.eside.manager.EditorPrefs
import com.h0lymarc.eside.manager.EditorTheme
import com.h0lymarc.eside.manager.InstalledLibrary
import com.h0lymarc.eside.manager.InstalledPlatform
import com.h0lymarc.eside.manager.IntelliSenseSuggestion
import com.h0lymarc.eside.manager.LibraryManager
import com.h0lymarc.eside.manager.ProjectIndex
import com.h0lymarc.eside.manager.ProjectManager
import com.h0lymarc.eside.manager.ProjectSymbolIndexer
import com.h0lymarc.eside.manager.SelectedBoard
import com.h0lymarc.eside.manager.TextMateSetup
import com.h0lymarc.eside.manager.TreeEntry
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import com.h0lymarc.eside.drawer.BoardIndexUrlsDialog
import com.h0lymarc.eside.drawer.DrawerBoardsTab
import com.h0lymarc.eside.drawer.DrawerLibrariesTab
import com.h0lymarc.eside.drawer.DrawerBottomTabBar
import com.h0lymarc.eside.drawer.TreeRow
import com.h0lymarc.eside.drawer.SelectionHeader
import com.h0lymarc.eside.drawer.NewEntryDialog
import com.h0lymarc.eside.drawer.LibraryDetailsDialog
import com.h0lymarc.eside.editor.EditorTabBar
import com.h0lymarc.eside.editor.EmptyEditorState
import com.h0lymarc.eside.editor.FindBar
import com.h0lymarc.eside.editor.IntelliSenseBar
import com.h0lymarc.eside.editor.BottomPanel
import com.h0lymarc.eside.editor.BoardOptionsDialog
import com.h0lymarc.eside.editor.CompileLogView
import com.h0lymarc.eside.editor.DiagnosticsBanner
import com.h0lymarc.eside.editor.EditorQuickBar
import com.h0lymarc.eside.editor.GoToLineDialog
import com.h0lymarc.eside.editor.LogListView
import com.h0lymarc.eside.editor.ProblemsView
import com.h0lymarc.eside.editor.QuickBoardSwitcherDialog
import com.h0lymarc.eside.editor.SearchResultsView
import com.h0lymarc.eside.editor.SymbolsDialog
import com.h0lymarc.eside.search.findMatches
import com.h0lymarc.eside.search.searchWholeProject
import com.h0lymarc.eside.search.pickHitIndex

private fun snapshotStringMapSaver() =
    Saver<SnapshotStateMap<String, String>, HashMap<String, String>>(
        save = { HashMap(it) },
        restore = { restored -> mutableStateMapOf<String, String>().apply { putAll(restored) } }
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    templateAsset: String?,
    initialProject: String?,
    requiredLibraries: List<String> = emptyList(),
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val dark = isSystemInDarkTheme()

    var projectPath by rememberSaveable { mutableStateOf(initialProject) }
    var openPath by rememberSaveable { mutableStateOf<String?>(null) }
    var showCloseDialog by rememberSaveable { mutableStateOf(false) }

    // Open files (tabs). openPath is the active one.
    var tabs by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var noFileOpen by rememberSaveable { mutableStateOf(false) }

    // Per-tab saved disk baselines & live in-memory drafts
    val savedText = rememberSaveable(saver = snapshotStringMapSaver()) { mutableStateMapOf() }
    val draftText = rememberSaveable(saver = snapshotStringMapSaver()) { mutableStateMapOf() }
    val dirtyPaths by remember {
        derivedStateOf {
            tabs.filter { path ->
                val saved = savedText[path] ?: return@filter false
                val draft = draftText[path] ?: saved
                draft != saved
            }.toSet()
        }
    }
    var pendingClose by remember { mutableStateOf<PendingCloseAction?>(null) }

    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var compiling by remember { mutableStateOf(false) }
    var buildLog by remember { mutableStateOf<String?>(null) }
    var buildOk by remember { mutableStateOf(false) }

    // IntelliSense: project symbol index, live diagnostics, context suggestions
    var projectIndex by remember { mutableStateOf(ProjectIndex()) }
    var diagnostics by remember { mutableStateOf<List<CodeDiagnostic>>(emptyList()) }
    var suggestions by remember { mutableStateOf<List<IntelliSenseSuggestion>>(emptyList()) }
    var showSymbolsDialog by remember { mutableStateOf(false) }
    var contentVersion by remember { mutableIntStateOf(0) }

    // Drawer tab (Files / Libraries / Boards) & manager state
    var drawerTabIndex by rememberSaveable { mutableIntStateOf(0) }
    val drawerPagerState = rememberPagerState(
        initialPage = drawerTabIndex.coerceIn(0, DrawerTab.entries.lastIndex),
        pageCount = { DrawerTab.entries.size }
    )
    var libraryCatalog by remember { mutableStateOf<List<ArduinoLibrary>>(emptyList()) }
    var installedLibs by remember { mutableStateOf<Map<String, InstalledLibrary>>(emptyMap()) }
    var libraryQuery by rememberSaveable { mutableStateOf("") }
    var libraryFilter by rememberSaveable { mutableStateOf(LibraryFilter.ALL) }
    var libraryLoading by remember { mutableStateOf(false) }
    var libraryError by remember { mutableStateOf<String?>(null) }
    var downloadingLibs by remember { mutableStateOf(emptySet<String>()) }
    var selectedLibrary by remember { mutableStateOf<ArduinoLibrary?>(null) }
    var headerPickerLib by remember { mutableStateOf<InstalledLibrary?>(null) }
    var libraryKeywordsVersion by remember { mutableIntStateOf(0) }

    // The editor stays invisible until its theme + language are set (no white flash on start)
    var themeReady by remember { mutableStateOf(false) }
    var languageReady by remember { mutableStateOf(false) }

    var selectedBoard by remember { mutableStateOf(EditorPrefs.selectedBoard(context)) }
    var boardCatalog by remember { mutableStateOf<List<BoardPlatform>>(emptyList()) }
    var installedPlatforms by remember { mutableStateOf<Map<String, InstalledPlatform>>(emptyMap()) }
    var boardQuery by rememberSaveable { mutableStateOf("") }
    var boardFilter by rememberSaveable { mutableStateOf(BoardFilter.ALL) }
    var boardLoading by remember { mutableStateOf(false) }
    var boardError by remember { mutableStateOf<String?>(null) }
    var downloadingPlatforms by remember { mutableStateOf(emptySet<String>()) }
    var boardPickerPlatform by remember { mutableStateOf<InstalledPlatform?>(null) }
    var boardMenus by remember { mutableStateOf<List<BoardMenuGroup>>(emptyList()) }
    var boardInfo by remember { mutableStateOf<BoardBuildConfig?>(null) }
    var selectedBoardOptions by remember { mutableStateOf(EditorPrefs.boardOptions(context, selectedBoard.fqbn)) }
    var showBoardOptionsDialog by remember { mutableStateOf(false) }
    var showBoardSwitcherDialog by remember { mutableStateOf(false) }
    var showBoardUrlsDialog by remember { mutableStateOf(false) }

    // Bottom panel: compile logs, IDE logs, terminal logs, search results, problems
    var panelOpen by rememberSaveable { mutableStateOf(false) }
    var panelTabIndex by rememberSaveable { mutableStateOf(0) }
    var panelHeight by remember { mutableFloatStateOf(240f) }   // dp
    val ideLogs = remember { mutableStateListOf<LogLine>() }
    val terminalLogs = remember { mutableStateListOf<LogLine>() }
    val logTime = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    // Find (in the open file or in the whole project)
    var findOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var projectScope by rememberSaveable { mutableStateOf(false) }
    var matchCase by rememberSaveable { mutableStateOf(false) }
    var wholeWord by rememberSaveable { mutableStateOf(false) }
    var searchHits by remember { mutableStateOf(emptyList<SearchHit>()) }
    var currentHit by remember { mutableIntStateOf(-1) }
    var searching by remember { mutableStateOf(false) }

    // Theme picker + multi-select in the file drawer
    var themeMenuOpen by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var deleteMany by remember { mutableStateOf<List<File>?>(null) }

    // Theme + board (saved between sessions)
    var themeChoice by remember { mutableStateOf(EditorPrefs.theme(context)) }
    var board by remember { mutableStateOf(selectedBoard.family) }
    var availableThemes by remember { mutableStateOf(emptyList<EditorTheme>()) }
    var menuOpen by remember { mutableStateOf(false) }

    // File tree state
    var treeVersion by remember { mutableIntStateOf(0) }
    var collapsed by remember { mutableStateOf(emptySet<String>()) }
    var createTarget by remember { mutableStateOf<CreateRequest?>(null) }
    var deleteTarget by remember { mutableStateOf<File?>(null) }

    var suppressDraftSync by remember { mutableStateOf(false) }

    val editorPrefs = remember { context.getSharedPreferences("editor", Context.MODE_PRIVATE) }
    var editorTextSizeSp by rememberSaveable {
        mutableFloatStateOf(editorPrefs.getFloat("editor_text_size", 14f).coerceIn(10f, 24f))
    }
    var logZoom by rememberSaveable {
        mutableFloatStateOf(editorPrefs.getFloat("log_zoom_scale", 1f).coerceIn(0.6f, 2.5f))
    }
    var quickBarOpen by rememberSaveable {
        mutableStateOf(editorPrefs.getBoolean("quick_bar_visible", true))
    }
    var showGoToLineDialog by remember { mutableStateOf(false) }

    val editor = remember {
        CodeEditor(context).apply {
            typefaceText = Typeface.MONOSPACE
            setTextSize(editorTextSizeSp)
            isLineNumberEnabled = true
        }
    }

    fun ideLog(text: String, level: LogLevel = LogLevel.INFO) {
        ideLogs.add(LogLine(logTime.format(Date()), level, text))
        while (ideLogs.size > MAX_LOG_LINES) ideLogs.removeAt(0)
    }

    fun syncActiveDraft() {
        val path = openPath ?: return
        draftText[path] = editor.text.toString()
    }

    fun refreshUndoRedo() {
        canUndo = editor.canUndo()
        canRedo = editor.canRedo()
    }

    fun refreshIntelliSense() {
        val text = editor.text.toString()
        val cursor = editor.cursor
        val ctx = ProjectSymbolIndexer.detectCompletionContext(
            text = text,
            cursorLine = cursor.leftLine,
            cursorCol = cursor.leftColumn,
            index = projectIndex
        )
        suggestions = ProjectSymbolIndexer.buildSuggestions(ctx, projectIndex, installedLibs)
    }

    fun applySuggestion(item: IntelliSenseSuggestion) {
        val cursor = editor.cursor
        val line = cursor.leftLine
        val col = cursor.leftColumn
        val startCol = (col - item.replaceLen).coerceAtLeast(0)
        editor.text.replace(line, startCol, line, col, item.insertText)
        val isIncludeContainer = item.insertText.endsWith("\"\"") || item.insertText.endsWith("<>")
        if (item.insertText.endsWith("()") || isIncludeContainer) {
            val newCol = startCol + item.insertText.length - 1
            runCatching { editor.setSelectionRegion(line, newCol, line, newCol) }
        }
        suggestions = emptyList()
        if (isIncludeContainer) {
            editor.post { refreshIntelliSense() }
        }
    }

    fun updateTextSize(newSize: Float) {
        val clamped = newSize.coerceIn(10f, 24f)
        editorTextSizeSp = clamped
        editor.setTextSize(clamped)
        editorPrefs.edit().putFloat("editor_text_size", clamped).apply()
    }

    fun setQuickBarVisible(visible: Boolean) {
        quickBarOpen = visible
        editorPrefs.edit().putBoolean("quick_bar_visible", visible).apply()
    }

    fun indentTab() {
        if (openPath == null && noFileOpen) return
        editor.requestFocus()
        val lines = editor.text.toString().split('\n')
        if (lines.isEmpty()) return
        val maxLine = lines.lastIndex
        val c = editor.cursor
        val l1 = c.leftLine.coerceIn(0, maxLine)
        val l2 = c.rightLine.coerceIn(l1, maxLine)
        val c1 = c.leftColumn.coerceIn(0, lines[l1].length)
        val c2 = c.rightColumn.coerceIn(0, lines[l2].length)
        val hasSelection = l1 != l2 || c1 != c2

        if (!hasSelection) {
            val spaces = 4 - (c1 % 4)
            val pad = " ".repeat(spaces)
            editor.text.insert(l1, c1, pad)
            runCatching { editor.setSelectionRegion(l1, c1 + spaces, l1, c1 + spaces) }
        } else {
            val endLine = if (c2 == 0 && l2 > l1) l2 - 1 else l2
            val block = (l1..endLine).joinToString("\n") { "    " + lines[it] }
            editor.text.replace(l1, 0, endLine, lines[endLine].length, block)
            val newC2 = if (l1 == endLine) c2 + 4 else lines[endLine].length + 4
            runCatching { editor.setSelectionRegion(l1, c1 + 4, endLine, newC2) }
        }
    }

    fun unindentLines() {
        if (openPath == null && noFileOpen) return
        editor.requestFocus()
        val lines = editor.text.toString().split('\n')
        if (lines.isEmpty()) return
        val maxLine = lines.lastIndex
        val c = editor.cursor
        val l1 = c.leftLine.coerceIn(0, maxLine)
        val l2 = c.rightLine.coerceIn(l1, maxLine)
        val c1 = c.leftColumn.coerceIn(0, lines[l1].length)
        val c2 = c.rightColumn.coerceIn(0, lines[l2].length)
        val hasSelection = l1 != l2 || c1 != c2
        val endLine = if (hasSelection && c2 == 0 && l2 > l1) l2 - 1 else l2

        val updated = ArrayList<String>(endLine - l1 + 1)
        var removedFirst = 0
        var removedLast = 0
        for (i in l1..endLine) {
            val line = lines[i]
            val stripCount = when {
                line.startsWith('\t') -> 1
                else -> line.takeWhile { it == ' ' }.length.coerceAtMost(4)
            }
            if (i == l1) removedFirst = stripCount
            if (i == endLine) removedLast = stripCount
            updated.add(line.drop(stripCount))
        }
        editor.text.replace(l1, 0, endLine, lines[endLine].length, updated.joinToString("\n"))

        val newC1 = (c1 - removedFirst).coerceAtLeast(0)
        if (!hasSelection) {
            runCatching { editor.setSelectionRegion(l1, newC1, l1, newC1) }
        } else {
            val newC2 = if (l1 == endLine) (c2 - removedLast).coerceAtLeast(0) else updated.last().length
            runCatching { editor.setSelectionRegion(l1, newC1, endLine, newC2) }
        }
    }

    fun moveCursorHorizontal(delta: Int) {
        if (openPath == null && noFileOpen) return
        editor.requestFocus()
        val lines = editor.text.toString().split('\n')
        if (lines.isEmpty()) return
        val c = editor.cursor
        val line = c.leftLine.coerceIn(0, lines.lastIndex)
        val col = c.leftColumn.coerceIn(0, lines[line].length)

        var targetLine = line
        var targetCol = col + delta
        if (targetCol < 0) {
            if (line > 0) {
                targetLine = line - 1
                targetCol = lines[targetLine].length
            } else {
                targetCol = 0
            }
        } else if (targetCol > lines[line].length) {
            if (line < lines.lastIndex) {
                targetLine = line + 1
                targetCol = 0
            } else {
                targetCol = lines[line].length
            }
        }
        runCatching { editor.setSelectionRegion(targetLine, targetCol, targetLine, targetCol) }
        editor.post { refreshIntelliSense() }
    }

    fun moveCursorLineEdge(toEnd: Boolean) {
        if (openPath == null && noFileOpen) return
        editor.requestFocus()
        val lines = editor.text.toString().split('\n')
        if (lines.isEmpty()) return
        val c = editor.cursor
        val line = c.leftLine.coerceIn(0, lines.lastIndex)
        val current = lines[line]
        val targetCol = if (toEnd) {
            current.length
        } else {
            val firstNonSpace = current.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
            if (c.leftColumn == firstNonSpace) 0 else firstNonSpace
        }
        runCatching { editor.setSelectionRegion(line, targetCol, line, targetCol) }
    }

    fun insertQuickSymbol(token: String) {
        if (openPath == null && noFileOpen) return
        editor.requestFocus()
        val lines = editor.text.toString().split('\n')
        if (lines.isEmpty()) return
        val maxLine = lines.lastIndex
        val c = editor.cursor
        val l1 = c.leftLine.coerceIn(0, maxLine)
        val l2 = c.rightLine.coerceIn(l1, maxLine)
        val c1 = c.leftColumn.coerceIn(0, lines[l1].length)
        val c2 = c.rightColumn.coerceIn(0, lines[l2].length)
        val hasSelection = l1 != l2 || c1 != c2

        val pair = when (token) {
            "{}" -> "{" to "}"
            "()" -> "(" to ")"
            "[]" -> "[" to "]"
            "<>" -> "<" to ">"
            "\"\"" -> "\"" to "\""
            "''" -> "'" to "'"
            else -> null
        }

        if (pair != null) {
            if (hasSelection) {
                val selected = if (l1 == l2) {
                    lines[l1].substring(c1, c2)
                } else {
                    buildString {
                        append(lines[l1].substring(c1))
                        for (i in (l1 + 1) until l2) {
                            append('\n').append(lines[i])
                        }
                        append('\n').append(lines[l2].substring(0, c2))
                    }
                }
                val wrapped = pair.first + selected + pair.second
                editor.text.replace(l1, c1, l2, c2, wrapped)
                if (l1 == l2) {
                    runCatching {
                        editor.setSelectionRegion(l1, c1 + 1, l1, c1 + 1 + selected.length)
                    }
                }
            } else {
                editor.text.insert(l1, c1, token)
                runCatching { editor.setSelectionRegion(l1, c1 + 1, l1, c1 + 1) }
            }
        } else {
            editor.text.replace(l1, c1, l2, c2, token)
            runCatching {
                editor.setSelectionRegion(l1, c1 + token.length, l1, c1 + token.length)
            }
        }
        editor.post { refreshIntelliSense() }
    }

    fun toggleLineComment() {
        if (openPath == null && noFileOpen) return
        editor.requestFocus()
        val lines = editor.text.toString().split('\n')
        if (lines.isEmpty()) return
        val maxLine = lines.lastIndex
        val c = editor.cursor
        val l1 = c.leftLine.coerceIn(0, maxLine)
        val l2 = c.rightLine.coerceIn(l1, maxLine)
        val c1 = c.leftColumn.coerceIn(0, lines[l1].length)
        val c2 = c.rightColumn.coerceIn(0, lines[l2].length)
        val hasSelection = l1 != l2 || c1 != c2
        val endLine = if (hasSelection && c2 == 0 && l2 > l1) l2 - 1 else l2

        val slice = lines.subList(l1, endLine + 1)
        val targetLines = slice.filter { it.isNotBlank() }.ifEmpty { slice }
        val allCommented = targetLines.all { it.trimStart().startsWith("//") }

        val updated = if (allCommented) {
            slice.map { line ->
                val idx = line.indexOf("//")
                if (idx >= 0 && line.substring(0, idx).isBlank()) {
                    val after = if (idx + 2 < line.length && line[idx + 2] == ' ') idx + 3 else idx + 2
                    line.substring(0, idx) + line.substring(after)
                } else {
                    line
                }
            }
        } else {
            val minIndent = targetLines.minOfOrNull { line ->
                line.takeWhile { it == ' ' || it == '\t' }.length
            } ?: 0
            slice.map { line ->
                if (line.isBlank() && slice.size > 1) {
                    line
                } else {
                    val splitAt = minIndent.coerceAtMost(line.length)
                    line.substring(0, splitAt) + "// " + line.substring(splitAt)
                }
            }
        }

        editor.text.replace(l1, 0, endLine, lines[endLine].length, updated.joinToString("\n"))
        if (hasSelection) {
            runCatching { editor.setSelectionRegion(l1, 0, endLine, updated.last().length) }
        } else {
            val colDelta = updated.first().length - slice.first().length
            val nextCol = (c1 + colDelta).coerceIn(0, updated.first().length)
            runCatching { editor.setSelectionRegion(l1, nextCol, l1, nextCol) }
        }
    }

    fun duplicateLine() {
        if (openPath == null && noFileOpen) return
        editor.requestFocus()
        val lines = editor.text.toString().split('\n')
        if (lines.isEmpty()) return
        val maxLine = lines.lastIndex
        val c = editor.cursor
        val l1 = c.leftLine.coerceIn(0, maxLine)
        val l2 = c.rightLine.coerceIn(l1, maxLine)
        val c1 = c.leftColumn.coerceIn(0, lines[l1].length)
        val c2 = c.rightColumn.coerceIn(0, lines[l2].length)
        val hasSelection = l1 != l2 || c1 != c2
        val endLine = if (hasSelection && c2 == 0 && l2 > l1) l2 - 1 else l2

        val slice = lines.subList(l1, endLine + 1).joinToString("\n")
        editor.text.insert(endLine, lines[endLine].length, "\n$slice")
        val count = endLine - l1 + 1
        if (hasSelection) {
            runCatching { editor.setSelectionRegion(l1 + count, 0, endLine + count, lines[endLine].length) }
        } else {
            runCatching { editor.setSelectionRegion(l1 + 1, c1, l1 + 1, c1) }
        }
    }

    fun deleteLine() {
        if (openPath == null && noFileOpen) return
        editor.requestFocus()
        val lines = editor.text.toString().split('\n')
        if (lines.isEmpty()) return
        val maxLine = lines.lastIndex
        val c = editor.cursor
        val l1 = c.leftLine.coerceIn(0, maxLine)
        val l2 = c.rightLine.coerceIn(l1, maxLine)
        val c2 = c.rightColumn.coerceIn(0, lines[l2].length)
        val hasSelection = l1 != l2 || c.leftColumn != c2
        val endLine = if (hasSelection && c2 == 0 && l2 > l1) l2 - 1 else l2

        when {
            l1 == 0 && endLine == maxLine -> {
                editor.text.replace(0, 0, maxLine, lines[maxLine].length, "")
                runCatching { editor.setSelectionRegion(0, 0, 0, 0) }
            }
            endLine < maxLine -> {
                editor.text.replace(l1, 0, endLine + 1, 0, "")
                runCatching { editor.setSelectionRegion(l1, 0, l1, 0) }
            }
            else -> {
                val prevLine = l1 - 1
                editor.text.replace(prevLine, lines[prevLine].length, endLine, lines[endLine].length, "")
                runCatching { editor.setSelectionRegion(prevLine, 0, prevLine, 0) }
            }
        }
    }

    fun moveLine(up: Boolean) {
        if (openPath == null && noFileOpen) return
        editor.requestFocus()
        val lines = editor.text.toString().split('\n')
        if (lines.size < 2) return
        val maxLine = lines.lastIndex
        val c = editor.cursor
        val l1 = c.leftLine.coerceIn(0, maxLine)
        val l2 = c.rightLine.coerceIn(l1, maxLine)
        val c1 = c.leftColumn.coerceIn(0, lines[l1].length)
        val c2 = c.rightColumn.coerceIn(0, lines[l2].length)
        val hasSelection = l1 != l2 || c1 != c2
        val endLine = if (hasSelection && c2 == 0 && l2 > l1) l2 - 1 else l2

        if (up) {
            if (l1 == 0) return
            val above = lines[l1 - 1]
            val moved = lines.subList(l1, endLine + 1)
            val replacement = (moved + above).joinToString("\n")
            editor.text.replace(l1 - 1, 0, endLine, lines[endLine].length, replacement)
            if (hasSelection) {
                runCatching { editor.setSelectionRegion(l1 - 1, 0, endLine - 1, moved.last().length) }
            } else {
                runCatching { editor.setSelectionRegion(l1 - 1, c1, l1 - 1, c1) }
            }
        } else {
            if (endLine >= maxLine) return
            val below = lines[endLine + 1]
            val moved = lines.subList(l1, endLine + 1)
            val replacement = (listOf(below) + moved).joinToString("\n")
            editor.text.replace(l1, 0, endLine + 1, lines[endLine + 1].length, replacement)
            if (hasSelection) {
                runCatching { editor.setSelectionRegion(l1 + 1, 0, endLine + 1, moved.last().length) }
            } else {
                runCatching { editor.setSelectionRegion(l1 + 1, c1, l1 + 1, c1) }
            }
        }
    }

    fun selectCurrentLine() {
        if (openPath == null && noFileOpen) return
        editor.requestFocus()
        val lines = editor.text.toString().split('\n')
        if (lines.isEmpty()) return
        val line = editor.cursor.leftLine.coerceIn(0, lines.lastIndex)
        runCatching { editor.setSelectionRegion(line, 0, line, lines[line].length) }
    }

    fun formatCode() {
        if (openPath == null && noFileOpen) return
        val original = editor.text.toString()
        val lines = original.split('\n')
        var indentLevel = 0
        var inBlockComment = false
        val formatted = ArrayList<String>(lines.size)

        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) {
                formatted.add("")
                continue
            }

            // Scan line braces outside strings and comments
            var openCount = 0
            var closeCount = 0
            var leadingClose = 0
            var sawCodeToken = false
            var inString = false
            var inChar = false
            var escaped = false
            var i = 0
            while (i < trimmed.length) {
                val ch = trimmed[i]
                val next = trimmed.getOrNull(i + 1)
                if (inBlockComment) {
                    if (ch == '*' && next == '/') {
                        inBlockComment = false
                        i += 2
                        continue
                    }
                    i++
                    continue
                }
                if (escaped) {
                    escaped = false
                    i++
                    continue
                }
                if (inString) {
                    if (ch == '\\') escaped = true
                    else if (ch == '"') inString = false
                    i++
                    continue
                }
                if (inChar) {
                    if (ch == '\\') escaped = true
                    else if (ch == '\'') inChar = false
                    i++
                    continue
                }
                if (ch == '/' && next == '/') break
                if (ch == '/' && next == '*') {
                    inBlockComment = true
                    i += 2
                    continue
                }
                when (ch) {
                    '"' -> {
                        inString = true
                        sawCodeToken = true
                    }
                    '\'' -> {
                        inChar = true
                        sawCodeToken = true
                    }
                    '{' -> {
                        openCount++
                        sawCodeToken = true
                    }
                    '}' -> {
                        closeCount++
                        if (!sawCodeToken) leadingClose++
                    }
                    else -> if (!ch.isWhitespace()) sawCodeToken = true
                }
                i++
            }

            val isPreprocessor = trimmed.startsWith("#")
            val isCaseLabel = trimmed.startsWith("case ") || trimmed == "default:" || trimmed.startsWith("default :")
            val isAccessLabel = trimmed == "public:" || trimmed == "private:" || trimmed == "protected:"

            val effectiveIndent = when {
                isPreprocessor -> 0
                isCaseLabel || isAccessLabel -> (indentLevel - 1).coerceAtLeast(0)
                else -> (indentLevel - leadingClose).coerceAtLeast(0)
            }
            formatted.add("    ".repeat(effectiveIndent) + trimmed)
            indentLevel = (indentLevel + openCount - closeCount).coerceAtLeast(0)
        }

        val result = formatted.joinToString("\n")
        if (result != original) {
            val prevLine = editor.cursor.leftLine.coerceIn(0, formatted.lastIndex)
            editor.text.replace(0, 0, lines.lastIndex, lines.last().length, result)
            val targetCol = formatted[prevLine].indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
            runCatching { editor.setSelectionRegion(prevLine, targetCol, prevLine, targetCol) }
            Toast.makeText(context, "Code formatted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Already formatted", Toast.LENGTH_SHORT).show()
        }
    }

    fun setEditorTextSafely(text: String) {
        suppressDraftSync = true
        try {
            editor.setText(text)
        } finally {
            suppressDraftSync = false
            refreshUndoRedo()
            suggestions = emptyList()
            contentVersion++
        }
    }

    fun savePath(path: String): Boolean {
        val content = if (path == openPath) {
            editor.text.toString().also { draftText[path] = it }
        } else {
            draftText[path] ?: savedText[path] ?: return true
        }
        return runCatching {
            File(path).writeText(content)
        }.onSuccess {
            savedText[path] = content
            draftText[path] = content
        }.onFailure { err ->
            ideLog("Failed to save ${File(path).name}: ${err.message}", LogLevel.ERROR)
            Toast.makeText(context, "Could not save ${File(path).name}", Toast.LENGTH_SHORT).show()
        }.isSuccess
    }

    fun saveCurrent(): Boolean {
        val path = openPath ?: return true
        return savePath(path)
    }

    fun saveAllDirty(): Boolean {
        syncActiveDraft()
        return dirtyPaths.toList().all { savePath(it) }
    }

    /** Shows the file in the editor (using any unsaved draft if already open) and makes sure it has a tab. */
    fun openFile(file: File, syncPrevious: Boolean = true) {
        if (syncPrevious && openPath != null && openPath != file.absolutePath) {
            syncActiveDraft()
        }
        val path = file.absolutePath
        val diskText = runCatching { file.readText() }.getOrElse { "" }
        if (path !in savedText) {
            savedText[path] = diskText
        }
        val textToShow = draftText[path] ?: savedText[path] ?: diskText
        draftText[path] = textToShow

        openPath = path
        noFileOpen = false
        setEditorTextSafely(textToShow)
        if (path !in tabs) tabs = tabs + path
        ideLog("Opened ${file.name}")
    }

    fun jumpToLocation(path: String, line: Int, col: Int) {
        if (path != openPath) {
            val file = File(path)
            if (!file.exists()) return
            openFile(file)
        }
        editor.post {
            runCatching {
                editor.setSelectionRegion(line, col, line, col)
            }
        }
    }

    fun performCloseTab(path: String) {
        val index = tabs.indexOf(path)
        if (index < 0) return
        ideLog("Closed ${File(path).name}")
        val wasActive = path == openPath
        savedText.remove(path)
        draftText.remove(path)

        val remaining = tabs - path
        tabs = remaining

        if (wasActive) {
            val next = remaining.getOrNull(index) ?: remaining.getOrNull(index - 1)
            if (next != null) {
                openFile(File(next), syncPrevious = false)
            } else {
                openPath = null
                setEditorTextSafely("")
                noFileOpen = true
            }
        }
    }

    fun performCloseBatchTabs(pathsToClose: List<String>, keepPath: String?) {
        pathsToClose.forEach { path ->
            savedText.remove(path)
            draftText.remove(path)
        }
        if (keepPath != null) {
            if (keepPath != openPath) {
                openFile(File(keepPath), syncPrevious = false)
            }
            tabs = listOf(keepPath)
        } else {
            tabs = emptyList()
            openPath = null
            setEditorTextSafely("")
            noFileOpen = true
        }
    }

    fun requestCloseTab(path: String) {
        syncActiveDraft()
        if (path in dirtyPaths) {
            pendingClose = PendingCloseAction.SingleTab(path)
        } else {
            performCloseTab(path)
        }
    }

    fun requestCloseOtherTabs(keep: String) {
        syncActiveDraft()
        val toClose = tabs.filter { it != keep }
        if (toClose.any { it in dirtyPaths }) {
            pendingClose = PendingCloseAction.BatchTabs(toClose, keepPath = keep)
        } else {
            performCloseBatchTabs(toClose, keepPath = keep)
        }
    }

    fun requestCloseAllTabs() {
        syncActiveDraft()
        if (tabs.any { it in dirtyPaths }) {
            pendingClose = PendingCloseAction.BatchTabs(tabs.toList(), keepPath = null)
        } else {
            performCloseBatchTabs(tabs.toList(), keepPath = null)
        }
    }

    fun requestCloseProject() {
        syncActiveDraft()
        if (dirtyPaths.isNotEmpty()) {
            pendingClose = PendingCloseAction.Project
        } else {
            showCloseDialog = true
        }
    }

    // ---- Library actions (fetch, download, import #include, copy to src/, uninstall) ----

    fun refreshLibraryCatalog(forceRemote: Boolean) {
        if (libraryLoading) return
        scope.launch {
            libraryLoading = true
            libraryError = null
            installedLibs = LibraryManager.listInstalled(context)
            if (!forceRemote && libraryCatalog.isEmpty()) {
                val cached = LibraryManager.loadCachedIndex(context)
                if (cached.isNotEmpty()) {
                    libraryCatalog = cached
                    libraryLoading = false
                    return@launch
                }
            }
            LibraryManager.fetchRemoteIndex(context).fold(
                onSuccess = { list ->
                    libraryCatalog = list
                    ideLog("Loaded ${list.size} Arduino libraries")
                },
                onFailure = { err ->
                    libraryError = err.message ?: "Could not load library index"
                    ideLog("Library index failed: $libraryError", LogLevel.ERROR)
                }
            )
            libraryLoading = false
        }
    }

    fun downloadLibrary(lib: ArduinoLibrary) {
        if (lib.name in downloadingLibs) return
        scope.launch {
            downloadingLibs = downloadingLibs + lib.name
            ideLog("Downloading ${lib.name} (${lib.version})...")
            LibraryManager.install(context, lib, libraryCatalog).fold(
                onSuccess = {
                    installedLibs = LibraryManager.listInstalled(context)
                    libraryKeywordsVersion++
                    ideLog("Installed ${lib.name} ${lib.version}")
                    Toast.makeText(context, "Installed ${lib.name}", Toast.LENGTH_SHORT).show()
                },
                onFailure = { err ->
                    ideLog("Failed to install ${lib.name}: ${err.message}", LogLevel.ERROR)
                    Toast.makeText(
                        context,
                        err.message ?: "Failed to install ${lib.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
            downloadingLibs = downloadingLibs - lib.name
        }
    }

    fun insertInclude(header: String, libName: String) {
        if (openPath == null) {
            Toast.makeText(context, "Open a sketch file first", Toast.LENGTH_SHORT).show()
            return
        }
        val text = editor.text.toString()
        val alreadyIncluded = Regex("""^\s*#\s*include\s*[<"]${Regex.escape(header)}[>"]""", RegexOption.MULTILINE)
            .containsMatchIn(text)
        if (alreadyIncluded) {
            Toast.makeText(context, "$header is already included", Toast.LENGTH_SHORT).show()
            return
        }
        editor.text.insert(0, 0, "#include <$header>\n")
        ideLog("Imported $libName (#include <$header>)")
        Toast.makeText(context, "Added #include <$header>", Toast.LENGTH_SHORT).show()
        scope.launch { drawerState.close() }
    }

    fun importLibrary(installed: InstalledLibrary) {
        if (openPath == null) {
            Toast.makeText(context, "Open a sketch file first", Toast.LENGTH_SHORT).show()
            return
        }
        when {
            installed.headers.isEmpty() ->
                Toast.makeText(context, "No .h header found in ${installed.name}", Toast.LENGTH_SHORT).show()
            installed.headers.size == 1 ->
                insertInclude(installed.headers.first(), installed.name)
            else ->
                headerPickerLib = installed
        }
    }

    fun bundleLibraryIntoProjectSrc(installed: InstalledLibrary) {
        val dir = projectPath?.let { File(it) } ?: return
        scope.launch {
            LibraryManager.copyToProjectSrc(context, dir, installed.name).fold(
                onSuccess = { copiedHeaders ->
                    treeVersion++
                    ideLog("Bundled ${installed.name} into src/")
                    val first = copiedHeaders.firstOrNull()
                    if (first != null && openPath != null) {
                        val text = editor.text.toString()
                        if (!text.contains(first)) {
                            editor.text.insert(0, 0, "#include \"$first\"\n")
                        }
                    }
                    Toast.makeText(context, "Copied ${installed.name} to src/", Toast.LENGTH_SHORT).show()
                    drawerState.close()
                },
                onFailure = { err ->
                    Toast.makeText(context, err.message ?: "Copy failed", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    fun uninstallLibrary(libName: String) {
        scope.launch {
            if (LibraryManager.uninstall(context, libName)) {
                installedLibs = LibraryManager.listInstalled(context)
                libraryKeywordsVersion++
                ideLog("Removed library $libName")
                Toast.makeText(context, "Removed $libName", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Install any missing libraries by official index name (e.g. "LiquidCrystal I2C"). */
    suspend fun ensureLibrariesInstalled(names: List<String>) {
        if (names.isEmpty()) return
        val installed = LibraryManager.listInstalled(context)
        val missing = names.filter { it !in installed }
        if (missing.isEmpty()) {
            ideLog("Template libraries already installed: ${names.joinToString()}")
            return
        }
        ideLog("Installing template libraries: ${missing.joinToString()}")
        Toast.makeText(context, "Installing ${missing.joinToString()}…", Toast.LENGTH_SHORT).show()

        var catalog = libraryCatalog
        if (catalog.isEmpty()) {
            LibraryManager.fetchRemoteIndex(context).fold(
                onSuccess = {
                    catalog = it
                    libraryCatalog = it
                },
                onFailure = { err ->
                    ideLog("Could not load library index: ${err.message}", LogLevel.ERROR)
                    Toast.makeText(
                        context,
                        "Could not load library index to install ${missing.joinToString()}",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
            )
        }

        for (name in missing) {
            val lib = catalog.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: catalog.firstOrNull { it.name.contains(name, ignoreCase = true) }
            if (lib == null) {
                ideLog("Library not found in index: $name", LogLevel.WARN)
                Toast.makeText(context, "Library not found: $name", Toast.LENGTH_SHORT).show()
                continue
            }
            LibraryManager.install(context, lib, catalog).fold(
                onSuccess = {
                    installedLibs = LibraryManager.listInstalled(context)
                    libraryKeywordsVersion++
                    ideLog("Installed ${lib.name} ${lib.version}")
                    Toast.makeText(context, "Installed ${lib.name}", Toast.LENGTH_SHORT).show()
                },
                onFailure = { err ->
                    ideLog("Failed to install ${lib.name}: ${err.message}", LogLevel.ERROR)
                    Toast.makeText(
                        context,
                        err.message ?: "Failed to install ${lib.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    }

    // ---- Board manager actions ----

    fun selectBoard(target: SelectedBoard) {
        selectedBoard = target
        board = target.family
        selectedBoardOptions = EditorPrefs.boardOptions(context, target.fqbn)
        EditorPrefs.setSelectedBoard(context, target)
        ideLog("Board: ${target.name} (${target.fqbn})")
    }

    fun clearSelectedBoard() {
        val empty = SelectedBoard(
            packageName = "",
            architecture = "",
            boardId = "",
            name = "No board selected",
            fqbn = ""
        )
        selectedBoard = empty
        runCatching { board = empty.family }
        EditorPrefs.setSelectedBoard(context, empty)
        ideLog("Board selection cleared")
    }

    fun syncSelectedBoardWithInstalled(installed: Map<String, InstalledPlatform>) {
        val stillValid = selectedBoard.fqbn.isNotEmpty() && installed.values.any { inst ->
            inst.packageName == selectedBoard.packageName &&
                inst.architecture == selectedBoard.architecture &&
                inst.boards.any { it.fqbn == selectedBoard.fqbn }
        }
        if (stillValid) return
        val fallback = installed.values
            .flatMap { inst ->
                inst.boards.map { b ->
                    SelectedBoard(
                        packageName = inst.packageName,
                        architecture = inst.architecture,
                        boardId = b.id,
                        name = b.name,
                        fqbn = b.fqbn
                    )
                }
            }
            .firstOrNull()
        when {
            fallback != null -> selectBoard(fallback)
            selectedBoard.fqbn.isNotEmpty() -> clearSelectedBoard()
        }
    }

    fun refreshBoardCatalog(forceRemote: Boolean) {
        if (boardLoading) return
        scope.launch {
            boardLoading = true
            boardError = null
            installedPlatforms = BoardManager.listInstalled(context)
            if (!forceRemote && boardCatalog.isEmpty()) {
                val cached = BoardManager.loadCachedIndex(context)
                if (cached.isNotEmpty()) {
                    boardCatalog = cached
                    boardLoading = false
                    return@launch
                }
            }
            BoardManager.fetchRemoteIndex(context).fold(
                onSuccess = { list ->
                    boardCatalog = list
                    ideLog("Loaded ${list.size} board cores")
                },
                onFailure = { err ->
                    boardError = err.message ?: "Could not load board index"
                    ideLog("Board index failed: $boardError", LogLevel.ERROR)
                }
            )
            boardLoading = false
        }
    }

    fun downloadPlatform(platform: BoardPlatform) {
        if (platform.id in downloadingPlatforms) return
        scope.launch {
            downloadingPlatforms = downloadingPlatforms + platform.id
            ideLog("Downloading ${platform.name} (${platform.version})...")
            BoardManager.install(context, platform).fold(
                onSuccess = { inst ->
                    installedPlatforms = BoardManager.listInstalled(context)
                    syncSelectedBoardWithInstalled(installedPlatforms)
                    libraryKeywordsVersion++
                    ideLog("Installed ${platform.name} (${inst.boards.size} boards)")
                    Toast.makeText(context, "Installed ${platform.name}", Toast.LENGTH_SHORT).show()
                },
                onFailure = { err ->
                    ideLog("Failed to install ${platform.name}: ${err.message}", LogLevel.ERROR)
                    Toast.makeText(
                        context,
                        err.message ?: "Failed to install ${platform.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
            downloadingPlatforms = downloadingPlatforms - platform.id
        }
    }

    fun uninstallPlatform(platform: InstalledPlatform) {
        scope.launch {
            if (BoardManager.uninstall(context, platform.packageName, platform.architecture)) {
                val remaining = BoardManager.listInstalled(context)
                installedPlatforms = remaining
                libraryKeywordsVersion++
                syncSelectedBoardWithInstalled(remaining)
                ideLog("Removed core ${platform.name}")
                Toast.makeText(context, "Removed ${platform.name}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Initial content: existing project's main file, or the clean template
    LaunchedEffect(Unit) {
        installedPlatforms = BoardManager.listInstalled(context)
        syncSelectedBoardWithInstalled(installedPlatforms)
        installedLibs = LibraryManager.listInstalled(context)
        val restored = openPath?.let { File(it) }?.takeIf { it.exists() }
        when {
            restored != null -> {
                tabs = tabs.filter { File(it).exists() }
                openFile(restored, syncPrevious = false)
            }
            initialProject != null && !noFileOpen -> {
                val files = ProjectManager.listFiles(File(initialProject))
                (files.firstOrNull { it.extension == "ino" } ?: files.firstOrNull())
                    ?.let { openFile(it, syncPrevious = false) }
            }
            templateAsset != null && projectPath == null ->
                setEditorTextSafely(ProjectManager.readAsset(context, templateAsset))
        }
    }

    // Load catalogs automatically the first time Libraries or Boards tab is opened
    LaunchedEffect(drawerTabIndex) {
        if (drawerTabIndex == DrawerTab.LIBRARIES.ordinal && libraryCatalog.isEmpty() && !libraryLoading) {
            refreshLibraryCatalog(forceRemote = false)
        }
        if (drawerTabIndex == DrawerTab.BOARDS.ordinal && boardCatalog.isEmpty() && !boardLoading) {
            refreshBoardCatalog(forceRemote = false)
        }
    }

    // Load menus strictly from the downloaded core's offline boards.txt
    LaunchedEffect(selectedBoard.fqbn, installedPlatforms) {
        selectedBoardOptions = EditorPrefs.boardOptions(context, selectedBoard.fqbn)
        boardMenus = if (BoardManager.isBoardInstalled(installedPlatforms, selectedBoard)) {
            withContext(Dispatchers.IO) {
                BoardManager.loadBoardMenus(context, selectedBoard)
            }
        } else {
            emptyList()
        }
    }

    // Recompute offline board specs (MCU, F_CPU, flash limits, extra flags) whenever options change
    LaunchedEffect(selectedBoard.fqbn, selectedBoardOptions, installedPlatforms) {
        boardInfo = if (BoardManager.isBoardInstalled(installedPlatforms, selectedBoard)) {
            withContext(Dispatchers.IO) {
                BoardManager.loadOfflineBoardInfo(context, selectedBoard, selectedBoardOptions)
            }
        } else {
            null
        }
    }

    // Keep drawerTabIndex synced when swiping the drawer pager, and clear multi-select off Files
    LaunchedEffect(drawerPagerState) {
        snapshotFlow { drawerPagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                if (page != DrawerTab.FILES.ordinal) selection = emptySet()
                if (drawerTabIndex != page) drawerTabIndex = page
            }
    }

    // Theme + C++ language. The slow asset parsing runs on a background thread, and the
    // editor is only revealed once both are applied.
    LaunchedEffect(dark, themeChoice) {
        val prepared = withContext(Dispatchers.IO) {
            runCatching { TextMateSetup.prepare(context, themeChoice, dark) }
        }
        prepared.exceptionOrNull()?.let { err ->
            ideLog("TextMate init failed: ${err.message}", LogLevel.ERROR)
        }

        val themes = TextMateSetup.available
        availableThemes = themes
        if (themes.isEmpty()) {
            ideLog("No TextMate themes found in assets/textmate/", LogLevel.WARN)
        }

        val resolved = prepared.getOrNull() ?: TextMateSetup.resolve(themeChoice, dark)
        runCatching {
            if (editor.colorScheme !is TextMateColorScheme) {
                editor.colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
            }
            TextMateSetup.applyTheme(resolved)

            editor.colorScheme.apply {
                setColor(
                    EditorColorScheme.HIGHLIGHTED_DELIMITERS_FOREGROUND,
                    0xFF808080.toInt()
                )
                setColor(
                    EditorColorScheme.HIGHLIGHTED_DELIMITERS_BACKGROUND,
                    0x33808080
                )
                setColor(
                    EditorColorScheme.HIGHLIGHTED_DELIMITERS_UNDERLINE,
                    0xFF808080.toInt()
                )
            }
        }.onFailure { err ->
            ideLog("Theme apply failed ($resolved): ${err.message}", LogLevel.ERROR)
        }

        if (editor.editorLanguage !is TextMateLanguage) {
            runCatching {
                val lang = TextMateLanguage.create("source.cpp", true).apply {
                    setCompleterKeywords(ArduinoKeywords.forBoard(board))
                }
                editor.setEditorLanguage(lang)
            }.onFailure { err ->
                ideLog("Grammar load failed (source.cpp): ${err.message}", LogLevel.ERROR)
            }
        }

        languageReady = true
        themeReady = true
    }

    // Live project indexing + static diagnostics whenever files or active code change
    LaunchedEffect(projectPath, openPath, treeVersion, contentVersion, libraryKeywordsVersion) {
        delay(250)
        val dir = projectPath?.let { File(it) }
        val activePath = openPath
        val activeText = editor.text.toString()
        val draftsSnapshot = draftText.toMap()
        val (newIndex, newDiags) = withContext(Dispatchers.IO) {
            val idx = ProjectSymbolIndexer.indexProject(
                context = context,
                projectDir = dir,
                activePath = activePath,
                activeText = activeText,
                drafts = draftsSnapshot
            )
            val diags = ProjectSymbolIndexer.analyzeDiagnostics(
                projectDir = dir,
                openPath = activePath,
                openText = activeText,
                index = idx
            )
            idx to diags
        }
        projectIndex = newIndex
        diagnostics = newDiags
        refreshIntelliSense()
    }

    // Merge built-in, library, core, and project (.h/.cpp/.ino) symbols into autocomplete keywords
    LaunchedEffect(board, libraryKeywordsVersion, languageReady, projectIndex) {
        if (!languageReady) return@LaunchedEffect
        val extra = withContext(Dispatchers.IO) {
            LibraryManager.installedKeywords(context) + BoardManager.installedCoreKeywords(context)
        }
        val merged = (ArduinoKeywords.forBoard(board).asList() + extra + projectIndex.allKeywords)
            .distinct()
            .toTypedArray()
        (editor.editorLanguage as? TextMateLanguage)?.setCompleterKeywords(merged)
    }

    // Remember the open project so the app can reopen it on the next launch
    LaunchedEffect(projectPath) {
        projectPath?.let {
            ProjectManager.setLastProject(context, File(it))
            ideLog("Project ready: ${File(it).name}")
        }
    }

    val currentOpenPath by rememberUpdatedState(openPath)

    // Track live text edits for dirty state, IntelliSense, & sync active draft on pause
    DisposableEffect(Unit) {
        val subscription = editor.subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
            refreshUndoRedo()
            if (!suppressDraftSync) {
                currentOpenPath?.let { path ->
                    draftText[path] = editor.text.toString()
                }
                contentVersion++
                editor.post { refreshIntelliSense() }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                syncActiveDraft()
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose {
            runCatching { subscription.unsubscribe() }
            activity.lifecycle.removeObserver(observer)
            editor.release()
        }
    }

    val focusManager = LocalFocusManager.current

    fun closeFind() {
        focusManager.clearFocus()
        findOpen = false
    }

    BackHandler {
        when {
            suggestions.isNotEmpty() -> suggestions = emptyList()
            selection.isNotEmpty() -> selection = emptySet()
            drawerState.isOpen -> scope.launch { drawerState.close() }
            findOpen -> closeFind()
            panelOpen -> panelOpen = false
            projectPath != null -> requestCloseProject()
            else -> onClose()
        }
    }

    val projectDir = projectPath?.let { File(it) }

    fun compile() {
        val dir = projectDir
        if (dir == null) {
            Toast.makeText(context, "Create the project first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!BoardManager.isBoardInstalled(installedPlatforms, selectedBoard)) {
            Toast.makeText(context, "Download and select a board core first", Toast.LENGTH_SHORT).show()
            drawerTabIndex = DrawerTab.BOARDS.ordinal
            scope.launch {
                drawerPagerState.scrollToPage(DrawerTab.BOARDS.ordinal)
                drawerState.open()
            }
            return
        }
        if (!saveAllDirty()) return
        panelOpen = true
        panelTabIndex = PanelTab.COMPILE.ordinal
        scope.launch {
            compiling = true
            buildOk = false
            val effFqbn = BoardManager.buildEffectiveFqbn(selectedBoard.fqbn, boardMenus, selectedBoardOptions)
            buildLog = "Compiling ${dir.name} for ${selectedBoard.name} ($effFqbn)..."
            ideLog("Build started: ${dir.name} ($effFqbn)")
            val result = ArduinoCompiler.compile(context, dir, selectedBoard, selectedBoardOptions)
            buildOk = result.success
            buildLog = result.log
            compiling = false
            ideLog(
                if (result.success) "Build succeeded" else "Build failed",
                if (result.success) LogLevel.INFO else LogLevel.ERROR
            )
        }
    }

    /** Selects a search hit in the editor. */
    fun selectHit(hit: SearchHit) {
        runCatching {
            editor.setSelectionRegion(hit.line, hit.col, hit.line, hit.col + hit.length)
        }
    }

    /** Shows a search hit, opening its file first when it isn't the active one. */
    fun jumpTo(hit: SearchHit) {
        if (hit.path != openPath) {
            val file = File(hit.path)
            if (!file.exists()) return
            openFile(file)
        }
        editor.post { selectHit(hit) }
    }

    /** NEXT / PREVIOUS. In a file it works from the cursor; in the project it walks the result list. */
    fun goToMatch(forward: Boolean) {
        if (query.isEmpty()) return
        if (projectScope) {
            val hits = searchHits
            if (hits.isEmpty()) return
            val index = when {
                currentHit < 0 -> if (forward) 0 else hits.lastIndex
                forward -> (currentHit + 1) % hits.size
                else -> (currentHit - 1 + hits.size) % hits.size
            }
            currentHit = index
            jumpTo(hits[index])
        } else {
            val path = openPath ?: return
            val hits = findMatches(path, editor.text.toString(), query, matchCase, wholeWord, MAX_HITS)
            searchHits = hits
            if (hits.isEmpty()) {
                currentHit = -1
                return
            }
            val cursor = editor.cursor
            val index = if (forward) {
                pickHitIndex(hits, cursor.rightLine, cursor.rightColumn, forward = true)
            } else {
                pickHitIndex(hits, cursor.leftLine, cursor.leftColumn, forward = false)
            }
            currentHit = index
            selectHit(hits[index])
        }
    }

    fun startFind(inProject: Boolean) {
        projectScope = inProject
        findOpen = true
    }

    /** Deletes files/folders and keeps tabs, drafts, the editor and the tree consistent. */
    fun deleteEntries(dir: File, targets: List<File>) {
        val roots = targets.filter { t ->
            targets.none { o -> o != t && t.absolutePath.startsWith(o.absolutePath + File.separator) }
        }
        if (roots.isEmpty()) return

        val previousOpen = openPath
        val deletingOpen = previousOpen != null && roots.any {
            previousOpen == it.absolutePath ||
                previousOpen.startsWith(it.absolutePath + File.separator)
        }
        if (deletingOpen) openPath = null

        scope.launch {
            val results = withContext(Dispatchers.IO) {
                roots.map { it to ProjectManager.deleteEntry(dir, it) }
            }
            val failed = results.filter { it.second.isFailure }
            val deleted = results.size - failed.size

            val existingTabs = tabs.filter { File(it).exists() }
            val removedPaths = tabs - existingTabs.toSet()
            removedPaths.forEach { p ->
                savedText.remove(p)
                draftText.remove(p)
            }
            tabs = existingTabs

            if (deletingOpen) {
                if (previousOpen != null && File(previousOpen).exists()) {
                    openPath = previousOpen
                } else {
                    val fallback = tabs.firstOrNull()?.let { File(it) }
                        ?: File(dir, "${dir.name}.ino").takeIf { it.exists() }
                        ?: ProjectManager.listFiles(dir).firstOrNull()
                    if (fallback != null) {
                        openFile(fallback, syncPrevious = false)
                    } else {
                        setEditorTextSafely("")
                        noFileOpen = true
                    }
                }
            }

            if (deleted > 0) {
                val names = results.filter { it.second.isSuccess }.joinToString(", ") { it.first.name }
                ideLog("Deleted $names")
            }
            failed.firstOrNull()?.second?.exceptionOrNull()?.let {
                ideLog(it.message ?: "Could not delete", LogLevel.ERROR)
                Toast.makeText(context, it.message ?: "Could not delete", Toast.LENGTH_SHORT).show()
            }
            selection = emptySet()
            treeVersion++
        }
    }

    // Leaving the drawer ends multi-select
    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue == DrawerValue.Closed) selection = emptySet()
    }

    // Runs the search whenever the query or its options change (file scope also follows the open file)
    LaunchedEffect(query, projectScope, matchCase, wholeWord, if (projectScope) null else openPath) {
        if (query.isEmpty()) {
            searchHits = emptyList()
            currentHit = -1
            searching = false
        } else {
            delay(200)
            searching = true
            try {
                val dir = projectPath?.let { File(it) }
                val activePath = openPath
                val activeText = editor.text.toString()
                val draftsSnapshot = draftText.toMap()
                val hits = withContext(Dispatchers.IO) {
                    when {
                        projectScope && dir != null ->
                            searchWholeProject(dir, activePath, activeText, draftsSnapshot, query, matchCase, wholeWord)
                        activePath != null ->
                            findMatches(activePath, activeText, query, matchCase, wholeWord, MAX_HITS)
                        else -> emptyList()
                    }
                }
                searchHits = hits
                currentHit = -1
                if (hits.isNotEmpty() && findOpen) {
                    panelOpen = true
                    panelTabIndex = PanelTab.SEARCH.ordinal
                    if (!projectScope) {
                        val cursor = editor.cursor
                        val index = pickHitIndex(hits, cursor.leftLine, cursor.leftColumn, forward = true)
                        currentHit = index
                        selectHit(hits[index])
                    }
                }
            } finally {
                searching = false
            }
        }
    }

    // Rebuilt when the drawer opens, after create/delete, and when folders collapse
    val tree = remember(projectPath, drawerState.targetValue, treeVersion, collapsed) {
        projectDir?.let { ProjectManager.listTree(it, collapsed) }.orEmpty()
    }
    val fileCount = remember(projectPath, drawerState.targetValue, treeVersion) {
        projectDir?.walkTopDown()?.count { it.isFile } ?: 0
    }

    val selecting = selection.isNotEmpty()
    val selectablePaths = remember(tree, projectPath) {
        tree.map { it.file }
            .filter { f -> projectDir == null || !ProjectManager.isMainSketch(projectDir, f) }
            .map { it.absolutePath }
    }
    val activeDrawerTab = DrawerTab.entries[drawerPagerState.currentPage]

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet {
                Column(modifier = Modifier.fillMaxSize()) {
                    HorizontalPager(
                        state = drawerPagerState,
                        key = { DrawerTab.entries[it].name },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) { page ->
                        when (DrawerTab.entries[page]) {
                            DrawerTab.FILES -> Column(modifier = Modifier.fillMaxSize()) {
                                if (selecting) {
                                    SelectionHeader(
                                        count = selection.size,
                                        onCancel = { selection = emptySet() },
                                        onSelectAll = {
                                            selection = if (selection.containsAll(selectablePaths)) emptySet()
                                            else selectablePaths.toSet()
                                        },
                                        onDelete = {
                                            deleteMany = selection.map { File(it) }.filter { it.exists() }
                                        }
                                    )
                                } else {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 24.dp, end = 8.dp, top = 24.dp, bottom = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(RoundedCornerShape(14.dp))
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Outlined.FolderOpen,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Spacer(Modifier.width(14.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = projectDir?.name ?: "No project",
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "$fileCount ${if (fileCount == 1) "file" else "files"}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton(
                                            enabled = projectDir != null,
                                            onClick = { projectDir?.let { createTarget = CreateRequest(it, false) } }
                                        ) {
                                            Icon(Icons.Outlined.NoteAdd, contentDescription = "New file")
                                        }
                                        IconButton(
                                            enabled = projectDir != null,
                                            onClick = { projectDir?.let { createTarget = CreateRequest(it, true) } }
                                        ) {
                                            Icon(Icons.Outlined.CreateNewFolder, contentDescription = "New folder")
                                        }
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 16.dp)
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(MaterialTheme.colorScheme.outlineVariant)
                                )

                                if (tree.isEmpty()) {
                                    Text(
                                        text = "No files yet",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(24.dp)
                                    )
                                }

                                LazyColumn(
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    items(tree, key = { it.file.absolutePath }) { entry ->
                                        val file = entry.file
                                        val path = file.absolutePath
                                        val isMain = projectDir?.let { ProjectManager.isMainSketch(it, file) } == true
                                        TreeRow(
                                            entry = entry,
                                            isMain = isMain,
                                            selected = path == openPath,
                                            expanded = path !in collapsed,
                                            selecting = selecting,
                                            checked = path in selection,
                                            onClick = {
                                                when {
                                                    selecting -> {
                                                        if (!isMain) {
                                                            selection =
                                                                if (path in selection) selection - path else selection + path
                                                        }
                                                    }
                                                    file.isDirectory -> {
                                                        collapsed = if (path in collapsed) collapsed - path else collapsed + path
                                                    }
                                                    else -> {
                                                        openFile(file)
                                                        scope.launch { drawerState.close() }
                                                    }
                                                }
                                            },
                                            onLongClick = {
                                                if (isMain) {
                                                    Toast.makeText(
                                                        context,
                                                        "The main sketch can't be deleted",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                } else {
                                                    selection = selection + path
                                                }
                                            },
                                            onToggleExpand = {
                                                collapsed = if (path in collapsed) collapsed - path else collapsed + path
                                            },
                                            onNewFile = { createTarget = CreateRequest(file, false) },
                                            onNewFolder = { createTarget = CreateRequest(file, true) },
                                            onDelete = { deleteTarget = file }
                                        )
                                    }
                                }

                                if (projectDir != null && !selecting) {
                                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch { drawerState.close() }
                                            requestCloseProject()
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 12.dp)
                                    ) {
                                        Icon(
                                            Icons.Outlined.Close,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("Close project")
                                    }
                                }
                            }

                            DrawerTab.LIBRARIES -> DrawerLibrariesTab(
                                board = board,
                                catalog = libraryCatalog,
                                installed = installedLibs,
                                query = libraryQuery,
                                onQueryChange = { libraryQuery = it },
                                filter = libraryFilter,
                                onFilterChange = { libraryFilter = it },
                                loading = libraryLoading,
                                error = libraryError,
                                downloading = downloadingLibs,
                                onRefresh = { refreshLibraryCatalog(forceRemote = true) },
                                onDownload = { downloadLibrary(it) },
                                onImport = { importLibrary(it) },
                                onUninstall = { uninstallLibrary(it) },
                                onDetails = { selectedLibrary = it }
                            )

                            DrawerTab.BOARDS -> DrawerBoardsTab(
                                selectedBoard = selectedBoard,
                                boardInfo = boardInfo,
                                boardMenus = boardMenus,
                                boardOptions = selectedBoardOptions,
                                catalog = boardCatalog,
                                installed = installedPlatforms,
                                query = boardQuery,
                                onQueryChange = { boardQuery = it },
                                filter = boardFilter,
                                onFilterChange = { boardFilter = it },
                                loading = boardLoading,
                                error = boardError,
                                downloading = downloadingPlatforms,
                                onRefresh = { refreshBoardCatalog(forceRemote = true) },
                                onManageUrls = { showBoardUrlsDialog = true },
                                onDownload = { downloadPlatform(it) },
                                onPickBoard = { boardPickerPlatform = it },
                                onSwitchBoard = { showBoardSwitcherDialog = true },
                                onOpenBoardOptions = { showBoardOptionsDialog = true },
                                onUninstall = { uninstallPlatform(it) }
                            )
                        }
                    }

                    HorizontalDivider()

                    DrawerBottomTabBar(
                        selected = activeDrawerTab,
                        onSelect = { tab ->
                            if (tab != DrawerTab.FILES) selection = emptySet()
                            drawerTabIndex = tab.ordinal
                            scope.launch {
                                drawerPagerState.animateScrollToPage(
                                    page = tab.ordinal,
                                    animationSpec = tween(durationMillis = 260)
                                )
                            }
                        }
                    )
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        val activeName = openPath?.let { File(it).name }
                        val activeDirty = openPath != null && openPath in dirtyPaths
                        Text(
                            text = when {
                                activeName != null && activeDirty -> "$activeName *"
                                activeName != null -> activeName
                                else -> projectDir?.name ?: "New project"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Outlined.Menu, contentDescription = "Project files")
                        }
                    },
                    actions = {
                        IconButton(onClick = { editor.undo(); refreshUndoRedo() }, enabled = canUndo) {
                            Icon(Icons.Outlined.Undo, contentDescription = "Undo")
                        }
                        IconButton(onClick = { editor.redo(); refreshUndoRedo() }, enabled = canRedo) {
                            Icon(Icons.Outlined.Redo, contentDescription = "Redo")
                        }

                        Box {
                            IconButton(onClick = { themeMenuOpen = true }) {
                                Icon(Icons.Outlined.Palette, contentDescription = "Theme")
                            }
                            DropdownMenu(
                                expanded = themeMenuOpen,
                                onDismissRequest = { themeMenuOpen = false }
                            ) {
                                availableThemes.forEach { theme ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (theme.id == "github") {
                                                    if (dark) "GitHub (Dark)" else "GitHub (Light)"
                                                } else {
                                                    theme.label
                                                }
                                            )
                                        },
                                        leadingIcon = {
                                            if (themeChoice == theme.id) {
                                                Icon(Icons.Outlined.Check, contentDescription = null)
                                            } else {
                                                Spacer(Modifier.size(24.dp))
                                            }
                                        },
                                        onClick = {
                                            themeChoice = theme.id
                                            EditorPrefs.setTheme(context, theme.id)
                                            themeMenuOpen = false
                                            ideLog("Editor theme: ${theme.label}")
                                        }
                                    )
                                }
                            }
                        }

                        IconButton(onClick = { compile() }, enabled = !compiling) {
                            Icon(
                                Icons.Outlined.Build,
                                contentDescription = "Compile",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Save") },
                                    enabled = openPath != null,
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Save, contentDescription = null)
                                    },
                                    onClick = {
                                        menuOpen = false
                                        if (saveCurrent()) {
                                            openPath?.let { ideLog("Saved ${File(it).name}") }
                                            Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                                if (dirtyPaths.size > 1) {
                                    DropdownMenuItem(
                                        text = { Text("Save all (${dirtyPaths.size})") },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.DoneAll, contentDescription = null)
                                        },
                                        onClick = {
                                            menuOpen = false
                                            if (saveAllDirty()) {
                                                ideLog("Saved all modified files")
                                                Toast.makeText(context, "Saved all", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    )
                                }

                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Symbols & classes (${projectIndex.symbols.size})") },
                                    enabled = projectDir != null,
                                    leadingIcon = {
                                        Icon(Icons.Outlined.DataObject, contentDescription = null)
                                    },
                                    onClick = {
                                        menuOpen = false
                                        showSymbolsDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Find in file") },
                                    enabled = openPath != null,
                                    leadingIcon = {
                                        Icon(Icons.Outlined.FindInPage, contentDescription = null)
                                    },
                                    onClick = {
                                        menuOpen = false
                                        startFind(inProject = false)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Find in project") },
                                    enabled = projectDir != null,
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Search, contentDescription = null)
                                    },
                                    onClick = {
                                        menuOpen = false
                                        startFind(inProject = true)
                                    }
                                )
                                HorizontalDivider()
                                val hasDownloadedBoard = remember(installedPlatforms, selectedBoard) {
                                    BoardManager.isBoardInstalled(installedPlatforms, selectedBoard)
                                }
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (hasDownloadedBoard) "Board & options..."
                                            else "Board & options (download core first)"
                                        )
                                    },
                                    enabled = hasDownloadedBoard,
                                    leadingIcon = {
                                        Icon(Icons.Outlined.DeveloperBoard, contentDescription = null)
                                    },
                                    onClick = {
                                        menuOpen = false
                                        showBoardOptionsDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Go to line...") },
                                    enabled = !noFileOpen,
                                    leadingIcon = {
                                        Icon(Icons.Outlined.FormatListNumbered, contentDescription = null)
                                    },
                                    onClick = {
                                        menuOpen = false
                                        showGoToLineDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Format code") },
                                    enabled = !noFileOpen,
                                    leadingIcon = {
                                        Icon(Icons.Outlined.AutoFixHigh, contentDescription = null)
                                    },
                                    onClick = {
                                        menuOpen = false
                                        formatCode()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Toggle comment (//)") },
                                    enabled = !noFileOpen,
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Code, contentDescription = null)
                                    },
                                    onClick = {
                                        menuOpen = false
                                        toggleLineComment()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (quickBarOpen) "Hide quick keys bar" else "Show quick keys bar") },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Tune, contentDescription = null)
                                    },
                                    onClick = {
                                        menuOpen = false
                                        setQuickBarVisible(!quickBarOpen)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (panelOpen) "Hide logs panel" else "Show logs panel") },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Terminal, contentDescription = null)
                                    },
                                    onClick = {
                                        menuOpen = false
                                        panelOpen = !panelOpen
                                    }
                                )
                            }
                        }
                    }
                )
            }
        ) { padding ->
            val hitCountLabel = if (searchHits.size >= MAX_HITS) "$MAX_HITS+" else "${searchHits.size}"
            val findStatus = when {
                query.isEmpty() -> ""
                searching -> "..."
                searchHits.isEmpty() -> "No results"
                currentHit >= 0 -> "${currentHit + 1}/$hitCountLabel"
                else -> hitCountLabel
            }
            val panelTab = PanelTab.entries[panelTabIndex.coerceIn(0, PanelTab.entries.lastIndex)]
            val problemsLabel = if (diagnostics.isEmpty()) PanelTab.PROBLEMS.label
            else "${PanelTab.PROBLEMS.label} (${diagnostics.size})"

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
            ) {
                if (tabs.isNotEmpty()) {
                    EditorTabBar(
                        tabs = tabs,
                        activePath = openPath,
                        dirtyPaths = dirtyPaths,
                        onSelect = { path ->
                            if (path != openPath) {
                                openFile(File(path))
                            }
                        },
                        onCloseThis = { path -> requestCloseTab(path) },
                        onCloseOthers = { path -> requestCloseOtherTabs(path) },
                        onCloseAll = { requestCloseAllTabs() }
                    )
                }

                if (findOpen) {
                    FindBar(
                        query = query,
                        onQueryChange = { query = it },
                        projectScope = projectScope,
                        onScopeChange = { projectScope = it },
                        matchCase = matchCase,
                        onMatchCase = { matchCase = it },
                        wholeWord = wholeWord,
                        onWholeWord = { wholeWord = it },
                        status = findStatus,
                        onNext = { goToMatch(forward = true) },
                        onPrevious = { goToMatch(forward = false) },
                        onClose = { closeFind() }
                    )
                }

                if (suggestions.isNotEmpty() && !noFileOpen) {
                    IntelliSenseBar(
                        suggestions = suggestions,
                        onPick = { applySuggestion(it) },
                        onDismiss = { suggestions = emptyList() }
                    )
                }

                if (diagnostics.isNotEmpty() && !noFileOpen) {
                    DiagnosticsBanner(
                        diagnostics = diagnostics,
                        onJump = { diag ->
                            jumpToLocation(diag.path, diag.line, diag.col)
                        },
                        onOpenProblems = {
                            panelOpen = true
                            panelTabIndex = PanelTab.PROBLEMS.ordinal
                        }
                    )
                }

                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    val maxPanel = maxOf(120.dp, maxHeight * 0.6f)

                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            AndroidView(
                                factory = { editor },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .alpha(if (themeReady) 1f else 0f)
                            )

                            if (noFileOpen) {
                                EmptyEditorState(
                                    onOpenFiles = {
                                        drawerTabIndex = DrawerTab.FILES.ordinal
                                        scope.launch {
                                            drawerPagerState.scrollToPage(DrawerTab.FILES.ordinal)
                                            drawerState.open()
                                        }
                                    }
                                )
                            }
                        }

                        if (quickBarOpen && !noFileOpen) {
                            EditorQuickBar(
                                textSizeSp = editorTextSizeSp,
                                onTab = { indentTab() },
                                onUnindent = { unindentLines() },
                                onMoveCursor = { delta -> moveCursorHorizontal(delta) },
                                onCursorHome = { moveCursorLineEdge(toEnd = false) },
                                onCursorEnd = { moveCursorLineEdge(toEnd = true) },
                                onInsertSymbol = { token -> insertQuickSymbol(token) },
                                onToggleComment = { toggleLineComment() },
                                onDuplicateLine = { duplicateLine() },
                                onDeleteLine = { deleteLine() },
                                onMoveLine = { up -> moveLine(up) },
                                onSelectLine = { selectCurrentLine() },
                                onFormatCode = { formatCode() },
                                onGoToLine = { showGoToLineDialog = true },
                                onChangeTextSize = { size -> updateTextSize(size) },
                                onHide = { setQuickBarVisible(false) }
                            )
                        }

                        if (panelOpen) {
                            BottomPanel(
                                height = panelHeight.dp.coerceIn(120.dp, maxPanel),
                                selected = panelTab,
                                searchLabel = if (searchHits.isEmpty()) PanelTab.SEARCH.label
                                else "${PanelTab.SEARCH.label} ($hitCountLabel)",
                                problemsLabel = problemsLabel,
                                onSelect = { panelTabIndex = it.ordinal },
                                onResize = { delta ->
                                    panelHeight = (panelHeight + delta).coerceIn(120f, 900f)
                                },
                                onClear = {
                                    when (panelTab) {
                                        PanelTab.COMPILE -> {
                                            if (!compiling) buildLog = null
                                        }
                                        PanelTab.IDE -> ideLogs.clear()
                                        PanelTab.TERMINAL -> terminalLogs.clear()
                                        PanelTab.SEARCH -> query = ""
                                        PanelTab.PROBLEMS -> diagnostics = emptyList()
                                    }
                                },
                                onClose = { panelOpen = false }
                            ) { tab ->
                                val updateLogZoom: (Float) -> Unit = { next ->
                                    val clamped = next.coerceIn(0.6f, 2.5f)
                                    logZoom = clamped
                                    editorPrefs.edit().putFloat("log_zoom_scale", clamped).apply()
                                }
                                when (tab) {
                                    PanelTab.COMPILE -> CompileLogView(
                                        log = buildLog,
                                        compiling = compiling,
                                        success = buildOk,
                                        zoomScale = logZoom,
                                        onZoomChange = updateLogZoom
                                    )
                                    PanelTab.IDE -> LogListView(
                                        lines = ideLogs,
                                        emptyText = "IDE events (opening, saving, building) show up here.",
                                        zoomScale = logZoom,
                                        onZoomChange = updateLogZoom
                                    )
                                    PanelTab.TERMINAL -> LogListView(
                                        lines = terminalLogs,
                                        emptyText = "Terminal output shows up here.",
                                        zoomScale = logZoom,
                                        onZoomChange = updateLogZoom
                                    )
                                    PanelTab.SEARCH -> SearchResultsView(
                                        query = query,
                                        projectScope = projectScope,
                                        hits = searchHits,
                                        currentHit = currentHit,
                                        searching = searching,
                                        projectDir = projectDir,
                                        onHitClick = { index ->
                                            searchHits.getOrNull(index)?.let { hit ->
                                                currentHit = index
                                                jumpTo(hit)
                                            }
                                        },
                                        onFindInFile = { startFind(inProject = false) },
                                        onFindInProject = { startFind(inProject = true) }
                                    )
                                    PanelTab.PROBLEMS -> ProblemsView(
                                        diagnostics = diagnostics,
                                        onDiagnosticClick = { diag ->
                                            jumpToLocation(diag.path, diag.line, diag.col)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showGoToLineDialog) {
        val totalLines = editor.text.toString().count { it == '\n' } + 1
        GoToLineDialog(
            totalLines = totalLines,
            currentLine = editor.cursor.leftLine.coerceIn(0, totalLines - 1),
            onDismiss = { showGoToLineDialog = false },
            onConfirm = { lineNumber ->
                showGoToLineDialog = false
                val target = (lineNumber - 1).coerceIn(0, totalLines - 1)
                editor.post {
                    runCatching { editor.setSelectionRegion(target, 0, target, 0) }
                }
            }
        )
    }

    if (showBoardOptionsDialog) {
        BoardOptionsDialog(
            selectedBoard = selectedBoard,
            boardInfo = boardInfo,
            menus = boardMenus,
            selectedOptions = selectedBoardOptions,
            onSelectOption = { menuId, optId ->
                EditorPrefs.setBoardOption(context, selectedBoard.fqbn, menuId, optId)
                selectedBoardOptions = EditorPrefs.boardOptions(context, selectedBoard.fqbn)
            },
            onResetDefaults = {
                EditorPrefs.clearBoardOptions(context, selectedBoard.fqbn)
                selectedBoardOptions = emptyMap()
            },
            onSwitchBoard = { showBoardSwitcherDialog = true },
            onDismiss = { showBoardOptionsDialog = false }
        )
    }

    if (showBoardSwitcherDialog) {
        val allBoards = remember(installedPlatforms) {
            BoardManager.selectableBoards(installedPlatforms)
        }
        QuickBoardSwitcherDialog(
            selectedBoard = selectedBoard,
            boards = allBoards,
            onPick = { picked -> selectBoard(picked) },
            onDismiss = { showBoardSwitcherDialog = false }
        )
    }

    if (showSymbolsDialog) {
        SymbolsDialog(
            index = projectIndex,
            projectDir = projectDir,
            onDismiss = { showSymbolsDialog = false },
            onJump = { sym ->
                jumpToLocation(sym.filePath, sym.line, sym.col)
            }
        )
    }

    // Board picker for an installed platform (e.g. arduino:avr or esp32:esp32)
    boardPickerPlatform?.let { inst ->
        var pickerQuery by remember(inst.packageName, inst.architecture) { mutableStateOf("") }
        val filteredBoards = remember(inst.boards, pickerQuery) {
            val q = pickerQuery.trim().lowercase()
            if (q.isEmpty()) inst.boards
            else inst.boards.filter {
                it.name.lowercase().contains(q) || it.fqbn.lowercase().contains(q)
            }
        }
        AlertDialog(
            onDismissRequest = { boardPickerPlatform = null },
            icon = { Icon(Icons.Outlined.DeveloperBoard, contentDescription = null) },
            title = { Text(inst.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (inst.boards.size > 6) {
                        OutlinedTextField(
                            value = pickerQuery,
                            onValueChange = { pickerQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("Filter ${inst.boards.size} boards...") },
                            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                            trailingIcon = {
                                if (pickerQuery.isNotEmpty()) {
                                    IconButton(onClick = { pickerQuery = "" }) {
                                        Icon(Icons.Outlined.Close, contentDescription = "Clear")
                                    }
                                }
                            }
                        )
                    }
                    if (filteredBoards.isEmpty()) {
                        Text(
                            text = if (inst.boards.isEmpty()) "No boards found in this core."
                            else "No boards match \"$pickerQuery\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(filteredBoards) { b ->
                                val isCurrent = selectedBoard.fqbn == b.fqbn
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isCurrent) MaterialTheme.colorScheme.secondaryContainer
                                            else Color.Transparent
                                        )
                                        .clickable {
                                            boardPickerPlatform = null
                                            selectBoard(
                                                SelectedBoard(
                                                    packageName = inst.packageName,
                                                    architecture = inst.architecture,
                                                    boardId = b.id,
                                                    name = b.name,
                                                    fqbn = b.fqbn
                                                )
                                            )
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = b.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = b.fqbn,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (isCurrent) {
                                        Spacer(Modifier.width(8.dp))
                                        Icon(
                                            Icons.Outlined.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { boardPickerPlatform = null }) { Text("Close") }
            }
        )
    }

    // Library details dialog (author, architectures, dependencies, copy to src/, uninstall)
    selectedLibrary?.let { lib ->
        val inst = installedLibs[lib.name]
        LibraryDetailsDialog(
            lib = lib,
            installed = inst,
            downloading = lib.name in downloadingLibs,
            canCopyToProject = projectDir != null,
            onDismiss = { selectedLibrary = null },
            onDownload = { downloadLibrary(lib) },
            onImportInclude = {
                selectedLibrary = null
                inst?.let { importLibrary(it) }
            },
            onCopyToSrc = {
                selectedLibrary = null
                inst?.let { bundleLibraryIntoProjectSrc(it) }
            },
            onUninstall = {
                selectedLibrary = null
                uninstallLibrary(lib.name)
            }
        )
    }

    // Header picker when a library exposes multiple .h files
    headerPickerLib?.let { inst ->
        AlertDialog(
            onDismissRequest = { headerPickerLib = null },
            icon = { Icon(Icons.Outlined.Code, contentDescription = null) },
            title = { Text("Choose header to include") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    inst.headers.forEach { header ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    headerPickerLib = null
                                    insertInclude(header, inst.name)
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.DataObject,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(header, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { headerPickerLib = null }) { Text("Cancel") }
            }
        )
    }

    // Prompt to save / discard / cancel before closing modified file(s) or project
    pendingClose?.let { action ->
        UnsavedChangesDialog(
            action = action,
            dirtyPaths = dirtyPaths,
            projectName = projectDir?.name,
            onCancel = { pendingClose = null },
            onDiscard = {
                pendingClose = null
                when (action) {
                    is PendingCloseAction.SingleTab -> {
                        ideLog("Discarded changes in ${File(action.path).name}")
                        performCloseTab(action.path)
                    }
                    is PendingCloseAction.BatchTabs -> {
                        ideLog("Discarded unsaved changes")
                        performCloseBatchTabs(action.pathsToClose, action.keepPath)
                    }
                    PendingCloseAction.Project -> {
                        ProjectManager.clearLastProject(context)
                        onClose()
                    }
                }
            },
            onSave = {
                when (action) {
                    is PendingCloseAction.SingleTab -> {
                        if (savePath(action.path)) {
                            ideLog("Saved ${File(action.path).name}")
                            pendingClose = null
                            performCloseTab(action.path)
                        }
                    }
                    is PendingCloseAction.BatchTabs -> {
                        val targets = action.pathsToClose.filter { it in dirtyPaths }
                        if (targets.all { savePath(it) }) {
                            ideLog("Saved ${targets.size} modified ${if (targets.size == 1) "file" else "files"}")
                            pendingClose = null
                            performCloseBatchTabs(action.pathsToClose, action.keepPath)
                        }
                    }
                    PendingCloseAction.Project -> {
                        if (saveAllDirty()) {
                            pendingClose = null
                            ProjectManager.clearLastProject(context)
                            onClose()
                        }
                    }
                }
            }
        )
    }

    // Confirm before closing a clean project
    if (showCloseDialog) {
        AlertDialog(
            onDismissRequest = { showCloseDialog = false },
            icon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
            title = { Text("Close project?") },
            text = { Text("\"${projectDir?.name}\" will be closed.") },
            confirmButton = {
                TextButton(onClick = {
                    showCloseDialog = false
                    ProjectManager.clearLastProject(context)
                    onClose()
                }) { Text("Close") }
            },
            dismissButton = {
                TextButton(onClick = { showCloseDialog = false }) { Text("Cancel") }
            }
        )
    }

    // New file / new folder
    createTarget?.let { request ->
        val dir = projectDir
        if (dir != null) {
            NewEntryDialog(
                folder = request.folder,
                parentLabel = if (request.parent == dir) dir.name
                else request.parent.relativeTo(dir).path,
                onCancel = { createTarget = null },
                onConfirm = { name, extension ->
                    val result =
                        if (request.folder) ProjectManager.createFolder(dir, request.parent, name)
                        else ProjectManager.createFile(dir, request.parent, name, extension)

                    result.fold(
                        onSuccess = { created ->
                            createTarget = null
                            collapsed = collapsed - request.parent.absolutePath
                            treeVersion++
                            ideLog("Created ${if (request.folder) "folder" else "file"} ${created.name}")
                            if (!request.folder) {
                                openFile(created)
                                scope.launch { drawerState.close() }
                            }
                            null
                        },
                        onFailure = { it.message ?: "Could not create it" }
                    )
                }
            )
        }
    }

    // Delete file / folder
    deleteTarget?.let { target ->
        val dir = projectDir
        if (dir != null) {
            val isFolder = target.isDirectory
            val innerFiles = if (isFolder) target.walkTopDown().count { it.isFile } else 0

            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                icon = {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                title = { Text(if (isFolder) "Delete folder?" else "Delete file?") },
                text = {
                    Text(
                        if (isFolder) {
                            "\"${target.name}\" and its $innerFiles " +
                                "${if (innerFiles == 1) "file" else "files"} " +
                                "will be permanently deleted. This can't be undone."
                        } else {
                            "\"${target.name}\" will be permanently deleted. This can't be undone."
                        }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        deleteTarget = null
                        deleteEntries(dir, listOf(target))
                    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
                }
            )
        }
    }

    // Delete several files / folders picked in the drawer
    deleteMany?.let { targets ->
        val dir = projectDir
        if (dir != null) {
            val innerFiles = remember(targets) {
                targets.filter { it.isDirectory }
                    .sumOf { folder -> folder.walkTopDown().count { it.isFile } }
            }
            val names = targets.take(5).joinToString("\n") { "\u2022 ${it.name}" } +
                if (targets.size > 5) "\n...and ${targets.size - 5} more" else ""

            AlertDialog(
                onDismissRequest = { deleteMany = null },
                icon = {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                title = { Text("Delete ${targets.size} items?") },
                text = {
                    Text(
                        names + "\n\n" +
                            (if (innerFiles > 0) "Everything inside the selected folders is deleted too. " else "") +
                            "This can't be undone."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        deleteMany = null
                        deleteEntries(dir, targets)
                    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { deleteMany = null }) { Text("Cancel") }
                }
            )
        }
    }

    if (showBoardUrlsDialog) {
        val customUrls = remember(showBoardUrlsDialog) { BoardManager.customIndexUrls(context) }
        val allUrls = remember(showBoardUrlsDialog) { BoardManager.allIndexUrls(context) }
        BoardIndexUrlsDialog(
            builtInUrls = allUrls.filterNot { it in customUrls },
            customUrls = customUrls,
            onSaveCustomUrls = { nextUrls ->
                BoardManager.setCustomIndexUrls(context, nextUrls)
                refreshBoardCatalog(forceRemote = true)
            },
            onDismiss = { showBoardUrlsDialog = false }
        )
    }

    // Ask for the title right after the editor opens with the template
    if (projectPath == null) {
        ProjectTitleDialog(
            onCancel = onClose,
            onConfirm = { name ->
                ProjectManager
                    .createProject(context, name, templateAsset ?: "templates/blank.ino")
                    .fold(
                        onSuccess = { dir ->
                            projectPath = dir.absolutePath
                            openFile(File(dir, "$name.ino"), syncPrevious = false)
                            if (requiredLibraries.isNotEmpty()) {
                                scope.launch { ensureLibrariesInstalled(requiredLibraries) }
                            }
                            null
                        },
                        onFailure = { it.message ?: "Could not create project" }
                    )
            }
        )
    }
}
