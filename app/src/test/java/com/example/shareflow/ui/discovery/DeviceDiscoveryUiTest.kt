package com.example.shareflow.ui.discovery

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import com.example.shareflow.core.designsystem.theme.ShareFlowTheme
import com.example.shareflow.domain.model.ConnectionState
import com.example.shareflow.domain.model.ConnectionType
import com.example.shareflow.domain.model.DiscoverySnapshot
import com.example.shareflow.domain.model.DiscoveryStatus
import com.example.shareflow.domain.model.IncomingConnectionRequest
import com.example.shareflow.domain.model.LocalDeviceIdentity
import com.example.shareflow.domain.model.PeerConnectionStatus
import com.example.shareflow.domain.model.PeerDevice
import com.example.shareflow.domain.model.StrategyAvailability
import com.example.shareflow.domain.model.StrategyStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceDiscoveryUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `discovery screen renders local identity transports discovered peers and explicit approval dialog`() {
        var approvedId: String? = null
        var approvedRemember: Boolean? = null
        var tappedPeerId: String? = null

        val peer = PeerDevice(
            deviceId = "abcdef1234567890",
            displayName = "Office Pixel 9",
            connectionType = ConnectionType.LOCAL_LAN_NSD,
            hostAddress = "192.168.1.24",
            port = 52891,
            publicKeyFingerprint = "b".repeat(64),
            connectionStatus = PeerConnectionStatus.AVAILABLE
        )
        val incoming = IncomingConnectionRequest(
            requestId = "req_42",
            sessionId = "sess_1234567890ab",
            peerDeviceId = peer.deviceId,
            peerDisplayName = peer.displayName,
            peerPublicKeyFingerprint = peer.publicKeyFingerprint!!,
            sasVerificationCode = "482910",
            connectionType = ConnectionType.LOCAL_LAN_NSD,
            isPreviouslyTrusted = false,
            hasValidQrToken = true,
            requestedAtEpochMillis = 1_750_000_000_000L
        )

        var state by mutableStateOf(
            DiscoveryUiState(
                localIdentity = LocalDeviceIdentity(
                    deviceId = "1122334455667788",
                    publicKeyFingerprint = "c".repeat(64),
                    publicKeyBase64 = "test",
                    isHardwareBackedKeystore = true
                ),
                discoverySnapshot = DiscoverySnapshot(
                    peers = listOf(peer),
                    status = DiscoveryStatus.Idle,
                    strategyStatuses = listOf(
                        StrategyStatus(ConnectionType.WIFI_DIRECT, StrategyAvailability.Unsupported("No P2P")),
                        StrategyStatus(ConnectionType.LOCAL_LAN_NSD, StrategyAvailability.Available),
                        StrategyStatus(ConnectionType.HOTSPOT, StrategyAvailability.PermissionRequired(listOf("android.permission.NEARBY_WIFI_DEVICES")))
                    )
                ),
                connectionState = ConnectionState.Idle,
                pendingIncomingRequest = incoming
            )
        )

        composeRule.setContent {
            ShareFlowTheme {
                DeviceDiscoveryContent(
                    uiState = state,
                    onNavigateBack = {},
                    onOpenQrPairing = {},
                    onStartDiscovery = {},
                    onStopDiscovery = {},
                    onToggleAdvertising = {},
                    onToggleHotspotPreference = {},
                    onConnectToPeer = { tappedPeerId = it.deviceId },
                    onApproveIncoming = { id, remember ->
                        approvedId = id
                        approvedRemember = remember
                        state = state.copy(pendingIncomingRequest = null)
                    },
                    onRejectIncoming = {},
                    onDisconnect = {},
                    onRefreshCapabilities = {},
                    onConsumeFeedback = {}
                )
            }
        }

        // Explicit approval dialog must show SAS code and approve button
        composeRule.onNodeWithTag("incoming_approval_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("approval_sas_code_value").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("482910").assertIsDisplayed()
        composeRule.onNodeWithTag("approval_remember_trusted_checkbox").performScrollTo().performClick()
        composeRule.onNodeWithTag("approval_confirm_button").performClick()

        assertEquals("req_42", approvedId)
        assertEquals(true, approvedRemember)

        // Verify local identity & discovered peer card render properly once dialog closes
        composeRule.onNodeWithTag("discovery_local_device_id").assertIsDisplayed()
        composeRule.onNodeWithTag("discovery_screen_list")
            .performScrollToNode(hasTestTag("discovery_peer_${peer.deviceId}"))
        composeRule.onNodeWithTag("discovery_peer_${peer.deviceId}").assertIsDisplayed().performClick()
        assertTrue(tappedPeerId == peer.deviceId)
    }

    @Test
    fun `discovery screen shows timeout recovery card and active encrypted connection card`() {
        var retried = false
        var disconnected = false

        val connectedPeer = PeerDevice(
            deviceId = "9988776655443322",
            displayName = "Studio Tablet",
            connectionType = ConnectionType.WIFI_DIRECT,
            publicKeyFingerprint = "d".repeat(64),
            connectionStatus = PeerConnectionStatus.CONNECTED
        )

        val state = DiscoveryUiState(
            localIdentity = LocalDeviceIdentity(
                deviceId = "1122334455667788",
                publicKeyFingerprint = "c".repeat(64),
                publicKeyBase64 = "test",
                isHardwareBackedKeystore = true
            ),
            discoverySnapshot = DiscoverySnapshot(
                peers = emptyList(),
                status = DiscoveryStatus.TimedOut,
                strategyStatuses = listOf(
                    StrategyStatus(ConnectionType.LOCAL_LAN_NSD, StrategyAvailability.Available)
                )
            ),
            connectionState = ConnectionState.Connected(
                peer = connectedPeer,
                sessionId = "session_abc123456789",
                sasVerificationCode = "774120",
                isEncrypted = true,
                transport = ConnectionType.WIFI_DIRECT
            )
        )

        composeRule.setContent {
            ShareFlowTheme {
                DeviceDiscoveryContent(
                    uiState = state,
                    onNavigateBack = {},
                    onOpenQrPairing = {},
                    onStartDiscovery = { retried = true },
                    onStopDiscovery = {},
                    onToggleAdvertising = {},
                    onToggleHotspotPreference = {},
                    onConnectToPeer = {},
                    onApproveIncoming = { _, _ -> },
                    onRejectIncoming = {},
                    onDisconnect = { disconnected = true },
                    onRefreshCapabilities = {},
                    onConsumeFeedback = {}
                )
            }
        }

        composeRule.onNodeWithTag("active_encrypted_connection_card").assertIsDisplayed()
        composeRule.onNodeWithTag("active_connection_disconnect_button").performClick()
        assertTrue(disconnected)

        composeRule.onNodeWithTag("discovery_screen_list")
            .performScrollToNode(hasTestTag("discovery_timeout_card"))
        composeRule.onNodeWithTag("discovery_timeout_card").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        assertTrue(retried)
    }
}
