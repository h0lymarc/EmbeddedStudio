package com.h0lymarc.eside

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import java.io.File

@Composable
internal fun UnsavedChangesDialog(
    action: PendingCloseAction,
    dirtyPaths: Set<String>,
    projectName: String?,
    onCancel: () -> Unit,
    onDiscard: () -> Unit,
    onSave: () -> Unit
) {
    val dirtyFiles = remember(action, dirtyPaths) {
        when (action) {
            is PendingCloseAction.SingleTab -> listOf(File(action.path))
            is PendingCloseAction.BatchTabs ->
                action.pathsToClose.filter { it in dirtyPaths }.map { File(it) }
            PendingCloseAction.Project -> dirtyPaths.map { File(it) }
        }
    }
    val isSingle = action is PendingCloseAction.SingleTab || dirtyFiles.size == 1
    val body = when (action) {
        is PendingCloseAction.SingleTab ->
            "\"${File(action.path).name}\" has unsaved changes. Save before closing?"
        is PendingCloseAction.BatchTabs -> {
            val preview = dirtyFiles.take(5).joinToString("\n") { "\u2022 ${it.name}" } +
                if (dirtyFiles.size > 5) "\n...and ${dirtyFiles.size - 5} more" else ""
            "These ${dirtyFiles.size} files have unsaved changes:\n\n$preview\n\nSave before closing?"
        }
        PendingCloseAction.Project -> {
            val preview = dirtyFiles.take(5).joinToString("\n") { "\u2022 ${it.name}" } +
                if (dirtyFiles.size > 5) "\n...and ${dirtyFiles.size - 5} more" else ""
            "\"${projectName ?: "Project"}\" has unsaved changes:\n\n$preview\n\nSave before closing?"
        }
    }

    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Outlined.Save, contentDescription = null) },
        title = { Text("Save changes?") },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onSave) {
                Text(if (isSingle) "Save" else "Save all")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                TextButton(onClick = onDiscard) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    )
}


@Composable
internal fun ProjectTitleDialog(
    onCancel: () -> Unit,
    onConfirm: (String) -> String?
) {
    var name by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text("Project title") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; error = null },
                singleLine = true,
                label = { Text("Title") },
                isError = error != null,
                supportingText = { Text(error ?: "Letters, numbers, _ and - only") }
            )
        },
        confirmButton = {
            TextButton(onClick = { error = onConfirm(name.trim()) }) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    )
}
