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
import androidx.compose.foundation.verticalScroll
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
import com.h0lymarc.eside.manager.ArduinoLibrary
import com.h0lymarc.eside.manager.Board
import com.h0lymarc.eside.manager.InstalledLibrary

@Composable
internal fun DrawerLibrariesTab(
    board: Board,
    catalog: List<ArduinoLibrary>,
    installed: Map<String, InstalledLibrary>,
    query: String,
    onQueryChange: (String) -> Unit,
    filter: LibraryFilter,
    onFilterChange: (LibraryFilter) -> Unit,
    loading: Boolean,
    error: String?,
    downloading: Set<String>,
    onRefresh: () -> Unit,
    onDownload: (ArduinoLibrary) -> Unit,
    onImport: (InstalledLibrary) -> Unit,
    onUninstall: (String) -> Unit,
    onDetails: (ArduinoLibrary) -> Unit
) {
    val filtered by remember(catalog, installed, query, filter, board) {
        derivedStateOf {
            val q = query.trim().lowercase()
            catalog.asSequence()
                .filter { lib ->
                    when (filter) {
                        LibraryFilter.ALL -> true
                        LibraryFilter.INSTALLED -> lib.name in installed
                        LibraryFilter.COMPATIBLE -> lib.supports(board)
                    }
                }
                .filter { lib ->
                    q.isEmpty() ||
                        lib.name.lowercase().contains(q) ||
                        lib.sentence.lowercase().contains(q) ||
                        lib.author.lowercase().contains(q)
                }
                .toList()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 8.dp, top = 24.dp, bottom = 12.dp),
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
                    Icons.Outlined.Extension,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Libraries",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${installed.size} installed  \u00B7  $board",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRefresh, enabled = !loading) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Refresh library index")
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            singleLine = true,
            placeholder = { Text("Search Arduino libraries...") },
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
            LibraryFilter.entries.forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { onFilterChange(option) },
                    label = {
                        Text(
                            if (option == LibraryFilter.INSTALLED) "${option.label} (${installed.size})"
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
                        text = "Loading official Arduino library index...",
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
                    text = if (filter == LibraryFilter.INSTALLED && installed.isEmpty()) {
                        "No libraries downloaded yet."
                    } else {
                        "No matching libraries found."
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
                items(filtered, key = { it.name }) { lib ->
                    val inst = installed[lib.name]
                    LibraryRow(
                        lib = lib,
                        installed = inst,
                        downloading = lib.name in downloading,
                        compatible = lib.supports(board),
                        onClick = { onDetails(lib) },
                        onDownload = { onDownload(lib) },
                        onImport = { inst?.let(onImport) },
                        onUninstall = { onUninstall(lib.name) }
                    )
                }
            }
        }
    }
}

@Composable
internal fun LibraryRow(
    lib: ArduinoLibrary,
    installed: InstalledLibrary?,
    downloading: Boolean,
    compatible: Boolean,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onImport: () -> Unit,
    onUninstall: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = lib.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "v${installed?.version ?: lib.version}  \u00B7  ${lib.category}" +
                            if (!compatible) "  \u00B7  Other arch" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (compatible) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (installed != null) {
                    IconButton(onClick = onUninstall, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            contentDescription = "Remove ${lib.name}",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (lib.sentence.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = lib.sentence,
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
                    Text("Installing...", style = MaterialTheme.typography.labelMedium)
                } else if (installed != null) {
                    FilledTonalButton(
                        onClick = onImport,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Import", style = MaterialTheme.typography.labelMedium)
                    }
                } else {
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
                        Text(formatSize(lib.size), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
internal fun LibraryDetailsDialog(
    lib: ArduinoLibrary,
    installed: InstalledLibrary?,
    downloading: Boolean,
    canCopyToProject: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onImportInclude: () -> Unit,
    onCopyToSrc: () -> Unit,
    onUninstall: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Extension, contentDescription = null) },
        title = { Text(lib.name) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (lib.sentence.isNotBlank()) {
                    Text(lib.sentence, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    text = "Version: ${lib.version} (${formatSize(lib.size)})\n" +
                        "Category: ${lib.category}\n" +
                        "Author: ${lib.author.ifBlank { "Unknown" }}\n" +
                        "Architectures: ${lib.architectures.joinToString(", ").ifBlank { "*" }}" +
                        if (lib.dependencies.isNotEmpty()) {
                            "\nDependencies: ${lib.dependencies.joinToString(", ")}"
                        } else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (installed != null && canCopyToProject) {
                    HorizontalDivider()
                    TextButton(
                        onClick = onCopyToSrc,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            Icons.Outlined.FolderCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Copy sources into project src/")
                    }
                }
            }
        },
        confirmButton = {
            when {
                downloading -> TextButton(onClick = {}, enabled = false) { Text("Installing...") }
                installed != null -> TextButton(onClick = onImportInclude) { Text("Import #include") }
                else -> TextButton(onClick = onDownload) { Text("Download") }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (installed != null) {
                    TextButton(onClick = onUninstall) {
                        Text("Uninstall", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}
