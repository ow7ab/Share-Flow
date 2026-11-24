package com.example.shareflow.ui.files

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.R
import com.example.shareflow.core.common.SafDocumentInspector
import com.example.shareflow.core.common.ShareFlowFormatters
import com.example.shareflow.core.designsystem.components.LoadingState
import com.example.shareflow.core.designsystem.components.PrimaryActionButton
import com.example.shareflow.core.designsystem.components.SecondaryActionButton
import com.example.shareflow.core.designsystem.components.StatusPill
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.domain.model.FileCategory
import com.example.shareflow.domain.model.FileNameValidator
import com.example.shareflow.domain.model.FileSortField
import com.example.shareflow.domain.model.FileSortSpec
import com.example.shareflow.domain.model.FileSource
import com.example.shareflow.domain.model.GrantedDirectory
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.domain.model.SortDirection

/**
 * Helper to share single or multiple content URIs via Android Sharesheet with read grants.
 */
object FileSharesheetHelper {

    private fun resolveShareableUri(context: Context, rawUri: String): Uri? {
        val parsed = runCatching { Uri.parse(rawUri) }.getOrNull() ?: return null
        if (!parsed.scheme.equals("file", ignoreCase = true)) return parsed
        val path = parsed.path ?: return null
        val file = java.io.File(path)
        if (!file.exists()) return null
        return runCatching {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        }.getOrNull()
    }

    fun shareFiles(context: Context, entries: List<LocalFileEntry>, chooserTitle: String): Boolean {
        val fileEntries = entries.filterNot { it.isDirectory }
        if (fileEntries.isEmpty()) return false

        val uris = fileEntries.mapNotNull { resolveShareableUri(context, it.uriString) }
        if (uris.isEmpty()) return false

        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = fileEntries.first().mimeType.ifBlank { "*/*" }
                putExtra(Intent.EXTRA_STREAM, uris.first())
                clipData = ClipData.newRawUri(fileEntries.first().displayName, uris.first())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            val commonMime = fileEntries.map { it.mimeType.substringBefore('/') }.distinct().let { prefixes ->
                if (prefixes.size == 1) "${prefixes.first()}/*" else "*/*"
            }
            val clip = ClipData.newRawUri(fileEntries.first().displayName, uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = commonMime
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                clipData = clip
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        val chooser = Intent.createChooser(intent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(chooser)
            true
        }.getOrDefault(false)
    }

    fun openExternally(context: Context, entry: LocalFileEntry): Boolean {
        val uri = resolveShareableUri(context, entry.uriString) ?: return false
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, entry.mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileDetailsSheet(
    file: LocalFileEntry,
    sha256: String?,
    isComputingSha256: Boolean,
    onComputeSha256: () -> Unit,
    onDismiss: () -> Unit
) {
    val spacing = ShareFlowDesign.spacing
    val locale = ShareFlowDesign.locale
    val semantic = ShareFlowDesign.semanticColors

    val sourceLabel = when (file.source) {
        FileSource.SAF_TREE -> stringResource(R.string.files_source_saf_tree)
        FileSource.SAF_DOCUMENT -> stringResource(R.string.files_source_saf_doc)
        FileSource.MEDIA_STORE -> stringResource(R.string.files_source_media_store)
        FileSource.PHOTO_PICKER -> stringResource(R.string.files_source_photo_picker)
    }

    com.example.shareflow.core.designsystem.components.ShareFlowModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("file_details_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screenHorizontal, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Text(
                text = stringResource(R.string.files_details_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            DetailRow(stringResource(R.string.files_details_name), file.displayName)
            DetailRow(stringResource(R.string.files_details_type), file.mimeType)
            if (!file.isDirectory) {
                DetailRow(
                    stringResource(R.string.files_details_size),
                    "${ShareFlowFormatters.formatBytes(file.sizeBytes, locale)} (${
                        ShareFlowFormatters.formatInteger(file.sizeBytes, locale)
                    } B)"
                )
            }
            DetailRow(
                stringResource(R.string.files_details_modified),
                ShareFlowFormatters.formatTimestamp(file.lastModifiedEpochMillis, locale)
            )
            DetailRow(stringResource(R.string.files_details_source), sourceLabel)
            DetailRow(stringResource(R.string.files_details_uri), file.uriString)

            Text(
                text = stringResource(R.string.files_details_permissions),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (file.capabilities.canRead) {
                    StatusPill(
                        label = stringResource(R.string.files_details_perm_read),
                        containerColor = semantic.verifiedContainer,
                        contentColor = semantic.verifiedBadge
                    )
                }
                if (file.capabilities.canWrite) {
                    StatusPill(
                        label = stringResource(R.string.files_details_perm_write),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                if (file.capabilities.canRename) {
                    StatusPill(
                        label = stringResource(R.string.files_details_perm_rename),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                if (file.capabilities.canDelete) {
                    StatusPill(
                        label = stringResource(R.string.files_details_perm_delete),
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            if (!file.isDirectory) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (sha256 != null) {
                    DetailRow(stringResource(R.string.files_details_sha256_label), sha256)
                } else {
                    SecondaryActionButton(
                        text = stringResource(R.string.files_details_sha256_action),
                        onClick = onComputeSha256,
                        enabled = !isComputingSha256,
                        leadingIcon = Icons.Filled.VerifiedUser,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("file_details_compute_sha256")
                    )
                }
            }

            Spacer(modifier = Modifier.height(spacing.md))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = ShareFlowMonoStyle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun FileMediaPreviewDialog(
    file: LocalFileEntry,
    textPreview: String?,
    isLoadingText: Boolean,
    onShare: () -> Unit,
    onOpenExternal: () -> Unit,
    onDismiss: () -> Unit
) {
    val spacing = ShareFlowDesign.spacing
    val isImage = file.category == FileCategory.IMAGES || file.mimeType.startsWith("image/")
    val isVideo = file.category == FileCategory.VIDEOS || file.mimeType.startsWith("video/")
    val isAudio = file.category == FileCategory.AUDIO || file.mimeType.startsWith("audio/")
    val isText = SafDocumentInspector.isPreviewableText(file.mimeType, file.displayName)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = ShareFlowDesign.shapes.dialog,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = ShareFlowDesign.elevation.modal,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("file_preview_dialog"),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = file.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.action_close)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                when {
                    isImage -> {
                        AsyncImage(
                            model = Uri.parse(file.uriString),
                            contentDescription = file.displayName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 180.dp, max = 320.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .testTag("file_preview_image")
                        )
                    }

                    isVideo -> {
                        Media3InlinePreview(
                            uriString = file.uriString,
                            isVideo = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .testTag("file_preview_video")
                        )
                    }

                    isAudio -> {
                        Media3InlinePreview(
                            uriString = file.uriString,
                            isVideo = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("file_preview_audio")
                        )
                    }

                    isText -> {
                        if (isLoadingText) {
                            LoadingState()
                        } else {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 260.dp)
                            ) {
                                Text(
                                    text = textPreview.orEmpty(),
                                    style = ShareFlowMonoStyle,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .verticalScroll(rememberScrollState())
                                        .padding(spacing.sm)
                                        .testTag("file_preview_text")
                                )
                            }
                            Text(
                                text = stringResource(R.string.files_preview_truncated_note),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    else -> {
                        Text(
                            text = stringResource(R.string.files_preview_unsupported),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenExternal) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(4.dp))
                Text(stringResource(R.string.files_action_open_external))
            }
        },
        dismissButton = {
            TextButton(onClick = onShare) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(4.dp))
                Text(stringResource(R.string.files_action_share))
            }
        }
    )
}

@Composable
private fun Media3InlinePreview(
    uriString: String,
    isVideo: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }

    val player = remember(uriString) {
        runCatching {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(uriString)))
                prepare()
            }
        }.getOrNull()
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        player?.addListener(listener)
        onDispose {
            player?.removeListener(listener)
            player?.release()
        }
    }

    if (player == null) {
        Text(
            text = stringResource(R.string.files_preview_unsupported),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    if (isVideo) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                }
            },
            update = { view -> view.player = player },
            modifier = modifier
        )
    } else {
        Surface(
            modifier = modifier,
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.AudioFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    text = stringResource(R.string.files_category_audio),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { if (player.isPlaying) player.pause() else player.play() },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.files_action_preview),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

@Composable
fun RenameFileDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by rememberSaveable(initialName) { mutableStateOf(initialName) }
    val isValid = FileNameValidator.isValid(draft) && draft.trim() != initialName

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = ShareFlowDesign.shapes.dialog,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = ShareFlowDesign.elevation.modal,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("file_rename_dialog"),
        title = { Text(stringResource(R.string.files_rename_title)) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(stringResource(R.string.files_rename_label)) },
                shape = ShareFlowDesign.shapes.input,
                colors = com.example.shareflow.core.designsystem.components.ShareFlowInputDefaults.outlinedTextFieldColors(),
                isError = draft.isNotBlank() && !FileNameValidator.isValid(draft),
                supportingText = if (draft.isNotBlank() && !FileNameValidator.isValid(draft)) {
                    { Text(stringResource(R.string.files_rename_error)) }
                } else null,
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("file_rename_input")
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(draft.trim()) },
                enabled = isValid,
                modifier = Modifier.testTag("file_rename_confirm")
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun CreateFolderDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val isValid = FileNameValidator.isValid(draft)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = ShareFlowDesign.shapes.dialog,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = ShareFlowDesign.elevation.modal,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("create_folder_dialog"),
        title = { Text(stringResource(R.string.files_create_folder_title)) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(stringResource(R.string.files_create_folder_label)) },
                shape = ShareFlowDesign.shapes.input,
                colors = com.example.shareflow.core.designsystem.components.ShareFlowInputDefaults.outlinedTextFieldColors(),
                isError = draft.isNotBlank() && !isValid,
                supportingText = if (draft.isNotBlank() && !isValid) {
                    { Text(stringResource(R.string.files_rename_error)) }
                } else null,
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("create_folder_input")
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(draft.trim()) },
                enabled = isValid,
                modifier = Modifier.testTag("create_folder_confirm")
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortOptionsSheet(
    currentSpec: FileSortSpec,
    onSelectSort: (FileSortField, SortDirection) -> Unit,
    onDismiss: () -> Unit
) {
    val spacing = ShareFlowDesign.spacing
    var selectedField by remember { mutableStateOf(currentSpec.field) }
    var selectedDirection by remember { mutableStateOf(currentSpec.direction) }

    com.example.shareflow.core.designsystem.components.ShareFlowModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("files_sort_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.screenHorizontal, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Text(
                text = stringResource(R.string.files_sort_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs)
            ) {
                listOf(
                    FileSortField.NAME to R.string.files_sort_name,
                    FileSortField.DATE_MODIFIED to R.string.files_sort_date,
                    FileSortField.SIZE to R.string.files_sort_size,
                    FileSortField.TYPE to R.string.files_sort_type
                ).forEach { (field, labelRes) ->
                    val fieldSelected = selectedField == field
                    FilterChip(
                        selected = fieldSelected,
                        onClick = { selectedField = field },
                        label = { Text(stringResource(labelRes), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        shape = ShareFlowDesign.shapes.chip,
                        colors = com.example.shareflow.core.designsystem.components.ShareFlowChipDefaults.filterChipColors(),
                        border = com.example.shareflow.core.designsystem.components.ShareFlowChipDefaults.filterChipBorder(selected = fieldSelected),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("sort_field_${field.name.lowercase()}")
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs)
            ) {
                FilterChip(
                    selected = selectedDirection == SortDirection.ASCENDING,
                    onClick = { selectedDirection = SortDirection.ASCENDING },
                    label = { Text(stringResource(R.string.files_sort_asc)) },
                    shape = ShareFlowDesign.shapes.chip,
                    colors = com.example.shareflow.core.designsystem.components.ShareFlowChipDefaults.filterChipColors(),
                    border = com.example.shareflow.core.designsystem.components.ShareFlowChipDefaults.filterChipBorder(selected = selectedDirection == SortDirection.ASCENDING),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("sort_dir_asc")
                )
                FilterChip(
                    selected = selectedDirection == SortDirection.DESCENDING,
                    onClick = { selectedDirection = SortDirection.DESCENDING },
                    label = { Text(stringResource(R.string.files_sort_desc)) },
                    shape = ShareFlowDesign.shapes.chip,
                    colors = com.example.shareflow.core.designsystem.components.ShareFlowChipDefaults.filterChipColors(),
                    border = com.example.shareflow.core.designsystem.components.ShareFlowChipDefaults.filterChipBorder(selected = selectedDirection == SortDirection.DESCENDING),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("sort_dir_desc")
                )
            }

            PrimaryActionButton(
                text = stringResource(R.string.action_confirm),
                onClick = { onSelectSort(selectedField, selectedDirection) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sort_apply_button")
            )
            Spacer(modifier = Modifier.height(spacing.md))
        }
    }
}

@Composable
fun TargetDirectoryPickerDialog(
    operation: PendingTransferOperation,
    writableDirectories: List<GrantedDirectory>,
    onSelectDirectory: (String) -> Unit,
    onPickNewDirectory: () -> Unit,
    onDismiss: () -> Unit
) {
    val spacing = ShareFlowDesign.spacing
    val titleRes = if (operation == PendingTransferOperation.COPY) {
        R.string.files_target_copy_title
    } else {
        R.string.files_target_move_title
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = ShareFlowDesign.shapes.dialog,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = ShareFlowDesign.elevation.modal,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("target_directory_dialog"),
        title = { Text(stringResource(titleRes)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.xs)
            ) {
                if (writableDirectories.isEmpty()) {
                    Text(
                        text = stringResource(R.string.files_target_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    writableDirectories.forEach { dir ->
                        Surface(
                            onClick = { onSelectDirectory(dir.treeUriString) },
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(spacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = dir.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(spacing.xs))
                SecondaryActionButton(
                    text = stringResource(R.string.files_target_choose_new),
                    onClick = onPickNewDirectory,
                    leadingIcon = Icons.Filled.CreateNewFolder,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("target_pick_new_folder_button")
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
