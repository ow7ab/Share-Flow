package com.example.shareflow.vault

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

enum class BiometricCapability {
    AVAILABLE,
    NONE_ENROLLED,
    NO_HARDWARE,
    UNAVAILABLE
}

sealed interface BiometricAuthOutcome {
    data object Succeeded : BiometricAuthOutcome
    data class Failed(val errorCode: Int, val message: String) : BiometricAuthOutcome
}

/**
 * Wrapper around AndroidX [BiometricManager] and [BiometricPrompt] using `BIOMETRIC_STRONG`.
 * Keeps authentication logic isolated from Compose and cleanly testable.
 */
object VaultBiometricAuthenticator {

    fun checkCapability(context: Context): BiometricCapability {
        val manager = BiometricManager.from(context)
        return when (manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricCapability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricCapability.NONE_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricCapability.NO_HARDWARE
            else -> BiometricCapability.UNAVAILABLE
        }
    }

    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        negativeButtonText: String,
        onOutcome: (BiometricAuthOutcome) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onOutcome(BiometricAuthOutcome.Succeeded)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onOutcome(BiometricAuthOutcome.Failed(errorCode, errString.toString()))
            }

            override fun onAuthenticationFailed() {
                // Individual failed attempt; system prompt remains open until max tries or cancel
            }
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeButtonText)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        runCatching {
            BiometricPrompt(activity, executor, callback).authenticate(promptInfo)
        }.onFailure { err ->
            onOutcome(
                BiometricAuthOutcome.Failed(
                    errorCode = BiometricPrompt.ERROR_HW_UNAVAILABLE,
                    message = err.localizedMessage ?: "Biometric authentication unavailable"
                )
            )
        }
    }
}
