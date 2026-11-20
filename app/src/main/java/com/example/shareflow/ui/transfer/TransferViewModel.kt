package com.example.shareflow.ui.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.domain.model.ConnectionState
import com.example.shareflow.domain.model.IncomingTransferOffer
import com.example.shareflow.domain.model.TransferSession
import com.example.shareflow.domain.repository.ConnectionRepository
import com.example.shareflow.network.transfer.TransferManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TransferProgressUiState(
    val activeSessions: List<TransferSession> = emptyList(),
    val recentCompletedSessions: List<TransferSession> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.Idle,
    val pendingIncomingOffer: IncomingTransferOffer? = null,
    val feedbackMessage: String? = null
)

class TransferViewModel(
    private val transferManager: TransferManager,
    private val connectionRepository: ConnectionRepository
) : ViewModel() {

    private val feedbackState = MutableStateFlow<String?>(null)

    val uiState: StateFlow<TransferProgressUiState> = combine(
        transferManager.allSessions,
        connectionRepository.connectionState,
        transferManager.pendingIncomingOffer,
        feedbackState
    ) { all, connState, offer, feedback ->
        val active = all.filter { it.state.isActive || it.isResumable }
        val recent = all.filterNot { it.state.isActive || it.isResumable }.take(20)
        TransferProgressUiState(
            activeSessions = active,
            recentCompletedSessions = recent,
            connectionState = connState,
            pendingIncomingOffer = offer,
            feedbackMessage = feedback
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = TransferProgressUiState()
    )

    fun pauseSession(sessionId: String) {
        viewModelScope.launch {
            val res = transferManager.pauseTransfer(sessionId)
            if (res is AppResult.Failure) {
                feedbackState.value = res.error.message
            }
        }
    }

    fun resumeSession(sessionId: String) {
        viewModelScope.launch {
            val res = transferManager.resumeTransfer(sessionId)
            if (res is AppResult.Failure) {
                feedbackState.value = res.error.message
            }
        }
    }

    fun retrySession(sessionId: String) {
        viewModelScope.launch {
            val res = transferManager.retryTransfer(sessionId)
            if (res is AppResult.Failure) {
                feedbackState.value = res.error.message
            }
        }
    }

    fun cancelSession(sessionId: String) {
        viewModelScope.launch {
            val res = transferManager.cancelTransfer(sessionId)
            if (res is AppResult.Failure) {
                feedbackState.value = res.error.message
            }
        }
    }

    fun acceptIncomingOffer(sessionId: String) {
        val res = transferManager.acceptIncomingOffer(sessionId)
        if (res is AppResult.Failure) {
            feedbackState.value = res.error.message
        }
    }

    fun rejectIncomingOffer(sessionId: String) {
        val res = transferManager.rejectIncomingOffer(sessionId)
        if (res is AppResult.Failure) {
            feedbackState.value = res.error.message
        }
    }

    fun consumeFeedback() {
        feedbackState.value = null
    }
}
