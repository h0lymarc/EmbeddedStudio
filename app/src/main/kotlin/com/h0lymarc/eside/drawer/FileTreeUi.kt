package com.h0lymarc.eside.drawer

import com.h0lymarc.eside.*

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.h0lymarc.eside.manager.TreeEntry
import java.io.File

@Composable
internal fun TreeRow(
    entry: TreeEntry,
    isMain: Boolean,
    selected: Boolean,
    expanded: Boolean,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleExpand: () -> Unit,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onDelete: () -> Unit
) {
    val file = entry.file
    val isDir = file.isDirectory
    val style = if (isDir) FileStyle(Icons.Outlined.Folder, Color(0xFFFFC857)) else styleFor(file)
    val detail = if (isDir) {
        val n = file.listFiles()?.size ?: 0
        "$n ${if (n == 1) "item" else "items"}"
    } else {
        formatSize(file.length())
    }
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    checked -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                    selected -> MaterialTheme.colorScheme.secondaryContainer
                    else -> Color.Transparent
                }
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = (4 + entry.depth * 16).dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isDir) {
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(24.dp)
                    .then(if (selecting) Modifier.clickable(onClick = onToggleExpand) else Modifier)
            )
        } else {
            Spacer(Modifier.width(24.dp))
        }

        Spacer(Modifier.width(4.dp))

        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(style.color.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = style.icon,
                contentDescription = null,
                tint = style.color,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        when {
            isMain -> Spacer(Modifier.width(48.dp))

            selecting -> Checkbox(
                checked = checked,
                onCheckedChange = { onClick() }
            )

            else -> Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "Options")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (isDir) {
                        DropdownMenuItem(
                            text = { Text("New file") },
                            leadingIcon = { Icon(Icons.Outlined.NoteAdd, contentDescription = null) },
                            onClick = { menuOpen = false; onNewFile() }
                        )
                        DropdownMenuItem(
                            text = { Text("New folder") },
                            leadingIcon = {
                                Icon(Icons.Outlined.CreateNewFolder, contentDescription = null)
                            },
                            onClick = { menuOpen = false; onNewFolder() }
                        )
                        HorizontalDivider()
                    }
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
internal fun SelectionHeader(
    count: Int,
    onCancel: () -> Unit,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 24.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onCancel) {
            Icon(Icons.Outlined.Close, contentDescription = "Cancel selection")
        }
        Text(
            text = "$count selected",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onSelectAll) {
            Icon(Icons.Outlined.SelectAll, contentDescription = "Select all")
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.DeleteOutline,
                contentDescription = "Delete selected",
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
internal fun NewEntryDialog(
    folder: Boolean,
    parentLabel: String,
    onCancel: () -> Unit,
    onConfirm: (name: String, extension: String) -> String?
) {
    var name by rememberSaveable { mutableStateOf("") }
    var extension by rememberSaveable { mutableStateOf(".ino") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(if (folder) "New folder" else "New file") },
        text = {
            Column {
                Text(
                    text = "in $parentLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    singleLine = true,
                    label = { Text(if (folder) "Folder name" else "File name") },
                    isError = error != null,
                    supportingText = { Text(error ?: "Letters, numbers, _ . and - only") }
                )
                if (!folder) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(".ino", ".cpp", ".h").forEach { option ->
                            FilterChip(
                                selected = extension == option,
                                onClick = { extension = option },
                                label = { Text(option) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { error = onConfirm(name.trim(), extension) }) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    )
}
