package com.musico.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musico.update.UpdateManager
import com.musico.update.UpdateUiState
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun UpdateDialog(
    updateManager: UpdateManager,
    onDismiss: () -> Unit,
) {
    val state by updateManager.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentVersion = updateManager.currentVersion

    fun openReleasePage(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    val isDownloading = state is UpdateUiState.Downloading

    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        icon = {
            val icon = when (state) {
                is UpdateUiState.UpToDate -> Icons.Rounded.CheckCircle
                is UpdateUiState.Error -> Icons.Rounded.ErrorOutline
                else -> Icons.Rounded.SystemUpdate
            }
            Icon(imageVector = icon, contentDescription = null)
        },
        title = {
            val titleText = when (state) {
                is UpdateUiState.Idle,
                is UpdateUiState.Checking -> "Checking for Updates"
                is UpdateUiState.UpToDate -> "You’re Up to Date"
                is UpdateUiState.Available -> "Update Available"
                is UpdateUiState.Downloading -> "Downloading Update"
                is UpdateUiState.ReadyToInstall -> "Ready to Install"
                is UpdateUiState.Error -> "Update Error"
            }
            Text(titleText)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (val current = state) {
                    is UpdateUiState.Idle,
                    is UpdateUiState.Checking -> {
                        Text(
                            text = "Current version: $currentVersion",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = "Checking GitHub Releases…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    is UpdateUiState.UpToDate -> {
                        Text(
                            text = "Musico $currentVersion is the latest version.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(
                            onClick = { openReleasePage(UpdateManager.RELEASES_PAGE_URL) },
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Open Release Page")
                        }
                    }

                    is UpdateUiState.Available -> {
                        val release = current.release
                        VersionTransitionRow(
                            currentVersion = currentVersion,
                            latestVersion = release.version.toString(),
                            sizeBytes = release.apkAsset.sizeBytes,
                        )
                        ReleaseNotesBox(notes = release.body)
                        TextButton(
                            onClick = { openReleasePage(release.htmlUrl) },
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Open Release Page")
                        }
                    }

                    is UpdateUiState.Downloading -> {
                        val release = current.release
                        VersionTransitionRow(
                            currentVersion = currentVersion,
                            latestVersion = release.version.toString(),
                            sizeBytes = release.apkAsset.sizeBytes,
                        )
                        if (current.progress >= 0f) {
                            LinearProgressIndicator(
                                progress = { current.progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = "Downloading ${release.apkAsset.name} (${(current.progress * 100).toInt()}%)…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                text = "Downloading ${release.apkAsset.name}…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    is UpdateUiState.ReadyToInstall -> {
                        val release = current.release
                        VersionTransitionRow(
                            currentVersion = currentVersion,
                            latestVersion = release.version.toString(),
                            sizeBytes = release.apkAsset.sizeBytes,
                        )
                        Text(
                            text = "Package verified and ready to install.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        ReleaseNotesBox(notes = release.body)
                        TextButton(
                            onClick = { openReleasePage(release.htmlUrl) },
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Open Release Page")
                        }
                    }

                    is UpdateUiState.Error -> {
                        Text(
                            text = current.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = "Current version: $currentVersion",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = { openReleasePage(UpdateManager.RELEASES_PAGE_URL) },
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Open Release Page")
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (val current = state) {
                is UpdateUiState.Available -> {
                    Button(
                        onClick = {
                            scope.launch {
                                val result = updateManager.downloadAndVerify(current.release)
                                result.onSuccess { apkFile ->
                                    updateManager.installApk(context, apkFile)
                                }
                            }
                        },
                    ) {
                        Text("Download & Install")
                    }
                }

                is UpdateUiState.ReadyToInstall -> {
                    Button(
                        onClick = {
                            updateManager.installApk(context, current.apkFile)
                        },
                    ) {
                        Text("Install")
                    }
                }

                is UpdateUiState.UpToDate -> {
                    TextButton(
                        onClick = {
                            scope.launch { updateManager.checkForUpdates(silent = false) }
                        },
                    ) {
                        Text("Check Again")
                    }
                }

                is UpdateUiState.Error -> {
                    TextButton(
                        onClick = {
                            scope.launch { updateManager.checkForUpdates(silent = false) }
                        },
                    ) {
                        Text("Retry")
                    }
                }

                is UpdateUiState.Idle,
                is UpdateUiState.Checking,
                is UpdateUiState.Downloading -> {
                    // No primary confirm action while checking/downloading.
                }
            }
        },
        dismissButton = {
            when (val current = state) {
                is UpdateUiState.Available -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = {
                                updateManager.skipVersion(current.release.version.toString())
                                onDismiss()
                            },
                        ) {
                            Text("Skip Version")
                        }
                        TextButton(onClick = onDismiss) {
                            Text("Later")
                        }
                    }
                }

                is UpdateUiState.Downloading -> {
                    TextButton(onClick = onDismiss) {
                        Text("Hide")
                    }
                }

                else -> {
                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }
            }
        },
    )
}

@Composable
private fun VersionTransitionRow(
    currentVersion: String,
    latestVersion: String,
    sizeBytes: Long,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                text = "$currentVersion → $latestVersion",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        if (sizeBytes > 0L) {
            Text(
                text = formatBytes(sizeBytes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReleaseNotesBox(notes: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            Text(
                text = notes.ifBlank { "No release notes provided." },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.1f MB", mb)
}
