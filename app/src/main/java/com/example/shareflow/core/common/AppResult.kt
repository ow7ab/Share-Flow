package com.example.shareflow.core.common

/**
 * Type-safe domain result wrapper for repository, storage, security, and network operations.
 */
sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}

/**
 * Structured, non-sensitive error taxonomy shared across Clean Architecture layers.
 */
sealed interface AppError {
    val message: String
    val cause: Throwable?
        get() = null

    data class Validation(override val message: String) : AppError
    data class NotFound(override val message: String) : AppError
    data class PermissionDenied(override val message: String, override val cause: Throwable? = null) : AppError
    data class Storage(override val message: String, override val cause: Throwable? = null) : AppError
    data class Security(override val message: String, override val cause: Throwable? = null) : AppError
    data class Network(override val message: String, override val cause: Throwable? = null) : AppError
    data class IntegrityMismatch(
        override val message: String,
        val expectedHash: String,
        val actualHash: String
    ) : AppError
    data class NotSupported(override val message: String) : AppError
    data class Unexpected(override val message: String, override val cause: Throwable? = null) : AppError
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(data))
    is AppResult.Failure -> this
}

inline fun <T> AppResult<T>.onSuccess(block: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) block(data)
    return this
}

inline fun <T> AppResult<T>.onFailure(block: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Failure) block(error)
    return this
}
