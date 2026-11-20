package com.example.shareflow.data.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/**
 * Receives real Android [PackageInstaller] session commit callbacks.
 *
 * Strictly enforces Android installation security:
 * - On [PackageInstaller.STATUS_PENDING_USER_ACTION], extracts Android's system confirmation
 *   [Intent] and publishes it so the user explicitly approves installation via Android's UI.
 * - Never bypasses Android installation confirmation or claims success prematurely.
 */
class PackageInstallStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_RESULT) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
            ?: intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
            ?: ""
        val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()

        val confirmIntent: Intent? = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
        } else {
            null
        }

        PackageInstallCallbackBus.dispatch(
            PackageInstallCallbackEvent(
                sessionId = sessionId,
                packageName = packageName,
                status = status,
                statusMessage = statusMessage,
                confirmationIntent = confirmIntent
            )
        )
    }

    companion object {
        const val ACTION_INSTALL_RESULT = "com.example.shareflow.action.PACKAGE_INSTALL_RESULT"
        const val EXTRA_PACKAGE_NAME = "com.example.shareflow.extra.PACKAGE_NAME"
    }
}
