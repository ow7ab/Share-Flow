package com.example.shareflow.network.transfer

import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChunkProcessorSecurityAndIoTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dummyDataSource = object : TransferDataSource {
        override fun openInputStream(uriString: String): AppResult<InputStream> {
            val file = File(uriString)
            return if (file.exists()) {
                AppResult.Success(FileInputStream(file))
            } else {
                AppResult.Failure(AppError.NotFound("Missing"))
            }
        }

        override fun getFileSizeBytes(uriString: String): AppResult<Long> =
            AppResult.Success(File(uriString).length())

        override fun isReadable(uriString: String): Boolean = File(uriString).canRead()
    }

    private val processor = ChunkProcessor(
        dataSource = dummyDataSource,
        storageSpaceChecker = object : StorageSpaceChecker {
            override fun getAvailableBytes(directory: File): Long = 1024L * 1024L * 1024L
        }
    )

    @Test
    fun resolveTempPartFile_sanitizesTraversalInSessionIdAndItemId() {
        val receiveRoot = tempFolder.newFolder("ShareFlowReceived").canonicalFile
        val stagingRoot = File(receiveRoot, ".shareflow_staging").canonicalFile

        val tempFile = processor.resolveTempPartFile(
            destinationRoot = receiveRoot,
            sessionId = "../../outside_session",
            itemId = "../evil/item"
        )

        val canonicalTemp = tempFile.canonicalFile
        assertTrue(
            "Staging file must remain strictly inside .shareflow_staging: ${canonicalTemp.path}",
            canonicalTemp.path.startsWith(stagingRoot.path + File.separator)
        )
        assertFalse(canonicalTemp.name.contains(".."))
    }

    @Test
    fun finalizeToNonOverwritingDestination_blocksMaliciousFileNamesAndRelativeTraversal() {
        val receiveRoot = tempFolder.newFolder("ShareFlowReceived").canonicalFile
        val stagingFile = processor.resolveTempPartFile(receiveRoot, "sess_1", "item_1").apply {
            writeBytes("payload".toByteArray())
        }

        val traversalName = processor.finalizeToNonOverwritingDestination(
            tempFile = stagingFile,
            destinationRoot = receiveRoot,
            relativePath = "",
            fileName = "../escaped.txt"
        )
        assertTrue(traversalName is AppResult.Failure)
        assertTrue((traversalName as AppResult.Failure).error is AppError.Security)
        assertFalse(File(receiveRoot.parentFile, "escaped.txt").exists())

        val nestedSlashName = processor.finalizeToNonOverwritingDestination(
            tempFile = stagingFile,
            destinationRoot = receiveRoot,
            relativePath = "",
            fileName = "sub/escaped.txt"
        )
        assertTrue(nestedSlashName is AppResult.Failure)
        assertTrue((nestedSlashName as AppResult.Failure).error is AppError.Security)

        val dotDotName = processor.finalizeToNonOverwritingDestination(
            tempFile = stagingFile,
            destinationRoot = receiveRoot,
            relativePath = "",
            fileName = ".."
        )
        assertTrue(dotDotName is AppResult.Failure)
        assertTrue((dotDotName as AppResult.Failure).error is AppError.Security)

        val traversalRelPath = processor.finalizeToNonOverwritingDestination(
            tempFile = stagingFile,
            destinationRoot = receiveRoot,
            relativePath = "../ShareFlowReceivedEvil",
            fileName = "ok.txt"
        )
        assertTrue(traversalRelPath is AppResult.Failure)
        assertTrue((traversalRelPath as AppResult.Failure).error is AppError.Security)
    }

    @Test
    fun writeVerifiedChunkToOpenFile_writesMultipleChunksAndRejectsCorruption() {
        val receiveRoot = tempFolder.newFolder("ShareFlowReceived").canonicalFile
        val tempFile = processor.resolveTempPartFile(receiveRoot, "sess_open", "item_open")
        val chunk0 = "Hello ShareFlow ".toByteArray()
        val chunk1 = "Streaming Engine!".toByteArray()

        RandomAccessFile(tempFile, "rw").use { raf ->
            val res0 = processor.writeVerifiedChunkToOpenFile(
                raf = raf,
                byteOffset = 0L,
                data = chunk0,
                expectedChunkSha256 = ChunkProcessor.computeChunkSha256(chunk0)
            )
            assertTrue(res0 is AppResult.Success)

            val res1 = processor.writeVerifiedChunkToOpenFile(
                raf = raf,
                byteOffset = chunk0.size.toLong(),
                data = chunk1,
                expectedChunkSha256 = ChunkProcessor.computeChunkSha256(chunk1)
            )
            assertTrue(res1 is AppResult.Success)

            val badDigest = ChunkProcessor.computeChunkSha256("tampered".toByteArray())
            val corrupted = processor.writeVerifiedChunkToOpenFile(
                raf = raf,
                byteOffset = 0L,
                data = chunk0,
                expectedChunkSha256 = badDigest
            )
            assertTrue(corrupted is AppResult.Failure)
            assertTrue((corrupted as AppResult.Failure).error is AppError.IntegrityMismatch)
        }

        assertArrayEquals(chunk0 + chunk1, tempFile.readBytes())

        val finalized = processor.finalizeToNonOverwritingDestination(
            tempFile = tempFile,
            destinationRoot = receiveRoot,
            relativePath = "Documents/Nested",
            fileName = "report.txt"
        )
        assertTrue(finalized is AppResult.Success)
        val outFile = (finalized as AppResult.Success).data
        assertTrue(outFile.canonicalPath.startsWith(receiveRoot.path + File.separator))
        assertArrayEquals(chunk0 + chunk1, outFile.readBytes())

        processor.cleanupSessionStagingDirIfEmpty(receiveRoot, "sess_open")
        assertFalse(File(receiveRoot, ".shareflow_staging/sess_open").exists())
    }
}
