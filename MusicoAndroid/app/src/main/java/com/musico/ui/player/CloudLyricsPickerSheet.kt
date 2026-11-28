package com.musico.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
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
    var selectedSource by remember(activeLyricsExts.isEmpty()) {
        mutableStateOf<CloudLyricsSourceSelection>(
            if (activeLyricsExts.isEmpty()) CloudLyricsSourceSelection.Lrclib else CloudLyricsSourceSelection.Auto,
        )
    }
    LaunchedEffect(activeLyricsExts) {
        if (activeLyricsExts.isEmpty()) {
            if (selectedSource != CloudLyricsSourceSelection.Lrclib) {
                selectedSource = CloudLyricsSourceSelection.Lrclib
            }
        } else if (selectedSource is CloudLyricsSourceSelection.Extension) {
            val extId = (selectedSource as CloudLyricsSourceSelection.Extension).extensionId
            if (activeLyricsExts.none { it.id == extId }) {
                selectedSource = CloudLyricsSourceSelection.Auto
            }
        }
    }
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
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding(),
        ) {
            val isWideLayout = maxWidth >= 680.dp
            val isTwoRowSearch = maxWidth < 560.dp

            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = strings.cancel)
                    }
                    Text(
                        text = strings.searchCloudLyricsTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        enabled = selectedCandidate != null && !isSearching,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
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
                        Text(strings.useLyrics, maxLines = 1)
                    }
                }

                val canSearch = !isSearching && (queryTitle.isNotBlank() || queryArtist.isNotBlank())
                if (isTwoRowSearch) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = queryTitle,
                            onValueChange = { queryTitle = it },
                            label = { Text(strings.lyricsTitleField) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { searchTrigger += 1 }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
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
                                enabled = canSearch,
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                onClick = { searchTrigger += 1 },
                            ) {
                                Icon(
                                    Icons.Rounded.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(strings.searchAction, maxLines = 1)
                            }
                        }
                    }
                } else {
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
                            modifier = Modifier.weight(1.2f),
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
                            enabled = canSearch,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            onClick = { searchTrigger += 1 },
                        ) {
                            Icon(
                                Icons.Rounded.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(strings.searchAction, maxLines = 1)
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 6.dp),
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
                            label = { Text(option.label, maxLines = 1) },
                        )
                    }
                }
                if (activeLyricsExts.isEmpty()) {
                    Text(
                        text = strings.builtInLyricsSourceHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                    )
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
                            if (isWideLayout) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f, fill = true),
                                ) {
                                    CandidateListPane(
                                        candidates = candidates,
                                        selectedCandidateId = selectedCandidate?.id,
                                        fallbackTitle = track.title,
                                        targetDurationSec = targetDurationSec,
                                        onSelect = { selectedId = it },
                                        modifier = Modifier
                                            .weight(0.46f)
                                            .fillMaxHeight(),
                                    )
                                    VerticalDivider()
                                    PreviewPane(
                                        candidate = selectedCandidate,
                                        fallbackTitle = track.title,
                                        includeTranslation = includeTranslation,
                                        onToggleTranslation = { includeTranslation = !includeTranslation },
                                        modifier = Modifier
                                            .weight(0.54f)
                                            .fillMaxHeight(),
                                    )
                                }
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f, fill = true),
                                ) {
                                    CandidateListPane(
                                        candidates = candidates,
                                        selectedCandidateId = selectedCandidate?.id,
                                        fallbackTitle = track.title,
                                        targetDurationSec = targetDurationSec,
                                        onSelect = { selectedId = it },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(0.44f),
                                    )
                                    HorizontalDivider()
                                    PreviewPane(
                                        candidate = selectedCandidate,
                                        fallbackTitle = track.title,
                                        includeTranslation = includeTranslation,
                                        onToggleTranslation = { includeTranslation = !includeTranslation },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(0.56f),
                                    )
                                }
                            }
                        }
                    }
                }

                if (canExportSidecar || saveError != null) {
                    HorizontalDivider()
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
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
                                        .padding(horizontal = 12.dp, vertical = 4.dp),
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
        }
    }
}

@Composable
private fun CandidateListPane(
    candidates: List<CloudLyricsCandidate>,
    selectedCandidateId: String?,
    fallbackTitle: String,
    targetDurationSec: Double?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(candidates, key = { it.id }) { candidate ->
            val isSelected = candidate.id == selectedCandidateId
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
                contentColor = if (isSelected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(candidate.id) },
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = candidate.title.ifBlank { fallbackTitle },
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
}

@Composable
private fun PreviewPane(
    candidate: CloudLyricsCandidate?,
    fallbackTitle: String,
    includeTranslation: Boolean,
    onToggleTranslation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    Column(modifier = modifier) {
        if (candidate != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${candidate.sourceName} · ${candidate.title.ifBlank { fallbackTitle }}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (candidate.hasTranslation) {
                        FilterChip(
                            selected = includeTranslation,
                            onClick = onToggleTranslation,
                            leadingIcon = if (includeTranslation) {
                                {
                                    Icon(
                                        Icons.Rounded.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            } else null,
                            label = { Text(strings.includeTranslation, maxLines = 1) },
                        )
                    }
                }
            }
            HorizontalDivider(thickness = 0.5.dp)
            val previewText = remember(candidate, includeTranslation) {
                candidate.formattedText(includeTranslation)
            }
            val previewLines = remember(previewText) {
                LrcParser.parse(previewText).lines
            }
            val previewListState = rememberLazyListState()
            LaunchedEffect(candidate.id) {
                previewListState.scrollToItem(0)
            }
            LazyColumn(
                state = previewListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (previewLines.isEmpty()) {
                    item(key = "raw_preview") {
                        Text(previewText, style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    items(previewLines.size, key = { idx -> idx }) { idx ->
                        Text(
                            text = previewLines[idx].text.ifBlank { "♪" },
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
