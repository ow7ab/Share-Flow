package com.example.shareflow.network.transfer

import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.Clock
import com.example.shareflow.core.common.DispatcherProvider
import com.example.shareflow.core.common.SystemClock
import com.example.shareflow.domain.model.ConnectionState
import com.example.shareflow.domain.model.IncomingTransferOffer
import com.example.shareflow.domain.model.PeerDevice
import com.example.shareflow.domain.model.RecoveryVerificationReport
import com.example.shareflow.domain.model.TransferConfig
import com.example.shareflow.domain.model.TransferDirection
import com.example.shareflow.domain.model.TransferItem
import com.example.shareflow.domain.model.TransferSession
import com.example.shareflow.domain.model.TransferSourceItem
import com.example.shareflow.domain.model.TransferState
import com.example.shareflow.domain.repository.TransferRepository
import com.example.shareflow.network.coordinator.ConnectionCoordinator
import com.example.shareflow.network.security.EncryptedPeerChannel
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Production orchestrator for the ShareFlow File Transfer Engine.
 * Integrates directly with [ConnectionCoordinator] without replacing it, coordinating
 * [TransferQueue], [TransferScheduler], [TransferSender], [TransferReceiver],
 * [TransferVerifier], [TransferRecoveryManager], and Room-backed [TransferRepository].
 */
class TransferManager(
    private val connectionCoordinator: ConnectionCoordinator,
    private val transferRepository: TransferRepository,
    private val transferQueue: TransferQueue,
    private val transferScheduler: TransferScheduler,
    private val transferSender: TransferSender,
    private val transferReceiver: TransferReceiver,
    private val recoveryManager: TransferRecoveryManager,
    private val destinationRootProvider: () -> File,
    private val dispatchers: DispatcherProvider,
    private val clock: Clock = SystemClock(),
    private val requireExplicitOfferApproval: Boolean = false,
    private val chunkProcessor: ChunkProcessor? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val sendStartMutex = Mutex()
    private val controlHandles = ConcurrentHashMap<String, TransferControlHandle>()
    private val liveSessionsById = MutableStateFlow<Map<String, TransferSession>>(emptyMap())

    private val _pendingIncomingOffer = MutableStateFlow<IncomingTransferOffer?>(null)
    val pendingIncomingOffer: StateFlow<IncomingTransferOffer?> = _pendingIncomingOffer.asStateFlow()
    private val pendingOfferDecisions = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val _recoveryReports = MutableStateFlow<List<RecoveryVerificationReport>>(emptyList())
    val recoveryReports: StateFlow<List<RecoveryVerificationReport>> = _recoveryReports.asStateFlow()

    /**
     * Reactive stream of all sessions (live telemetry overlaid onto Room-persisted truth).
     */
    val allSessions: StateFlow<List<TransferSession>> = combine(
        transferRepository.observeAllSessions(),
        liveSessionsById
    ) { persisted, liveMap ->
        val merged = LinkedHashMap<String, TransferSession>()
        persisted.forEach { session ->
            merged[session.sessionId] = liveMap[session.sessionId] ?: session
        }
        liveMap.forEach { (id, liveSession) ->
            if (!merged.containsKey(id)) {
                merged[id] = liveSession
            }
        }
        merged.values.sortedByDescending { it.updatedAtEpochMillis }
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    val activeSessions: StateFlow<List<TransferSession>> = combine(
        allSessions,
        liveSessionsById
    ) { sessions, _ ->
        sessions.filter { it.state.isActive || it.isResumable }
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    private var receiverListenerJob: Job? = null
    private var observedChannelSessionId: String? = null

    init {
        scope.launch {
            _recoveryReports.value = recoveryManager.inspectAndRecoverInterruptedSessions()
            val protectedIds = runCatching {
                transferRepository.getIncompleteSessions().map { it.sessionId }.toSet()
            }.getOrDefault(emptySet())
            runCatching {
                chunkProcessor?.cleanupOrphanedStagingDirs(
                    destinationRoot = destinationRootProvider(),
                    protectedSessionIds = protectedIds,
                    nowEpochMillis = clock.nowEpochMillis()
                )
            }
        }
        observeConnectionCoordinator()
    }

    /**
     * Audits persisted incomplete sessions via [TransferRecoveryManager] without assuming resumability.
     */
    suspend fun inspectInterruptedSessions(): List<RecoveryVerificationReport> {
        val reports = recoveryManager.inspectAndRecoverInterruptedSessions()
        _recoveryReports.value = reports
        return reports
    }

    /**
     * Starts sending one or more files / expanded directory items over the currently connected
     * [ConnectionCoordinator] encrypted channel.
     */
    suspend fun startSendingOnActiveConnection(
        sources: List<TransferSourceItem>,
        config: TransferConfig = TransferConfig()
    ): AppResult<TransferSession> = sendStartMutex.withLock {
        val deduplicatedSources = sources.distinctBy { src ->
            "${src.uriString}|${TransferWireProtocol.sanitizeRelativePath(src.relativePath)}|${src.fileName}"
        }
        if (deduplicatedSources.isEmpty()) {
            return@withLock AppResult.Failure(AppError.Validation("Select at least one file or folder to send"))
        }
        val channel = connectionCoordinator.activeChannel
            ?: return@withLock AppResult.Failure(AppError.Network("No active encrypted peer connection"))
        if (!channel.isOpen) {
            return@withLock AppResult.Failure(AppError.Network("Peer connection is closed; reconnect to send files"))
        }

        val incomingBatchSignature = computeSourceBatchSignature(deduplicatedSources)
        val duplicateActiveSession = liveSessionsById.value.values.firstOrNull { existing ->
            existing.direction == TransferDirection.SENT &&
                existing.state.isActive &&
                existing.peer.deviceId.equals(channel.remotePeer.deviceId, ignoreCase = true) &&
                computeSessionBatchSignature(existing) == incomingBatchSignature
        }
        if (duplicateActiveSession != null) {
            return@withLock AppResult.Success(duplicateActiveSession)
        }

        val sessionId = "tx_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val now = clock.nowEpochMillis()
        val chunkSize = config.validatedChunkSizeBytes

        val batch = TransferQueue.QueuedTransferBatch(
            batchId = sessionId,
            items = deduplicatedSources,
            enqueuedAtEpochMillis = now
        )
        transferQueue.enqueue(batch)
        val dequeued = transferQueue.dequeue() ?: batch

        val items = dequeued.items.mapIndexed { idx, src ->
            TransferItem(
                itemId = "${sessionId}_$idx",
                sessionId = sessionId,
                fileName = src.fileName,
                relativePath = TransferWireProtocol.sanitizeRelativePath(src.relativePath),
                mimeType = src.mimeType,
                fileType = src.fileType,
                fileSizeBytes = src.fileSizeBytes.coerceAtLeast(0L),
                chunkSizeBytes = chunkSize,
                totalChunks = ChunkProcessor.calculateTotalChunks(src.fileSizeBytes, chunkSize),
                sourceUri = src.uriString,
                state = TransferState.PENDING,
                isRecoverable = true,
                startedAtEpochMillis = now
            )
        }

        val session = TransferSession(
            sessionId = sessionId,
            peer = channel.remotePeer,
            direction = TransferDirection.SENT,
            state = TransferState.PENDING,
            transferItems = items,
            chunkSizeBytes = chunkSize,
            isResumable = true,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now
        )

        publishLiveSession(session)
        transferRepository.saveSession(session)

        val handle = TransferControlHandle()
        controlHandles[sessionId] = handle

        transferScheduler.schedule(sessionId) {
            try {
                transferSender.sendSession(
                    channel = channel,
                    initialSession = session,
                    config = config,
                    controlHandle = handle
                ) { updated ->
                    publishLiveSession(updated)
                }
            } finally {
                controlHandles.remove(sessionId)
            }
        }

        AppResult.Success(session)
    }

    /**
     * Connects to [peer] via [ConnectionCoordinator] if not already connected, then launches transfer.
     */
    suspend fun sendFilesToPeer(
        peer: PeerDevice,
        localDeviceName: String,
        sources: List<TransferSourceItem>,
        config: TransferConfig = TransferConfig()
    ): AppResult<TransferSession> {
        val existingChannel = connectionCoordinator.activeChannel
        if (existingChannel == null ||
            !existingChannel.isOpen ||
            !existingChannel.remotePeer.deviceId.equals(peer.deviceId, ignoreCase = true)
        ) {
            val connectResult = connectionCoordinator.connectToPeer(peer, localDeviceName)
            if (connectResult is AppResult.Failure) {
                return connectResult
            }
        }
        return startSendingOnActiveConnection(sources = sources, config = config)
    }

    /**
     * Pauses an active transfer session cleanly (preserves verified chunk progress).
     */
    suspend fun pauseTransfer(sessionId: String): AppResult<Unit> {
        val handle = controlHandles[sessionId]
            ?: return AppResult.Failure(AppError.NotFound("No running transfer found for session $sessionId"))
        handle.pause()
        val current = liveSessionsById.value[sessionId]
        if (current != null) {
            publishLiveSession(
                current.copy(
                    state = TransferState.PAUSED,
                    currentSpeedBytesPerSec = 0L,
                    estimatedRemainingSeconds = null,
                    updatedAtEpochMillis = clock.nowEpochMillis()
                )
            )
        }
        return AppResult.Success(Unit)
    }

    /**
     * Resumes either a live paused session OR an interrupted persisted session after verifying
     * peer identity & underlying file state via [TransferRecoveryManager].
     */
    suspend fun resumeTransfer(
        sessionId: String,
        config: TransferConfig = TransferConfig()
    ): AppResult<Unit> {
        val activeHandle = controlHandles[sessionId]
        if (activeHandle != null && activeHandle.isPaused && !activeHandle.isCancelled) {
            activeHandle.resume()
            return AppResult.Success(Unit)
        }

        val persisted = transferRepository.getSessionById(sessionId)
            ?: return AppResult.Failure(AppError.NotFound("Transfer session not found"))

        val inspection = recoveryManager.inspectSession(persisted)
        if (!inspection.isResumable) {
            return AppResult.Failure(AppError.Validation(inspection.reason))
        }

        val refreshed = transferRepository.getSessionById(sessionId) ?: persisted
        if (refreshed.direction != TransferDirection.SENT) {
            return AppResult.Failure(
                AppError.Validation("Reconnect with ${refreshed.peer.displayName}; sender will automatically resume verified chunks")
            )
        }

        val channel = connectionCoordinator.activeChannel
            ?: return AppResult.Failure(AppError.Network("Reconnect to ${refreshed.peer.displayName} before resuming"))
        if (!channel.isOpen) {
            return AppResult.Failure(AppError.Network("Encrypted peer channel is closed; reconnect to resume"))
        }
        if (!recoveryManager.canResumeWithPeer(refreshed, channel.remotePeer)) {
            return AppResult.Failure(
                AppError.Security("Connected peer cryptographic identity does not match interrupted session")
            )
        }

        val handle = TransferControlHandle()
        controlHandles[sessionId] = handle
        val effectiveConfig = config.copy(chunkSizeBytes = refreshed.chunkSizeBytes)

        transferScheduler.schedule(sessionId) {
            try {
                transferSender.sendSession(
                    channel = channel,
                    initialSession = refreshed,
                    config = effectiveConfig,
                    controlHandle = handle
                ) { updated ->
                    publishLiveSession(updated)
                }
            } finally {
                controlHandles.remove(sessionId)
            }
        }
        return AppResult.Success(Unit)
    }

    /**
     * Retries a recoverable failed session by verifying source/state and resuming over the active channel.
     */
    suspend fun retryTransfer(
        sessionId: String,
        config: TransferConfig = TransferConfig()
    ): AppResult<Unit> = resumeTransfer(sessionId, config)

    /**
     * Cancels an active or paused transfer session cleanly and persists CANCELLED state to Room.
     */
    suspend fun cancelTransfer(sessionId: String): AppResult<Unit> {
        val handle = controlHandles[sessionId]
        if (handle != null) {
            handle.cancel()
        } else {
            val persisted = transferRepository.getSessionById(sessionId)
                ?: return AppResult.Failure(AppError.NotFound("Transfer session not found"))
            val updatedItems = persisted.transferItems.map { item ->
                if (item.state == TransferState.COMPLETED) {
                    item
                } else {
                    chunkProcessor?.cleanupTempFile(item.tempFilePath)
                    item.copy(
                        state = TransferState.CANCELLED,
                        tempFilePath = null,
                        errorMessage = "Cancelled",
                        isRecoverable = false
                    )
                }
            }
            chunkProcessor?.cleanupSessionStagingDirIfEmpty(destinationRootProvider(), sessionId)
            val cancelled = persisted.copy(
                state = TransferState.CANCELLED,
                transferItems = updatedItems,
                currentSpeedBytesPerSec = 0L,
                estimatedRemainingSeconds = null,
                errorMessage = "Transfer cancelled",
                isResumable = false,
                updatedAtEpochMillis = clock.nowEpochMillis()
            )
            publishLiveSession(cancelled)
            transferRepository.saveSession(cancelled)
        }
        return AppResult.Success(Unit)
    }

    fun acceptIncomingOffer(sessionId: String): AppResult<Unit> {
        val deferred = pendingOfferDecisions.remove(sessionId)
            ?: return AppResult.Failure(AppError.NotFound("Incoming transfer offer expired"))
        deferred.complete(true)
        if (_pendingIncomingOffer.value?.sessionId == sessionId) {
            _pendingIncomingOffer.value = null
        }
        return AppResult.Success(Unit)
    }

    fun rejectIncomingOffer(sessionId: String): AppResult<Unit> {
        val deferred = pendingOfferDecisions.remove(sessionId)
            ?: return AppResult.Failure(AppError.NotFound("Incoming transfer offer expired"))
        deferred.complete(false)
        if (_pendingIncomingOffer.value?.sessionId == sessionId) {
            _pendingIncomingOffer.value = null
        }
        return AppResult.Success(Unit)
    }

    private fun observeConnectionCoordinator() {
        scope.launch {
            connectionCoordinator.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        val channel = connectionCoordinator.activeChannel
                        if (channel != null && !state.isInitiator && observedChannelSessionId != channel.sessionId) {
                            observedChannelSessionId = channel.sessionId
                            startInboundReceiveLoop(channel)
                        }
                    }

                    is ConnectionState.Idle,
                    is ConnectionState.Advertising,
                    is ConnectionState.Error -> {
                        observedChannelSessionId = null
                    }

                    else -> Unit
                }
            }
        }
    }

    private fun startInboundReceiveLoop(channel: EncryptedPeerChannel) {
        receiverListenerJob?.cancel()
        receiverListenerJob = scope.launch {
            while (isActive && channel.isOpen) {
                val handle = TransferControlHandle()
                var activeReceiveSessionId: String? = null

                val result = transferReceiver.receiveNextSession(
                    channel = channel,
                    destinationRoot = destinationRootProvider(),
                    controlHandle = handle,
                    offerApprovalCallback = { offer ->
                        activeReceiveSessionId = offer.sessionId
                        controlHandles[offer.sessionId] = handle
                        if (!requireExplicitOfferApproval) {
                            true
                        } else {
                            val deferred = CompletableDeferred<Boolean>()
                            pendingOfferDecisions[offer.sessionId] = deferred
                            _pendingIncomingOffer.value = offer
                            try {
                                deferred.await()
                            } finally {
                                pendingOfferDecisions.remove(offer.sessionId)
                                if (_pendingIncomingOffer.value?.sessionId == offer.sessionId) {
                                    _pendingIncomingOffer.value = null
                                }
                            }
                        }
                    }
                ) { updated ->
                    activeReceiveSessionId = updated.sessionId
                    controlHandles.putIfAbsent(updated.sessionId, handle)
                    publishLiveSession(updated)
                }

                activeReceiveSessionId?.let { controlHandles.remove(it) }

                if (result is AppResult.Failure && result.error is AppError.Network) {
                    // Socket closed or dropped; exit loop cleanly until next connection
                    break
                }
            }
        }
    }

    private fun publishLiveSession(session: TransferSession) {
        liveSessionsById.update { current ->
            val map = LinkedHashMap(current)
            map[session.sessionId] = session
            // Bound in-memory live overlay so completed/cancelled sessions don't accumulate forever in RAM
            // (persisted history always remains safely in Room).
            if (map.size > MAX_IN_MEMORY_LIVE_SESSIONS) {
                val oldestTerminalKey = map.entries
                    .filter { (_, s) -> !s.state.isActive && !s.isResumable && s.sessionId != session.sessionId }
                    .minByOrNull { (_, s) -> s.updatedAtEpochMillis }
                    ?.key
                if (oldestTerminalKey != null) {
                    map.remove(oldestTerminalKey)
                }
            }
            map
        }
    }

    fun shutdown() {
        receiverListenerJob?.cancel()
        pendingOfferDecisions.values.forEach { it.complete(false) }
        pendingOfferDecisions.clear()
        _pendingIncomingOffer.value = null
        transferScheduler.shutdown()
        scope.cancel()
    }

    private fun computeSourceBatchSignature(sources: List<TransferSourceItem>): List<String> =
        sources.map { src ->
            "${src.uriString}|${TransferWireProtocol.sanitizeRelativePath(src.relativePath)}|${src.fileName}|${src.fileSizeBytes.coerceAtLeast(0L)}"
        }.sorted()

    private fun computeSessionBatchSignature(session: TransferSession): List<String> =
        session.transferItems.map { item ->
            "${item.sourceUri.orEmpty()}|${item.relativePath}|${item.fileName}|${item.fileSizeBytes.coerceAtLeast(0L)}"
        }.sorted()

    companion object {
        private const val MAX_IN_MEMORY_LIVE_SESSIONS = 24
    }
}
