package com.h0lymarc.eside.drawer

import com.h0lymarc.eside.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.h0lymarc.eside.manager.BoardBuildConfig
import com.h0lymarc.eside.manager.BoardMenuGroup
import com.h0lymarc.eside.manager.BoardPlatform
import com.h0lymarc.eside.manager.InstalledPlatform
import com.h0lymarc.eside.manager.SelectedBoard

@Composable
internal fun DrawerBoardsTab(
    selectedBoard: SelectedBoard,
    boardInfo: BoardBuildConfig?,
    boardMenus: List<BoardMenuGroup>,
    boardOptions: Map<String, String>,
    catalog: List<BoardPlatform>,
    installed: Map<String, InstalledPlatform>,
    query: String,
    onQueryChange: (String) -> Unit,
    filter: BoardFilter,
    onFilterChange: (BoardFilter) -> Unit,
    loading: Boolean,
    error: String?,
    downloading: Set<String>,
    onRefresh: () -> Unit,
    onManageUrls: () -> Unit,
    onDownload: (BoardPlatform) -> Unit,
    onPickBoard: (InstalledPlatform) -> Unit,
    onSwitchBoard: () -> Unit,
    onOpenBoardOptions: () -> Unit,
    onUninstall: (InstalledPlatform) -> Unit
) {
    val filtered by remember(catalog, installed, query, filter) {
        derivedStateOf {
            val q = query.trim().lowercase()
            catalog.asSequence()
                .filter { p ->
                    when (filter) {
                        BoardFilter.ALL -> true
                        BoardFilter.INSTALLED -> p.id in installed
                        BoardFilter.ARDUINO ->
                            p.packageName.equals("arduino", ignoreCase = true) ||
                                p.category.equals("Arduino", ignoreCase = true)
                        BoardFilter.THIRD_PARTY ->
                            !p.packageName.equals("arduino", ignoreCase = true)
                    }
                }
                .filter { p ->
                    q.isEmpty() ||
                        p.name.lowercase().contains(q) ||
                        p.architecture.lowercase().contains(q) ||
                        p.packageName.lowercase().contains(q) ||
                        p.maintainer.lowercase().contains(q) ||
                        p.boardNames.any { it.lowercase().contains(q) }
                }
                .toList()
        }
    }

    val hasInstalledCores = remember(installed) {
        installed.values.any { it.boards.isNotEmpty() }
    }
    val isBoardReady = remember(installed, selectedBoard) {
        selectedBoard.fqbn.isNotBlank() && installed.values.any { inst ->
            inst.packageName == selectedBoard.packageName &&
                inst.architecture == selectedBoard.architecture &&
                inst.boards.any { it.fqbn == selectedBoard.fqbn }
        }
    }

    val optionsSummary = remember(boardInfo, boardMenus, boardOptions, isBoardReady) {
        if (!isBoardReady || boardInfo == null) return@remember ""
        val specs = ArrayList<String>()
        if (boardInfo.mcu.isNotBlank()) specs += boardInfo.mcu
        if (boardInfo.fCpu.isNotBlank()) {
            val digits = boardInfo.fCpu.trim().removeSuffix("L").removeSuffix("l").removeSuffix("UL").removeSuffix("ul")
            val hz = digits.toLongOrNull()
            specs += if (hz != null && hz >= 1_000_000L && hz % 1_000_000L == 0L) {
                "${hz / 1_000_000L} MHz"
            } else {
                boardInfo.fCpu
            }
        }
        val priority = listOf("CPUFreq", "cpu", "DebugLevel", "UploadSpeed", "FlashSize")
        for (id in priority) {
            if (specs.size >= 3) break
            val group = boardMenus.firstOrNull { it.id == id } ?: continue
            val activeId = boardOptions[id] ?: group.defaultOptionId
            val label = group.options.firstOrNull { it.id == activeId }?.label ?: continue
            specs += "${group.label.substringBefore(' ')}: ${label.substringBefore(" (")}"
        }
        if (specs.size < 3 && boardInfo.uploadMaximumSize > 0L) {
            specs += "Flash ${boardInfo.uploadMaximumSize / 1024}KB"
        }
        specs.take(3).joinToString(" \u00B7 ")
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 8.dp, top = 24.dp, bottom = 10.dp),
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
                    Icons.Outlined.DeveloperBoard,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Boards",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (!isBoardReady) {
                        if (!hasInstalledCores) "No board core downloaded" else "No board selected"
                    } else {
                        "${selectedBoard.name} (${selectedBoard.fqbn})"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onManageUrls) {
                Icon(Icons.Outlined.Settings, contentDescription = "Board manager URLs")
            }
            IconButton(onClick = onRefresh, enabled = !loading) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Refresh board index")
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                if (!hasInstalledCores) {
                    Text(
                        text = "Download any board core below (AVR, ESP32, ESP8266, RP2040, SAMD, STM32, UNO R4...) to unlock board selection & options.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                } else if (optionsSummary.isNotBlank()) {
                    Text(
                        text = optionsSummary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onSwitchBoard,
                        enabled = hasInstalledCores,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                    ) {
                        Icon(
                            Icons.Outlined.SwapHoriz,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Switch board", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                    FilledTonalButton(
                        onClick = onOpenBoardOptions,
                        enabled = isBoardReady,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Board options", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            singleLine = true,
            placeholder = { Text("Search boards, cores, MCU, architecture...") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Clear search")
                    }
                }
            }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BoardFilter.entries.forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { onFilterChange(option) },
                    label = {
                        Text(
                            if (option == BoardFilter.INSTALLED) "${option.label} (${installed.size})"
                            else option.label
                        )
                    }
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        when {
            loading && catalog.isEmpty() -> Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Loading board catalog...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            error != null && catalog.isEmpty() -> Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Outlined.CloudOff,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                FilledTonalButton(onClick = onRefresh) { Text("Retry") }
            }

            filtered.isEmpty() -> Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (filter == BoardFilter.INSTALLED && installed.isEmpty()) {
                        "No board cores downloaded yet."
                    } else {
                        "No matching board cores found."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { it.id }) { platform ->
                    val inst = installed[platform.id]
                    BoardPlatformRow(
                        platform = platform,
                        installed = inst,
                        downloading = platform.id in downloading,
                        onDownload = { onDownload(platform) },
                        onPickBoard = { inst?.let(onPickBoard) },
                        onUninstall = { inst?.let(onUninstall) }
                    )
                }
            }
        }
    }
}

@Composable
internal fun BoardPlatformRow(
    platform: BoardPlatform,
    installed: InstalledPlatform?,
    downloading: Boolean,
    onDownload: () -> Unit,
    onPickBoard: () -> Unit,
    onUninstall: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = installed != null, onClick = onPickBoard)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = platform.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "v${installed?.version ?: platform.version}  \u00B7  ${platform.id}  \u00B7  ${platform.maintainer}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (installed != null) {
                    IconButton(onClick = onUninstall, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            contentDescription = "Remove ${platform.name}",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            val previewBoards = installed?.boards?.take(4)?.joinToString(", ") { it.name }
                ?: platform.boardNames.take(4).joinToString(", ")
            if (previewBoards.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Includes: $previewBoards" +
                        if ((installed?.boards?.size ?: platform.boardNames.size) > 4) "..." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (downloading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Downloading core...", style = MaterialTheme.typography.labelMedium)
                } else if (installed != null) {
                    FilledTonalButton(
                        onClick = onPickBoard,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(
                            Icons.Outlined.DeveloperBoard,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Select board (${installed.boards.size})", style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    val sizeLabel = if (platform.size > 1_048_576L) {
                        "%.1f MB".format(platform.size / 1_048_576.0)
                    } else if (platform.size > 0L) {
                        formatSize(platform.size)
                    } else {
                        "Download"
                    }
                    OutlinedButton(
                        onClick = onDownload,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Download,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(sizeLabel, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
internal fun BoardIndexUrlsDialog(
    builtInUrls: List<String>,
    customUrls: List<String>,
    onSaveCustomUrls: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var draftText by remember(customUrls) { mutableStateOf(customUrls.joinToString("\n")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
        title = { Text("Board Manager URLs") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Built-in indexes (${builtInUrls.size}): Arduino, ESP32, ESP8266, RP2040, STM32, Adafruit, SparkFun, Seeed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Additional package_*.json URLs (one per line):",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                OutlinedTextField(
                    value = draftText,
                    onValueChange = { draftText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp, max = 180.dp),
                    placeholder = { Text("https://example.com/package_board_index.json") },
                    textStyle = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = draftText.lines()
                    .map { it.trim() }
                    .filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
                    .distinct()
                onSaveCustomUrls(parsed)
                onDismiss()
            }) { Text("Save & refresh") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

