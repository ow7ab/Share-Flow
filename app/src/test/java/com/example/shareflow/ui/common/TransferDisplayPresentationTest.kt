package com.example.shareflow.ui.common

import com.example.shareflow.domain.model.TransferFileType
import com.example.shareflow.domain.model.TransferItem
import com.example.shareflow.domain.model.TransferState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferDisplayPresentationTest {

    @Test
    fun `cleanAppDisplayName strips split suffixes, version codes, and apk extension`() {
        assertEquals(
            "WhatsApp",
            TransferDisplayPresentation.cleanAppDisplayName("base.apk", "WhatsApp_v2.24.10_split_apk")
        )
        assertEquals(
            "Telegram Messenger",
            TransferDisplayPresentation.cleanAppDisplayName("Telegram_Messenger_v10.5.2.apk")
        )
    }

    @Test
    fun `groupTransferItemsForDisplay merges split APK bundle into single clean App item with combined progress`() {
        val items = listOf(
            TransferItem(
                itemId = "1",
                sessionId = "s1",
                fileName = "base.apk",
                relativePath = "WhatsApp_v2.24_split_apk",
                mimeType = "application/vnd.android.package-archive",
                fileType = TransferFileType.APK,
                fileSizeBytes = 600L,
                bytesTransferred = 600L,
                destinationUri = "file:///storage/WhatsApp_v2.24_split_apk/base.apk",
                state = TransferState.COMPLETED
            ),
            TransferItem(
                itemId = "2",
                sessionId = "s1",
                fileName = "split_config.arm64_v8a.apk",
                relativePath = "WhatsApp_v2.24_split_apk",
                mimeType = "application/vnd.android.package-archive",
                fileType = TransferFileType.APK,
                fileSizeBytes = 400L,
                bytesTransferred = 200L,
                state = TransferState.TRANSFERRING
            ),
            TransferItem(
                itemId = "3",
                sessionId = "s1",
                fileName = "shareflow_apk_bundle.json",
                relativePath = "WhatsApp_v2.24_split_apk",
                mimeType = "application/json",
                fileType = TransferFileType.OTHER,
                fileSizeBytes = 100L,
                bytesTransferred = 0L,
                state = TransferState.PENDING
            ),
            TransferItem(
                itemId = "4",
                sessionId = "s1",
                fileName = "holiday_photo.jpg",
                mimeType = "image/jpeg",
                fileType = TransferFileType.IMAGE,
                fileSizeBytes = 2_000L,
                bytesTransferred = 2_000L,
                destinationUri = "content://media/external/images/media/42",
                state = TransferState.COMPLETED
            )
        )

        val grouped = TransferDisplayPresentation.groupTransferItemsForDisplay(items)
        assertEquals(2, grouped.size)

        val appItem = grouped[0]
        assertTrue(appItem.isApp)
        assertEquals("WhatsApp", appItem.displayTitle)
        assertEquals(1100L, appItem.fileSizeBytes)
        assertEquals(800L, appItem.bytesTransferred)
        assertEquals(72, appItem.progressPercent)
        assertEquals(TransferState.TRANSFERRING, appItem.state)

        val photoItem = grouped[1]
        assertFalse(photoItem.isApp)
        assertEquals("holiday_photo.jpg", photoItem.displayTitle)
        assertEquals(TransferState.COMPLETED, photoItem.state)
        assertNotNull(TransferDisplayPresentation.toLocalFileEntry(photoItem))
    }
}
