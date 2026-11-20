package com.example.shareflow.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.domain.model.TransferDirection
import com.example.shareflow.domain.model.TransferRecord
import com.example.shareflow.domain.model.TransferStatus
import com.example.shareflow.domain.repository.TransferHistoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class HistoryFilter {
    ALL,
    SENT,
    RECEIVED,
    FAILED,
    CANCELLED
}

data class TransferHistoryUiState(
    val isLoading: Boolean = true,
    val allRecords: List<TransferRecord> = emptyList(),
    val records: List<TransferRecord> = emptyList(),
    val selectedFilter: HistoryFilter = HistoryFilter.ALL,
    val searchQuery: String = "",
    val sessionFileCounts: Map<String, Int> = emptyMap(),
    val totalFilteredBytes: Long = 0L,
    val distinctSessionCount: Int = 0,
    val localDeviceName: String = "This Device",
    val errorMessage: String? = null
)

class HistoryViewModel(
    private val repository: TransferHistoryRepository,
    private val localDeviceNameProvider: () -> String = { "This Device" }
) : ViewModel() {

    private val filterState = MutableStateFlow(HistoryFilter.ALL)
    private val queryState = MutableStateFlow("")
    private val errorState = MutableStateFlow<String?>(null)

    val uiState: StateFlow<TransferHistoryUiState> = combine(
        repository.observeAllRecords(),
        filterState,
        queryState,
        errorState
    ) { allRecords, filter, query, error ->
        val trimmedQuery = query.trim()
        val filtered = allRecords.filter { record ->
            val matchesFilter = when (filter) {
                HistoryFilter.ALL -> true
                HistoryFilter.SENT -> record.direction == TransferDirection.SENT
                HistoryFilter.RECEIVED -> record.direction == TransferDirection.RECEIVED
                HistoryFilter.FAILED -> record.status == TransferStatus.FAILED
                HistoryFilter.CANCELLED -> record.status == TransferStatus.CANCELLED
            }
            val matchesQuery = trimmedQuery.isEmpty() ||
                record.fileName.contains(trimmedQuery, ignoreCase = true) ||
                record.peerDeviceName.contains(trimmedQuery, ignoreCase = true) ||
                record.sessionId.contains(trimmedQuery, ignoreCase = true)
            matchesFilter && matchesQuery
        }

        val sessionCounts = allRecords.groupingBy { it.sessionId }.eachCount()

        TransferHistoryUiState(
            isLoading = false,
            allRecords = allRecords,
            records = filtered,
            selectedFilter = filter,
            searchQuery = query,
            sessionFileCounts = sessionCounts,
            totalFilteredBytes = filtered.sumOf { it.fileSizeBytes.coerceAtLeast(0L) },
            distinctSessionCount = filtered.map { it.sessionId }.distinct().size,
            localDeviceName = localDeviceNameProvider(),
            errorMessage = error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = TransferHistoryUiState(isLoading = true)
    )

    fun selectFilter(filter: HistoryFilter) {
        filterState.value = filter
    }

    fun onSearchQueryChange(query: String) {
        queryState.value = query
    }

    fun deleteRecord(recordId: String) {
        viewModelScope.launch {
            when (val result = repository.deleteRecordById(recordId)) {
                is AppResult.Success -> errorState.value = null
                is AppResult.Failure -> errorState.value = result.error.message
            }
        }
    }

    /**
     * Clears persisted history log records ONLY — never deletes actual user files on storage.
     */
    fun clearHistory() {
        viewModelScope.launch {
            when (val result = repository.clearAllRecords()) {
                is AppResult.Success -> errorState.value = null
                is AppResult.Failure -> errorState.value = result.error.message
            }
        }
    }

    fun dismissError() {
        errorState.value = null
    }
}
