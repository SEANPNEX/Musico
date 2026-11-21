package com.musico.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initialValue: String = "",
    supportingText: String? = null,
    confirmLabel: String = "Save",
    allowBlankWithDefault: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    val trimmed = value.trim()
    val canConfirm = trimmed.isNotEmpty() || allowBlankWithDefault != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (supportingText != null) {
                    Text(supportingText)
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(label) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = {
                    val result = if (trimmed.isEmpty()) allowBlankWithDefault ?: "" else trimmed
                    onConfirm(result)
                    onDismiss()
                }
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                }
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

data class PickerOption(
    val id: String,
    val title: String,
    val subtitle: String? = null
)

@Composable
fun OptionPickerDialog(
    title: String,
    options: List<PickerOption>,
    extraActionLabel: String? = null,
    onExtraAction: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                items(options, key = { it.id }) { option ->
                    ListItem(
                        headlineContent = { Text(option.title) },
                        supportingContent = option.subtitle?.let { sub -> { Text(sub) } },
                        modifier = Modifier.clickable {
                            onSelect(option.id)
                            onDismiss()
                        }
                    )
                }
                if (extraActionLabel != null && onExtraAction != null) {
                    item {
                        HorizontalDivider()
                        ListItem(
                            headlineContent = { Text(extraActionLabel) },
                            leadingContent = {
                                Icon(Icons.Rounded.Add, contentDescription = null)
                            },
                            modifier = Modifier.clickable {
                                onDismiss()
                                onExtraAction()
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
