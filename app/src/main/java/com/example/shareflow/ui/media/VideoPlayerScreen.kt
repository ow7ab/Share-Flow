package com.example.shareflow.ui.media

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionDisabled
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.R
import com.example.shareflow.core.common.ShareFlowFormatters
import com.example.shareflow.core.designsystem.components.EmptyState
import com.example.shareflow.core.designsystem.components.ErrorState
import com.example.shareflow.core.designsystem.components.LoadingState
import com.example.shareflow.core.designsystem.components.ShareFlowTopBar
import com.example.shareflow.core.designsystem.theme.LocalShareFlowHostActivity
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.core.designsystem.theme.findActivity
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.core.di.shareFlowViewModelFactory
import com.example.shareflow.domain.model.MediaVideoItem
import com.example.shareflow.ui.viewer.CustomFileViewerDialog
import com.example.shareflow.ui.viewer.toViewerFileEntry
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun VideoPlayerScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSend: () -> Unit = {},
    onOpenVault: () -> Unit = {},
    modifier: Modifier = Modifier,
    providedViewModel: VideoPlayerViewModel? = null
) {
    val container = LocalAppContainer.current
    val viewModel: VideoPlayerViewModel = providedViewModel
        ?: androidx.lifecycle.viewmodel.compose.viewModel(
            factory = shareFlowViewModelFactory {
                VideoPlayerViewModel(container.mediaCenterRepository)
            }
        )
    val context = LocalContext.current
    val activity = LocalShareFlowHostActivity.current ?: context.findActivity()
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = ShareFlowDesign.spacing
    val locale = ShareFlowDesign.locale

    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var inspectVideoForVault by remember { mutableStateOf<MediaVideoItem?>(null) }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 25)
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addPickedVideoUris(uris.map { it.toString() })
        }
    }

    val subtitlePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.attachSubtitle(uri.toString(), uri.lastPathSegment)
        }
    }

    // Real AndroidX Media3 ExoPlayer with audio focus & becoming-noisy handling
    val exoPlayer = remember(context) {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        ExoPlayer.Builder(context)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    // Player listener for playback status & error handling
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (!playing) {
                    val active = uiState.currentVideo ?: return
                    viewModel.persistProgress(
                        mediaUri = active.uriString,
                        positionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
                        durationMs = exoPlayer.duration.takeIf { it > 0L } ?: active.durationMs,
                        speed = uiState.playbackSpeed
                    )
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val dur = exoPlayer.duration.takeIf { it > 0L } ?: (uiState.currentVideo?.durationMs ?: 0L)
                durationMs = dur
                positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            }

            override fun onPlayerError(error: PlaybackException) {
                isPlaying = false
                viewModel.reportPlaybackError(
                    error.localizedMessage
                        ?: "Unsupported video format or inaccessible file (${error.errorCodeName})."
                )
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            uiState.currentVideo?.let { active ->
                viewModel.persistProgress(
                    mediaUri = active.uriString,
                    positionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
                    durationMs = exoPlayer.duration.takeIf { it > 0L } ?: active.durationMs,
                    speed = uiState.playbackSpeed
                )
            }
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Pause cleanly on ON_STOP to prevent background battery drain
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                exoPlayer.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Handle fullscreen immersive mode & landscape orientation safely with guaranteed restoration
    DisposableEffect(uiState.isFullScreen, uiState.isLandscapeLocked, activity) {
        if (activity != null) {
            val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (uiState.isFullScreen) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
            activity.requestedOrientation = if (uiState.isLandscapeLocked) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        onDispose {
            if (activity != null) {
                val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    // Load active MediaItem whenever video or subtitle changes
    LaunchedEffect(
        uiState.currentVideo?.uriString,
        uiState.subtitleUriString,
        uiState.subtitleMimeType
    ) {
        val video = uiState.currentVideo ?: return@LaunchedEffect
        val builder = MediaItem.Builder().setUri(Uri.parse(video.uriString))
        val subUri = uiState.subtitleUriString
        val subMime = uiState.subtitleMimeType
        if (!subUri.isNullOrBlank() && !subMime.isNullOrBlank()) {
            val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUri))
                .setMimeType(subMime)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            builder.setSubtitleConfigurations(listOf(subtitleConfig))
        }
        exoPlayer.setMediaItem(builder.build(), uiState.resumePositionMs)
        exoPlayer.playbackParameters = PlaybackParameters(uiState.playbackSpeed)
        exoPlayer.prepare()
        durationMs = video.durationMs
        positionMs = uiState.resumePositionMs
    }

    LaunchedEffect(uiState.playbackSpeed) {
        exoPlayer.playbackParameters = PlaybackParameters(uiState.playbackSpeed)
    }

    LaunchedEffect(uiState.subtitlesEnabled) {
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !uiState.subtitlesEnabled)
            .build()
    }

    // Poll seek position ONLY while playing
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            durationMs = exoPlayer.duration.takeIf { it > 0L } ?: durationMs
            delay(500L)
        }
    }

    if (uiState.isFullScreen) {
        BackHandler { viewModel.setFullScreen(false) }
    }

    inspectVideoForVault?.let { vid ->
        val entry = remember(vid) { vid.toViewerFileEntry() }
        CustomFileViewerDialog(
            file = entry,
            onSendWithShareFlow = {
                inspectVideoForVault = null
                container.localSessionController.addSelectedFiles(listOf(entry))
                onNavigateToSend()
            },
            onMoveToVaultSuccess = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
            onOpenVaultSetup = {
                inspectVideoForVault = null
                onOpenVault()
            },
            onDismiss = { inspectVideoForVault = null }
        )
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("video_player_screen"),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                com.example.shareflow.core.designsystem.components.SFSnackbar(snackbarData = data)
            }
        },
        topBar = {
            if (!uiState.isFullScreen) {
                ShareFlowTopBar(
                    title = stringResource(R.string.media_video_title),
                    onNavigateBack = onNavigateBack,
                    actions = {
                        uiState.currentVideo?.let { activeVideo ->
                            IconButton(
                                onClick = {
                                    exoPlayer.pause()
                                    inspectVideoForVault = activeVideo
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("video_move_to_vault_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Lock,
                                    contentDescription = stringResource(R.string.viewer_action_move_to_vault)
                                )
                            }
                            IconButton(
                                onClick = {
                                    exoPlayer.pause()
                                    container.localSessionController.addSelectedFiles(listOf(activeVideo.toViewerFileEntry()))
                                    onNavigateToSend()
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("video_send_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = stringResource(R.string.viewer_action_send_shareflow)
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                videoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                )
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("video_add_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.VideoFile,
                                contentDescription = stringResource(R.string.media_video_pick)
                            )
                        }
                        IconButton(
                            onClick = viewModel::refresh,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("video_refresh_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.action_refresh)
                            )
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(if (uiState.isFullScreen) PaddingValues(0.dp) else innerPadding)

        when {
            uiState.isLoading -> {
                Box(modifier = contentModifier) {
                    LoadingState(
                        message = stringResource(R.string.media_video_loading),
                        modifier = Modifier.testTag("video_loading")
                    )
                }
            }
            uiState.loadError != null -> {
                Box(modifier = contentModifier.padding(spacing.screenHorizontal)) {
                    ErrorState(
                        message = uiState.loadError.orEmpty(),
                        onRetry = viewModel::refresh,
                        modifier = Modifier.testTag("video_load_error")
                    )
                }
            }
            uiState.videos.isEmpty() -> {
                Box(modifier = contentModifier.padding(spacing.screenHorizontal)) {
                    EmptyState(
                        icon = Icons.Filled.VideoLibrary,
                        title = stringResource(R.string.media_video_empty_title),
                        body = stringResource(R.string.media_video_empty_body),
                        actionLabel = stringResource(R.string.media_video_pick),
                        onAction = {
                            videoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            )
                        },
                        modifier = Modifier.testTag("video_empty_state")
                    )
                }
            }
            else -> {
                Column(modifier = contentModifier) {
                    // Video Surface + Media3 PlayerView
                    Box(
                        modifier = if (uiState.isFullScreen) {
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black)
                        } else {
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .background(Color.Black)
                        }
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    player = exoPlayer
                                    useController = false
                                    keepScreenOn = true
                                }
                            },
                            update = { view ->
                                if (view.player !== exoPlayer) view.player = exoPlayer
                                view.keepScreenOn = isPlaying
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("video_player_surface")
                        )

                        // Fullscreen floating controls overlay
                        if (uiState.isFullScreen) {
                            VideoControlDeck(
                                currentVideo = uiState.currentVideo,
                                isPlaying = isPlaying,
                                positionMs = positionMs,
                                durationMs = durationMs,
                                playbackSpeed = uiState.playbackSpeed,
                                isFullScreen = true,
                                hasPrevious = uiState.hasPrevious,
                                hasNext = uiState.hasNext,
                                subtitlesEnabled = uiState.subtitlesEnabled,
                                hasSubtitlesAttached = uiState.subtitleUriString != null,
                                onTogglePlayPause = {
                                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                },
                                onSeekTo = { target ->
                                    exoPlayer.seekTo(target)
                                    positionMs = target
                                },
                                onRewind10 = {
                                    val next = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                                    exoPlayer.seekTo(next)
                                    positionMs = next
                                },
                                onForward10 = {
                                    val maxDur = durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
                                    val next = (exoPlayer.currentPosition + 10_000L).coerceAtMost(maxDur)
                                    exoPlayer.seekTo(next)
                                    positionMs = next
                                },
                                onPrevious = viewModel::playPrevious,
                                onNext = viewModel::playNext,
                                onCycleSpeed = viewModel::cyclePlaybackSpeed,
                                onToggleFullScreen = { viewModel.setFullScreen(false) },
                                onToggleLandscape = viewModel::toggleLandscape,
                                onPickSubtitle = { subtitlePickerLauncher.launch(arrayOf("*/*")) },
                                onToggleSubtitles = viewModel::toggleSubtitles,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .background(Color.Black.copy(alpha = 0.65f))
                                    .navigationBarsPadding()
                                    .displayCutoutPadding()
                            )
                        }
                    }

                    if (!uiState.isFullScreen) {
                        uiState.playbackError?.let { err ->
                            Box(modifier = Modifier.padding(spacing.sm)) {
                                ErrorState(
                                    message = err,
                                    onRetry = {
                                        viewModel.clearPlaybackError()
                                        exoPlayer.prepare()
                                        exoPlayer.play()
                                    },
                                    modifier = Modifier.testTag("video_playback_error")
                                )
                            }
                        }

                        VideoControlDeck(
                            currentVideo = uiState.currentVideo,
                            isPlaying = isPlaying,
                            positionMs = positionMs,
                            durationMs = durationMs,
                            playbackSpeed = uiState.playbackSpeed,
                            isFullScreen = false,
                            hasPrevious = uiState.hasPrevious,
                            hasNext = uiState.hasNext,
                            subtitlesEnabled = uiState.subtitlesEnabled,
                            hasSubtitlesAttached = uiState.subtitleUriString != null,
                            onTogglePlayPause = {
                                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            },
                            onSeekTo = { target ->
                                exoPlayer.seekTo(target)
                                positionMs = target
                            },
                            onRewind10 = {
                                val next = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                                exoPlayer.seekTo(next)
                                positionMs = next
                            },
                            onForward10 = {
                                val maxDur = durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
                                val next = (exoPlayer.currentPosition + 10_000L).coerceAtMost(maxDur)
                                exoPlayer.seekTo(next)
                                positionMs = next
                            },
                            onPrevious = viewModel::playPrevious,
                            onNext = viewModel::playNext,
                            onCycleSpeed = viewModel::cyclePlaybackSpeed,
                            onToggleFullScreen = { viewModel.setFullScreen(true) },
                            onToggleLandscape = viewModel::toggleLandscape,
                            onPickSubtitle = { subtitlePickerLauncher.launch(arrayOf("*/*")) },
                            onToggleSubtitles = viewModel::toggleSubtitles
                        )

                        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 152.dp),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .testTag("video_queue_list"),
                            contentPadding = PaddingValues(
                                start = spacing.screenHorizontal,
                                top = spacing.sm,
                                end = spacing.screenHorizontal,
                                bottom = spacing.md + navBarBottom
                            ),
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                            verticalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            itemsIndexed(
                                items = uiState.videos,
                                key = { _, item -> item.uriString }
                            ) { index, item ->
                                VideoBrowserCard(
                                    video = item,
                                    index = index,
                                    isSelected = index == uiState.currentIndex,
                                    formattedSize = ShareFlowFormatters.formatBytes(item.sizeBytes, locale),
                                    onClick = { viewModel.selectVideo(index) },
                                    onSend = {
                                        exoPlayer.pause()
                                        container.localSessionController.addSelectedFiles(
                                            listOf(item.toViewerFileEntry())
                                        )
                                        onNavigateToSend()
                                    },
                                    onMoveToVault = {
                                        exoPlayer.pause()
                                        inspectVideoForVault = item
                                    },
                                    modifier = Modifier.testTag("video_list_item_$index")
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoControlDeck(
    currentVideo: MediaVideoItem?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    playbackSpeed: Float,
    isFullScreen: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    subtitlesEnabled: Boolean,
    hasSubtitlesAttached: Boolean,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onRewind10: () -> Unit,
    onForward10: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCycleSpeed: () -> Unit,
    onToggleFullScreen: () -> Unit,
    onToggleLandscape: () -> Unit,
    onPickSubtitle: () -> Unit,
    onToggleSubtitles: () -> Unit,
    modifier: Modifier = Modifier
) {
    val contentColor = if (isFullScreen) Color.White else MaterialTheme.colorScheme.onSurface
    val subtleColor = if (isFullScreen) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("video_controls_deck")
    ) {
        currentVideo?.let { video ->
            Text(
                text = video.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        val maxSlider = durationMs.coerceAtLeast(1L).toFloat()
        Slider(
            value = positionMs.coerceIn(0L, durationMs.coerceAtLeast(1L)).toFloat(),
            onValueChange = { onSeekTo(it.toLong()) },
            valueRange = 0f..maxSlider,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("video_seek_slider")
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatMediaDuration(positionMs),
                style = MaterialTheme.typography.labelSmall,
                color = subtleColor
            )
            Text(
                text = formatMediaDuration(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = subtleColor
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onPrevious,
                enabled = hasPrevious,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("video_previous_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.SkipPrevious,
                    contentDescription = stringResource(R.string.media_action_previous),
                    tint = if (hasPrevious) contentColor else subtleColor.copy(alpha = 0.4f)
                )
            }
            IconButton(
                onClick = onRewind10,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("video_rewind_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Replay10,
                    contentDescription = stringResource(R.string.media_action_rewind_10),
                    tint = contentColor
                )
            }
            IconButton(
                onClick = onTogglePlayPause,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("video_play_pause_button")
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (isPlaying) R.string.media_action_pause else R.string.media_action_play
                    ),
                    tint = contentColor,
                    modifier = Modifier.size(32.dp)
                )
            }
            IconButton(
                onClick = onForward10,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("video_forward_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Forward10,
                    contentDescription = stringResource(R.string.media_action_forward_10),
                    tint = contentColor
                )
            }
            IconButton(
                onClick = onNext,
                enabled = hasNext,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("video_next_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = stringResource(R.string.media_action_next),
                    tint = if (hasNext) contentColor else subtleColor.copy(alpha = 0.4f)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AssistChip(
                onClick = onCycleSpeed,
                label = { Text(text = String.format(Locale.US, "%.2gx", playbackSpeed)) },
                shape = ShareFlowDesign.shapes.chip,
                colors = com.example.shareflow.core.designsystem.components.ShareFlowChipDefaults.assistChipColors(),
                border = com.example.shareflow.core.designsystem.components.ShareFlowChipDefaults.assistChipBorder(),
                modifier = Modifier.testTag("video_speed_chip")
            )
            IconButton(
                onClick = onPickSubtitle,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("video_subtitle_picker_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Subtitles,
                    contentDescription = stringResource(R.string.media_video_add_subtitle),
                    tint = contentColor
                )
            }
            if (hasSubtitlesAttached) {
                IconButton(
                    onClick = onToggleSubtitles,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("video_subtitle_toggle_button")
                ) {
                    Icon(
                        imageVector = if (subtitlesEnabled) Icons.Filled.ClosedCaption else Icons.Filled.ClosedCaptionDisabled,
                        contentDescription = stringResource(R.string.media_video_toggle_subtitle),
                        tint = contentColor
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = onToggleLandscape,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("video_rotate_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.ScreenRotation,
                    contentDescription = stringResource(R.string.media_video_rotate),
                    tint = contentColor
                )
            }
            IconButton(
                onClick = onToggleFullScreen,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("video_fullscreen_button")
            ) {
                Icon(
                    imageVector = if (isFullScreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    contentDescription = stringResource(R.string.media_video_fullscreen),
                    tint = contentColor
                )
            }
        }
    }
}

@Composable
private fun VideoBrowserCard(
    video: MediaVideoItem,
    index: Int,
    isSelected: Boolean,
    formattedSize: String,
    onClick: () -> Unit,
    onSend: () -> Unit,
    onMoveToVault: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val thumbnailRequest = com.example.shareflow.core.common.rememberThumbnailImageRequest(
        uriString = video.uriString,
        targetSizePx = 300,
        isVideo = true
    )
    val cardShape = ShareFlowDesign.shapes.card
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1.25f)
            .clip(cardShape)
            .clickable(onClick = onClick),
        shape = cardShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            width = if (isSelected) 2.dp else 0.75.dp,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
            }
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 1. Full-bleed downsampled video thumbnail
            AsyncImage(
                model = thumbnailRequest,
                contentDescription = stringResource(R.string.media_video_play_badge_cd),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // 2. Centered translucent play badge
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.52f))
                    .border(1.dp, Color.White.copy(alpha = 0.45f), CircleShape)
                    .testTag("video_tile_play_badge_$index")
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.media_video_play_badge_cd),
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            // 3. Bottom gradient scrim displaying ONLY file size (no file name)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.68f)
                            )
                        )
                    )
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Text(
                    text = formattedSize,
                    style = ShareFlowMonoStyle.copy(
                        fontSize = MaterialTheme.typography.labelSmall.fontSize,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .testTag("video_tile_size_$index")
                )
            }

            // 4. Top-end action icon
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
            ) {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("video_tile_action_$index")
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.44f))
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.files_cd_more_options),
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.media_action_play)) },
                        leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                        onClick = { menuExpanded = false; onClick() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.viewer_action_send_shareflow)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                        onClick = { menuExpanded = false; onSend() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.viewer_action_move_to_vault)) },
                        leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                        onClick = { menuExpanded = false; onMoveToVault() }
                    )
                }
            }
        }
    }
}

internal fun formatMediaDuration(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L)) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
