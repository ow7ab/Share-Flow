package com.example.shareflow.data.files

import android.content.ContentResolver
import android.content.ContentUris
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.SafDocumentInspector
import com.example.shareflow.domain.model.FileCapabilities
import com.example.shareflow.domain.model.FileSource
import com.example.shareflow.domain.model.LocalFileEntry
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Compliant MediaStore reader and ContentObserver.
 * Queries only collections accessible under Android Scoped Storage without requiring broad permissions,
 * and gracefully reports SecurityException / permission denial without crashing.
 */
class MediaStoreScanner(
    private val contentResolver: ContentResolver
) {
    private data class CollectionSpec(
        val uri: Uri,
        val isDownloadCollection: Boolean
    )

    private fun collections(): List<CollectionSpec> = buildList {
        add(CollectionSpec(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false))
        add(CollectionSpec(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, false))
        add(CollectionSpec(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, false))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(CollectionSpec(MediaStore.Downloads.EXTERNAL_CONTENT_URI, true))
        }
    }

    fun scanAccessibleMedia(): AppResult<List<LocalFileEntry>> {
        val entries = LinkedHashMap<String, LocalFileEntry>()
        var securityFailures = 0
        val specs = collections()

        for (spec in specs) {
            try {
                queryCollection(spec.uri, spec.isDownloadCollection).forEach { entry ->
                    entries[entry.uriString] = entry
                }
            } catch (_: SecurityException) {
                securityFailures++
            } catch (_: Throwable) {
                // Skip individual unavailable collection without discarding others
            }
        }

        if (securityFailures == specs.size && entries.isEmpty()) {
            return AppResult.Failure(
                AppError.PermissionDenied(
                    "MediaStore access is currently restricted. Use Photo Picker or select a folder via SAF."
                )
            )
        }

        return AppResult.Success(entries.values.toList())
    }

    private fun queryCollection(
        collectionUri: Uri,
        isDownloadCollection: Boolean
    ): List<LocalFileEntry> {
        val results = ArrayList<LocalFileEntry>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )

        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        contentResolver.query(collectionUri, projection, null, null, sortOrder)?.use { cursor ->
            val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
            val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val modIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                if (idIdx < 0 || cursor.isNull(idIdx)) continue
                val id = cursor.getLong(idIdx)
                val itemUri = ContentUris.withAppendedId(collectionUri, id)
                val name = if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                    cursor.getString(nameIdx)
                } else {
                    "Media_$id"
                }
                val mime = if (mimeIdx >= 0 && !cursor.isNull(mimeIdx)) {
                    cursor.getString(mimeIdx) ?: "application/octet-stream"
                } else {
                    contentResolver.getType(itemUri) ?: "application/octet-stream"
                }
                val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                    cursor.getLong(sizeIdx).coerceAtLeast(0L)
                } else {
                    0L
                }
                val modifiedSeconds = if (modIdx >= 0 && !cursor.isNull(modIdx)) {
                    cursor.getLong(modIdx).coerceAtLeast(0L)
                } else {
                    0L
                }

                results.add(
                    LocalFileEntry(
                        uriString = itemUri.toString(),
                        displayName = name,
                        mimeType = mime,
                        sizeBytes = size,
                        lastModifiedEpochMillis = modifiedSeconds * 1000L,
                        category = SafDocumentInspector.classifyCategory(mime, name),
                        isDirectory = false,
                        parentUriString = collectionUri.toString(),
                        source = FileSource.MEDIA_STORE,
                        capabilities = FileCapabilities(
                            canRead = true,
                            canWrite = false,
                            canRename = false,
                            canDelete = true,
                            canCreateChildren = false
                        ),
                        isAccessible = true,
                        isDownload = isDownloadCollection
                    )
                )
            }
        }
        return results
    }

    fun observeChanges(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        val registered = collections().filter { spec ->
            runCatching {
                contentResolver.registerContentObserver(spec.uri, true, observer)
                true
            }.getOrDefault(false)
        }
        awaitClose {
            if (registered.isNotEmpty()) {
                runCatching { contentResolver.unregisterContentObserver(observer) }
            }
        }
    }
}
