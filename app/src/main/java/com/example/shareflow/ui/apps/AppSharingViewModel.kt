package com.example.shareflow.ui.apps

import android.content.Intent
import android.graphics.drawable.Drawable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.data.repository.AndroidAppSharingRepository
import com.example.shareflow.domain.model.ConnectionState
import com.example.shareflow.domain.model.FileCategory
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.domain.model.ReceivedApkInfo
import com.example.shareflow.domain.model.ShareableAppInfo
import com.example.shareflow.domain.model.TransferFileType
import com.example.shareflow.domain.model.TransferSourceItem
import com.example.shareflow.domain.repository.AppSharingRepository
import com.example.shareflow.domain.repository.ConnectionRepository
import com.example.shareflow.network.LocalSessionController
import com.example.shareflow.network.transfer.TransferManager
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppSharingTab {
    INSTALLED,
    RECEIVED
}

data class AppSharingUiState(
    val activeTab: AppSharingTab = AppSharingTab.INSTALLED,
    val isLoadingInstalled: Boolean = true,
    val isExtracting: Boolean = false,
    val searchQuery: String = "",
    val includeSystemApps: Boolean = false,
    val allInstalledApps: List<ShareableAppInfo> = emptyList(),
    val filteredInstalledApps: List<ShareableAppInfo> = emptyList(),
    val selectedPackages: Set<String> = emptySet(),
    val selectedCount: Int = 0,
    val selectedTotalBytes: Long = 0L,
    val receivedApks: List<ReceivedApkInfo> = emptyList(),
    val pendingInstallApk: ReceivedApkInfo? = null,
    val errorMessage: String? = null,
    val feedbackMessage: String? = null
)

class AppSharingViewModel(
    private val appSharingRepository: AppSharingRepository,
    private val localSessionController: LocalSessionController,
    private val connectionRepository: ConnectionRepository? = null,
    private val transferManager: TransferManager? = null
) : ViewModel() {

    private data class LocalScreenState(
        val activeTab: AppSharingTab = AppSharingTab.INSTALLED,
        val isLoadingInstalled: Boolean = true,
        val isExtracting: Boolean = false,
        val searchQuery: String = "",
        val includeSystemApps: Boolean = false,
        val allInstalledApps: List<ShareableAppInfo> = emptyList(),
        val selectedPackages: Set<String> = emptySet(),
        val pendingInstallApk: ReceivedApkInfo? = null,
        val errorMessage: String? = null,
        val feedbackMessage: String? = null
    )

    private val localState = MutableStateFlow(LocalScreenState())

    val uiState: StateFlow<AppSharingUiState> = combine(
        localState,
        appSharingRepository.observeReceivedApks()
    ) { local, received ->
        val query = local.searchQuery.trim().lowercase()
        val filtered = if (query.isEmpty()) {
            local.allInstalledApps
        } else {
            local.allInstalledApps.filter { app ->
                app.appName.lowercase().contains(query) ||
                    app.packageName.lowercase().contains(query) ||
                    app.versionName.lowercase().contains(query)
            }
        }
        val selectedApps = local.allInstalledApps.filter { it.packageName in local.selectedPackages }
        AppSharingUiState(
            activeTab = local.activeTab,
            isLoadingInstalled = local.isLoadingInstalled,
            isExtracting = local.isExtracting,
            searchQuery = local.searchQuery,
            includeSystemApps = local.includeSystemApps,
            allInstalledApps = local.allInstalledApps,
            filteredInstalledApps = filtered,
            selectedPackages = local.selectedPackages,
            selectedCount = selectedApps.size,
            selectedTotalBytes = selectedApps.sumOf { it.totalApkSizeBytes ?: 0L },
            receivedApks = received,
            pendingInstallApk = local.pendingInstallApk,
            errorMessage = local.errorMessage,
            feedbackMessage = local.feedbackMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = AppSharingUiState()
    )

    init {
        refreshInstalledApps()
    }

    fun selectTab(tab: AppSharingTab) {
        localState.update { it.copy(activeTab = tab) }
    }

    fun updateSearchQuery(query: String) {
        localState.update { it.copy(searchQuery = query) }
    }

    fun toggleIncludeSystemApps() {
        localState.update { it.copy(includeSystemApps = !it.includeSystemApps) }
        refreshInstalledApps()
    }

    fun refreshInstalledApps() {
        val includeSystem = localState.value.includeSystemApps
        viewModelScope.launch {
            localState.update { it.copy(isLoadingInstalled = true, errorMessage = null) }
            when (val result = appSharingRepository.discoverInstalledApps(includeSystem)) {
                is AppResult.Success -> {
                    val validPackages = result.data.filter { it.isExtractable }.map { it.packageName }.toSet()
                    localState.update { current ->
                        current.copy(
                            isLoadingInstalled = false,
                            allInstalledApps = result.data,
                            selectedPackages = current.selectedPackages.intersect(validPackages),
                            errorMessage = null
                        )
                    }
                }
                is AppResult.Failure -> {
                    localState.update {
                        it.copy(
                            isLoadingInstalled = false,
                            errorMessage = result.error.message
                        )
                    }
                }
            }
        }
    }

    fun toggleAppSelection(app: ShareableAppInfo) {
        if (!app.isExtractable) {
            localState.update {
                it.copy(
                    feedbackMessage = app.unavailableReason
                        ?: "APK extraction is unavailable for ${app.appName}."
                )
            }
            return
        }
        localState.update { current ->
            val updated = if (app.packageName in current.selectedPackages) {
                current.selectedPackages - app.packageName
            } else {
                current.selectedPackages + app.packageName
            }
            current.copy(selectedPackages = updated)
        }
    }

    fun clearSelection() {
        localState.update { it.copy(selectedPackages = emptySet()) }
    }

    fun extractAndSendSelected(
        onQueuedForConfirm: () -> Unit,
        onStartedLiveTransfer: () -> Unit
    ) {
        val current = localState.value
        val selectedApps = current.allInstalledApps.filter {
            it.packageName in current.selectedPackages && it.isExtractable
        }
        if (selectedApps.isEmpty() || current.isExtracting) return

        viewModelScope.launch {
            localState.update { it.copy(isExtracting = true, feedbackMessage = null) }
            try {
                when (val extractResult = appSharingRepository.extractApksForSharing(selectedApps)) {
                    is AppResult.Failure -> {
                        localState.update {
                            it.copy(feedbackMessage = extractResult.error.message)
                        }
                    }
                    is AppResult.Success -> {
                        val artifacts = extractResult.data
                        val sources = artifacts.map { artifact ->
                            TransferSourceItem(
                                uriString = artifact.uriString,
                                fileName = artifact.fileName,
                                relativePath = artifact.relativePath,
                                mimeType = AndroidAppSharingRepository.APK_MIME_TYPE,
                                fileType = TransferFileType.APK,
                                fileSizeBytes = artifact.sizeBytes
                            )
                        }

                        val isConnected = connectionRepository?.connectionState?.value is ConnectionState.Connected
                        if (isConnected && transferManager != null) {
                            when (val sendRes = transferManager.startSendingOnActiveConnection(sources)) {
                                is AppResult.Success -> {
                                    localState.update { it.copy(selectedPackages = emptySet()) }
                                    onStartedLiveTransfer()
                                }
                                is AppResult.Failure -> {
                                    localState.update { it.copy(feedbackMessage = sendRes.error.message) }
                                }
                            }
                        } else {
                            val now = System.currentTimeMillis()
                            val entries = artifacts.map { artifact ->
                                LocalFileEntry(
                                    uriString = artifact.uriString,
                                    displayName = if (artifact.relativePath.isBlank()) {
                                        artifact.fileName
                                    } else {
                                        "${artifact.relativePath}/${artifact.fileName}"
                                    },
                                    mimeType = AndroidAppSharingRepository.APK_MIME_TYPE,
                                    sizeBytes = artifact.sizeBytes,
                                    lastModifiedEpochMillis = now,
                                    category = FileCategory.APKS,
                                    isDirectory = false
                                )
                            }
                            val relativePaths = artifacts.associate { it.uriString to it.relativePath }
                            localSessionController.addSelectedFiles(entries, relativePaths)
                            localState.update { it.copy(selectedPackages = emptySet()) }
                            onQueuedForConfirm()
                        }
                    }
                }
            } finally {
                localState.update { it.copy(isExtracting = false) }
            }
        }
    }

    fun requestInstallReview(apk: ReceivedApkInfo) {
        if (!apk.canLaunchSystemInstaller) {
            localState.update { it.copy(feedbackMessage = apk.statusDetail) }
            return
        }
        localState.update { it.copy(pendingInstallApk = apk) }
    }

    fun dismissInstallReview() {
        localState.update { it.copy(pendingInstallApk = null) }
    }

    fun confirmSystemInstall(onLaunchIntent: (Intent) -> Unit) {
        val target = localState.value.pendingInstallApk ?: return
        localState.update { it.copy(pendingInstallApk = null) }
        viewModelScope.launch {
            when (
                val intentResult = appSharingRepository.createSystemInstallIntent(
                    apkFile = File(target.filePath),
                    expectedSha256 = target.expectedSha256
                )
            ) {
                is AppResult.Success -> onLaunchIntent(intentResult.data)
                is AppResult.Failure -> {
                    localState.update { it.copy(feedbackMessage = intentResult.error.message) }
                }
            }
        }
    }

    fun reportInstallLaunchFailure(message: String) {
        localState.update { it.copy(feedbackMessage = message) }
    }

    fun loadAppIcon(packageName: String): Drawable? =
        appSharingRepository.loadAppIcon(packageName)

    fun consumeFeedback() {
        localState.update { it.copy(feedbackMessage = null) }
    }
}
