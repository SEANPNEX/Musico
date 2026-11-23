package com.musico.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musico.core.LrcParser
import com.musico.extensions.ExtensionStore
import com.musico.i18n.LocalStrings
import com.musico.lyrics.CloudLyricsCandidate
import com.musico.lyrics.CloudLyricsFormatter
import com.musico.lyrics.CloudLyricsQuery
import com.musico.lyrics.CloudLyricsService
import com.musico.lyrics.CloudLyricsSourceFailure
import com.musico.lyrics.CloudLyricsSourceSelection
import com.musico.model.Track
import com.musico.ui.components.EmptyState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudLyricsPickerSheet(
    track: Track,
    canExportSidecar: Boolean,
    cloudLyricsService: CloudLyricsService,
    extensionStore: ExtensionStore?,
    onConfirm: suspend (lyricsText: String, exportSidecar: Boolean) -> Result<Unit>,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val extensionsSnapshot by (extensionStore?.extensions?.collectAsState()
        ?: remember { mutableStateOf(emptyList()) })
    val activeLyricsExts = remember(extensionsSnapshot, extensionStore) {
        extensionStore?.activeLyricsExtensions().orEmpty()
    }
    val sourceOptions = remember(activeLyricsExts, strings.autoSourceLabel) {
        CloudLyricsService.sourceOptions(activeLyricsExts, strings.autoSourceLabel)
    }

    var queryTitle by remember(track.id) { mutableStateOf(track.title) }
    var queryArtist by remember(track.id) {
        val cleaned = if (track.artist == "Unknown Artist" || track.artist == strings.unknownArtist) "" else track.artist
        mutableStateOf(cleaned)
    }
    var selectedSource by remember { mutableStateOf<CloudLyricsSourceSelection>(CloudLyricsSourceSelection.Auto) }
    var candidates by remember { mutableStateOf<List<CloudLyricsCandidate>>(emptyList()) }
    var partialFailures by remember { mutableStateOf<List<CloudLyricsSourceFailure>>(emptyList()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var includeTranslation by remember { mutableStateOf(true) }
    var alsoExportSidecar by remember(track.id, canExportSidecar) { mutableStateOf(canExportSidecar) }
    var isSearching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var searchTrigger by remember { mutableIntStateOf(0) }

    val selectedCandidate = remember(candidates, selectedId) {
        candidates.firstOrNull { it.id == selectedId } ?: candidates.firstOrNull()
    }
    val targetDurationSec = remember(track.durationSec) {
        track.durationSec.takeIf { it > 0.0 }
    }

    LaunchedEffect(searchTrigger, selectedSource) {
        val query = CloudLyricsQuery(
            title = queryTitle,
            artist = queryArtist,
            album = track.album.takeIf { it.isNotBlank() && it != "Unknown Album" },
            durationSec = targetDurationSec,
        )
        if (query.isEmpty) {
            candidates = emptyList()
            selectedId = null
            searchError = strings.noOnlineLyricsDesc
            isSearching = false
            return@LaunchedEffect
        }
        isSearching = true
        searchError = null
        partialFailures = emptyList()
        try {
            val response = cloudLyricsService.search(
                query = query,
                source = selectedSource,
                activeExtensions = activeLyricsExts,
            )
            candidates = response.candidates
            partialFailures = response.failures
            selectedId = if (selectedId != null && candidates.any { it.id == selectedId }) {
                selectedId
            } else {
                candidates.firstOrNull()?.id
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (ex: Exception) {
            candidates = emptyList()
            selectedId = null
            searchError = ex.message ?: strings.couldNotSearchLyricsTitle
        } finally {
            isSearching = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 680.dp)
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(strings.cancel)
                }
                Text(
                    text = strings.searchCloudLyricsTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    enabled = selectedCandidate != null && !isSearching,
                    onClick = {
                        val candidate = selectedCandidate ?: return@Button
                        val text = candidate.formattedText(includeTranslation)
                        scope.launch {
                            val result = onConfirm(text, alsoExportSidecar && canExportSidecar)
                            result.onSuccess {
                                onDismiss()
                            }.onFailure { err ->
                                saveError = err.message ?: strings.couldNotSaveLyricsTitle
                            }
                        }
                    },
                ) {
                    Text(strings.useLyrics)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = queryTitle,
                    onValueChange = { queryTitle = it },
                    label = { Text(strings.lyricsTitleField) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { searchTrigger += 1 }),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = queryArtist,
                    onValueChange = { queryArtist = it },
                    label = { Text(strings.lyricsArtistField) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { searchTrigger += 1 }),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    enabled = !isSearching && (queryTitle.isNotBlank() || queryArtist.isNotBlank()),
                    onClick = { searchTrigger += 1 },
                ) {
                    Text(strings.searchAction)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (option in sourceOptions) {
                    FilterChip(
                        selected = option.selection == selectedSource,
                        onClick = {
                            if (selectedSource != option.selection) {
                                selectedSource = option.selection
                            } else {
                                searchTrigger += 1
                            }
                        },
                        label = { Text(option.label) },
                    )
                }
            }

            HorizontalDivider()

            val bodyModifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = true)

            when {
                isSearching && candidates.isEmpty() -> {
                    Box(modifier = bodyModifier, contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(10.dp))
                            Text(strings.searchingOnline, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                searchError != null && candidates.isEmpty() -> {
                    Box(modifier = bodyModifier, contentAlignment = Alignment.Center) {
                        EmptyState(
                            icon = Icons.Rounded.Warning,
                            title = strings.couldNotSearchLyricsTitle,
                            description = searchError.orEmpty(),
                            actionLabel = strings.retryAction,
                            onAction = { searchTrigger += 1 },
                        )
                    }
                }
                candidates.isEmpty() -> {
                    Box(modifier = bodyModifier, contentAlignment = Alignment.Center) {
                        EmptyState(
                            icon = Icons.Rounded.Search,
                            title = strings.noOnlineLyricsTitle,
                            description = strings.noOnlineLyricsDesc,
                        )
                    }
                }
                else -> {
                    Column(modifier = bodyModifier) {
                        if (partialFailures.isNotEmpty()) {
                            Text(
                                text = strings.partialSourcesFailedHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(0.45f),
                        ) {
                            items(candidates, key = { it.id }) { candidate ->
                                val isSelected = candidate.id == selectedCandidate?.id
                                val formatLabel = when {
                                    candidate.isTimed -> strings.lyricsFormatSynced
                                    candidate.isInstrumental -> strings.lyricsFormatInstrumental
                                    else -> strings.lyricsFormatPlain
                                }
                                val durText = CloudLyricsFormatter.formatDurationAndDelta(
                                    candidate.durationSec,
                                    targetDurationSec,
                                )
                                Surface(
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedId = candidate.id },
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    ) {
                                        Text(
                                            text = candidate.title.ifBlank { track.title },
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        val sub = listOfNotNull(
                                            candidate.artist.takeIf { it.isNotBlank() },
                                            candidate.album?.takeIf { it.isNotBlank() },
                                        ).joinToString(" · ")
                                        if (sub.isNotBlank()) {
                                            Text(
                                                text = sub,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            BadgeChip(candidate.sourceName)
                                            BadgeChip(formatLabel)
                                            if (candidate.hasTranslation) {
                                                BadgeChip(strings.lyricsTranslatedBadge)
                                            }
                                            if (durText != null) {
                                                Spacer(Modifier.weight(1f))
                                                Text(
                                                    text = durText,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                                HorizontalDivider(thickness = 0.5.dp)
                            }
                        }

                        HorizontalDivider()

                        val previewCandidate = selectedCandidate
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(0.55f),
                        ) {
                            if (previewCandidate != null) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "${previewCandidate.sourceName} · ${previewCandidate.title.ifBlank { track.title }}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (previewCandidate.hasTranslation) {
                                        Text(
                                            text = strings.includeTranslation,
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Switch(
                                            checked = includeTranslation,
                                            onCheckedChange = { includeTranslation = it },
                                        )
                                    }
                                }
                                HorizontalDivider(thickness = 0.5.dp)
                                val previewText = remember(previewCandidate, includeTranslation) {
                                    previewCandidate.formattedText(includeTranslation)
                                }
                                val previewLines = remember(previewText) {
                                    LrcParser.parse(previewText).lines
                                }
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    if (previewLines.isEmpty()) {
                                        Text(previewText, style = MaterialTheme.typography.bodyMedium)
                                    } else {
                                        for (line in previewLines) {
                                            Text(
                                                text = line.text.ifBlank { "♪" },
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (canExportSidecar || saveError != null) {
                HorizontalDivider()
                if (saveError != null) {
                    Text(
                        text = saveError.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                if (canExportSidecar) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { alsoExportSidecar = !alsoExportSidecar }
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = alsoExportSidecar,
                            onCheckedChange = { alsoExportSidecar = it },
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = strings.alsoSaveLrcToFolder,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BadgeChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
