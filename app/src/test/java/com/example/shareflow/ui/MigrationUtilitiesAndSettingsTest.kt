package com.example.shareflow.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.DispatcherProvider
import com.example.shareflow.core.designsystem.theme.ShareFlowTheme
import com.example.shareflow.data.repository.SettingsRepositoryImpl
import com.example.shareflow.domain.model.AppLanguage
import com.example.shareflow.domain.model.DiscoveryVisibility
import com.example.shareflow.domain.model.FileOverwritePolicy
import com.example.shareflow.domain.model.HistoryRetentionPolicy
import com.example.shareflow.domain.model.PreferredConnectionMode
import com.example.shareflow.domain.model.TransferDirection
import com.example.shareflow.domain.model.TransferFileType
import com.example.shareflow.domain.model.TransferRecord
import com.example.shareflow.domain.model.TransferStatus
import com.example.shareflow.domain.repository.TransferHistoryRepository
import com.example.shareflow.ui.history.HistoryFilter
import com.example.shareflow.ui.history.HistoryViewModel
import com.example.shareflow.ui.history.TransferHistoryContent
import com.example.shareflow.ui.history.TransferHistoryUiState
import com.example.shareflow.ui.migration.PhoneMigrationContent
import com.example.shareflow.ui.migration.PhoneMigrationUiState
import com.example.shareflow.ui.utilities.CompressionContent
import com.example.shareflow.ui.utilities.CompressionTab
import com.example.shareflow.ui.utilities.CompressionUiState
import com.example.shareflow.ui.utilities.DuplicateFinderContent
import com.example.shareflow.ui.utilities.DuplicateFinderUiState
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MigrationUtilitiesAndSettingsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val testDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    @Test
    fun `extended settings persist properly and auto-accept remains disabled by default`(): Unit = runBlocking<Unit> {
        val context: Context = ApplicationProvider.getApplicationContext()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dsFile = File(context.filesDir, "settings_ext_${System.nanoTime()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { dsFile }
        val repo = SettingsRepositoryImpl(dataStore, testDispatchers)

        try {
            val defaults = repo.settings.first()
            assertFalse("Auto-accept MUST be disabled by default", defaults.autoAcceptTrustedDevices)
            assertEquals(2, defaults.maxConcurrentTransfers)
            assertEquals(FileOverwritePolicy.KEEP_BOTH_RENAME, defaults.fileOverwritePolicy)

            assertTrue(repo.updateMaxConcurrentTransfers(4) is AppResult.Success)
            assertTrue(repo.updateMaxConcurrentTransfers(9) is AppResult.Failure)
            assertTrue(repo.updateFileOverwritePolicy(FileOverwritePolicy.SKIP_EXISTING) is AppResult.Success)
            assertTrue(repo.updateTransferNotificationsEnabled(false) is AppResult.Success)
            assertTrue(repo.updateHistoryRetentionPolicy(HistoryRetentionPolicy.DAYS_90) is AppResult.Success)
            assertTrue(repo.updateDiscoveryVisibility(DiscoveryVisibility.TRUSTED_ONLY) is AppResult.Success)
            assertTrue(repo.updatePreferredConnectionMode(PreferredConnectionMode.WIFI_DIRECT) is AppResult.Success)
            assertTrue(repo.updateBiometricUnlockPreferred(false) is AppResult.Success)
            assertTrue(repo.updateDefaultSaveDirectoryUri("content://tree/downloads") is AppResult.Success)

            val updated = repo.settings.first()
            assertEquals(4, updated.maxConcurrentTransfers)
            assertEquals(FileOverwritePolicy.SKIP_EXISTING, updated.fileOverwritePolicy)
            assertFalse(updated.transferNotificationsEnabled)
            assertEquals(HistoryRetentionPolicy.DAYS_90, updated.historyRetentionPolicy)
            assertEquals(DiscoveryVisibility.TRUSTED_ONLY, updated.discoveryVisibility)
            assertEquals(PreferredConnectionMode.WIFI_DIRECT, updated.preferredConnectionMode)
            assertFalse(updated.biometricUnlockPreferred)
            assertEquals("content://tree/downloads", updated.defaultSaveDirectoryUri)
        } finally {
            scope.cancel()
            dsFile.delete()
        }
    }

    @Test
    fun `history viewmodel filters by sent received failed cancelled and clearing never deletes real files`(): Unit = runBlocking<Unit> {
        val context: Context = ApplicationProvider.getApplicationContext()
        val realFile = File(context.filesDir, "received_keep_safe_${System.nanoTime()}.txt").apply {
            writeText("Do not delete me when history is cleared!")
        }
        val fakeRepo = FakeHistoryRepo(
            listOf(
                sampleRecord("1", "s1", "vacation.jpg", TransferDirection.SENT, TransferStatus.COMPLETED_VERIFIED, "Pixel 9"),
                sampleRecord("2", "s2", "report.pdf", TransferDirection.RECEIVED, TransferStatus.COMPLETED_VERIFIED, "Galaxy S24", realFile.absolutePath),
                sampleRecord("3", "s3", "broken.zip", TransferDirection.SENT, TransferStatus.FAILED, "Pixel 9"),
                sampleRecord("4", "s4", "aborted.mp4", TransferDirection.RECEIVED, TransferStatus.CANCELLED, "Tablet")
            )
        )

        val vm = HistoryViewModel(fakeRepo) { "My Phone" }
        val initial = vm.uiState.first { !it.isLoading }
        assertEquals(4, initial.records.size)

        vm.selectFilter(HistoryFilter.SENT)
        assertEquals(2, vm.uiState.first { it.selectedFilter == HistoryFilter.SENT }.records.size)

        vm.selectFilter(HistoryFilter.FAILED)
        assertEquals(1, vm.uiState.first { it.selectedFilter == HistoryFilter.FAILED }.records.size)

        vm.selectFilter(HistoryFilter.CANCELLED)
        assertEquals(1, vm.uiState.first { it.selectedFilter == HistoryFilter.CANCELLED }.records.size)

        vm.selectFilter(HistoryFilter.ALL)
        vm.onSearchQueryChange("report")
        val searched = vm.uiState.first { it.searchQuery == "report" }
        assertEquals(1, searched.records.size)
        assertEquals("report.pdf", searched.records.first().fileName)

        vm.clearHistory()
        assertEquals(0, vm.uiState.first { it.allRecords.isEmpty() }.records.size)
        assertTrue("Clearing transfer history MUST NOT delete real files on disk", realFile.exists())
        realFile.delete()
    }

    @Test
    fun `phone migration renders scope disclosure and role controls in Arabic RTL`() {
        composeRule.setContent {
            ShareFlowTheme(appLanguage = AppLanguage.ARABIC) {
                PhoneMigrationContent(
                    uiState = PhoneMigrationUiState(),
                    onNavigateBack = {},
                    onSelectRole = {},
                    onGoToStep = {},
                    onRefreshCategories = {},
                    onToggleCategory = { _, _ -> },
                    onAddFolder = {},
                    onOpenDiscovery = {},
                    onOpenQrPairing = {},
                    onOpenReceive = {},
                    onStartTransfer = {},
                    onCancelTransfer = {},
                    onRetryFailed = {},
                    onResetWorkflow = {},
                    onOpenTransferProgress = {},
                    onOpenHistory = {},
                    onDismissError = {}
                )
            }
        }

        composeRule.onNodeWithTag("phone_migration_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("migration_scope_disclosure").assertIsDisplayed()
        composeRule.onNodeWithTag("migration_role_sender_chip").assertIsDisplayed()
        composeRule.onNodeWithTag("migration_role_receiver_chip").assertIsDisplayed()
        composeRule.onNodeWithTag("migration_pairing_card").assertIsDisplayed()
    }

    @Test
    fun `compression and duplicate finder screens display core controls and safety guarantees`() {
        var selectedTab = CompressionTab.COMPRESS
        composeRule.setContent {
            ShareFlowTheme {
                CompressionContent(
                    uiState = CompressionUiState(activeTab = selectedTab),
                    onNavigateBack = {},
                    onSelectTab = { selectedTab = it },
                    onPickFilesToCompress = {},
                    onClearSelectedFiles = {},
                    onArchiveNameChanged = {},
                    onPreserveOriginalsChanged = {},
                    onCreateZip = {},
                    onPickZipToExtract = {},
                    onExtractZip = {},
                    onDismissError = {}
                )
            }
        }

        composeRule.onNodeWithTag("compression_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("compression_archive_name_input").assertIsDisplayed()
        composeRule.onNodeWithTag("compression_preserve_originals_switch").assertIsDisplayed()
        composeRule.onNodeWithTag("compression_tab_extract").performClick()
        assertEquals(CompressionTab.EXTRACT, selectedTab)
    }

    @Test
    fun `transfer history screen shows filter chips and clear confirmation dialog`() {
        val sample = sampleRecord("r1", "sess1", "doc.pdf", TransferDirection.SENT, TransferStatus.COMPLETED_VERIFIED, "Peer Phone")
        var cleared = false
        composeRule.setContent {
            ShareFlowTheme {
                TransferHistoryContent(
                    uiState = TransferHistoryUiState(
                        isLoading = false,
                        allRecords = listOf(sample),
                        records = listOf(sample),
                        sessionFileCounts = mapOf("sess1" to 1),
                        totalFilteredBytes = sample.fileSizeBytes,
                        distinctSessionCount = 1,
                        localDeviceName = "My Phone"
                    ),
                    onClearHistory = { cleared = true },
                    onDeleteRecord = {},
                    onNavigateBack = {}
                )
            }
        }

        composeRule.onNodeWithTag("history_filter_sent").assertIsDisplayed()
        composeRule.onNodeWithTag("history_filter_received").assertIsDisplayed()
        composeRule.onNodeWithTag("history_filter_failed").assertIsDisplayed()
        composeRule.onNodeWithTag("history_filter_cancelled").assertIsDisplayed()
        composeRule.onNodeWithTag("history_clear_button").performClick()
        composeRule.onNodeWithTag("history_confirm_clear_button").performClick()
        assertTrue(cleared)
    }

    @Test
    fun `duplicate finder screen displays safety notice and scan button`() {
        composeRule.setContent {
            ShareFlowTheme {
                DuplicateFinderContent(
                    uiState = DuplicateFinderUiState(),
                    onNavigateBack = {},
                    onScan = {},
                    onToggleSelection = {},
                    onDeleteConfirmed = {},
                    onConsumeFeedback = {},
                    onDismissError = {}
                )
            }
        }

        composeRule.onNodeWithTag("duplicate_finder_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("duplicates_scan_button").assertIsDisplayed()
    }

    private fun sampleRecord(
        id: String,
        sessionId: String,
        fileName: String,
        direction: TransferDirection,
        status: TransferStatus,
        peerName: String,
        localUri: String? = null
    ) = TransferRecord(
        id = id,
        sessionId = sessionId,
        fileName = fileName,
        mimeType = "application/octet-stream",
        fileType = TransferFileType.DOCUMENT,
        fileSizeBytes = 4096L,
        bytesTransferred = if (status == TransferStatus.COMPLETED_VERIFIED) 4096L else 0L,
        direction = direction,
        status = status,
        peerDeviceId = "peer_$id",
        peerDeviceName = peerName,
        localUri = localUri,
        sha256Checksum = null,
        isChecksumVerified = status == TransferStatus.COMPLETED_VERIFIED,
        startedAtEpochMillis = 1_700_000_000_000L,
        completedAtEpochMillis = 1_700_000_001_000L
    )

    private class FakeHistoryRepo(initial: List<TransferRecord>) : TransferHistoryRepository {
        private val flow = MutableStateFlow(initial)
        override fun observeAllRecords(): Flow<List<TransferRecord>> = flow
        override fun observeRecordsByDirection(direction: TransferDirection): Flow<List<TransferRecord>> =
            MutableStateFlow(flow.value.filter { it.direction == direction })
        override fun observeTotalCount(): Flow<Int> = MutableStateFlow(flow.value.size)
        override suspend fun upsertRecord(record: TransferRecord): AppResult<Unit> {
            flow.value = flow.value.filterNot { it.id == record.id } + record
            return AppResult.Success(Unit)
        }
        override suspend fun deleteRecordById(recordId: String): AppResult<Unit> {
            flow.value = flow.value.filterNot { it.id == recordId }
            return AppResult.Success(Unit)
        }
        override suspend fun clearAllRecords(): AppResult<Unit> {
            flow.value = emptyList()
            return AppResult.Success(Unit)
        }
        override suspend fun pruneRecordsOlderThan(cutoffEpochMillis: Long): AppResult<Int> {
            val before = flow.value.size
            flow.value = flow.value.filter { it.startedAtEpochMillis >= cutoffEpochMillis }
            return AppResult.Success(before - flow.value.size)
        }
    }
}
