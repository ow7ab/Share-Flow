package com.example.shareflow.ui.migration

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.Clock
import com.example.shareflow.core.common.SystemClock
import com.example.shareflow.domain.model.AppSettings
import com.example.shareflow.domain.model.ConnectionState
import com.example.shareflow.domain.model.FileCategory
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.domain.model.MigrationCategory
import com.example.shareflow.domain.model.MigrationCategorySummary
import com.example.shareflow.domain.model.MigrationCompletionReport
import com.example.shareflow.domain.model.MigrationFailedItem
import com.example.shareflow.domain.model.MigrationRole
import com.example.shareflow.domain.model.MigrationStep
import com.example.shareflow.domain.model.TransferConfig
import com.example.shareflow.domain.model.TransferFileType
import com.example.shareflow.domain.model.TransferSession
import com.example.shareflow.domain.model.TransferSourceItem
import com.example.shareflow.domain.model.TransferState
import com.example.shareflow.domain.repository.ConnectionRepository
import com.example.shareflow.domain.repository.FileManagerRepository
import com.example.shareflow.domain.repository.SettingsRepository
import com.example.shareflow.migration.ContactsMigrationExporter
import com.example.shareflow.network.transfer.TransferManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PhoneMigrationUiState(
    val role: MigrationRole = MigrationRole.SENDER_OLD_PHONE,
    val step: MigrationStep = MigrationStep.PAIRING,
    val connectionState: ConnectionState = ConnectionState.Idle,
    val isScanningCategories: Boolean = false,
    val categories: List<MigrationCategorySummary> = emptyList(),
    val addedFolderUris: List<String> = emptyList(),
    val isStartingTransfer: Boolean = false,
    val activeSessionId: String? = null,
    val activeSession: TransferSession? = null,
    val completionReport: MigrationCompletionReport? = null,
    val errorMessage: String? = null
) {
    val isConnected: Boolean
        get() = connectionState is ConnectionState.Connected

    val connectedPeerName: String
        get() = (connectionState as? ConnectionState.Connected)?.peer?.displayName.orEmpty()

    val selectedCategories: List<MigrationCategorySummary>
        get() = categories.filter { it.selected && (!it.permissionRequired || it.permissionGranted) }

    val totalSelectedItems: Int
        get() = selectedCategories.sumOf { it.itemCount }

    val totalSelectedBytes: Long
        get() = selectedCategories.sumOf { it.totalBytes }
}

class PhoneMigrationViewModel(
    private val connectionRepository: ConnectionRepository,
    private val transferManager: TransferManager,
    private val fileManagerRepository: FileManagerRepository,
    private val settingsRepository: SettingsRepository,
    private val contactsExporter: ContactsMigrationExporter,
    private val clock: Clock = SystemClock()
) : ViewModel() {

    private val _uiState = MutableStateFlow(PhoneMigrationUiState())
    val uiState: StateFlow<PhoneMigrationUiState> = _uiState.asStateFlow()

    private val categoryFilesCache = LinkedHashMap<MigrationCategory, List<LocalFileEntry>>()
    private val extraFolderEntries = mutableListOf<LocalFileEntry>()
    private var lastPreparedSources: List<TransferSourceItem> = emptyList()
    private var sourceCategoryMap: Map<String, MigrationCategory> = emptyMap()

    init {
        observeConnection()
        observeTransferSessions()
        scanCategories()
    }

    private fun observeConnection() {
        viewModelScope.launch {
            connectionRepository.connectionState.collectLatest { state ->
                _uiState.update { it.copy(connectionState = state) }
            }
        }
    }

    private fun observeTransferSessions() {
        viewModelScope.launch {
            transferManager.allSessions.collectLatest { sessions ->
                val targetId = _uiState.value.activeSessionId ?: return@collectLatest
                val matched = sessions.firstOrNull { it.sessionId == targetId } ?: return@collectLatest
                val isFinished = matched.state.isTerminal
                val report = if (isFinished) buildReport(matched) else _uiState.value.completionReport
                _uiState.update { current ->
                    current.copy(
                        activeSession = matched,
                        step = if (isFinished && current.step == MigrationStep.TRANSFERRING) {
                            MigrationStep.REPORT
                        } else {
                            current.step
                        },
                        completionReport = report
                    )
                }
            }
        }
    }

    fun selectRole(role: MigrationRole) {
        _uiState.update {
            it.copy(
                role = role,
                step = MigrationStep.PAIRING,
                errorMessage = null
            )
        }
    }

    fun goToStep(step: MigrationStep) {
        _uiState.update { it.copy(step = step, errorMessage = null) }
    }

    fun scanCategories() {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanningCategories = true, errorMessage = null) }
            val previousSelection = _uiState.value.categories.associate { it.category to it.selected }

            val photos = queryAccessible(FileCategory.IMAGES)
            val videos = queryAccessible(FileCategory.VIDEOS)
            val audio = queryAccessible(FileCategory.AUDIO)
            val docs = queryAccessible(FileCategory.DOCUMENTS)
            val other = (queryAccessible(FileCategory.ARCHIVES) +
                queryAccessible(FileCategory.DOWNLOADS) +
                queryAccessible(FileCategory.OTHER))
                .distinctBy { it.uriString }

            categoryFilesCache[MigrationCategory.PHOTOS] = photos
            categoryFilesCache[MigrationCategory.VIDEOS] = videos
            categoryFilesCache[MigrationCategory.AUDIO] = audio
            categoryFilesCache[MigrationCategory.DOCUMENTS] = docs
            categoryFilesCache[MigrationCategory.USER_FOLDERS] = extraFolderEntries.toList()
            categoryFilesCache[MigrationCategory.OTHER_FILES] = other

            val hasContactsPerm = contactsExporter.hasContactsPermission()
            val contactsCount = if (hasContactsPerm) {
                (contactsExporter.countContacts() as? AppResult.Success)?.data ?: 0
            } else {
                0
            }

            val summaries = listOf(
                buildSummary(MigrationCategory.PHOTOS, photos, previousSelection[MigrationCategory.PHOTOS] ?: photos.isNotEmpty()),
                buildSummary(MigrationCategory.VIDEOS, videos, previousSelection[MigrationCategory.VIDEOS] ?: videos.isNotEmpty()),
                buildSummary(MigrationCategory.AUDIO, audio, previousSelection[MigrationCategory.AUDIO] ?: audio.isNotEmpty()),
                buildSummary(MigrationCategory.DOCUMENTS, docs, previousSelection[MigrationCategory.DOCUMENTS] ?: docs.isNotEmpty()),
                buildSummary(
                    MigrationCategory.USER_FOLDERS,
                    extraFolderEntries.toList(),
                    previousSelection[MigrationCategory.USER_FOLDERS] ?: extraFolderEntries.isNotEmpty()
                ),
                MigrationCategorySummary(
                    category = MigrationCategory.CONTACTS,
                    itemCount = contactsCount,
                    totalBytes = contactsCount * 512L,
                    selected = previousSelection[MigrationCategory.CONTACTS] ?: false,
                    permissionRequired = true,
                    permissionGranted = hasContactsPerm
                ),
                buildSummary(MigrationCategory.OTHER_FILES, other, previousSelection[MigrationCategory.OTHER_FILES] ?: false)
            )

            _uiState.update {
                it.copy(
                    isScanningCategories = false,
                    categories = summaries
                )
            }
        }
    }

    fun toggleCategory(category: MigrationCategory, selected: Boolean) {
        _uiState.update { current ->
            current.copy(
                categories = current.categories.map { item ->
                    if (item.category == category) item.copy(selected = selected) else item
                },
                errorMessage = null
            )
        }
    }

    fun onContactsPermissionResult(granted: Boolean) {
        viewModelScope.launch {
            val count = if (granted) {
                (contactsExporter.countContacts() as? AppResult.Success)?.data ?: 0
            } else {
                0
            }
            _uiState.update { current ->
                current.copy(
                    categories = current.categories.map { item ->
                        if (item.category == MigrationCategory.CONTACTS) {
                            item.copy(
                                permissionGranted = granted,
                                itemCount = count,
                                totalBytes = count * 512L,
                                selected = granted && count > 0
                            )
                        } else {
                            item
                        }
                    },
                    errorMessage = if (!granted) {
                        "Contacts permission was denied. Other accessible categories can still be migrated."
                    } else {
                        null
                    }
                )
            }
        }
    }

    fun addUserSelectedFolder(treeUri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanningCategories = true, errorMessage = null) }
            val uriStr = treeUri.toString()
            fileManagerRepository.registerGrantedDirectory(uriStr)
            val result = fileManagerRepository.listDirectory(uriStr)
            when (result) {
                is AppResult.Success -> {
                    val files = result.data.filter { !it.isDirectory && it.isAccessible }
                    val existingUris = extraFolderEntries.map { it.uriString }.toSet()
                    extraFolderEntries += files.filter { it.uriString !in existingUris }
                    val updatedFolders = (_uiState.value.addedFolderUris + uriStr).distinct()
                    _uiState.update { it.copy(addedFolderUris = updatedFolders) }
                    scanCategories()
                }
                is AppResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isScanningCategories = false,
                            errorMessage = result.error.message
                        )
                    }
                }
            }
        }
    }

    fun startMigrationTransfer() {
        if (_uiState.value.isStartingTransfer) return
        viewModelScope.launch {
            _uiState.update { it.copy(isStartingTransfer = true, errorMessage = null) }
            val sources = mutableListOf<TransferSourceItem>()
            val catMap = LinkedHashMap<String, MigrationCategory>()
            val preFailed = mutableListOf<MigrationFailedItem>()

            val selected = _uiState.value.selectedCategories
            for (summary in selected) {
                if (summary.category == MigrationCategory.CONTACTS) {
                    when (val exported = contactsExporter.exportContactsToVcf()) {
                        is AppResult.Success -> {
                            sources += exported.data.transferSourceItem
                            catMap[exported.data.transferSourceItem.fileName] = MigrationCategory.CONTACTS
                        }
                        is AppResult.Failure -> {
                            preFailed += MigrationFailedItem(
                                displayName = "Contacts.vcf",
                                category = MigrationCategory.CONTACTS,
                                reason = exported.error.message
                            )
                        }
                    }
                } else {
                    val folderPrefix = when (summary.category) {
                        MigrationCategory.PHOTOS -> "Migration/Photos"
                        MigrationCategory.VIDEOS -> "Migration/Videos"
                        MigrationCategory.AUDIO -> "Migration/Audio"
                        MigrationCategory.DOCUMENTS -> "Migration/Documents"
                        MigrationCategory.USER_FOLDERS -> "Migration/Folders"
                        MigrationCategory.OTHER_FILES -> "Migration/Other"
                        MigrationCategory.CONTACTS -> "Migration/Contacts"
                    }
                    val entries = categoryFilesCache[summary.category].orEmpty()
                    entries.forEach { entry ->
                        sources += TransferSourceItem(
                            uriString = entry.uriString,
                            fileName = entry.displayName,
                            relativePath = "$folderPrefix/${entry.displayName}",
                            mimeType = entry.mimeType,
                            fileType = TransferFileType.fromCategory(entry.category),
                            fileSizeBytes = entry.sizeBytes.coerceAtLeast(0L)
                        )
                        catMap[entry.displayName] = summary.category
                    }
                }
            }

            if (sources.isEmpty()) {
                _uiState.update {
                    it.copy(
                        isStartingTransfer = false,
                        errorMessage = preFailed.firstOrNull()?.reason
                            ?: "No accessible files selected to migrate."
                    )
                }
                return@launch
            }

            lastPreparedSources = sources
            sourceCategoryMap = catMap

            val settings = runCatching { settingsRepository.settings.first() }.getOrDefault(AppSettings())
            val config = TransferConfig(
                maxConcurrentFileTransfers = settings.maxConcurrentTransfers
            )

            when (val started = transferManager.startSendingOnActiveConnection(sources, config)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isStartingTransfer = false,
                            activeSessionId = started.data.sessionId,
                            activeSession = started.data,
                            step = MigrationStep.TRANSFERRING,
                            errorMessage = null
                        )
                    }
                }
                is AppResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isStartingTransfer = false,
                            errorMessage = started.error.message
                        )
                    }
                }
            }
        }
    }

    fun cancelActiveMigration() {
        val sessionId = _uiState.value.activeSessionId ?: return
        viewModelScope.launch {
            transferManager.cancelTransfer(sessionId)
        }
    }

    fun retryFailedItems() {
        val report = _uiState.value.completionReport ?: return
        val failedNames = report.failedItems.map { it.displayName }.toSet()
        val retrySources = lastPreparedSources.filter { it.fileName in failedNames }
        if (retrySources.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isStartingTransfer = true, errorMessage = null) }
            val settings = runCatching { settingsRepository.settings.first() }.getOrDefault(AppSettings())
            when (
                val started = transferManager.startSendingOnActiveConnection(
                    sources = retrySources,
                    config = TransferConfig(maxConcurrentFileTransfers = settings.maxConcurrentTransfers)
                )
            ) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isStartingTransfer = false,
                            activeSessionId = started.data.sessionId,
                            activeSession = started.data,
                            step = MigrationStep.TRANSFERRING,
                            completionReport = null
                        )
                    }
                }
                is AppResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isStartingTransfer = false,
                            errorMessage = started.error.message
                        )
                    }
                }
            }
        }
    }

    fun resetWorkflow() {
        _uiState.update {
            it.copy(
                step = MigrationStep.PAIRING,
                activeSessionId = null,
                activeSession = null,
                completionReport = null,
                errorMessage = null
            )
        }
        scanCategories()
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private suspend fun queryAccessible(category: FileCategory): List<LocalFileEntry> =
        when (val res = fileManagerRepository.queryByCategory(category)) {
            is AppResult.Success -> res.data.filter { !it.isDirectory && it.isAccessible }
            is AppResult.Failure -> emptyList()
        }

    private fun buildSummary(
        category: MigrationCategory,
        entries: List<LocalFileEntry>,
        selected: Boolean
    ): MigrationCategorySummary = MigrationCategorySummary(
        category = category,
        itemCount = entries.size,
        totalBytes = entries.sumOf { it.sizeBytes.coerceAtLeast(0L) },
        selected = selected
    )

    private fun buildReport(session: TransferSession): MigrationCompletionReport {
        val completedItems = session.transferItems.filter { it.state == TransferState.COMPLETED }
        val failedItems = session.transferItems
            .filter { it.state == TransferState.FAILED || it.state == TransferState.CANCELLED }
            .map { item ->
                MigrationFailedItem(
                    displayName = item.fileName,
                    category = sourceCategoryMap[item.fileName] ?: MigrationCategory.OTHER_FILES,
                    reason = item.errorMessage ?: item.state.name
                )
            }
        return MigrationCompletionReport(
            sessionId = session.sessionId,
            peerDeviceName = session.peer.displayName,
            completedCount = completedItems.size,
            failedItems = failedItems,
            totalBytesTransferred = completedItems.sumOf { it.fileSizeBytes },
            completedAtEpochMillis = clock.nowEpochMillis()
        )
    }
}
