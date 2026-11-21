package com.h0lymarc.eside.editor

import com.h0lymarc.eside.*
import com.h0lymarc.eside.manager.BoardBuildConfig
import com.h0lymarc.eside.manager.BoardManager
import com.h0lymarc.eside.manager.BoardMenuGroup
import com.h0lymarc.eside.manager.CodeDiagnostic
import com.h0lymarc.eside.manager.CodeSymbol
import com.h0lymarc.eside.manager.DiagnosticSeverity
import com.h0lymarc.eside.manager.IntelliSenseSuggestion
import com.h0lymarc.eside.manager.ProjectIndex
import com.h0lymarc.eside.manager.SelectedBoard
import com.h0lymarc.eside.manager.SymbolKind

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun EditorTabBar(
    tabs: List<String>,
    activePath: String?,
    dirtyPaths: Set<String>,
    onSelect: (String) -> Unit,
    onCloseThis: (String) -> Unit,
    onCloseOthers: (String) -> Unit,
    onCloseAll: () -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(activePath, tabs.size) {
        val index = tabs.indexOf(activePath)
        if (index >= 0) listState.animateScrollToItem(index)
    }

    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(tabs, key = { it }) { path ->
                val file = File(path)
                val duplicate = tabs.count { File(it).name == file.name } > 1
                EditorTab(
                    label = if (duplicate) "${file.parentFile?.name}/${file.name}" else file.name,
                    file = file,
                    active = path == activePath,
                    dirty = path in dirtyPaths,
                    hasOtherTabs = tabs.size > 1,
                    onSelect = { onSelect(path) },
                    onCloseThis = { onCloseThis(path) },
                    onCloseOthers = { onCloseOthers(path) },
                    onCloseAll = onCloseAll
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EditorTab(
    label: String,
    file: File,
    active: Boolean,
    dirty: Boolean,
    hasOtherTabs: Boolean,
    onSelect: () -> Unit,
    onCloseThis: () -> Unit,
    onCloseOthers: () -> Unit,
    onCloseAll: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val style = styleFor(file)

    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (active) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
                .combinedClickable(
                    onClick = { if (active) menuOpen = true else onSelect() },
                    onLongClick = { menuOpen = true }
                )
                .padding(start = 12.dp, end = if (active) 6.dp else 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = style.icon,
                contentDescription = null,
                tint = style.color,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1
            )
            if (dirty) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
            if (active) {
                Icon(
                    imageVector = Icons.Outlined.ArrowDropDown,
                    contentDescription = "Tab options",
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Close this file") },
                leadingIcon = { Icon(Icons.Outlined.Close, contentDescription = null) },
                onClick = { menuOpen = false; onCloseThis() }
            )
            DropdownMenuItem(
                text = { Text("Close others") },
                enabled = hasOtherTabs,
                leadingIcon = { Icon(Icons.Outlined.FilterNone, contentDescription = null) },
                onClick = { menuOpen = false; onCloseOthers() }
            )
            DropdownMenuItem(
                text = { Text("Close all") },
                leadingIcon = { Icon(Icons.Outlined.ClearAll, contentDescription = null) },
                onClick = { menuOpen = false; onCloseAll() }
            )
        }
    }
}

@Composable
internal fun IntelliSenseBar(
    suggestions: List<IntelliSenseSuggestion>,
    onPick: (IntelliSenseSuggestion) -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(suggestions, key = { "${it.kind}:${it.label}:${it.detail}" }) { item ->
                    val badgeColor = when (item.kind) {
                        SymbolKind.METHOD -> Color(0xFFB07CFF)
                        SymbolKind.FIELD -> Color(0xFF4F8CFF)
                        SymbolKind.CLASS, SymbolKind.STRUCT -> Color(0xFFFFB454)
                        SymbolKind.HEADER -> Color(0xFF00979D)
                        SymbolKind.DIRECTIVE -> Color(0xFFFF7A90)
                        else -> MaterialTheme.colorScheme.primary
                    }
                    val badgeText = if (item.kind == SymbolKind.DIRECTIVE) "#"
                    else item.kind.label.take(1).uppercase()
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onPick(item) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = badgeText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = badgeColor
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = item.label,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        if (item.detail.isNotBlank()) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = item.detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Hide suggestions",
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
internal fun DiagnosticsBanner(
    diagnostics: List<CodeDiagnostic>,
    onJump: (CodeDiagnostic) -> Unit,
    onOpenProblems: () -> Unit
) {
    val first = diagnostics.firstOrNull() ?: return
    val hasError = diagnostics.any { it.severity == DiagnosticSeverity.ERROR }
    val bgColor = if (hasError) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.tertiaryContainer
    val contentColor = if (hasError) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onTertiaryContainer

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable { onJump(first) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (hasError) Icons.Outlined.ErrorOutline else Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Line ${first.line + 1}: ${first.message}",
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (diagnostics.size > 1) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = "+${diagnostics.size - 1}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = "Problems",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onOpenProblems)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
internal fun EditorQuickBar(
    textSizeSp: Float,
    onTab: () -> Unit,
    onUnindent: () -> Unit,
    onMoveCursor: (Int) -> Unit,
    onCursorHome: () -> Unit,
    onCursorEnd: () -> Unit,
    onInsertSymbol: (String) -> Unit,
    onToggleComment: () -> Unit,
    onDuplicateLine: () -> Unit,
    onDeleteLine: () -> Unit,
    onMoveLine: (Boolean) -> Unit,
    onSelectLine: () -> Unit,
    onFormatCode: () -> Unit,
    onGoToLine: () -> Unit,
    onChangeTextSize: (Float) -> Unit,
    onHide: () -> Unit
) {
    var toolsOpen by remember { mutableStateOf(false) }

    val quickSymbols = remember {
        listOf(
            "{ }" to "{}",
            "( )" to "()",
            "[ ]" to "[]",
            ";" to ";",
            "#" to "#",
            "< >" to "<>",
            "\"" to "\"\"",
            "'" to "''",
            "=" to "=",
            "->" to "->",
            "::" to "::",
            "&" to "&",
            "|" to "|",
            "!" to "!",
            "\\" to "\\"
        )
    }

    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                QuickBarChip(
                    label = "TAB",
                    highlighted = true,
                    onClick = onTab
                )
                QuickBarChip(
                    label = "\u21E4",
                    onClick = onUnindent
                )

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(18.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )

                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    item(key = "cur_left") {
                        QuickBarChip(label = "\u25C0", onClick = { onMoveCursor(-1) })
                    }
                    item(key = "cur_right") {
                        QuickBarChip(label = "\u25B6", onClick = { onMoveCursor(1) })
                    }
                    item(key = "comment") {
                        QuickBarChip(label = "//", onClick = onToggleComment)
                    }
                    items(quickSymbols, key = { it.first }) { (label, token) ->
                        QuickBarChip(
                            label = label,
                            onClick = { onInsertSymbol(token) }
                        )
                    }
                }

                Box {
                    IconButton(
                        onClick = { toolsOpen = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Tune,
                            contentDescription = "Editor actions",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = toolsOpen,
                        onDismissRequest = { toolsOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Indent (Tab)") },
                            leadingIcon = { Icon(Icons.Outlined.ArrowForward, contentDescription = null) },
                            onClick = { toolsOpen = false; onTab() }
                        )
                        DropdownMenuItem(
                            text = { Text("Unindent (Shift+Tab)") },
                            leadingIcon = { Icon(Icons.Outlined.ArrowBack, contentDescription = null) },
                            onClick = { toolsOpen = false; onUnindent() }
                        )
                        DropdownMenuItem(
                            text = { Text("Toggle comment (//)") },
                            leadingIcon = { Icon(Icons.Outlined.Code, contentDescription = null) },
                            onClick = { toolsOpen = false; onToggleComment() }
                        )
                        DropdownMenuItem(
                            text = { Text("Duplicate line") },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                            onClick = { toolsOpen = false; onDuplicateLine() }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete line") },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                            onClick = { toolsOpen = false; onDeleteLine() }
                        )
                        DropdownMenuItem(
                            text = { Text("Move line up") },
                            leadingIcon = { Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = null) },
                            onClick = { toolsOpen = false; onMoveLine(true) }
                        )
                        DropdownMenuItem(
                            text = { Text("Move line down") },
                            leadingIcon = { Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null) },
                            onClick = { toolsOpen = false; onMoveLine(false) }
                        )
                        DropdownMenuItem(
                            text = { Text("Select current line") },
                            leadingIcon = { Icon(Icons.Outlined.SelectAll, contentDescription = null) },
                            onClick = { toolsOpen = false; onSelectLine() }
                        )
                        DropdownMenuItem(
                            text = { Text("Jump to line start") },
                            leadingIcon = { Icon(Icons.Outlined.FirstPage, contentDescription = null) },
                            onClick = { toolsOpen = false; onCursorHome() }
                        )
                        DropdownMenuItem(
                            text = { Text("Jump to line end") },
                            leadingIcon = { Icon(Icons.Outlined.LastPage, contentDescription = null) },
                            onClick = { toolsOpen = false; onCursorEnd() }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Format code") },
                            leadingIcon = { Icon(Icons.Outlined.AutoFixHigh, contentDescription = null) },
                            onClick = { toolsOpen = false; onFormatCode() }
                        )
                        DropdownMenuItem(
                            text = { Text("Go to line...") },
                            leadingIcon = { Icon(Icons.Outlined.FormatListNumbered, contentDescription = null) },
                            onClick = { toolsOpen = false; onGoToLine() }
                        )
                        HorizontalDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Text ${textSizeSp.toInt()}sp",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedButton(
                                    onClick = { onChangeTextSize((textSizeSp - 1f).coerceAtLeast(10f)) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text("A-")
                                }
                                OutlinedButton(
                                    onClick = { onChangeTextSize((textSizeSp + 1f).coerceAtMost(24f)) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text("A+")
                                }
                            }
                        }
                        DropdownMenuItem(
                            text = { Text("Hide quick bar") },
                            leadingIcon = { Icon(Icons.Outlined.VisibilityOff, contentDescription = null) },
                            onClick = { toolsOpen = false; onHide() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickBarChip(
    label: String,
    highlighted: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .height(32.dp)
            .widthIn(min = 34.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(
                if (highlighted) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium,
            color = if (highlighted) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
internal fun EmptyEditorState(onOpenFiles: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.Description,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Text("No file open", style = MaterialTheme.typography.titleMedium)
        Text(
            "Pick a file from the project drawer to start editing.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        FilledTonalButton(onClick = onOpenFiles) { Text("Browse files") }
    }
}

@Composable
internal fun FindBar(
    query: String,
    onQueryChange: (String) -> Unit,
    projectScope: Boolean,
    onScopeChange: (Boolean) -> Unit,
    matchCase: Boolean,
    onMatchCase: (Boolean) -> Unit,
    wholeWord: Boolean,
    onWholeWord: (Boolean) -> Unit,
    status: String,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClose: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    singleLine = true,
                    placeholder = { Text(if (projectScope) "Find in project" else "Find in file") },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = status,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                IconButton(
                                    onClick = { onQueryChange("") },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = "Clear",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onNext() })
                )
                IconButton(onClick = onPrevious, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Previous match")
                }
                IconButton(onClick = onNext, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Next match")
                }
                IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close find")
                }
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = !projectScope,
                    onClick = { onScopeChange(false) },
                    label = { Text("In file") }
                )
                FilterChip(
                    selected = projectScope,
                    onClick = { onScopeChange(true) },
                    label = { Text("In project") }
                )
                FilterChip(
                    selected = matchCase,
                    onClick = { onMatchCase(!matchCase) },
                    label = { Text("Aa") }
                )
                FilterChip(
                    selected = wholeWord,
                    onClick = { onWholeWord(!wholeWord) },
                    label = { Text("Word") }
                )
            }
        }
    }
}

@Composable
internal fun BottomPanel(
    height: Dp,
    selected: PanelTab,
    searchLabel: String,
    problemsLabel: String,
    onSelect: (PanelTab) -> Unit,
    onResize: (Float) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
    content: @Composable (PanelTab) -> Unit
) {
    val currentOnResize by rememberUpdatedState(onResize)
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)

    val pagerState = rememberPagerState(
        initialPage = selected.ordinal,
        pageCount = { PanelTab.entries.size }
    )

    LaunchedEffect(selected) {
        if (pagerState.currentPage != selected.ordinal) {
            pagerState.animateScrollToPage(
                page = selected.ordinal,
                animationSpec = tween(durationMillis = 260)
            )
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val tab = PanelTab.entries[page]
                if (tab != currentSelected) {
                    currentOnSelect(tab)
                }
            }
    }

    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { change, dragAmount ->
                            change.consume()
                            currentOnResize(-dragAmount / density)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState())
                ) {
                    PanelTab.entries.forEach { tab ->
                        PanelTabButton(
                            label = when (tab) {
                                PanelTab.SEARCH -> searchLabel
                                PanelTab.PROBLEMS -> problemsLabel
                                else -> tab.label
                            },
                            selected = tab == selected,
                            onClick = { onSelect(tab) }
                        )
                    }
                }
                IconButton(onClick = onClear) {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = "Clear")
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Hide panel")
                }
            }

            HorizontalDivider()

            HorizontalPager(
                state = pagerState,
                key = { PanelTab.entries[it].name },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { page ->
                Box(modifier = Modifier.fillMaxSize()) {
                    content(PanelTab.entries[page])
                }
            }
        }
    }
}

@Composable
internal fun PanelTabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val textColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 200),
        label = "tabColor"
    )

    Box(
        modifier = Modifier
            .height(40.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = textColor,
            maxLines = 1
        )
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
internal fun PanelEmpty(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
internal fun CompileLogView(
    log: String?,
    compiling: Boolean,
    success: Boolean,
    zoomScale: Float = 1f,
    onZoomChange: (Float) -> Unit = {}
) {
    if (log == null) {
        PanelEmpty("No build yet. Tap the build icon to compile the project.")
    } else {
        val errorColor = MaterialTheme.colorScheme.error
        val warnColor = MaterialTheme.colorScheme.tertiary
        val text = remember(log, errorColor, warnColor) {
            buildAnnotatedString {
                val lines = log.lines()
                lines.forEachIndexed { i, line ->
                    val lower = line.lowercase()
                    val color = when {
                        "error" in lower -> errorColor
                        "warning" in lower -> warnColor
                        else -> Color.Unspecified
                    }
                    withStyle(SpanStyle(color = color)) { append(line) }
                    if (i < lines.lastIndex) append("\n")
                }
            }
        }
        val clampedZoom = zoomScale.coerceIn(0.6f, 2.5f)

        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = when {
                    compiling -> "Compiling..."
                    success -> "Build succeeded"
                    else -> "Build failed"
                },
                style = MaterialTheme.typography.titleSmall,
                color = if (!compiling && !success) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (compiling) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            SelectionContainer(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pinchToZoom { factor ->
                        onZoomChange((clampedZoom * factor).coerceIn(0.6f, 2.5f))
                    }
            ) {
                Text(
                    text = text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = (12f * clampedZoom).sp,
                    lineHeight = (17f * clampedZoom).sp,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
internal fun LogListView(
    lines: List<LogLine>,
    emptyText: String,
    zoomScale: Float = 1f,
    onZoomChange: (Float) -> Unit = {}
) {
    if (lines.isEmpty()) {
        PanelEmpty(emptyText)
    } else {
        val listState = rememberLazyListState()
        val errorColor = MaterialTheme.colorScheme.error
        val warnColor = MaterialTheme.colorScheme.tertiary
        val dimColor = MaterialTheme.colorScheme.onSurfaceVariant
        val clampedZoom = zoomScale.coerceIn(0.6f, 2.5f)

        LaunchedEffect(lines.size) {
            if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .pinchToZoom { factor ->
                    onZoomChange((clampedZoom * factor).coerceIn(0.6f, 2.5f))
                },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            items(lines.size) { i ->
                val line = lines[i]
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = dimColor)) { append(line.time + "  ") }
                        val color = when (line.level) {
                            LogLevel.INFO -> Color.Unspecified
                            LogLevel.WARN -> warnColor
                            LogLevel.ERROR -> errorColor
                        }
                        withStyle(SpanStyle(color = color)) { append(line.text) }
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = (12f * clampedZoom).sp,
                    lineHeight = (17f * clampedZoom).sp
                )
            }
        }
    }
}

@Composable
private fun Modifier.pinchToZoom(onZoom: (Float) -> Unit): Modifier {
    val currentOnZoom by rememberUpdatedState(onZoom)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var pinching = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressedCount = event.changes.count { it.pressed }
                if (pressedCount >= 2) {
                    pinching = true
                    val zoomChange = event.calculateZoom()
                    if (!zoomChange.isNaN() && zoomChange > 0f && zoomChange != 1f) {
                        currentOnZoom(zoomChange)
                    }
                    event.changes.forEach { change ->
                        if (change.pressed) change.consume()
                    }
                } else if (pinching) {
                    event.changes.forEach { change ->
                        if (change.pressed) change.consume()
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}

@Composable
internal fun ProblemsView(
    diagnostics: List<CodeDiagnostic>,
    onDiagnosticClick: (CodeDiagnostic) -> Unit
) {
    if (diagnostics.isEmpty()) {
        PanelEmpty("No syntax or header issues detected in the open file.")
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(diagnostics, key = { "${it.path}:${it.line}:${it.col}:${it.message}" }) { diag ->
                val isError = diag.severity == DiagnosticSeverity.ERROR
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDiagnosticClick(diag) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = diag.message,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${File(diag.path).name}:${diag.line + 1}:${diag.col + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
internal fun SymbolsDialog(
    index: ProjectIndex,
    projectDir: File?,
    onDismiss: () -> Unit,
    onJump: (CodeSymbol) -> Unit
) {
    var filter by remember { mutableStateOf("") }
    val filtered = remember(index.symbols, filter) {
        val q = filter.trim().lowercase()
        index.symbols.filter { sym ->
            q.isEmpty() ||
                sym.name.lowercase().contains(q) ||
                (sym.owner?.lowercase()?.contains(q) == true) ||
                sym.signature.lowercase().contains(q)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.DataObject, contentDescription = null) },
        title = { Text("Project symbols (${index.symbols.size})") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Filter classes, methods, functions...") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (filter.isNotEmpty()) {
                            IconButton(onClick = { filter = "" }) {
                                Icon(Icons.Outlined.Close, contentDescription = "Clear")
                            }
                        }
                    }
                )
                if (filtered.isEmpty()) {
                    Text(
                        text = if (index.symbols.isEmpty()) "No classes, structs, or functions detected yet."
                        else "No symbols match \"$filter\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 340.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(filtered, key = { "${it.filePath}:${it.line}:${it.col}:${it.name}" }) { sym ->
                            val rel = projectDir?.let {
                                runCatching { File(sym.filePath).relativeTo(it).path }.getOrNull()
                            } ?: File(sym.filePath).name
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        onDismiss()
                                        onJump(sym)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = sym.kind.label,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.width(46.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (sym.owner != null) "${sym.owner}::${sym.signature}" else sym.signature,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "$rel:${sym.line + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
internal fun GoToLineDialog(
    totalLines: Int,
    currentLine: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var input by remember { mutableStateOf("${currentLine + 1}") }
    val parsed = input.trim().toIntOrNull()
    val valid = parsed != null && parsed in 1..totalLines
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.FormatListNumbered, contentDescription = null) },
        title = { Text("Go to line") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Enter a line number between 1 and $totalLines.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { value -> input = value.filter { it.isDigit() } },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    placeholder = { Text("1..$totalLines") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = { if (valid && parsed != null) onConfirm(parsed) }
                    )
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { if (parsed != null) onConfirm(parsed) }
            ) { Text("Go") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
internal fun BoardOptionsDialog(
    selectedBoard: SelectedBoard,
    boardInfo: BoardBuildConfig?,
    menus: List<BoardMenuGroup>,
    selectedOptions: Map<String, String>,
    onSelectOption: (menuId: String, optionId: String) -> Unit,
    onResetDefaults: () -> Unit,
    onSwitchBoard: () -> Unit,
    onDismiss: () -> Unit
) {
    var pickingGroup by remember { mutableStateOf<BoardMenuGroup?>(null) }
    val effectiveFqbn = remember(selectedBoard.fqbn, menus, selectedOptions) {
        BoardManager.buildEffectiveFqbn(selectedBoard.fqbn, menus, selectedOptions)
    }
    val isEsp32 = selectedBoard.architecture.contains("esp32", ignoreCase = true)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
        title = {
            val active = pickingGroup
            Text(
                text = active?.label ?: selectedBoard.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            val active = pickingGroup
            if (active != null) {
                val activeOptId = selectedOptions[active.id] ?: active.defaultOptionId
                val supportsCustom = active.id == "CPUFreq" || active.id == "UploadSpeed"
                var customInput by remember(active.id, activeOptId) {
                    val presetMatch = active.options.any { it.id == activeOptId }
                    val initial = if (!supportsCustom || presetMatch) ""
                    else activeOptId.removeSuffix("L").removeSuffix("l").removeSuffix("UL").removeSuffix("ul")
                    mutableStateOf(initial)
                }
                val parsedCustomId = remember(customInput, active.id, isEsp32) {
                    parseCustomBoardOptionId(active.id, customInput, isEsp32)
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(active.options, key = { it.id }) { opt ->
                        val isChosen = opt.id == activeOptId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isChosen) MaterialTheme.colorScheme.secondaryContainer
                                    else Color.Transparent
                                )
                                .clickable {
                                    onSelectOption(active.id, opt.id)
                                    pickingGroup = null
                                }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isChosen,
                                onClick = {
                                    onSelectOption(active.id, opt.id)
                                    pickingGroup = null
                                },
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = opt.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isChosen) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    if (supportsCustom) {
                        item(key = "custom_input_section") {
                            Spacer(Modifier.height(6.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = if (active.id == "CPUFreq") "Custom CPU frequency" else "Custom upload speed",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = customInput,
                                    onValueChange = { next ->
                                        customInput = next.filter { it.isDigit() }
                                    },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    placeholder = {
                                        Text(
                                            text = when {
                                                active.id == "CPUFreq" && isEsp32 -> "MHz (e.g. 120, 240)"
                                                active.id == "CPUFreq" -> "MHz or Hz (e.g. 8, 16000000)"
                                                else -> "Baud (e.g. 74880, 2000000)"
                                            },
                                            fontSize = 12.sp
                                        )
                                    },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(
                                        onDone = {
                                            if (parsedCustomId != null) {
                                                onSelectOption(active.id, parsedCustomId)
                                                pickingGroup = null
                                            }
                                        }
                                    )
                                )
                                FilledTonalButton(
                                    enabled = parsedCustomId != null,
                                    onClick = {
                                        if (parsedCustomId != null) {
                                            onSelectOption(active.id, parsedCustomId)
                                            pickingGroup = null
                                        }
                                    }
                                ) {
                                    Text("Apply")
                                }
                            }
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = effectiveFqbn.ifBlank { "No board selected" },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(
                            onClick = {
                                onDismiss()
                                onSwitchBoard()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text("Switch", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    HorizontalDivider()

                    if (boardInfo == null) {
                        Text(
                            text = "Download a board core in Drawer -> Boards first to inspect offline board specs and edit board options.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (menus.isNotEmpty()) {
                                item(key = "menus_header") {
                                    Text(
                                        text = "Editable board options (tap to change)",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                items(menus, key = { it.id }) { group ->
                                    val activeOptId = selectedOptions[group.id] ?: group.defaultOptionId
                                    val activeLabel = formatBoardOptionDisplay(group, activeOptId)
                                    val isCustom = activeOptId != group.defaultOptionId
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(
                                                if (isCustom) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                            )
                                            .clickable { pickingGroup = group }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = group.label,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = activeLabel,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                color = if (isCustom) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Icon(
                                            Icons.Outlined.ChevronRight,
                                            contentDescription = "Edit ${group.label}",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }

                            item(key = "offline_board_info") {
                                Spacer(Modifier.height(2.dp))
                                OfflineBoardInfoCard(info = boardInfo)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (pickingGroup != null) {
                TextButton(onClick = { pickingGroup = null }) { Text("Back") }
            } else {
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
        dismissButton = {
            if (pickingGroup == null && boardInfo != null && selectedOptions.isNotEmpty()) {
                TextButton(onClick = onResetDefaults) { Text("Reset defaults") }
            }
        }
    )
}

private fun parseCustomBoardOptionId(
    menuId: String,
    rawDigits: String,
    isEsp32: Boolean
): String? {
    val num = rawDigits.trim().toLongOrNull() ?: return null
    if (num <= 0L) return null
    return when (menuId) {
        "CPUFreq" -> if (isEsp32) {
            val mhz = if (num >= 1_000_000L) num / 1_000_000L else num
            if (mhz in 1L..1000L) mhz.toString() else null
        } else {
            val hz = if (num in 1L..1000L) num * 1_000_000L else num
            if (hz >= 10_000L) "${hz}L" else null
        }
        "UploadSpeed" -> if (num >= 300L) num.toString() else null
        else -> null
    }
}

private fun formatBoardOptionDisplay(group: BoardMenuGroup, activeOptId: String): String {
    group.options.firstOrNull { it.id == activeOptId }?.let { return it.label }
    return when (group.id) {
        "CPUFreq" -> {
            val digits = activeOptId.trim().removeSuffix("UL").removeSuffix("ul").removeSuffix("L").removeSuffix("l")
            val num = digits.toLongOrNull()
            when {
                num == null -> activeOptId
                num in 1L..480L -> "$num MHz (Custom)"
                num >= 1_000_000L && num % 1_000_000L == 0L -> "${num / 1_000_000L} MHz (Custom)"
                num >= 1_000L && num % 1_000L == 0L -> "${num / 1_000L} kHz (Custom)"
                else -> "$num Hz (Custom)"
            }
        }
        "UploadSpeed" -> "$activeOptId baud (Custom)"
        else -> activeOptId
    }
}

@Composable
private fun OfflineBoardInfoCard(info: BoardBuildConfig) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Offline board info",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            if (info.platformName.isNotBlank()) {
                val ver = if (info.platformVersion.isNotBlank()) " v${info.platformVersion}" else ""
                BoardSpecRow("Core", "${info.platformName}$ver")
            }
            if (info.mcu.isNotBlank()) BoardSpecRow("MCU", info.mcu)
            if (info.fCpu.isNotBlank()) BoardSpecRow("CPU Clock", formatFcpuDisplay(info.fCpu))
            if (info.uploadMaximumSize > 0L) BoardSpecRow("Max Flash", formatMemoryBytes(info.uploadMaximumSize))
            if (info.uploadMaximumDataSize > 0L) BoardSpecRow("Max RAM", formatMemoryBytes(info.uploadMaximumDataSize))
            if (info.coreName.isNotBlank() || info.variantName.isNotBlank()) {
                val pair = listOf(info.coreName, info.variantName).filter { it.isNotBlank() }.joinToString(" / ")
                BoardSpecRow("Core / Variant", pair)
            }
            if (info.uploadProtocol.isNotBlank() || info.uploadSpeed.isNotBlank()) {
                val up = listOfNotNull(
                    info.uploadProtocol.takeIf { it.isNotBlank() },
                    info.uploadSpeed.takeIf { it.isNotBlank() }?.let { "$it baud" }
                ).joinToString(" @ ")
                BoardSpecRow("Upload", up)
            }
            if (info.flashMode.isNotBlank() || info.flashFreq.isNotBlank() || info.flashSize.isNotBlank()) {
                val flash = listOf(info.flashSize, info.flashMode, info.flashFreq)
                    .filter { it.isNotBlank() }
                    .joinToString(" \u00B7 ")
                BoardSpecRow("Flash", flash)
            }
            if (info.partitionScheme.isNotBlank()) BoardSpecRow("Partitions", info.partitionScheme)
            if (info.psramMode.isNotBlank()) BoardSpecRow("PSRAM", info.psramMode)
            if (info.boardDefine.isNotBlank()) BoardSpecRow("Board Macro", "-DARDUINO_${info.boardDefine}")
            if (info.vidPids.isNotEmpty()) BoardSpecRow("USB VID:PID", info.vidPids.joinToString(", "))
            if (info.extraFlags.isNotEmpty()) {
                BoardSpecRow("Extra Flags", info.extraFlags.joinToString(" "))
            }
        }
    }
}

@Composable
private fun BoardSpecRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(86.dp)
        )
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun formatFcpuDisplay(raw: String): String {
    val digits = raw.trim().removeSuffix("L").removeSuffix("l").removeSuffix("UL").removeSuffix("ul")
    val hz = digits.toLongOrNull() ?: return raw
    return if (hz >= 1_000_000L && hz % 1_000_000L == 0L) {
        "${hz / 1_000_000L} MHz ($raw)"
    } else {
        raw
    }
}

private fun formatMemoryBytes(bytes: Long): String = when {
    bytes >= 1_048_576L -> "%.2f MB (%d bytes)".format(bytes / 1_048_576.0, bytes)
    bytes >= 1024L -> "%d KB (%d bytes)".format(bytes / 1024L, bytes)
    else -> "$bytes bytes"
}

@Composable
internal fun QuickBoardSwitcherDialog(
    selectedBoard: SelectedBoard,
    boards: List<SelectedBoard>,
    onPick: (SelectedBoard) -> Unit,
    onDismiss: () -> Unit
) {
    var filterQuery by remember { mutableStateOf("") }
    var archFilter by remember { mutableStateOf("ALL") }

    val archOptions = remember(boards) {
        listOf("ALL") + boards.map { it.architecture.lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    val filtered = remember(boards, filterQuery, archFilter) {
        val q = filterQuery.trim().lowercase()
        boards.filter { b ->
            val archMatch = archFilter == "ALL" || b.architecture.equals(archFilter, ignoreCase = true)
            archMatch && (
                q.isEmpty() ||
                    b.name.lowercase().contains(q) ||
                    b.fqbn.lowercase().contains(q) ||
                    b.architecture.lowercase().contains(q)
                )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.DeveloperBoard, contentDescription = null) },
        title = { Text("Select target board") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = filterQuery,
                    onValueChange = { filterQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Filter boards, architecture, or FQBN...") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (filterQuery.isNotEmpty()) {
                            IconButton(onClick = { filterQuery = "" }) {
                                Icon(Icons.Outlined.Close, contentDescription = "Clear")
                            }
                        }
                    }
                )
                if (archOptions.size > 2) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        archOptions.forEach { archId ->
                            FilterChip(
                                selected = archFilter == archId,
                                onClick = { archFilter = archId },
                                label = {
                                    Text(if (archId == "ALL") "All" else archId.uppercase())
                                }
                            )
                        }
                    }
                }
                if (boards.isEmpty()) {
                    Text(
                        text = "No board cores downloaded yet. Download a core from Drawer -> Boards first.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else if (filtered.isEmpty()) {
                    Text(
                        text = "No boards match \"$filterQuery\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(filtered, key = { it.fqbn }) { b ->
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
                                        onPick(b)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
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
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
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
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
internal fun SearchResultsView(
    query: String,
    projectScope: Boolean,
    hits: List<SearchHit>,
    currentHit: Int,
    searching: Boolean,
    projectDir: File?,
    onHitClick: (Int) -> Unit,
    onFindInFile: () -> Unit,
    onFindInProject: () -> Unit
) {
    if (query.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Search this file or the whole project. Matches show up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onFindInFile) { Text("Find in file") }
                FilledTonalButton(onClick = onFindInProject) { Text("Find in project") }
            }
        }
    } else {
        val groups = remember(hits) { hits.withIndex().groupBy { it.value.path } }
        val listState = rememberLazyListState()

        LaunchedEffect(currentHit, hits) {
            if (currentHit in hits.indices) {
                var position = 0
                var target = -1
                for ((_, entries) in groups) {
                    if (projectScope) position++
                    val k = entries.indexOfFirst { it.index == currentHit }
                    if (k >= 0) {
                        target = position + k
                        break
                    }
                    position += entries.size
                }
                if (target >= 0) listState.animateScrollToItem(target)
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            if (hits.isNotEmpty()) {
                val count = if (hits.size >= MAX_HITS) "$MAX_HITS+" else "${hits.size}"
                val where = if (projectScope) {
                    "${groups.size} ${if (groups.size == 1) "file" else "files"}"
                } else {
                    "this file"
                }
                Text(
                    text = "\"$query\"  \u00B7  $count ${if (hits.size == 1) "result" else "results"} in $where",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            if (searching) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            if (hits.isEmpty()) {
                if (!searching) PanelEmpty("No results for \"$query\"")
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    groups.forEach { (path, entries) ->
                        if (projectScope) {
                            item(key = "file:$path") {
                                SearchFileHeader(path = path, projectDir = projectDir, count = entries.size)
                            }
                        }
                        items(entries, key = { "$path:${it.value.line}:${it.value.col}" }) { entry ->
                            SearchHitRow(
                                hit = entry.value,
                                selected = entry.index == currentHit,
                                onClick = { onHitClick(entry.index) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SearchFileHeader(path: String, projectDir: File?, count: Int) {
    val file = File(path)
    val style = styleFor(file)
    val label = projectDir?.let { runCatching { file.relativeTo(it).path }.getOrNull() } ?: file.name

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = style.icon,
            contentDescription = null,
            tint = style.color,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun SearchHitRow(hit: SearchHit, selected: Boolean, onClick: () -> Unit) {
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
    val preview = remember(hit, highlight) {
        buildAnnotatedString {
            append(hit.preview)
            val start = hit.previewStart
            if (start >= 0 && start < hit.preview.length) {
                addStyle(
                    SpanStyle(background = highlight, fontWeight = FontWeight.Bold),
                    start,
                    minOf(hit.preview.length, start + hit.length)
                )
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${hit.line + 1}",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(36.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = preview,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
