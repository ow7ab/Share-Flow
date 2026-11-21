package com.example.shareflow.ui.discovery

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.example.shareflow.core.designsystem.theme.ShareFlowTheme
import com.example.shareflow.domain.model.QrScannerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QrScannerRetryUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `scanner shows Scan in progress state and immediate inline Retry action on invalid and expired codes`() {
        var currentState by mutableStateOf(QrScannerUiState.SCANNING)
        var retryClicks = 0

        composeTestRule.setContent {
            ShareFlowTheme {
                AdvancedQrScannerView(
                    scannerState = currentState,
                    onQrDecoded = {},
                    onRetryScan = {
                        retryClicks++
                        currentState = QrScannerUiState.SCANNING
                    }
                )
            }
        }

        // 1. Active scanning state shows "Scan in progress" badge and no error overlay
        composeTestRule.onNodeWithTag("qr_scanner_state_badge").assertIsDisplayed()
        assertTrue(
            composeTestRule.onAllNodesWithTag("qr_scanner_error_overlay", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        )

        // 2. Invalid QR immediately displays inline error overlay + Retry button inside same scanning view
        composeTestRule.runOnIdle { currentState = QrScannerUiState.INVALID_QR }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("qr_scanner_error_overlay").assertIsDisplayed()
        composeTestRule.onNodeWithTag("qr_scanner_retry_button").assertIsDisplayed()

        // Tapping Retry returns immediately to SCANNING without leaving view
        composeTestRule.onNodeWithTag("qr_scanner_retry_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, retryClicks)
        assertEquals(QrScannerUiState.SCANNING, currentState)
        assertTrue(
            composeTestRule.onAllNodesWithTag("qr_scanner_error_overlay", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        )

        // 3. Expired QR immediately displays inline error overlay + Retry button
        composeTestRule.runOnIdle { currentState = QrScannerUiState.EXPIRED_QR }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("qr_scanner_error_overlay").assertIsDisplayed()
        composeTestRule.onNodeWithTag("qr_scanner_retry_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("qr_scanner_retry_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(2, retryClicks)
        assertEquals(QrScannerUiState.SCANNING, currentState)
    }
}
