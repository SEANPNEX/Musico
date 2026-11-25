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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musico.extensions.ExtQrLoginPollResult
import com.musico.extensions.ExtQrLoginSession
import com.musico.extensions.ExtQrLoginStatus
import com.musico.extensions.ExtensionCapability
import com.musico.extensions.ExtensionLogEntry
import com.musico.extensions.ExtensionManifest
import com.musico.extensions.ExtensionQualityOption
import com.musico.extensions.ExtensionSettingSpec
import com.musico.extensions.ExtensionSettingType
import com.musico.extensions.ExtensionStore
import com.musico.extensions.ExtensionUpdateCheckOutcome
import com.musico.extensions.InstalledExtension
import com.musico.extensions.PendingExtensionUpdate
import com.musico.extensions.StagedExtensionPackage
import com.musico.i18n.AppStrings
import com.musico.i18n.LocalStrings
import com.musico.ui.components.ConfirmDialog
import com.musico.ui.components.EmptyState
import com.musico.ui.components.QrCodeCanvas
import com.musico.ui.components.QrCodeEncoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(
    extensionStore: ExtensionStore,
    onPickExtensionFiles: ((List<Uri>) -> Unit) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val extensions by extensionStore.extensions.collectAsStateWithLifecycle()
    val allLogs by extensionStore.logs.collectAsStateWithLifecycle()
    val autoUpdateEnabled by extensionStore.autoUpdateEnabled.collectAsStateWithLifecycle()
    val isCheckingUpdates by extensionStore.isCheckingUpdates.collectAsStateWithLifecycle()
    val pendingUpdates by extensionStore.pendingUpdates.collectAsStateWithLifecycle()
    val updateOutcomes by extensionStore.updateOutcomes.collectAsStateWithLifecycle()
    val lastBatchSummary by extensionStore.lastBatchUpdateSummary.collectAsStateWithLifecycle()

    var isStaging by remember { mutableStateOf(false) }
    var stagedForReview by remember { mutableStateOf<StagedExtensionPackage?>(null) }
    var stagedBatchForReview by remember { mutableStateOf<List<StagedExtensionPackage>?>(null) }
    var reviewUpdateTarget by remember { mutableStateOf<PendingExtensionUpdate?>(null) }
    var showImportUrlDialog by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var settingsTargetId by remember { mutableStateOf<String?>(null) }
    var qrLoginTargetId by remember { mutableStateOf<String?>(null) }
    var logsTargetId by remember { mutableStateOf<String?>(null) }
    var permissionsTarget by remember { mutableStateOf<ExtensionManifest?>(null) }
    var uninstallTarget by remember { mutableStateOf<InstalledExtension?>(null) }

    BackHandler(onBack = onBack)

    fun presentStagedPackages(staged: List<StagedExtensionPackage>) {
        when {
            staged.size == 1 -> stagedForReview = staged.first()
            staged.size > 1 -> stagedBatchForReview = staged
        }
    }

    fun launchFileImport() {
        onPickExtensionFiles { uris ->
            if (uris.isEmpty()) return@onPickExtensionFiles
            isStaging = true
            scope.launch {
                val res = extensionStore.stagePackagesFromUris(context, uris)
                isStaging = false
                res.onSuccess { staged ->
                    presentStagedPackages(staged)
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
                        enabled = !isStaging && !isCheckingUpdates && extensions.isNotEmpty(),
                        onClick = { scope.launch { extensionStore.checkAllForUpdates(manual = true) } },
                    ) {
                        Icon(Icons.Rounded.SystemUpdate, contentDescription = strings.checkForUpdates)
                    }
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
            if (isStaging || isCheckingUpdates) {
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
                    item(key = "extension_auto_update_header") {
                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { extensionStore.setAutoUpdateEnabled(!autoUpdateEnabled) },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(end = 12.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            text = strings.autoUpdateExtensions,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            text = strings.autoUpdateExtensionsDesc,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Switch(
                                        checked = autoUpdateEnabled,
                                        onCheckedChange = { extensionStore.setAutoUpdateEnabled(it) },
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val statusMsg = when {
                                        isCheckingUpdates -> strings.checkingExtensionUpdates
                                        lastBatchSummary != null -> {
                                            val s = lastBatchSummary!!
                                            strings.extensionBatchUpdateSummary(
                                                checked = s.checkedCount,
                                                applied = s.appliedCount,
                                                reviewRequired = s.reviewRequiredCount,
                                                failed = s.failedCount,
                                            )
                                        }
                                        else -> null
                                    }
                                    Text(
                                        text = statusMsg.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    FilledTonalButton(
                                        enabled = !isCheckingUpdates && !isStaging,
                                        onClick = { scope.launch { extensionStore.checkAllForUpdates(manual = true) } },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.SystemUpdate,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(strings.checkForUpdates)
                                    }
                                }
                            }
                        }
                    }

                    items(extensions, key = { it.id }) { ext ->
                        val extLogs = allLogs[ext.id].orEmpty()
                        val hasUpdateUrl = extensionStore.effectiveUpdateUrl(ext) != null
                        val pendingUpdate = pendingUpdates[ext.id]
                        val outcome = updateOutcomes[ext.id]
                        ExtensionItemCard(
                            extension = ext,
                            logCount = extLogs.size,
                            hasUpdateUrl = hasUpdateUrl,
                            pendingUpdate = pendingUpdate,
                            updateOutcome = outcome,
                            strings = strings,
                            onToggleEnabled = { enabled ->
                                extensionStore.setEnabled(ext.id, enabled)
                            },
                            onCheckUpdate = {
                                scope.launch { extensionStore.checkForUpdate(ext.id) }
                            },
                            onReviewUpdate = {
                                if (pendingUpdate != null) {
                                    reviewUpdateTarget = pendingUpdate
                                }
                            },
                            onOpenPermissions = { permissionsTarget = ext.manifest },
                            onOpenSettings = { settingsTargetId = ext.id },
                            onOpenQrLogin = { qrLoginTargetId = ext.id },
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
            onImport = { url -> extensionStore.stagePackagesFromUrl(url) },
            onStaged = { staged ->
                showImportUrlDialog = false
                presentStagedPackages(staged)
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

    val pendingBatchReview = stagedBatchForReview
    if (pendingBatchReview != null) {
        BatchPermissionReviewDialog(
            packages = pendingBatchReview,
            strings = strings,
            onDismiss = { stagedBatchForReview = null },
            onConfirmInstall = { selected ->
                extensionStore.installStagedBatch(selected, enableImmediately = true)
                stagedBatchForReview = null
            },
        )
    }

    val pendingEscalation = reviewUpdateTarget
    if (pendingEscalation != null) {
        ReviewExtensionUpdateDialog(
            pending = pendingEscalation,
            strings = strings,
            onDismiss = { reviewUpdateTarget = null },
            onApprove = {
                extensionStore.approvePendingUpdate(pendingEscalation.installed.id)
                reviewUpdateTarget = null
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
            onUpdateCookie = { cookie ->
                extensionStore.updateCookie(activeSettingsExt.id, cookie)
            },
            onUpdateQualities = { streamQualityId, downloadQualityId ->
                extensionStore.updateQualityPreferences(
                    extensionId = activeSettingsExt.id,
                    streamQualityId = streamQualityId,
                    downloadQualityId = downloadQualityId,
                )
            },
            onOpenQrLogin = {
                settingsTargetId = null
                qrLoginTargetId = activeSettingsExt.id
            },
            onResetDefaults = {
                extensionStore.resetSettings(activeSettingsExt.id)
            },
            onDismiss = { settingsTargetId = null },
        )
    }

    val activeQrLoginExt = qrLoginTargetId?.let { id -> extensions.firstOrNull { it.id == id } }
    if (activeQrLoginExt != null) {
        ExtensionQrLoginDialog(
            extension = activeQrLoginExt,
            extensionStore = extensionStore,
            strings = strings,
            onDismiss = { qrLoginTargetId = null },
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
    hasUpdateUrl: Boolean,
    pendingUpdate: PendingExtensionUpdate?,
    updateOutcome: ExtensionUpdateCheckOutcome?,
    strings: AppStrings,
    onToggleEnabled: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
    onReviewUpdate: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenQrLogin: () -> Unit,
    onOpenLogs: () -> Unit,
    onRequestUninstall: () -> Unit,
) {
    val manifest = extension.manifest
    val isCheckingThis = updateOutcome is ExtensionUpdateCheckOutcome.Checking
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

            if (pendingUpdate != null) {
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.WarningAmber,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = strings.extensionVersionTransition(
                                    pendingUpdate.installed.manifest.version,
                                    pendingUpdate.staged.manifest.version,
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        FilledTonalButton(onClick = onReviewUpdate) {
                            Text(strings.reviewUpdateAction)
                        }
                    }
                }
            } else {
                val outcomeText = when (updateOutcome) {
                    ExtensionUpdateCheckOutcome.UpToDate -> strings.extensionUpToDate
                    is ExtensionUpdateCheckOutcome.Applied -> strings.extensionUpdatedTo(updateOutcome.toVersion)
                    is ExtensionUpdateCheckOutcome.Error -> updateOutcome.message
                    else -> null
                }
                if (outcomeText != null) {
                    Text(
                        text = outcomeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (updateOutcome is ExtensionUpdateCheckOutcome.Error) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
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
                    if (pendingUpdate != null) {
                        TextButton(onClick = onReviewUpdate) {
                            Icon(Icons.Rounded.SystemUpdate, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(strings.reviewUpdateAction)
                        }
                    } else if (hasUpdateUrl) {
                        TextButton(
                            enabled = !isCheckingThis,
                            onClick = onCheckUpdate,
                        ) {
                            if (isCheckingThis) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.Rounded.SystemUpdate, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(strings.updateAction)
                        }
                    }
                    if (manifest.settings.isNotEmpty() || manifest.supportsCookieAuth || manifest.qualities.isNotEmpty()) {
                        TextButton(onClick = onOpenSettings) {
                            Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(strings.settingsLabel)
                        }
                    }
                    if (manifest.supportsQrLogin) {
                        TextButton(enabled = extension.enabled, onClick = onOpenQrLogin) {
                            Icon(Icons.Rounded.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(strings.qrLoginAction)
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
internal fun ReviewExtensionUpdateDialog(
    pending: PendingExtensionUpdate,
    strings: AppStrings,
    onDismiss: () -> Unit,
    onApprove: () -> Unit,
) {
    val oldManifest = pending.installed.manifest
    val newManifest = pending.staged.manifest
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Security, contentDescription = null) },
        title = { Text(strings.extensionUpdateReviewTitle) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = newManifest.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = strings.extensionVersionTransition(oldManifest.version, newManifest.version),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = newManifest.id,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
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
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = strings.extensionUpdateEscalationWarning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }

                if (pending.diff.addedCapabilities.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = strings.newCapabilitiesLabel,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        pending.diff.addedCapabilities.forEach { cap ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    shape = MaterialTheme.shapes.small,
                                ) {
                                    Text(
                                        text = capabilityLabel(cap, strings),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                                Text(
                                    text = capabilityDescription(cap, strings),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }

                if (pending.diff.addedHosts.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = strings.newAllowedHostsLabel,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        pending.diff.addedHosts.forEach { host ->
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = host,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
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
                    newManifest.permissions.hosts.forEach { host ->
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
            Button(onClick = onApprove) {
                Text(strings.approveAndUpdate)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.later)
            }
        },
    )
}

@Composable
private fun ImportExtensionUrlDialog(
    onDismiss: () -> Unit,
    onImport: suspend (String) -> Result<List<StagedExtensionPackage>>,
    onStaged: (List<StagedExtensionPackage>) -> Unit,
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
private fun BatchPermissionReviewDialog(
    packages: List<StagedExtensionPackage>,
    strings: AppStrings,
    onDismiss: () -> Unit,
    onConfirmInstall: (List<StagedExtensionPackage>) -> Unit,
) {
    var selectedIds by remember(packages) {
        mutableStateOf(packages.map { it.manifest.id }.toSet())
    }
    val selectedPackages = packages.filter { selectedIds.contains(it.manifest.id) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Security, contentDescription = null) },
        title = { Text(strings.batchPermissionReviewTitle(packages.size)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = strings.batchPermissionReviewSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                packages.forEach { pkg ->
                    val manifest = pkg.manifest
                    val isChecked = selectedIds.contains(manifest.id)
                    ElevatedCard(
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedIds = if (isChecked) {
                                        selectedIds - manifest.id
                                    } else {
                                        selectedIds + manifest.id
                                    }
                                }
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        selectedIds = if (checked) {
                                            selectedIds + manifest.id
                                        } else {
                                            selectedIds - manifest.id
                                        }
                                    },
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = manifest.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = "${strings.extensionVersionAuthor(manifest.version, manifest.author)} · ${manifest.id}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (manifest.capabilities.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    manifest.capabilities.forEach { cap ->
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            shape = MaterialTheme.shapes.small,
                                        ) {
                                            Text(
                                                text = capabilityLabel(cap, strings),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                            }
                            if (manifest.permissions.hosts.isNotEmpty()) {
                                Text(
                                    text = manifest.permissions.hosts.joinToString(", "),
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = selectedPackages.isNotEmpty(),
                onClick = { onConfirmInstall(selectedPackages) },
            ) {
                Text(strings.installAndEnableCount(selectedPackages.size))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
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

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = strings.capabilitiesLabel,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        manifest.capabilities.forEach { cap ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
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
                                Text(
                                    text = capabilityDescription(cap, strings),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
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
    onUpdateCookie: (String?) -> Unit,
    onUpdateQualities: (streamQualityId: String?, downloadQualityId: String?) -> Unit,
    onOpenQrLogin: () -> Unit,
    onResetDefaults: () -> Unit,
    onDismiss: () -> Unit,
) {
    val overrides = extension.state.settings
    val qualities = extension.manifest.qualities
    val hasQualities = qualities.isNotEmpty()
    val hasCookie = extension.manifest.supportsCookieAuth
    val hasQrLogin = extension.manifest.supportsQrLogin
    val cookieValue = extension.state.cookie.orEmpty()
    var showCookie by remember(extension.id) { mutableStateOf(false) }

    val selectedStreamQualityId = extension.state.streamQualityId
        ?.takeIf { id -> qualities.any { it.id == id } }
        ?: extension.manifest.effectiveDefaultStreamQuality
    val selectedDownloadQualityId = extension.state.downloadQualityId
        ?.takeIf { id -> qualities.any { it.id == id } }
        ?: extension.manifest.effectiveDefaultDownloadQuality

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
                if (hasQualities) {
                    ExtensionQualitySelectorRow(
                        label = strings.streamingQualityLabel,
                        qualities = qualities,
                        selectedId = selectedStreamQualityId,
                        onSelect = { nextId ->
                            onUpdateQualities(nextId, selectedDownloadQualityId)
                        },
                    )
                    ExtensionQualitySelectorRow(
                        label = strings.downloadQualityLabel,
                        qualities = qualities,
                        selectedId = selectedDownloadQualityId,
                        onSelect = { nextId ->
                            onUpdateQualities(selectedStreamQualityId, nextId)
                        },
                    )
                    if (extension.manifest.settings.isNotEmpty()) {
                        HorizontalDivider()
                    }
                }
                extension.manifest.settings.forEach { spec ->
                    SettingFieldRow(
                        spec = spec,
                        overrides = overrides,
                        strings = strings,
                        onUpdate = { value -> onUpdateSetting(spec.key, value) },
                    )
                }
                if (hasCookie) {
                    if (extension.manifest.settings.isNotEmpty() || hasQualities) {
                        HorizontalDivider()
                    }
                    if (hasQrLogin) {
                        OutlinedButton(
                            enabled = extension.enabled,
                            onClick = onOpenQrLogin,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(strings.signInWithQrCode)
                        }
                    }
                    OutlinedTextField(
                        value = cookieValue,
                        onValueChange = { onUpdateCookie(it) },
                        label = { Text(strings.extensionCookieLabel) },
                        placeholder = { Text(strings.extensionCookiePlaceholder) },
                        supportingText = { Text(strings.extensionCookieSupportingText) },
                        singleLine = true,
                        visualTransformation = if (showCookie) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { showCookie = !showCookie }) {
                                    Icon(
                                        imageVector = if (showCookie) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                        contentDescription = if (showCookie) strings.hideCookie else strings.showCookie,
                                    )
                                }
                                if (cookieValue.isNotEmpty()) {
                                    IconButton(onClick = { onUpdateCookie(null) }) {
                                        Icon(
                                            imageVector = Icons.Rounded.Clear,
                                            contentDescription = strings.clearCookie,
                                        )
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.done)
            }
        },
        dismissButton = if (extension.manifest.settings.isNotEmpty() || hasQualities) {
            {
                TextButton(onClick = onResetDefaults) {
                    Text(strings.resetDefaults)
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun ExtensionQualitySelectorRow(
    label: String,
    qualities: List<ExtensionQualityOption>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    val selected = qualities.firstOrNull { it.id == selectedId } ?: qualities.firstOrNull()
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            FilledTonalButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = selected?.label ?: selectedId.orEmpty(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                qualities.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        leadingIcon = if (option.id == selected?.id) {
                            { Icon(Icons.Rounded.Check, contentDescription = null) }
                        } else null,
                        onClick = {
                            onSelect(option.id)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingFieldRow(
    spec: ExtensionSettingSpec,
    overrides: Map<String, JsonPrimitive>,
    strings: AppStrings,
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
        ExtensionSettingType.SERVER_URL -> {
            val current = spec.resolveString(overrides)
            val approvedHost = com.musico.extensions.HostAllowlistMatcher.extractApprovedHost(current)
            OutlinedTextField(
                value = current,
                onValueChange = { onUpdate(JsonPrimitive(it)) },
                label = { Text(spec.label) },
                placeholder = { Text("https://server.example.com") },
                supportingText = {
                    Text(
                        if (approvedHost != null) {
                            strings.extensionServerUrlAllowedHost(approvedHost)
                        } else {
                            strings.extensionServerUrlHint
                        }
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
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
    ExtensionCapability.SEARCH -> strings.capabilitySearch
    ExtensionCapability.QR_LOGIN -> strings.capabilityQrLogin
}

private fun capabilityDescription(cap: ExtensionCapability, strings: AppStrings): String = when (cap) {
    ExtensionCapability.PLAYLIST -> strings.capabilityPlaylistDesc
    ExtensionCapability.TREE -> strings.capabilityTreeDesc
    ExtensionCapability.STREAM -> strings.capabilityStreamDesc
    ExtensionCapability.COOKIE -> strings.capabilityCookieDesc
    ExtensionCapability.PROXY -> strings.capabilityProxyDesc
    ExtensionCapability.LYRICS -> strings.capabilityLyricsDesc
    ExtensionCapability.SEARCH -> strings.capabilitySearchDesc
    ExtensionCapability.QR_LOGIN -> strings.capabilityQrLoginDesc
}

@Composable
private fun ExtensionQrLoginDialog(
    extension: InstalledExtension,
    extensionStore: ExtensionStore,
    strings: AppStrings,
    onDismiss: () -> Unit,
) {
    var reloadToken by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var session by remember { mutableStateOf<ExtQrLoginSession?>(null) }
    var matrix by remember { mutableStateOf<com.musico.ui.components.QrCodeMatrix?>(null) }
    var pollResult by remember { mutableStateOf<ExtQrLoginPollResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoadingStart by remember { mutableStateOf(true) }

    androidx.compose.runtime.LaunchedEffect(extension.id, reloadToken) {
        isLoadingStart = true
        errorMessage = null
        pollResult = null
        matrix = null
        session = null
        try {
            val started = extensionStore.startQrLogin(extension.id)
            if (!isActive) return@LaunchedEffect
            val encoded = QrCodeEncoder.encode(started.qrContent)
            session = started
            matrix = encoded
            isLoadingStart = false

            while (isActive) {
                val exp = started.expiresAtMs
                if (exp != null && System.currentTimeMillis() >= exp) {
                    pollResult = ExtQrLoginPollResult(
                        status = ExtQrLoginStatus.EXPIRED,
                        message = strings.qrLoginRefreshHint,
                    )
                    break
                }
                delay(started.pollIntervalMs.toLong())
                if (!isActive) break
                val next = extensionStore.pollQrLogin(extension.id, started.sessionId)
                if (!isActive) break
                pollResult = next
                when (next.status) {
                    ExtQrLoginStatus.PENDING, ExtQrLoginStatus.SCANNED -> Unit
                    ExtQrLoginStatus.CONFIRMED -> {
                        delay(1200)
                        if (isActive) onDismiss()
                        return@LaunchedEffect
                    }
                    ExtQrLoginStatus.EXPIRED, ExtQrLoginStatus.ERROR -> return@LaunchedEffect
                }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            errorMessage = t.message ?: strings.qrLoginFailed
            isLoadingStart = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.QrCode2, contentDescription = null) },
        title = { Text("${extension.manifest.name} — ${strings.capabilityQrLogin}") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    isLoadingStart -> {
                        CircularProgressIndicator()
                        Text(
                            text = strings.qrLoginGenerating,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    errorMessage != null -> {
                        Icon(
                            imageVector = Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(40.dp),
                        )
                        Text(
                            text = errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    pollResult?.status == ExtQrLoginStatus.EXPIRED -> {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(40.dp),
                        )
                        Text(
                            text = strings.qrLoginExpired,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = pollResult?.message ?: strings.qrLoginRefreshHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    pollResult?.status == ExtQrLoginStatus.ERROR -> {
                        Icon(
                            imageVector = Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(40.dp),
                        )
                        Text(
                            text = strings.qrLoginFailed,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        pollResult?.message?.let { msg ->
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    pollResult?.status == ExtQrLoginStatus.CONFIRMED -> {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(44.dp),
                        )
                        Text(
                            text = strings.qrLoginSignedIn,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        pollResult?.accountName?.takeIf { it.isNotBlank() }?.let { acct ->
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text(
                                    text = acct,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        }
                        Text(
                            text = pollResult?.message ?: strings.qrLoginCookieSaved(extension.manifest.name),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    matrix != null -> {
                        QrCodeCanvas(
                            matrix = matrix!!,
                            modifier = Modifier.size(220.dp),
                        )
                        if (pollResult?.status == ExtQrLoginStatus.SCANNED) {
                            Text(
                                text = strings.qrLoginScannedConfirm,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            pollResult?.accountName?.takeIf { it.isNotBlank() }?.let { acct ->
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = MaterialTheme.shapes.small,
                                ) {
                                    Text(
                                        text = acct,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = strings.qrLoginScanPrompt(extension.manifest.effectiveBadgeLabel),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = pollResult?.message ?: session?.message ?: strings.qrLoginWaiting,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (pollResult?.status != ExtQrLoginStatus.CONFIRMED && !isLoadingStart) {
                TextButton(onClick = { reloadToken += 1 }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(strings.qrLoginRefresh)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (pollResult?.status == ExtQrLoginStatus.CONFIRMED) strings.done else strings.cancel)
            }
        },
    )
}
