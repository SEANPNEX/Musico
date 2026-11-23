package com.musico.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musico.extensions.ExtensionCapability
import com.musico.extensions.ExtensionLogEntry
import com.musico.extensions.ExtensionManifest
import com.musico.extensions.ExtensionSettingSpec
import com.musico.extensions.ExtensionSettingType
import com.musico.extensions.ExtensionStore
import com.musico.extensions.InstalledExtension
import com.musico.extensions.StagedExtensionPackage
import com.musico.i18n.AppStrings
import com.musico.i18n.LocalStrings
import com.musico.ui.components.ConfirmDialog
import com.musico.ui.components.EmptyState
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(
    extensionStore: ExtensionStore,
    onPickExtensionFile: ((Uri) -> Unit) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val extensions by extensionStore.extensions.collectAsStateWithLifecycle()
    val allLogs by extensionStore.logs.collectAsStateWithLifecycle()

    var isStaging by remember { mutableStateOf(false) }
    var stagedForReview by remember { mutableStateOf<StagedExtensionPackage?>(null) }
    var showImportUrlDialog by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var settingsTargetId by remember { mutableStateOf<String?>(null) }
    var logsTargetId by remember { mutableStateOf<String?>(null) }
    var permissionsTarget by remember { mutableStateOf<ExtensionManifest?>(null) }
    var uninstallTarget by remember { mutableStateOf<InstalledExtension?>(null) }

    BackHandler(onBack = onBack)

    fun launchFileImport() {
        onPickExtensionFile { uri ->
            isStaging = true
            scope.launch {
                val res = extensionStore.stageFromUri(context, uri)
                isStaging = false
                res.onSuccess { staged ->
                    stagedForReview = staged
                }.onFailure { err ->
                    importError = err.message ?: strings.extensionImportFailedFallback
                }
            }
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = strings.back)
                    }
                },
                title = { Text(strings.extensionsTitle) },
                actions = {
                    IconButton(
                        enabled = !isStaging,
                        onClick = { showImportUrlDialog = true },
                    ) {
                        Icon(Icons.Rounded.Link, contentDescription = strings.importFromUrl)
                    }
                    IconButton(
                        enabled = !isStaging,
                        onClick = { launchFileImport() },
                    ) {
                        Icon(Icons.Rounded.UploadFile, contentDescription = strings.importFromFile)
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (isStaging) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (extensions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        EmptyState(
                            icon = Icons.Rounded.Extension,
                            title = strings.noExtensionsTitle,
                            description = strings.noExtensionsDesc,
                            actionLabel = strings.importFromFile,
                            onAction = { launchFileImport() },
                        )
                        OutlinedButton(
                            enabled = !isStaging,
                            onClick = { showImportUrlDialog = true },
                        ) {
                            Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(strings.importFromUrl)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(extensions, key = { it.id }) { ext ->
                        val extLogs = allLogs[ext.id].orEmpty()
                        ExtensionItemCard(
                            extension = ext,
                            logCount = extLogs.size,
                            strings = strings,
                            onToggleEnabled = { enabled ->
                                extensionStore.setEnabled(ext.id, enabled)
                            },
                            onOpenPermissions = { permissionsTarget = ext.manifest },
                            onOpenSettings = { settingsTargetId = ext.id },
                            onOpenLogs = { logsTargetId = ext.id },
                            onRequestUninstall = { uninstallTarget = ext },
                        )
                    }
                }
            }
        }
    }

    if (showImportUrlDialog) {
        ImportExtensionUrlDialog(
            onDismiss = { showImportUrlDialog = false },
            onImport = { url -> extensionStore.stageFromUrl(url) },
            onStaged = { staged ->
                showImportUrlDialog = false
                stagedForReview = staged
            },
        )
    }

    val pendingReview = stagedForReview
    if (pendingReview != null) {
        PermissionReviewDialog(
            manifest = pendingReview.manifest,
            strings = strings,
            isInteractiveInstall = true,
            onDismiss = { stagedForReview = null },
            onConfirmInstall = {
                extensionStore.installStaged(pendingReview, enableImmediately = true)
                stagedForReview = null
            },
        )
    }

    val permManifest = permissionsTarget
    if (permManifest != null) {
        PermissionReviewDialog(
            manifest = permManifest,
            strings = strings,
            isInteractiveInstall = false,
            onDismiss = { permissionsTarget = null },
            onConfirmInstall = { permissionsTarget = null },
        )
    }

    val activeSettingsExt = settingsTargetId?.let { id -> extensions.firstOrNull { it.id == id } }
    if (activeSettingsExt != null) {
        ExtensionSettingsDialog(
            extension = activeSettingsExt,
            strings = strings,
            onUpdateSetting = { key, value ->
                extensionStore.updateSetting(activeSettingsExt.id, key, value)
            },
            onResetDefaults = {
                extensionStore.resetSettings(activeSettingsExt.id)
            },
            onDismiss = { settingsTargetId = null },
        )
    }

    val activeLogsExt = logsTargetId?.let { id -> extensions.firstOrNull { it.id == id } }
    if (activeLogsExt != null) {
        val entries = allLogs[activeLogsExt.id].orEmpty()
        ExtensionLogsDialog(
            extensionName = activeLogsExt.manifest.name,
            logs = entries,
            strings = strings,
            onClear = { extensionStore.clearLogs(activeLogsExt.id) },
            onDismiss = { logsTargetId = null },
        )
    }

    val pendingUninstall = uninstallTarget
    if (pendingUninstall != null) {
        ConfirmDialog(
            title = strings.uninstallExtensionConfirmTitle(pendingUninstall.manifest.name),
            message = strings.uninstallExtensionMessage,
            confirmLabel = strings.uninstall,
            onDismiss = { uninstallTarget = null },
            onConfirm = {
                extensionStore.uninstall(pendingUninstall.id)
                uninstallTarget = null
            },
        )
    }

    val err = importError
    if (err != null) {
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text(strings.extensionImportFailedTitle) },
            text = { Text(err) },
            confirmButton = {
                TextButton(onClick = { importError = null }) {
                    Text(strings.ok)
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExtensionItemCard(
    extension: InstalledExtension,
    logCount: Int,
    strings: AppStrings,
    onToggleEnabled: (Boolean) -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLogs: () -> Unit,
    onRequestUninstall: () -> Unit,
) {
    val manifest = extension.manifest
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = manifest.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.extraSmall,
                        ) {
                            Text(
                                text = manifest.effectiveBadgeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Text(
                        text = strings.extensionVersionAuthor(manifest.version, manifest.author),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = manifest.id,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Switch(
                    checked = extension.enabled,
                    onCheckedChange = onToggleEnabled,
                )
            }

            manifest.description?.takeIf { it.isNotBlank() }?.let { desc ->
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                manifest.capabilities.forEach { cap ->
                    AssistChip(
                        onClick = onOpenPermissions,
                        label = { Text(capabilityLabel(cap, strings)) },
                    )
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = onOpenPermissions) {
                        Icon(Icons.Rounded.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(strings.permissionsLabel)
                    }
                    if (manifest.settings.isNotEmpty()) {
                        TextButton(onClick = onOpenSettings) {
                            Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(strings.settingsLabel)
                        }
                    }
                    TextButton(onClick = onOpenLogs) {
                        Icon(Icons.Rounded.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (logCount > 0) strings.extensionLogsCountLabel(logCount) else strings.logsLabel)
                    }
                }
                IconButton(onClick = onRequestUninstall) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = strings.uninstall,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportExtensionUrlDialog(
    onDismiss: () -> Unit,
    onImport: suspend (String) -> Result<StagedExtensionPackage>,
    onStaged: (StagedExtensionPackage) -> Unit,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        icon = { Icon(Icons.Rounded.Link, contentDescription = null) },
        title = { Text(strings.importExtensionUrlTitle) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        errorText = null
                    },
                    label = { Text(strings.extensionUrlLabel) },
                    placeholder = { Text(strings.extensionUrlPlaceholder) },
                    supportingText = { Text(strings.extensionUrlSupportingText) },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                errorText?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.trim().isNotEmpty() && !isLoading,
                onClick = {
                    isLoading = true
                    errorText = null
                    scope.launch {
                        val res = onImport(url)
                        isLoading = false
                        res.onSuccess(onStaged).onFailure { err ->
                            errorText = err.message ?: strings.extensionImportFailedFallback
                        }
                    }
                },
            ) {
                Text(strings.add)
            }
        },
        dismissButton = {
            TextButton(enabled = !isLoading, onClick = onDismiss) {
                Text(strings.cancel)
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PermissionReviewDialog(
    manifest: ExtensionManifest,
    strings: AppStrings,
    isInteractiveInstall: Boolean,
    onDismiss: () -> Unit,
    onConfirmInstall: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Security, contentDescription = null) },
        title = { Text(if (isInteractiveInstall) strings.permissionReviewTitle else strings.permissionsLabel) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = manifest.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = strings.extensionVersionAuthor(manifest.version, manifest.author),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = manifest.id,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (isInteractiveInstall) {
                    ElevatedCard(
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.WarningAmber,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = strings.permissionReviewWarning,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = strings.capabilitiesLabel,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        manifest.capabilities.forEach { cap ->
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text(
                                    text = capabilityLabel(cap, strings),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = strings.allowedHostsLabel,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    manifest.permissions.hosts.forEach { host ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = host,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (isInteractiveInstall) {
                Button(onClick = onConfirmInstall) {
                    Text(strings.installAndEnable)
                }
            } else {
                TextButton(onClick = onConfirmInstall) {
                    Text(strings.close)
                }
            }
        },
        dismissButton = {
            if (isInteractiveInstall) {
                TextButton(onClick = onDismiss) {
                    Text(strings.cancel)
                }
            }
        },
    )
}

@Composable
private fun ExtensionSettingsDialog(
    extension: InstalledExtension,
    strings: AppStrings,
    onUpdateSetting: (key: String, value: JsonPrimitive) -> Unit,
    onResetDefaults: () -> Unit,
    onDismiss: () -> Unit,
) {
    val overrides = extension.state.settings
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
        title = { Text("${extension.manifest.name} — ${strings.extensionSettingsTitle}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                extension.manifest.settings.forEach { spec ->
                    SettingFieldRow(
                        spec = spec,
                        overrides = overrides,
                        onUpdate = { value -> onUpdateSetting(spec.key, value) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.done)
            }
        },
        dismissButton = {
            TextButton(onClick = onResetDefaults) {
                Text(strings.resetDefaults)
            }
        },
    )
}

@Composable
private fun SettingFieldRow(
    spec: ExtensionSettingSpec,
    overrides: Map<String, JsonPrimitive>,
    onUpdate: (JsonPrimitive) -> Unit,
) {
    when (spec.type) {
        ExtensionSettingType.BOOLEAN -> {
            val checked = spec.resolveBoolean(overrides)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onUpdate(JsonPrimitive(!checked)) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = spec.label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp),
                )
                Switch(
                    checked = checked,
                    onCheckedChange = { onUpdate(JsonPrimitive(it)) },
                )
            }
        }
        ExtensionSettingType.STRING -> {
            val current = spec.resolveString(overrides)
            OutlinedTextField(
                value = current,
                onValueChange = { onUpdate(JsonPrimitive(it)) },
                label = { Text(spec.label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ExtensionSettingType.SELECT -> {
            val currentVal = spec.resolveString(overrides)
            val currentOption = spec.options.firstOrNull { it.value == currentVal } ?: spec.options.firstOrNull()
            var expanded by remember { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = spec.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box {
                    FilledTonalButton(
                        onClick = { expanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = currentOption?.label ?: currentVal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        spec.options.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                leadingIcon = if (option.value == currentVal) {
                                    { Icon(Icons.Rounded.Check, contentDescription = null) }
                                } else null,
                                onClick = {
                                    onUpdate(JsonPrimitive(option.value))
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtensionLogsDialog(
    extensionName: String,
    logs: List<ExtensionLogEntry>,
    strings: AppStrings,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val timeFormatter = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Description, contentDescription = null) },
        title = { Text("$extensionName — ${strings.extensionLogsTitle}") },
        text = {
            if (logs.isEmpty()) {
                Text(
                    text = strings.noLogsYet,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(logs.asReversed()) { entry ->
                        val levelColor = when (entry.level.lowercase()) {
                            "error" -> MaterialTheme.colorScheme.error
                            "warn" -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = entry.level.uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = levelColor,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = timeFormatter.format(Date(entry.timestampMs)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    text = entry.message,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.close)
            }
        },
        dismissButton = {
            TextButton(enabled = logs.isNotEmpty(), onClick = onClear) {
                Text(strings.clearLogs)
            }
        },
    )
}

private fun capabilityLabel(cap: ExtensionCapability, strings: AppStrings): String = when (cap) {
    ExtensionCapability.PLAYLIST -> strings.capabilityPlaylist
    ExtensionCapability.TREE -> strings.capabilityTree
    ExtensionCapability.STREAM -> strings.capabilityStream
    ExtensionCapability.COOKIE -> strings.capabilityCookie
    ExtensionCapability.PROXY -> strings.capabilityProxy
    ExtensionCapability.LYRICS -> strings.capabilityLyrics
}
