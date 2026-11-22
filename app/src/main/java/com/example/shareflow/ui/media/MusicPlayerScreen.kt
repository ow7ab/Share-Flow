package com.example.shareflow.ui.media

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.shareflow.core.designsystem.components.ConfirmationDialog
import com.example.shareflow.core.designsystem.components.EmptyState
import com.example.shareflow.core.designsystem.components.ErrorState
import com.example.shareflow.core.designsystem.components.LoadingState
import com.example.shareflow.core.designsystem.components.PrimaryActionButton
import com.example.shareflow.core.designsystem.components.ShareFlowSignatureTabIndicator
import com.example.shareflow.core.designsystem.components.ShareFlowTopBar
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.core.di.shareFlowViewModelFactory
import com.example.shareflow.domain.model.MediaAudioTrack
import com.example.shareflow.domain.model.MusicPlaylist
import com.example.shareflow.domain.model.MusicRepeatMode
import com.example.shareflow.media.music.MusicPlaybackState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    providedViewModel: MusicPlayerViewModel? = null
) {
    val container = LocalAppContainer.current
    val viewModel: MusicPlayerViewModel = providedViewModel
        ?: androidx.lifecycle.viewmodel.compose.viewModel(
            factory = shareFlowViewModelFactory {
                MusicPlayerViewModel(
                    mediaCenterRepository = container.mediaCenterRepository,
                    playbackController = container.musicPlaybackController
                )
            }
        )
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val spacing = ShareFlowDesign.spacing

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            viewModel.addPickedAudioUris(uris.map { it.toString() })
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* Media3 playback works cleanly even if notification permission is declined */ }

    fun ensureNotificationPermissionForPlayback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(uiState.statusMessage) {
        val msg = uiState.statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearStatusMessage()
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("music_player_screen"),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ShareFlowTopBar(
                title = stringResource(R.string.media_music_title),
                onNavigateBack = onNavigateBack,
                actions = {
                    IconButton(
                        onClick = { audioPickerLauncher.launch(arrayOf("audio/*")) },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("music_add_audio_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AudioFile,
                            contentDescription = stringResource(R.string.media_music_pick_audio)
                        )
                    }
                    IconButton(
                        onClick = viewModel::refreshLibrary,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("music_refresh_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.action_refresh)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            NowPlayingDeck(
                playbackState = playbackState,
                onTogglePlayPause = {
                    ensureNotificationPermissionForPlayback()
                    viewModel.playbackController.togglePlayPause()
                },
                onSeekTo = viewModel.playbackController::seekTo,
                onNext = viewModel.playbackController::skipNext,
                onPrevious = viewModel.playbackController::skipPrevious,
                onToggleShuffle = viewModel.playbackController::toggleShuffle,
                onCycleRepeat = viewModel.playbackController::cycleRepeatMode,
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal, vertical = spacing.xs)
            )

            playbackState.errorMessage?.let { err ->
                Box(modifier = Modifier.padding(horizontal = spacing.screenHorizontal, vertical = spacing.xs)) {
                    ErrorState(
                        message = err,
                        onRetry = {
                            viewModel.playbackController.clearError()
                            viewModel.playbackController.togglePlayPause()
                        },
                        modifier = Modifier.testTag("music_playback_error")
                    )
                }
            }

            val selectedMusicTab = uiState.selectedTab.ordinal
            PrimaryTabRow(
                selectedTabIndex = selectedMusicTab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary,
                indicator = {
                    ShareFlowSignatureTabIndicator(
                        modifier = Modifier.tabIndicatorOffset(selectedMusicTab, matchContentSize = false)
                    )
                },
                modifier = Modifier.testTag("music_tabs")
            ) {
                Tab(
                    selected = uiState.selectedTab == MusicTab.LIBRARY,
                    onClick = { viewModel.selectTab(MusicTab.LIBRARY) },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = { Text(stringResource(R.string.media_music_tab_library)) },
                    modifier = Modifier.testTag("music_tab_library")
                )
                Tab(
                    selected = uiState.selectedTab == MusicTab.QUEUE,
                    onClick = { viewModel.selectTab(MusicTab.QUEUE) },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = { Text(stringResource(R.string.media_music_tab_queue, playbackState.queue.size)) },
                    modifier = Modifier.testTag("music_tab_queue")
                )
                Tab(
                    selected = uiState.selectedTab == MusicTab.PLAYLISTS,
                    onClick = { viewModel.selectTab(MusicTab.PLAYLISTS) },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = { Text(stringResource(R.string.media_music_tab_playlists, playlists.size)) },
                    modifier = Modifier.testTag("music_tab_playlists")
                )
            }

            when (uiState.selectedTab) {
                MusicTab.LIBRARY -> {
                    when {
                        uiState.isLoading -> LoadingState(
                            message = stringResource(R.string.media_music_loading),
                            modifier = Modifier.testTag("music_loading")
                        )
                        uiState.loadError != null -> Box(modifier = Modifier.padding(spacing.screenHorizontal)) {
                            ErrorState(
                                message = uiState.loadError.orEmpty(),
                                onRetry = viewModel::refreshLibrary,
                                modifier = Modifier.testTag("music_load_error")
                            )
                        }
                        uiState.libraryTracks.isEmpty() -> Box(modifier = Modifier.padding(spacing.screenHorizontal)) {
                            EmptyState(
                                icon = Icons.Filled.LibraryMusic,
                                title = stringResource(R.string.media_music_empty_title),
                                body = stringResource(R.string.media_music_empty_body),
                                actionLabel = stringResource(R.string.media_music_pick_audio),
                                onAction = { audioPickerLauncher.launch(arrayOf("audio/*")) },
                                modifier = Modifier.testTag("music_empty_state")
                            )
                        }
                        else -> {
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .testTag("music_library_list"),
                                contentPadding = PaddingValues(spacing.screenHorizontal, spacing.sm),
                                verticalArrangement = Arrangement.spacedBy(spacing.xs)
                            ) {
                                itemsIndexed(
                                    items = uiState.libraryTracks,
                                    key = { _, track -> track.uriString }
                                ) { index, track ->
                                    AudioTrackRow(
                                        track = track,
                                        isCurrent = playbackState.currentTrack?.uriString == track.uriString,
                                        onPlay = {
                                            ensureNotificationPermissionForPlayback()
                                            viewModel.playFromLibrary(index)
                                        },
                                        onAddToPlaylist = { viewModel.openAddTrackToPlaylist(track) },
                                        modifier = Modifier.testTag("music_library_track_$index")
                                    )
                                }
                            }
                        }
                    }
                }

                MusicTab.QUEUE -> {
                    if (playbackState.queue.isEmpty()) {
                        Box(modifier = Modifier.padding(spacing.screenHorizontal)) {
                            EmptyState(
                                icon = Icons.AutoMirrored.Filled.QueueMusic,
                                title = stringResource(R.string.media_music_queue_empty_title),
                                body = stringResource(R.string.media_music_queue_empty_body),
                                modifier = Modifier.testTag("music_queue_empty")
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .testTag("music_queue_list"),
                            contentPadding = PaddingValues(spacing.screenHorizontal, spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            itemsIndexed(
                                items = playbackState.queue,
                                key = { idx, track -> "${idx}_${track.uriString}" }
                            ) { index, track ->
                                AudioTrackRow(
                                    track = track,
                                    isCurrent = index == playbackState.currentIndex,
                                    onPlay = { viewModel.playbackController.selectQueueIndex(index) },
                                    onAddToPlaylist = { viewModel.openAddTrackToPlaylist(track) },
                                    modifier = Modifier.testTag("music_queue_item_$index")
                                )
                            }
                        }
                    }
                }

                MusicTab.PLAYLISTS -> {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .testTag("music_playlists_list"),
                        contentPadding = PaddingValues(spacing.screenHorizontal, spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(spacing.sm)
                    ) {
                        item(key = "create_playlist_cta") {
                            if (uiState.showCreatePlaylistDialog) {
                                CreatePlaylistCard(
                                    onConfirm = viewModel::createPlaylist,
                                    onDismiss = { viewModel.showCreatePlaylist(false) }
                                )
                            } else {
                                PrimaryActionButton(
                                    text = stringResource(R.string.media_music_create_playlist),
                                    onClick = { viewModel.showCreatePlaylist(true) },
                                    leadingIcon = Icons.Filled.Add,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("music_create_playlist_button")
                                )
                            }
                        }
                        if (playlists.isEmpty()) {
                            item(key = "playlists_empty") {
                                EmptyState(
                                    icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                                    title = stringResource(R.string.media_music_playlists_empty_title),
                                    body = stringResource(R.string.media_music_playlists_empty_body),
                                    modifier = Modifier.testTag("music_playlists_empty")
                                )
                            }
                        } else {
                            items(playlists, key = { it.id }) { playlist ->
                                PlaylistCard(
                                    playlist = playlist,
                                    onPlay = {
                                        ensureNotificationPermissionForPlayback()
                                        viewModel.playPlaylist(playlist)
                                    },
                                    onDelete = { viewModel.requestDeletePlaylist(playlist) },
                                    modifier = Modifier.testTag("music_playlist_card_${playlist.id}")
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    uiState.trackPendingPlaylistAdd?.let { track ->
        AddToPlaylistDialog(
            track = track,
            playlists = playlists,
            onSelectPlaylist = { viewModel.addTrackToPlaylist(it, track) },
            onCreateNew = {
                viewModel.dismissAddTrackToPlaylist()
                viewModel.selectTab(MusicTab.PLAYLISTS)
                viewModel.showCreatePlaylist(true)
            },
            onDismiss = viewModel::dismissAddTrackToPlaylist
        )
    }

    uiState.playlistPendingDelete?.let { playlist ->
        ConfirmationDialog(
            title = stringResource(R.string.media_music_delete_playlist_title),
            body = stringResource(R.string.media_music_delete_playlist_body, playlist.name),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = viewModel::confirmDeletePlaylist,
            onDismiss = viewModel::dismissDeletePlaylist,
            isDestructive = true,
            confirmTestTag = "music_confirm_delete_playlist_button",
            dismissTestTag = "music_cancel_delete_playlist_button"
        )
    }
}

@Composable
private fun NowPlayingDeck(
    playbackState: MusicPlaybackState,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    modifier: Modifier = Modifier
) {
    val track = playbackState.currentTrack
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("music_now_playing_card"),
        shape = ShareFlowDesign.shapes.featuredCard,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track?.title ?: stringResource(R.string.media_music_nothing_playing),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (track != null) "${track.artist} • ${track.album}" else stringResource(R.string.media_music_select_track_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            val maxDur = playbackState.durationMs.coerceAtLeast(1L).toFloat()
            Slider(
                value = playbackState.positionMs.coerceIn(0L, playbackState.durationMs.coerceAtLeast(1L)).toFloat(),
                onValueChange = { onSeekTo(it.toLong()) },
                valueRange = 0f..maxDur,
                enabled = track != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("music_seek_slider")
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatMediaDuration(playbackState.positionMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatMediaDuration(playbackState.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onToggleShuffle,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("music_shuffle_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shuffle,
                        contentDescription = stringResource(R.string.media_music_shuffle),
                        tint = if (playbackState.shuffleEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                IconButton(
                    onClick = onPrevious,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("music_previous_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipPrevious,
                        contentDescription = stringResource(R.string.media_action_previous)
                    )
                }
                IconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("music_play_pause_button")
                ) {
                    Icon(
                        imageVector = if (playbackState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(
                            if (playbackState.isPlaying) R.string.media_action_pause else R.string.media_action_play
                        ),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(34.dp)
                    )
                }
                IconButton(
                    onClick = onNext,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("music_next_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = stringResource(R.string.media_action_next)
                    )
                }
                IconButton(
                    onClick = onCycleRepeat,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("music_repeat_button")
                ) {
                    Icon(
                        imageVector = if (playbackState.repeatMode == MusicRepeatMode.ONE) {
                            Icons.Filled.RepeatOne
                        } else {
                            Icons.Filled.Repeat
                        },
                        contentDescription = stringResource(R.string.media_music_repeat),
                        tint = if (playbackState.repeatMode != MusicRepeatMode.OFF) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioTrackRow(
    track: MediaAudioTrack,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    onAddToPlaylist: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${track.artist} • ${formatMediaDuration(track.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onAddToPlaylist,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = stringResource(R.string.media_music_add_to_playlist)
                )
            }
        }
    }
}

@Composable
private fun PlaylistCard(
    playlist: MusicPlaylist,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.media_music_track_count, playlist.tracks.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(
                    imageVector = Icons.Filled.DeleteOutline,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun CreatePlaylistCard(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("music_create_playlist_dialog"),
        shape = ShareFlowDesign.shapes.featuredCard,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.media_music_create_playlist),
                style = MaterialTheme.typography.titleMedium
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.media_music_playlist_name_hint)) },
                shape = ShareFlowDesign.shapes.input,
                colors = com.example.shareflow.core.designsystem.components.ShareFlowInputDefaults.outlinedTextFieldColors(),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("music_playlist_name_input")
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = { onConfirm(name) },
                    enabled = name.isNotBlank(),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("music_create_playlist_confirm")
                ) {
                    Text(stringResource(R.string.action_create))
                }
            }
        }
    }
}

@Composable
private fun AddToPlaylistDialog(
    track: MediaAudioTrack,
    playlists: List<MusicPlaylist>,
    onSelectPlaylist: (MusicPlaylist) -> Unit,
    onCreateNew: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = ShareFlowDesign.shapes.dialog,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = ShareFlowDesign.elevation.modal,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = { Text(stringResource(R.string.media_music_add_to_playlist)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (playlists.isEmpty()) {
                    Text(
                        text = stringResource(R.string.media_music_playlists_empty_body),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    playlists.forEach { playlist ->
                        TextButton(
                            onClick = { onSelectPlaylist(playlist) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                        ) {
                            Text(
                                text = "${playlist.name} (${playlist.tracks.size})",
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onCreateNew,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Text(stringResource(R.string.media_music_create_playlist))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
