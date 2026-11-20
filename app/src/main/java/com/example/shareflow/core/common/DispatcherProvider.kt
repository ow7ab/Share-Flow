package com.example.shareflow.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Injectable coroutine dispatchers for deterministic testing and strict thread discipline.
 */
interface DispatcherProvider {
    val main: CoroutineDispatcher
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
}

class DefaultDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Main
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
}

/**
 * Injectable clock abstraction for deterministic timestamp generation and unit tests.
 */
interface Clock {
    fun nowEpochMillis(): Long
}

class SystemClock : Clock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
}
