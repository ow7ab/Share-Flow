package com.example.shareflow.ui.transfer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.example.shareflow.core.designsystem.theme.ShareFlowTheme
import com.example.shareflow.domain.model.AppLanguage
import com.example.shareflow.domain.model.ConnectionType
import com.example.shareflow.domain.model.PeerDevice
import com.example.shareflow.domain.model.TransferDirection
import com.example.shareflow.domain.model.TransferFileType
import com.example.shareflow.domain.model.TransferItem
import com.example.shareflow.domain.model.TransferSession
import com.example.shareflow.domain.model.TransferState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TransferProgressUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val samplePeer = PeerDevice(
        deviceId = "peer_99",
        displayName = "Studio Pixel 9",
        connectionType = ConnectionType.LOCAL_LAN_NSD,
        publicKeyFingerprint = "a".repeat(64)
    )

    @Test
    fun `transfer progress screen displays real metrics and dispatches pause and confirmed cancel`() {
        var pausedId: String? = null
        var cancelledId: String? = null

        val activeSession = TransferSession(
            sessionId = "tx_live_1",
            peer = samplePeer,
            direction = TransferDirection.SENT,
            state = TransferState.TRANSFERRING,
            transferItems = listOf(
                TransferItem(
                    itemId = "tx_live_1_0",
                    sessionId = "tx_live_1",
                    fileName = "4K_Documentary.mp4",
                    relativePath = "Videos",
                    mimeType = "video/mp4",
                    fileType = TransferFileType.VIDEO,
                    fileSizeBytes = 100L * 1024L * 1024L,
                    bytesTransferred = 50L * 1024L * 1024L,
                    chunkSizeBytes = 256 * 1024,
                    totalChunks = 400,
                    nextChunkIndex = 200,
                    state = TransferState.TRANSFERRING,
                    startedAtEpochMillis = 1_750_000_000_000L
                )
            ),
            currentSpeedBytesPerSec = 10L * 1024L * 1024L,
            estimatedRemainingSeconds = 5L,
            createdAtEpochMillis = 1_750_000_000_000L,
            updatedAtEpochMillis = 1_750_000_005_000L
        )

        val uiState = TransferProgressUiState(activeSessions = listOf(activeSession))

        composeRule.setContent {
            ShareFlowTheme(appLanguage = AppLanguage.ENGLISH) {
                TransferProgressContent(
                    uiState = uiState,
                    onNavigateBack = {},
                    onOpenSend = {},
                    onOpenHistory = {},
                    onPauseSession = { pausedId = it },
                    onResumeSession = {},
                    onRetrySession = {},
                    onCancelSession = { cancelledId = it },
                    onConsumeFeedback = {}
                )
            }
        }

        // Verify all required live telemetry fields
        composeRule.onNodeWithTag("transfer_file_name_tx_live_1")
            .assertIsDisplayed()
            .assertTextContains("Videos/4K_Documentary.mp4")
        composeRule.onNodeWithTag("transfer_device_name_tx_live_1")
            .assertIsDisplayed()
            .assertTextContains("Studio Pixel 9", substring = true)
        composeRule.onNodeWithTag("transfer_state_pill_tx_live_1").assertIsDisplayed()
        composeRule.onNodeWithText("Transferring").assertIsDisplayed()
        composeRule.onNodeWithTag("transfer_file_size_tx_live_1").assertIsDisplayed()
        composeRule.onNodeWithTag("transfer_percent_tx_live_1")
            .assertIsDisplayed()
            .assertTextContains("50%")
        composeRule.onNodeWithTag("transfer_speed_tx_live_1").assertIsDisplayed()
        composeRule.onNodeWithTag("transfer_eta_tx_live_1")
            .assertIsDisplayed()
            .assertTextContains("5s")

        // Tap Pause
        composeRule.onNodeWithTag("transfer_pause_button_tx_live_1").performClick()
        assertEquals("tx_live_1", pausedId)

        // Tap Cancel -> must require explicit confirmation dialog first
        composeRule.onNodeWithTag("transfer_cancel_button_tx_live_1").performClick()
        assertNull("Cancel must wait for confirmation dialog", cancelledId)
        composeRule.onNodeWithTag("confirmation_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("confirmation_dialog_confirm").performClick()
        assertEquals("tx_live_1", cancelledId)
    }

    @Test
    fun `paused failed and completed sessions show resume retry and verified controls in Arabic RTL`() {
        var resumedId: String? = null
        var retriedId: String? = null

        val pausedSession = TransferSession(
            sessionId = "tx_paused",
            peer = samplePeer,
            direction = TransferDirection.SENT,
            state = TransferState.PAUSED,
            isResumable = true,
            transferItems = listOf(
                TransferItem(
                    itemId = "tx_paused_0",
                    sessionId = "tx_paused",
                    fileName = "slides.pdf",
                    mimeType = "application/pdf",
                    fileType = TransferFileType.DOCUMENT,
                    fileSizeBytes = 2048L,
                    bytesTransferred = 1024L,
                    state = TransferState.PAUSED,
                    startedAtEpochMillis = 1_750_000_000_000L
                )
            ),
            createdAtEpochMillis = 1_750_000_000_000L,
            updatedAtEpochMillis = 1_750_000_001_000L
        )

        val failedSession = TransferSession(
            sessionId = "tx_failed",
            peer = samplePeer,
            direction = TransferDirection.SENT,
            state = TransferState.FAILED,
            isResumable = true,
            errorMessage = "Wi-Fi interrupted",
            transferItems = listOf(
                TransferItem(
                    itemId = "tx_failed_0",
                    sessionId = "tx_failed",
                    fileName = "archive.zip",
                    mimeType = "application/zip",
                    fileType = TransferFileType.ARCHIVE,
                    fileSizeBytes = 4096L,
                    bytesTransferred = 1024L,
                    state = TransferState.FAILED,
                    startedAtEpochMillis = 1_750_000_000_000L
                )
            ),
            createdAtEpochMillis = 1_750_000_000_000L,
            updatedAtEpochMillis = 1_750_000_002_000L
        )

        val completedSession = TransferSession(
            sessionId = "tx_done",
            peer = samplePeer,
            direction = TransferDirection.RECEIVED,
            state = TransferState.COMPLETED,
            isResumable = false,
            transferItems = listOf(
                TransferItem(
                    itemId = "tx_done_0",
                    sessionId = "tx_done",
                    fileName = "photo.jpg",
                    mimeType = "image/jpeg",
                    fileType = TransferFileType.IMAGE,
                    fileSizeBytes = 5120L,
                    bytesTransferred = 5120L,
                    verifiedSha256 = "c".repeat(64),
                    state = TransferState.COMPLETED,
                    startedAtEpochMillis = 1_750_000_000_000L,
                    completedAtEpochMillis = 1_750_000_003_000L
                )
            ),
            createdAtEpochMillis = 1_750_000_000_000L,
            updatedAtEpochMillis = 1_750_000_003_000L
        )

        val uiState = TransferProgressUiState(
            activeSessions = listOf(pausedSession, failedSession),
            recentCompletedSessions = listOf(completedSession)
        )

        composeRule.setContent {
            ShareFlowTheme(appLanguage = AppLanguage.ARABIC) {
                TransferProgressContent(
                    uiState = uiState,
                    onNavigateBack = {},
                    onOpenSend = {},
                    onOpenHistory = {},
                    onPauseSession = {},
                    onResumeSession = { resumedId = it },
                    onRetrySession = { retriedId = it },
                    onCancelSession = {},
                    onConsumeFeedback = {}
                )
            }
        }

        composeRule.onNodeWithTag("transfer_resume_button_tx_paused").assertIsDisplayed().performClick()
        assertEquals("tx_paused", resumedId)

        composeRule.onNodeWithTag("transfer_progress_list")
            .performScrollToNode(hasTestTag("transfer_retry_button_tx_failed"))
        composeRule.onNodeWithTag("transfer_retry_button_tx_failed").assertIsDisplayed().performClick()
        assertEquals("tx_failed", retriedId)

        composeRule.onNodeWithTag("transfer_progress_list")
            .performScrollToNode(hasTestTag("transfer_session_card_tx_done"))
        composeRule.onNodeWithTag("transfer_session_card_tx_done").assertIsDisplayed()
        composeRule.onNodeWithText("photo.jpg").assertIsDisplayed()
    }
}
