package com.example.shareflow.ui.viewer

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.SafDocumentInspector
import com.example.shareflow.core.common.ShareFlowFormatters
import com.example.shareflow.core.designsystem.components.LoadingState
import com.example.shareflow.core.designsystem.components.StatusPill
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.domain.model.FileCategory
import com.example.shareflow.domain.model.FileSource
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.domain.model.MediaImageItem
import com.example.shareflow.domain.model.MediaVideoItem
import com.example.shareflow.domain.model.TransferFileType
import com.example.shareflow.domain.model.TransferRecord
import com.example.shareflow.domain.model.VaultLockState
import com.example.shareflow.ui.media.formatMediaDuration
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen Material 3 Custom File Viewer powered by:
 * - **Media3 ExoPlayer** for smooth video & audio playback with lifecycle-aware pause/release and speed control.
 * - **Coil** for crisp image rendering with pinch-to-zoom, pan, and double-tap zoom gestures.
 * - Direct pre-share / pre-vault actions: **Send via ShareFlow**, **Move to Private Vault (AES-256-GCM)**,
 *   **Install APK**, System Share, and Open Externally.
 */
@Composable
fun CustomFileViewerDialog(
    file: LocalFileEntry,
    onSendWithShareFlow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    textPreview: String? = null,
    isLoadingText: Boolean = false,
    primaryShareLabel: String? = null,
    onShareExternal: (() -> Unit)? = null,
    onOpenExternal: (() -> Unit)? = null,
    onMoveToVaultSuccess: ((String) -> Unit)? = null,
    onOpenVaultSetup: (() -> Unit)? = null,
    onInstallApk: (() -> Unit)? = null
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val spacing = ShareFlowDesign.spacing
    val locale = ShareFlowDesign.locale
    val semantic = ShareFlowDesign.semanticColors

    val isImage = file.category == FileCategory.IMAGES || file.mimeType.startsWith("image/", ignoreCase = true)
    val isVideo = file.category == FileCategory.VIDEOS || file.mimeType.startsWith("video/", ignoreCase = true)
    val isAudio = file.category == FileCategory.AUDIO || file.mimeType.startsWith("audio/", ignoreCase = true)
    val isApk = file.category == FileCategory.APKS ||
        file.displayName.endsWith(".apk", ignoreCase = true) ||
        file.mimeType.equals("application/vnd.android.package-archive", ignoreCase = true)
    val isText = !isImage && !isVideo && !isAudio && !isApk &&
        SafDocumentInspector.isPreviewableText(file.mimeType, file.displayName)

    var loadedText by remember(file.uriString, textPreview) { mutableStateOf(textPreview) }
    var loadingTextInternal by remember(file.uriString, isLoadingText) { mutableStateOf(isLoadingText) }

    LaunchedEffect(file.uriString, isText, textPreview) {
        if (isText && textPreview == null && loadedText == null) {
            loadingTextInternal = true
            val result = container.fileManagerRepository.readTextPreview(file.uriString)
            loadedText = (result as? AppResult.Success)?.data
            loadingTextInternal = false
        }
    }

    var isMovingToVault by remember { mutableStateOf(false) }
    var vaultBannerMessage by remember { mutableStateOf<String?>(null) }
    var vaultBannerIsError by remember { mutableStateOf(false) }
    var showVaultPinDialog by remember { mutableStateOf(false) }
    var showVaultNotSetupDialog by remember { mutableStateOf(false) }
    var vaultPinError by remember { mutableStateOf<String?>(null) }
    var isUnlockingVault by remember { mutableStateOf(false) }

    val vaultMovedTemplate = stringResource(R.string.viewer_vault_moved_success)

    suspend fun performVaultImport() {
        isMovingToVault = true
        vaultBannerMessage = null
        vaultBannerIsError = false
        val result = withContext(Dispatchers.IO) {
            container.vaultManager.importFile(
                displayName = file.displayName,
                mimeType = file.mimeType.ifBlank { "application/octet-stream" }
            ) {
                openLocalFileStream(context, file.uriString)
            }
        }
        isMovingToVault = false
        when (result) {
            is AppResult.Success -> {
                val msg = String.format(locale, vaultMovedTemplate, file.displayName)
                vaultBannerMessage = msg
                vaultBannerIsError = false
                onMoveToVaultSuccess?.invoke(msg)
            }
            is AppResult.Failure -> {
                vaultBannerMessage = result.error.message
                vaultBannerIsError = true
            }
        }
    }

    fun requestMoveToVault() {
        if (isMovingToVault) return
        scope.launch {
            val status = container.vaultManager.refreshStatus()
            when (status.lockState) {
                VaultLockState.UNLOCKED -> performVaultImport()
                VaultLockState.LOCKED -> {
                    vaultPinError = null
                    showVaultPinDialog = true
                }
                VaultLockState.NOT_INITIALIZED -> {
                    showVaultNotSetupDialog = true
                }
            }
        }
    }

    if (showVaultPinDialog) {
        VaultQuickUnlockDialog(
            isUnlocking = isUnlockingVault,
            errorMessage = vaultPinError,
            onConfirmPin = { pin ->
                scope.launch {
                    isUnlockingVault = true
                    vaultPinError = null
                    when (val unlockRes = container.vaultManager.unlockWithPin(pin)) {
                        is AppResult.Success -> {
                            isUnlockingVault = false
                            showVaultPinDialog = false
                            performVaultImport()
                        }
                        is AppResult.Failure -> {
                            isUnlockingVault = false
                            vaultPinError = unlockRes.error.message
                        }
                    }
                }
            },
            onDismiss = {
                if (!isUnlockingVault) showVaultPinDialog = false
            }
        )
    }

    if (showVaultNotSetupDialog) {
        AlertDialog(
            onDismissRequest = { showVaultNotSetupDialog = false },
            modifier = Modifier.testTag("viewer_vault_setup_dialog"),
            icon = {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text(stringResource(R.string.viewer_vault_not_setup_title)) },
            text = { Text(stringResource(R.string.viewer_vault_not_setup_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        showVaultNotSetupDialog = false
                        onOpenVaultSetup?.invoke()
                    },
                    enabled = onOpenVaultSetup != null,
                    modifier = Modifier.testTag("viewer_vault_setup_confirm")
                ) {
                    Text(stringResource(R.string.viewer_vault_setup_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showVaultNotSetupDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    val isMediaDarkStage = isImage || isVideo

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = modifier
                .fillMaxSize()
                .testTag("custom_file_viewer_dialog"),
            color = if (isMediaDarkStage) {
                com.example.shareflow.core.designsystem.theme.ShareFlowBrandColors.DeepNavy
            } else {
                MaterialTheme.colorScheme.background
            }
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top Bar
                Surface(
                    color = if (isMediaDarkStage) {
                        Color.Black.copy(alpha = 0.72f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                    tonalElevation = if (isMediaDarkStage) 0.dp else 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = spacing.xs, vertical = spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                    ) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("viewer_close_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.action_close),
                                tint = if (isMediaDarkStage) Color.White else MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = file.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isMediaDarkStage) Color.White else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("viewer_file_title")
                            )
                            Text(
                                text = "${ShareFlowFormatters.formatBytes(file.sizeBytes, locale)} • ${
                                    file.mimeType.ifBlank { "application/octet-stream" }
                                }",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isMediaDarkStage) {
                                    Color.White.copy(alpha = 0.72f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (onShareExternal != null) {
                            IconButton(
                                onClick = onShareExternal,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("viewer_action_system_share")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Share,
                                    contentDescription = stringResource(R.string.files_action_share),
                                    tint = if (isMediaDarkStage) Color.White else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        if (onOpenExternal != null) {
                            IconButton(
                                onClick = onOpenExternal,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("viewer_action_open_external")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.OpenInNew,
                                    contentDescription = stringResource(R.string.files_action_open_external),
                                    tint = if (isMediaDarkStage) Color.White else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                // Inline Vault Feedback Banner
                vaultBannerMessage?.let { msg ->
                    Surface(
                        color = if (vaultBannerIsError) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            semantic.verifiedContainer
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("viewer_vault_feedback_banner")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = spacing.screenHorizontal, vertical = spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            Icon(
                                imageVector = if (vaultBannerIsError) Icons.Filled.ErrorOutline else Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = if (vaultBannerIsError) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    semantic.verifiedBadge
                                },
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (vaultBannerIsError) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    semantic.verifiedBadge
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Main Media / Content Preview Stage
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isImage -> {
                            CoilZoomableImageStage(
                                uriString = file.uriString,
                                displayName = file.displayName,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        isVideo -> {
                            Media3VideoStage(
                                uriString = file.uriString,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        isAudio -> {
                            Media3AudioStage(
                                uriString = file.uriString,
                                displayName = file.displayName,
                                sizeBytes = file.sizeBytes,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = spacing.maxContentWidth)
                                    .padding(spacing.screenHorizontal)
                            )
                        }

                        isText -> {
                            TextPreviewStage(
                                textContent = loadedText,
                                isLoading = loadingTextInternal,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .widthIn(max = spacing.maxContentWidth)
                                    .padding(spacing.screenHorizontal, spacing.md)
                            )
                        }

                        else -> {
                            GenericFilePreviewCard(
                                file = file,
                                isApk = isApk,
                                onInstallApk = onInstallApk,
                                onOpenExternal = onOpenExternal,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = spacing.maxContentWidth)
                                    .padding(spacing.screenHorizontal)
                            )
                        }
                    }
                }

                // Bottom Action Dock: Send via ShareFlow + Move to Private Vault (+ Install APK)
                Surface(
                    color = if (isMediaDarkStage) {
                        com.example.shareflow.core.designsystem.theme.ShareFlowBrandColors.GlassSurfaceLow
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                    tonalElevation = 3.dp,
                    border = BorderStroke(
                        0.5.dp,
                        if (isMediaDarkStage) Color.White.copy(alpha = 0.12f) else MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = spacing.screenHorizontal, vertical = spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(spacing.xs)
                    ) {
                        if (isApk && onInstallApk != null) {
                            OutlinedButton(
                                onClick = onInstallApk,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .minimumInteractiveComponentSize()
                                    .testTag("viewer_action_install_apk")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.InstallMobile,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                                Text(text = stringResource(R.string.viewer_action_install_apk))
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledTonalButton(
                                onClick = { requestMoveToVault() },
                                enabled = !isMovingToVault,
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("viewer_action_move_to_vault")
                            ) {
                                if (isMovingToVault) {
                                    CircularProgressIndicator(
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(16.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.Lock,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.size(6.dp))
                                Text(
                                    text = if (isMovingToVault) {
                                        stringResource(R.string.viewer_vault_moving)
                                    } else {
                                        stringResource(R.string.viewer_action_move_to_vault)
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Button(
                                onClick = onSendWithShareFlow,
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("viewer_action_send")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.size(6.dp))
                                Text(
                                    text = primaryShareLabel ?: stringResource(R.string.viewer_action_send_shareflow),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
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
private fun CoilZoomableImageStage(
    uriString: String,
    displayName: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var scale by remember(uriString) { mutableFloatStateOf(1f) }
    var offset by remember(uriString) { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .pointerInput(uriString) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1.25f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    }
                )
            }
            .pointerInput(uriString) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val nextScale = (scale * zoom).coerceIn(1f, 5f)
                    scale = nextScale
                    offset = if (nextScale > 1f) {
                        Offset(
                            x = (offset.x + pan.x).coerceIn(-750f * nextScale, 750f * nextScale),
                            y = (offset.y + pan.y).coerceIn(-750f * nextScale, 750f * nextScale)
                        )
                    } else {
                        Offset.Zero
                    }
                }
            }
            .testTag("viewer_image_content"),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(Uri.parse(uriString))
                .crossfade(true)
                .build(),
            contentDescription = displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )

        if (scale > 1.05f) {
            Surface(
                onClick = {
                    scale = 1f
                    offset = Offset.Zero
                },
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.68f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .testTag("viewer_reset_zoom_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ZoomOutMap,
                        contentDescription = stringResource(R.string.viewer_action_reset_zoom),
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = String.format(Locale.US, "%.1fx", scale),
                        style = ShareFlowMonoStyle,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun Media3VideoStage(
    uriString: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isBuffering by remember(uriString) { mutableStateOf(true) }
    var playbackError by remember(uriString) { mutableStateOf<String?>(null) }
    val speeds = remember { listOf(0.5f, 1.0f, 1.5f, 2.0f) }
    var speedIndex by remember(uriString) { mutableStateOf(1) }

    val exoPlayer = remember(uriString) {
        runCatching {
            val audioAttrs = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()
            ExoPlayer.Builder(context.applicationContext)
                .setAudioAttributes(audioAttrs, true)
                .setHandleAudioBecomingNoisy(true)
                .build()
                .apply {
                    setMediaItem(MediaItem.fromUri(Uri.parse(uriString)))
                    prepare()
                    playWhenReady = true
                }
        }.getOrNull()
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
            }

            override fun onPlayerError(error: PlaybackException) {
                isBuffering = false
                playbackError = error.localizedMessage ?: error.errorCodeName
            }
        }
        exoPlayer?.addListener(listener)
        onDispose {
            exoPlayer?.removeListener(listener)
            exoPlayer?.release()
        }
    }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                exoPlayer?.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(
        modifier = modifier.testTag("viewer_video_player"),
        contentAlignment = Alignment.Center
    ) {
        if (exoPlayer == null || playbackError != null) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(40.dp)
                )
                Text(
                    text = playbackError ?: stringResource(R.string.viewer_video_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
            }
        } else {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                        keepScreenOn = true
                    }
                },
                update = { view ->
                    if (view.player !== exoPlayer) {
                        view.player = exoPlayer
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            )

            if (isBuffering) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }

            AssistChip(
                onClick = {
                    val nextIdx = (speedIndex + 1) % speeds.size
                    speedIndex = nextIdx
                    exoPlayer.playbackParameters = PlaybackParameters(speeds[nextIdx])
                },
                label = {
                    Text(
                        text = stringResource(
                            R.string.viewer_speed_label,
                            String.format(Locale.US, "%.2gx", speeds[speedIndex])
                        )
                    )
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .testTag("viewer_speed_chip")
            )
        }
    }
}

@Composable
private fun Media3AudioStage(
    uriString: String,
    displayName: String,
    sizeBytes: Long,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val spacing = ShareFlowDesign.spacing
    val locale = ShareFlowDesign.locale

    var isPlaying by remember(uriString) { mutableStateOf(false) }
    var positionMs by remember(uriString) { mutableLongStateOf(0L) }
    var durationMs by remember(uriString) { mutableLongStateOf(0L) }
    var errorMsg by remember(uriString) { mutableStateOf<String?>(null) }

    val exoPlayer = remember(uriString) {
        runCatching {
            val audioAttrs = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()
            ExoPlayer.Builder(context.applicationContext)
                .setAudioAttributes(audioAttrs, true)
                .setHandleAudioBecomingNoisy(true)
                .build()
                .apply {
                    setMediaItem(MediaItem.fromUri(Uri.parse(uriString)))
                    prepare()
                }
        }.getOrNull()
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val dur = exoPlayer?.duration ?: 0L
                if (dur > 0L) durationMs = dur
            }

            override fun onPlayerError(error: PlaybackException) {
                isPlaying = false
                errorMsg = error.localizedMessage ?: error.errorCodeName
            }
        }
        exoPlayer?.addListener(listener)
        onDispose {
            exoPlayer?.removeListener(listener)
            exoPlayer?.release()
        }
    }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                exoPlayer?.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(isPlaying, exoPlayer) {
        while (isPlaying && exoPlayer != null) {
            positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            val dur = exoPlayer.duration
            if (dur > 0L) durationMs = dur
            delay(400L)
        }
    }

    Surface(
        modifier = modifier.testTag("viewer_audio_player"),
        shape = ShareFlowDesign.shapes.featuredCard,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(80.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.AudioFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = ShareFlowFormatters.formatBytes(sizeBytes, locale),
                style = ShareFlowMonoStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (errorMsg != null || exoPlayer == null) {
                Text(
                    text = errorMsg ?: stringResource(R.string.files_preview_unsupported),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                val maxSlider = durationMs.coerceAtLeast(1L).toFloat()
                Slider(
                    value = positionMs.coerceIn(0L, durationMs.coerceAtLeast(1L)).toFloat(),
                    onValueChange = {
                        val pos = it.toLong()
                        positionMs = pos
                        exoPlayer.seekTo(pos)
                    },
                    valueRange = 0f..maxSlider,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatMediaDuration(positionMs),
                        style = ShareFlowMonoStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatMediaDuration(durationMs),
                        style = ShareFlowMonoStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            val next = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                            exoPlayer.seekTo(next)
                            positionMs = next
                        },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Replay10,
                            contentDescription = stringResource(R.string.media_action_rewind_10)
                        )
                    }
                    Surface(
                        onClick = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = stringResource(
                                    if (isPlaying) R.string.media_action_pause else R.string.media_action_play
                                ),
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            val maxDur = durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
                            val next = (exoPlayer.currentPosition + 10_000L).coerceAtMost(maxDur)
                            exoPlayer.seekTo(next)
                            positionMs = next
                        },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Forward10,
                            contentDescription = stringResource(R.string.media_action_forward_10)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TextPreviewStage(
    textContent: String?,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    val spacing = ShareFlowDesign.spacing
    if (isLoading) {
        LoadingState()
    } else {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            Surface(
                shape = ShareFlowDesign.shapes.card,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Text(
                    text = textContent?.takeIf { it.isNotEmpty() }
                        ?: stringResource(R.string.files_preview_unsupported),
                    style = ShareFlowMonoStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(spacing.md)
                        .testTag("viewer_text_content")
                )
            }
            Text(
                text = stringResource(R.string.files_preview_truncated_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GenericFilePreviewCard(
    file: LocalFileEntry,
    isApk: Boolean,
    onInstallApk: (() -> Unit)?,
    onOpenExternal: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val spacing = ShareFlowDesign.spacing
    val locale = ShareFlowDesign.locale
    val icon = when {
        isApk -> Icons.Filled.Android
        file.category == FileCategory.ARCHIVES -> Icons.Filled.Archive
        file.category == FileCategory.DOCUMENTS -> Icons.Filled.Description
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }

    Surface(
        modifier = modifier.testTag("viewer_file_card"),
        shape = ShareFlowDesign.shapes.featuredCard,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
            Text(
                text = file.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            StatusPill(
                label = ShareFlowFormatters.formatBytes(file.sizeBytes, locale),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = file.mimeType.ifBlank { "application/octet-stream" },
                style = ShareFlowMonoStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (file.lastModifiedEpochMillis > 0L) {
                Text(
                    text = ShareFlowFormatters.formatTimestamp(file.lastModifiedEpochMillis, locale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isApk && onInstallApk == null && onOpenExternal != null) {
                OutlinedButton(
                    onClick = onOpenExternal,
                    modifier = Modifier.padding(top = spacing.xs)
                ) {
                    Icon(Icons.Filled.InstallMobile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.viewer_action_install_apk))
                }
            } else if (!isApk && onOpenExternal != null) {
                OutlinedButton(
                    onClick = onOpenExternal,
                    modifier = Modifier.padding(top = spacing.xs)
                ) {
                    Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.files_action_open_external))
                }
            }
        }
    }
}

@Composable
private fun VaultQuickUnlockDialog(
    isUnlocking: Boolean,
    errorMessage: String?,
    onConfirmPin: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("viewer_vault_pin_dialog"),
        icon = {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(stringResource(R.string.viewer_vault_locked_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.viewer_vault_locked_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { next -> pin = next.filter { it.isDigit() }.take(12) },
                    label = { Text(stringResource(R.string.viewer_vault_pin_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { err -> { Text(err) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("viewer_vault_pin_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmPin(pin) },
                enabled = pin.length >= 4 && !isUnlocking,
                modifier = Modifier.testTag("viewer_vault_pin_confirm")
            ) {
                Text(stringResource(R.string.viewer_vault_unlock_and_move))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isUnlocking) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

internal fun openLocalFileStream(context: Context, uriString: String): InputStream {
    val uri = Uri.parse(uriString)
    if (uri.scheme.equals("file", ignoreCase = true)) {
        val path = uri.path ?: throw IOException("Invalid file URI path.")
        return FileInputStream(File(path))
    }
    return context.contentResolver.openInputStream(uri)
        ?: throw IOException("Unable to open file stream for $uriString")
}

fun MediaImageItem.toViewerFileEntry(): LocalFileEntry = LocalFileEntry(
    uriString = uriString,
    displayName = displayName,
    mimeType = mimeType.ifBlank { "image/*" },
    sizeBytes = sizeBytes,
    lastModifiedEpochMillis = dateModifiedEpochMillis,
    category = FileCategory.IMAGES,
    isDirectory = false,
    source = FileSource.MEDIA_STORE
)

fun MediaVideoItem.toViewerFileEntry(): LocalFileEntry = LocalFileEntry(
    uriString = uriString,
    displayName = displayName,
    mimeType = mimeType.ifBlank { "video/*" },
    sizeBytes = sizeBytes,
    lastModifiedEpochMillis = dateModifiedEpochMillis,
    category = FileCategory.VIDEOS,
    isDirectory = false,
    source = FileSource.MEDIA_STORE
)

fun TransferRecord.toViewerFileEntry(): LocalFileEntry? {
    val uri = localUri?.takeIf { it.isNotBlank() } ?: return null
    val category = when (fileType) {
        TransferFileType.IMAGE -> FileCategory.IMAGES
        TransferFileType.VIDEO -> FileCategory.VIDEOS
        TransferFileType.AUDIO -> FileCategory.AUDIO
        TransferFileType.DOCUMENT -> FileCategory.DOCUMENTS
        TransferFileType.APK -> FileCategory.APKS
        TransferFileType.ARCHIVE -> FileCategory.ARCHIVES
        TransferFileType.OTHER -> FileCategory.OTHER
    }
    return LocalFileEntry(
        uriString = uri,
        displayName = fileName,
        mimeType = mimeType.ifBlank { "application/octet-stream" },
        sizeBytes = fileSizeBytes,
        lastModifiedEpochMillis = completedAtEpochMillis ?: startedAtEpochMillis,
        category = category,
        isDirectory = false,
        source = FileSource.MEDIA_STORE
    )
}
