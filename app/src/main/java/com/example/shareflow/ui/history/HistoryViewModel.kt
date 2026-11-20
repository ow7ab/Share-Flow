package com.example.shareflow.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.domain.model.TransferRecord
import com.example.shareflow.domain.repository.TransferHistoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TransferHistoryUiState(
    val isLoading: Boolean = true,
    val records: List<TransferRecord> = emptyList(),
    val errorMessage: String? = null
)

class HistoryViewModel(
    private val repository: TransferHistoryRepository
) : ViewModel() {

    private val errorState = MutableStateFlow<String?>(null)

    val uiState: StateFlow<TransferHistoryUiState> = combine(
        repository.observeAllRecords(),
        errorState
    ) { records, error ->
        TransferHistoryUiState(
            isLoading = false,
            records = records,
            errorMessage = error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = TransferHistoryUiState(isLoading = true)
    )

    fun deleteRecord(recordId: String) {
        viewModelScope.launch {
            when (val result = repository.deleteRecordById(recordId)) {
                is AppResult.Success -> errorState.value = null
                is AppResult.Failure -> errorState.value = result.error.message
            }
        }
    }

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
