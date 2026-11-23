package com.musico.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musico.i18n.AppLanguage
import com.musico.i18n.LocalStrings
import com.musico.store.CloudDownloadJob

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initialValue: String = "",
    supportingText: String? = null,
    confirmLabel: String? = null,
    allowBlankWithDefault: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val strings = LocalStrings.current
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
                Text(confirmLabel ?: strings.save)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.cancel)
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
    val strings = LocalStrings.current
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
                Text(strings.cancel)
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
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                items(options, key = { it.id }) { option ->
                    ListItem(
                        headlineContent = {
                            Text(
                                text = option.title,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
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
                Text(strings.cancel)
            }
        }
    )
}

@Composable
fun LanguageDialog(
    current: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val options = listOf(
        AppLanguage.SYSTEM to strings.languageFollowSystem,
        AppLanguage.ENGLISH to strings.languageEnglish,
        AppLanguage.SIMPLIFIED_CHINESE to strings.languageSimplifiedChinese,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Language, contentDescription = null) },
        title = { Text(strings.languageTitle) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                options.forEach { (lang, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(lang)
                                onDismiss()
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = current == lang,
                            onClick = {
                                onSelect(lang)
                                onDismiss()
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.close)
            }
        },
    )
}

@Composable
fun CloudTransferProgressSection(
    job: CloudDownloadJob,
    statusLine: String? = null,
    modifier: Modifier = Modifier,
) {
    val hasDeterminate = job.totalTracks > 0 || job.trackTotalBytes != null || job.overallFraction > 0f
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (hasDeterminate) {
            LinearProgressIndicator(
                progress = { job.overallFraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        val bytesText = when {
            job.trackTotalBytes != null ->
                "${formatBytes(job.trackBytesDownloaded)} / ${formatBytes(job.trackTotalBytes)}"
            job.trackBytesDownloaded > 0L -> formatBytes(job.trackBytesDownloaded)
            else -> null
        }

        if (bytesText != null || job.bytesPerSecond > 0.0 || hasDeterminate) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = bytesText ?: statusLine.orEmpty(),
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (job.bytesPerSecond > 0.0) {
                        Text(
                            text = formatTransferSpeed(job.bytesPerSecond),
                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (hasDeterminate) {
                        Text(
                            text = formatPercentage(job.overallFraction),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontFeatureSettings = "tnum",
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (statusLine != null && (bytesText != null || !hasDeterminate)) {
            Text(
                text = statusLine,
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
