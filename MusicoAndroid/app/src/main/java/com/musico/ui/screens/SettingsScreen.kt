package com.musico.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musico.cloud.GoogleDriveOAuthManager
import com.musico.i18n.AppLanguage
import com.musico.i18n.AppStrings
import com.musico.i18n.LanguageStore
import com.musico.i18n.LocalStrings
import com.musico.store.LibraryStore
import com.musico.store.LibraryUiState
import com.musico.ui.components.LanguageDialog
import com.musico.ui.components.UpdateDialog
import com.musico.ui.theme.AppThemeMode
import com.musico.ui.theme.AppearanceSettings
import com.musico.ui.theme.BackgroundColorPreset
import com.musico.ui.theme.ForegroundColorPreset
import com.musico.ui.theme.LocalHasBackgroundImage
import com.musico.ui.theme.RgbColor
import com.musico.ui.theme.ThemeStore
import com.musico.ui.theme.isLightColor
import com.musico.ui.theme.musicoScaffoldContainerColor
import com.musico.ui.theme.musicoTopAppBarColors
import com.musico.ui.theme.normalizeHexOrNull
import com.musico.ui.theme.parseHexRgb
import com.musico.ui.theme.rememberSampledBitmap
import com.musico.ui.theme.toComposeColorOrNull
import com.musico.update.UpdateManager
import com.musico.update.UpdateUiState
import com.musico.update.hasAvailableUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class ActiveColorDialog {
    BACKGROUND,
    ACCENT,
    TEXT,
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    themeStore: ThemeStore,
    languageStore: LanguageStore,
    libraryStore: LibraryStore,
    libraryState: LibraryUiState,
    updateManager: UpdateManager,
    googleDriveOAuthManager: GoogleDriveOAuthManager? = null,
    onPickImage: ((Uri) -> Unit) -> Unit,
    showBackButton: Boolean = false,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appearance by themeStore.settings.collectAsStateWithLifecycle()
    val currentLanguage by languageStore.language.collectAsStateWithLifecycle()
    val updateState by updateManager.state.collectAsStateWithLifecycle()
    val hasBackgroundImage = LocalHasBackgroundImage.current

    val backgroundImageFile = remember(appearance.backgroundImageFileName) {
        themeStore.resolveBackgroundImageFile()
    }
    val previewBitmap = rememberSampledBitmap(file = backgroundImageFile, maxDimension = 720)

    var activeColorDialog by remember { mutableStateOf<ActiveColorDialog?>(null) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var imageError by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = showBackButton, onBack = onBack)

    val cardContainerColor = if (hasBackgroundImage) {
        MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.86f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    fun openUpdateDialog() {
        showUpdateDialog = true
        if (updateState is UpdateUiState.Idle ||
            updateState is UpdateUiState.UpToDate ||
            updateState is UpdateUiState.Error
        ) {
            scope.launch { updateManager.checkForUpdates(silent = false) }
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = musicoScaffoldContainerColor(),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (showBackButton) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = strings.back)
                        }
                    }
                },
                title = {
                    Text(
                        text = strings.settingsLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = musicoTopAppBarColors(),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "appearance_section") {
                SettingsSectionHeader(title = strings.appearanceSection)
                Spacer(Modifier.height(8.dp))
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(containerColor = cardContainerColor),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        // Theme Mode
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = strings.themeModeLabel,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                            )
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                AppThemeMode.entries.forEachIndexed { index, mode ->
                                    SegmentedButton(
                                        selected = appearance.themeMode == mode,
                                        onClick = { themeStore.setThemeMode(mode) },
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index = index,
                                            count = AppThemeMode.entries.size,
                                        ),
                                        label = {
                                            Text(
                                                text = strings.themeModeLabel(mode),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // Background Color Set
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = strings.backgroundColorSetLabel,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                BackgroundColorPreset.entries.forEach { preset ->
                                    val selected = appearance.backgroundPreset == preset
                                    val swatchColor = when (preset) {
                                        BackgroundColorPreset.DEFAULT -> MaterialTheme.colorScheme.surfaceContainerHighest
                                        BackgroundColorPreset.CUSTOM ->
                                            appearance.customBackgroundHex?.toComposeColorOrNull()
                                                ?: MaterialTheme.colorScheme.surfaceContainerHighest
                                        else -> preset.hex?.toComposeColorOrNull()
                                            ?: MaterialTheme.colorScheme.surface
                                    }
                                    val isDefault = preset == BackgroundColorPreset.DEFAULT
                                    val isCustom = preset == BackgroundColorPreset.CUSTOM
                                    ColorPresetSwatch(
                                        label = strings.backgroundPresetLabel(preset),
                                        color = swatchColor,
                                        selected = selected,
                                        showAutoIcon = isDefault,
                                        showCustomIcon = isCustom && appearance.customBackgroundHex == null,
                                        onClick = {
                                            if (isCustom) {
                                                activeColorDialog = ActiveColorDialog.BACKGROUND
                                            } else {
                                                themeStore.setBackgroundPreset(preset)
                                            }
                                        },
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // Foreground & Accent Color Set
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = strings.foregroundAndAccentLabel,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ForegroundColorPreset.entries.forEach { preset ->
                                    val selected = appearance.foregroundPreset == preset
                                    val isCustom = preset == ForegroundColorPreset.CUSTOM
                                    val swatchColor = if (isCustom) {
                                        appearance.customAccentHex?.toComposeColorOrNull()
                                            ?: MaterialTheme.colorScheme.primary
                                    } else {
                                        preset.hex?.toComposeColorOrNull()
                                            ?: MaterialTheme.colorScheme.primary
                                    }
                                    ColorPresetSwatch(
                                        label = strings.foregroundPresetLabel(preset),
                                        color = swatchColor,
                                        selected = selected,
                                        showAutoIcon = false,
                                        showCustomIcon = isCustom && appearance.customAccentHex == null,
                                        onClick = {
                                            if (isCustom) {
                                                activeColorDialog = ActiveColorDialog.ACCENT
                                            } else {
                                                themeStore.setForegroundPreset(preset)
                                            }
                                        },
                                    )
                                }
                            }

                            // Optional Custom Text Color row
                            Surface(
                                onClick = { activeColorDialog = ActiveColorDialog.TEXT },
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(
                                    alpha = if (hasBackgroundImage) 0.72f else 1f
                                ),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    val customTextColor = appearance.customForegroundHex?.toComposeColorOrNull()
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(customTextColor ?: MaterialTheme.colorScheme.onBackground)
                                            .border(
                                                width = 1.dp,
                                                color = MaterialTheme.colorScheme.outline,
                                                shape = CircleShape,
                                            ),
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = strings.customTextColorLabel,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        Text(
                                            text = appearance.customForegroundHex ?: strings.customTextColorAuto,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (appearance.customForegroundHex != null) {
                                        IconButton(
                                            onClick = { themeStore.setCustomForegroundHex(null) },
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Clear,
                                                contentDescription = strings.useAutoTextColor,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // Background Image
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = strings.backgroundImageLabel,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                            )

                            if (backgroundImageFile != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(width = 88.dp, height = 56.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                            .border(
                                                width = 1.dp,
                                                color = MaterialTheme.colorScheme.outlineVariant,
                                                shape = RoundedCornerShape(10.dp),
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (previewBitmap != null) {
                                            Image(
                                                bitmap = previewBitmap,
                                                contentDescription = strings.backgroundImageLabel,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Rounded.Image,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.weight(1f),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        FilledTonalButton(
                                            onClick = {
                                                onPickImage { uri ->
                                                    scope.launch {
                                                        val result = withContext(Dispatchers.IO) {
                                                            themeStore.importBackgroundImage(uri)
                                                        }
                                                        result.onFailure {
                                                            imageError = strings.couldNotLoadSelectedImage
                                                        }
                                                    }
                                                }
                                            },
                                        ) {
                                            Text(
                                                text = strings.changeImageAction,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        OutlinedButton(
                                            onClick = { themeStore.clearBackgroundImage() },
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Delete,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(strings.remove)
                                        }
                                    }
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = strings.imageVisibilityLabel,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            text = "${(appearance.backgroundImageOpacity * 100f).roundToInt()}%",
                                            style = MaterialTheme.typography.labelLarge.copy(
                                                fontFeatureSettings = "tnum",
                                            ),
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    Slider(
                                        value = appearance.backgroundImageOpacity,
                                        onValueChange = { themeStore.setBackgroundImageOpacity(it) },
                                        valueRange = AppearanceSettings.MIN_BACKGROUND_IMAGE_OPACITY..
                                            AppearanceSettings.MAX_BACKGROUND_IMAGE_OPACITY,
                                    )
                                }
                            } else {
                                FilledTonalButton(
                                    onClick = {
                                        onPickImage { uri ->
                                            scope.launch {
                                                val result = withContext(Dispatchers.IO) {
                                                    themeStore.importBackgroundImage(uri)
                                                }
                                                result.onFailure {
                                                    imageError = strings.couldNotLoadSelectedImage
                                                }
                                            }
                                        }
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Image,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(strings.chooseImageAction)
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // Reset to Default
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            OutlinedButton(onClick = { themeStore.resetToDefaults() }) {
                                Icon(
                                    imageVector = Icons.Rounded.RestartAlt,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(strings.resetAppearanceToDefault)
                            }
                        }
                    }
                }
            }

            // General Section
            item(key = "general_section") {
                SettingsSectionHeader(title = strings.generalSection)
                Spacer(Modifier.height(8.dp))
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(containerColor = cardContainerColor),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showLanguageDialog = true }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = strings.languageTitle,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = languageLabel(currentLanguage, strings),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // Google Drive OAuth 2.0 Section
            if (googleDriveOAuthManager != null) {
                item(key = "gdrive_oauth_section") {
                    val oauthState by googleDriveOAuthManager.state.collectAsStateWithLifecycle()
                    SettingsSectionHeader(title = strings.googleDriveAccountSection)
                    Spacer(Modifier.height(8.dp))
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(containerColor = cardContainerColor),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = strings.accountStatusLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = if (oauthState.isSignedIn) {
                                        oauthState.accountEmail ?: strings.googleDriveConnected
                                    } else {
                                        strings.googleDriveNotSignedIn
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (oauthState.isSignedIn) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }

                            OutlinedTextField(
                                value = oauthState.clientId,
                                onValueChange = {
                                    googleDriveOAuthManager.updateClientCredentials(it, oauthState.clientSecret)
                                },
                                label = { Text(strings.googleDriveClientIdLabel) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            OutlinedTextField(
                                value = oauthState.clientSecret,
                                onValueChange = {
                                    googleDriveOAuthManager.updateClientCredentials(oauthState.clientId, it)
                                },
                                label = { Text(strings.googleDriveClientSecretLabel) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FilledTonalButton(
                                    enabled = !oauthState.isSigningIn && oauthState.clientId.trim().isNotEmpty(),
                                    onClick = {
                                        scope.launch { googleDriveOAuthManager.startSignIn(context) }
                                    },
                                ) {
                                    if (oauthState.isSigningIn) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(
                                        if (oauthState.isSignedIn) {
                                            strings.reconnectGoogleDriveAction
                                        } else {
                                            strings.signInWithGoogleAction
                                        }
                                    )
                                }

                                if (oauthState.isSignedIn) {
                                    OutlinedButton(onClick = { googleDriveOAuthManager.signOut() }) {
                                        Text(strings.signOutGoogleDriveAction)
                                    }
                                }
                            }

                            oauthState.errorMessage?.takeIf { it.isNotBlank() }?.let { errMsg ->
                                Text(
                                    text = errMsg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }

                            Text(
                                text = strings.googleDriveOAuthHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // Library Section
            item(key = "library_section") {
                SettingsSectionHeader(title = strings.librarySection)
                Spacer(Modifier.height(8.dp))
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(containerColor = cardContainerColor),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !libraryState.isScanning) {
                                scope.launch { libraryStore.rescan() }
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (libraryState.isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.5.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = strings.rescanLibraryAction,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            if (libraryState.isScanning) {
                                Text(
                                    text = strings.rescanningLibrary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // About & Updates Section
            item(key = "about_updates_section") {
                SettingsSectionHeader(title = strings.aboutAndUpdatesSection)
                Spacer(Modifier.height(8.dp))
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(containerColor = cardContainerColor),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = strings.appVersionLabel,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = updateManager.currentVersion,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { openUpdateDialog() }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            BadgedBox(
                                badge = {
                                    if (updateState.hasAvailableUpdate) {
                                        Badge()
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.SystemUpdate,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = strings.checkForUpdates,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                if (updateState.hasAvailableUpdate) {
                                    Text(
                                        text = strings.updateAvailable,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    when (activeColorDialog) {
        ActiveColorDialog.BACKGROUND -> ColorPickerDialog(
            title = strings.customBackgroundColorTitle,
            initialHex = appearance.customBackgroundHex ?: "#18181B",
            onDismiss = { activeColorDialog = null },
            onConfirm = { hex ->
                themeStore.setCustomBackgroundHex(hex)
                activeColorDialog = null
            },
        )
        ActiveColorDialog.ACCENT -> ColorPickerDialog(
            title = strings.customAccentColorTitle,
            initialHex = appearance.customAccentHex ?: ForegroundColorPreset.CORAL.hex!!,
            onDismiss = { activeColorDialog = null },
            onConfirm = { hex ->
                themeStore.setCustomAccentHex(hex)
                activeColorDialog = null
            },
        )
        ActiveColorDialog.TEXT -> ColorPickerDialog(
            title = strings.customTextColorTitle,
            initialHex = appearance.customForegroundHex ?: "#F5F5F7",
            clearActionLabel = if (appearance.customForegroundHex != null) strings.useAutoTextColor else null,
            onClear = {
                themeStore.setCustomForegroundHex(null)
                activeColorDialog = null
            },
            onDismiss = { activeColorDialog = null },
            onConfirm = { hex ->
                themeStore.setCustomForegroundHex(hex)
                activeColorDialog = null
            },
        )
        null -> Unit
    }

    if (showLanguageDialog) {
        LanguageDialog(
            current = currentLanguage,
            onSelect = { languageStore.setLanguage(it) },
            onDismiss = { showLanguageDialog = false },
        )
    }

    if (showUpdateDialog) {
        UpdateDialog(
            updateManager = updateManager,
            onDismiss = { showUpdateDialog = false },
        )
    }

    if (imageError != null) {
        AlertDialog(
            onDismissRequest = { imageError = null },
            title = { Text(strings.backgroundImageLabel) },
            text = { Text(imageError.orEmpty()) },
            confirmButton = {
                TextButton(onClick = { imageError = null }) {
                    Text(strings.ok)
                }
            },
        )
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun ColorPresetSwatch(
    label: String,
    color: Color,
    selected: Boolean,
    showAutoIcon: Boolean,
    showCustomIcon: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val swatchRgb = RgbColor(
        r = (color.red * 255f).roundToInt().coerceIn(0, 255),
        g = (color.green * 255f).roundToInt().coerceIn(0, 255),
        b = (color.blue * 255f).roundToInt().coerceIn(0, 255),
    )
    val iconTint = if (isLightColor(swatchRgb)) Color(0xFF141416) else Color(0xFFF5F5F7)

    Column(
        modifier = Modifier
            .width(68.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = if (selected) 2.5.dp else 1.dp,
                    color = borderColor,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                selected -> Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = label,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp),
                )
                showAutoIcon -> Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                showCustomIcon -> Icon(
                    imageVector = Icons.Rounded.ColorLens,
                    contentDescription = label,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ColorPickerDialog(
    title: String,
    initialHex: String,
    clearActionLabel: String? = null,
    onClear: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val strings = LocalStrings.current
    val initialRgb = remember(initialHex) {
        parseHexRgb(initialHex) ?: RgbColor(232, 84, 64)
    }
    var rgb by remember(initialHex) { mutableStateOf(initialRgb) }
    var hexInput by remember(initialHex) { mutableStateOf(initialRgb.toHex()) }

    val parsedHex = normalizeHexOrNull(hexInput)
    val previewRgb = parsedHex?.let(::parseHexRgb) ?: rgb
    val previewColor = previewRgb.toComposeColor()
    val previewTextColor = if (isLightColor(previewRgb)) Color(0xFF141416) else Color(0xFFF5F5F7)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Palette, contentDescription = null) },
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = previewColor,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "${strings.colorPreviewSample} · ${previewRgb.toHex()}",
                            style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                            color = previewTextColor,
                        )
                    }
                }

                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { next ->
                        hexInput = next
                        parseHexRgb(next)?.let { rgb = it }
                    },
                    label = { Text(strings.hexColorLabel) },
                    isError = parsedHex == null,
                    supportingText = if (parsedHex == null) {
                        { Text(strings.invalidHexColorError) }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                ColorChannelSlider(
                    label = strings.colorRedLabel,
                    value = rgb.r,
                    onValueChange = { r ->
                        val next = rgb.copy(r = r)
                        rgb = next
                        hexInput = next.toHex()
                    },
                )
                ColorChannelSlider(
                    label = strings.colorGreenLabel,
                    value = rgb.g,
                    onValueChange = { g ->
                        val next = rgb.copy(g = g)
                        rgb = next
                        hexInput = next.toHex()
                    },
                )
                ColorChannelSlider(
                    label = strings.colorBlueLabel,
                    value = rgb.b,
                    onValueChange = { b ->
                        val next = rgb.copy(b = b)
                        rgb = next
                        hexInput = next.toHex()
                    },
                )
            }
        },
        confirmButton = {
            ButtonRowConfirm(
                confirmLabel = strings.save,
                canConfirm = parsedHex != null,
                onConfirm = {
                    if (parsedHex != null) {
                        onConfirm(parsedHex)
                    }
                },
            )
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (clearActionLabel != null && onClear != null) {
                    TextButton(onClick = onClear) {
                        Text(clearActionLabel)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(strings.cancel)
                }
            }
        },
    )
}

@Composable
private fun ButtonRowConfirm(
    confirmLabel: String,
    canConfirm: Boolean,
    onConfirm: () -> Unit,
) {
    TextButton(
        enabled = canConfirm,
        onClick = onConfirm,
    ) {
        Text(confirmLabel)
    }
}

@Composable
private fun ColorChannelSlider(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt().coerceIn(0, 255)) },
            valueRange = 0f..255f,
        )
    }
}

private fun languageLabel(language: AppLanguage, strings: AppStrings): String = when (language) {
    AppLanguage.SYSTEM -> strings.languageFollowSystem
    AppLanguage.ENGLISH -> strings.languageEnglish
    AppLanguage.SIMPLIFIED_CHINESE -> strings.languageSimplifiedChinese
}
