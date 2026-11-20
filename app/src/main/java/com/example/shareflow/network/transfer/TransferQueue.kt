package com.example.shareflow.network.transfer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.example.shareflow.core.common.SafDocumentInspector
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.domain.model.TransferFileType
import com.example.shareflow.domain.model.TransferSourceItem
import java.io.File
import java.util.ArrayDeque

/**
 * Thread-safe queue managing pending transfer requests and recursive directory expansion
 * (preserving relative folder hierarchy for both local filesystem and Android SAF trees).
 */
class TransferQueue {

    private val lock = Any()
    private val queue = ArrayDeque<QueuedTransferBatch>()

    data class QueuedTransferBatch(
        val batchId: String,
        val items: List<TransferSourceItem>,
        val enqueuedAtEpochMillis: Long
    )

    fun enqueue(batch: QueuedTransferBatch) {
        synchronized(lock) {
            queue.addLast(batch)
        }
    }

    fun dequeue(): QueuedTransferBatch? = synchronized(lock) {
        if (queue.isEmpty()) null else queue.removeFirst()
    }

    fun peek(): QueuedTransferBatch? = synchronized(lock) {
        queue.peekFirst()
    }

    fun removeById(batchId: String): Boolean = synchronized(lock) {
        queue.removeIf { it.batchId == batchId }
    }

    fun snapshot(): List<QueuedTransferBatch> = synchronized(lock) {
        queue.toList()
    }

    fun size(): Int = synchronized(lock) { queue.size }

    fun clear() {
        synchronized(lock) { queue.clear() }
    }

    companion object {
        /**
         * Expands a local directory recursively into [TransferSourceItem] entries preserving
         * relative folder structure (e.g., `Project/assets/logo.png`).
         */
        fun expandLocalDirectory(rootDir: File): List<TransferSourceItem> {
            if (!rootDir.exists() || !rootDir.isDirectory) return emptyList()
            val results = mutableListOf<TransferSourceItem>()
            val rootName = rootDir.name.ifBlank { "Folder" }

            fun walk(current: File, currentRelative: String) {
                val children = current.listFiles()?.sortedBy { it.name.lowercase() } ?: return
                for (child in children) {
                    if (child.isDirectory) {
                        val nextRel = if (currentRelative.isBlank()) child.name else "$currentRelative/${child.name}"
                        walk(child, nextRel)
                    } else if (child.isFile && child.canRead()) {
                        val category = SafDocumentInspector.classifyCategory("application/octet-stream", child.name)
                        results.add(
                            TransferSourceItem(
                                uriString = Uri.fromFile(child).toString(),
                                fileName = child.name,
                                relativePath = currentRelative,
                                mimeType = "application/octet-stream",
                                fileType = TransferFileType.fromCategory(category),
                                fileSizeBytes = child.length().coerceAtLeast(0L)
                            )
                        )
                    }
                }
            }

            walk(rootDir, rootName)
            return results
        }

        /**
         * Expands a user-selected Android SAF directory tree URI recursively without requiring
         * broad storage permissions, preserving relative subfolder paths.
         */
        fun expandSafDirectoryTree(context: Context, treeUri: Uri): List<TransferSourceItem> {
            val resolver = context.contentResolver
            val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                ?: return emptyList()
            val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
            val rootName = SafDocumentInspector.inspectUri(context, rootDocUri)?.displayName
                ?.takeIf { it.isNotBlank() }
                ?: rootDocId.substringAfterLast(':').substringAfterLast('/').ifBlank { "SharedFolder" }

            val results = mutableListOf<TransferSourceItem>()
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            )

            fun walkTree(parentDocId: String, currentRelPath: String, depth: Int) {
                if (depth > MAX_DIRECTORY_DEPTH) return
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
                runCatching {
                    resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                        val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                        val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                        while (cursor.moveToNext()) {
                            val docId = if (idIdx >= 0 && !cursor.isNull(idIdx)) cursor.getString(idIdx) else continue
                            val name = if (nameIdx >= 0 && !cursor.isNull(nameIdx)) cursor.getString(nameIdx) else "item"
                            val mime = if (mimeIdx >= 0 && !cursor.isNull(mimeIdx)) {
                                cursor.getString(mimeIdx) ?: "application/octet-stream"
                            } else {
                                "application/octet-stream"
                            }
                            val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx).coerceAtLeast(0L) else 0L

                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                                val nextRel = if (currentRelPath.isBlank()) name else "$currentRelPath/$name"
                                walkTree(docId, nextRel, depth + 1)
                            } else {
                                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                                val category = SafDocumentInspector.classifyCategory(mime, name)
                                results.add(
                                    TransferSourceItem(
                                        uriString = docUri.toString(),
                                        fileName = name,
                                        relativePath = currentRelPath,
                                        mimeType = mime,
                                        fileType = TransferFileType.fromCategory(category),
                                        fileSizeBytes = size
                                    )
                                )
                            }
                        }
                    }
                }
            }

            walkTree(rootDocId, rootName, 0)
            return results
        }

        fun fromLocalFileEntry(entry: LocalFileEntry, relativePath: String = ""): TransferSourceItem =
            TransferSourceItem(
                uriString = entry.uriString,
                fileName = entry.displayName,
                relativePath = relativePath,
                mimeType = entry.mimeType,
                fileType = TransferFileType.fromCategory(entry.category),
                fileSizeBytes = entry.sizeBytes.coerceAtLeast(0L)
            )

        private const val MAX_DIRECTORY_DEPTH = 32
    }
}
