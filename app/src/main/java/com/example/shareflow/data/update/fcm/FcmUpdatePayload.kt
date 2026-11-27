package com.example.shareflow.data.update.fcm

import com.example.shareflow.data.update.CloudUpdateManager
import com.example.shareflow.data.update.CloudUpdateManifest
import com.example.shareflow.data.update.CloudUpdateValidator
import com.example.shareflow.data.update.UpdateDetails
import com.example.shareflow.navigation.ShareFlowRoutes

/**
 * Validated FCM data payload emitted by the GitHub Actions release notification workflow
 * when a new stable Share Flow release is published.
 */
data class FcmUpdatePayload(
    val versionCode: Int,
    val versionName: String,
    val apkDownloadUrl: String,
    val sha256: String,
    val fileSizeBytes: Long,
    val publishedAt: String,
    val releaseNotesAr: String,
    val releaseNotesEn: String,
    val mandatory: Boolean,
    val signingCertSha256: String?,
    val targetRoute: String,
    val notificationTitle: String?,
    val notificationBody: String?
) {
    fun toUpdateDetails(): UpdateDetails = UpdateDetails(
        versionCode = versionCode,
        versionName = versionName,
        apkDownloadUrl = apkDownloadUrl,
        sha256 = sha256,
        fileSizeBytes = fileSizeBytes,
        mandatory = mandatory,
        releaseNotes = releaseNotesAr.ifBlank { releaseNotesEn },
        releaseNotesAr = releaseNotesAr,
        releaseNotesEn = releaseNotesEn,
        portalUrl = CloudUpdateManager.OFFICIAL_RELEASES_PAGE_URL,
        publishedAt = publishedAt,
        signingCertSha256 = signingCertSha256,
        isUpdateAvailable = true
    )

    fun toCloudUpdateManifest(): CloudUpdateManifest = toUpdateDetails().toCloudUpdateManifest()

    companion object {
        private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")

        fun fromMap(
            data: Map<String, String>,
            fallbackTitle: String? = null,
            fallbackBody: String? = null
        ): FcmUpdatePayload? {
            if (data.isEmpty()) return null

            val type = data["type"]?.trim().orEmpty()
            if (type.isNotEmpty() && !type.equals("app_update", ignoreCase = true)) {
                return null
            }

            val versionCode = data["versionCode"]?.trim()?.toIntOrNull() ?: return null
            if (versionCode <= 0) return null

            val versionName = data["versionName"]?.trim()
                ?.removePrefix("v")
                ?.removePrefix("V")
                .orEmpty()
            if (versionName.isBlank() || versionName.length > 32 || versionName.any { it.isISOControl() }) {
                return null
            }

            val apkUrl = (data["apkUrl"] ?: data["apkDownloadUrl"])?.trim().orEmpty()
            if (!CloudUpdateValidator.isOfficialShareFlowReleaseApkUrl(apkUrl)) {
                return null
            }

            val sha256 = data["sha256"]?.trim()?.lowercase().orEmpty()
            if (!SHA256_REGEX.matches(sha256)) {
                return null
            }

            val fileSizeBytes = data["fileSizeBytes"]?.trim()?.toLongOrNull()
                ?.takeIf { it in 0L..CloudUpdateValidator.MAX_APK_BYTES }
                ?: 0L

            val publishedAt = data["publishedAt"]?.trim()?.filterNot { it.isISOControl() }?.take(64).orEmpty()
            val genericNotes = data["releaseNotes"]?.trim()?.take(2000).orEmpty()
            val notesAr = data["releaseNotesAr"]?.trim()?.ifBlank { genericNotes }?.take(2000) ?: genericNotes
            val notesEn = data["releaseNotesEn"]?.trim()?.ifBlank { genericNotes }?.take(2000) ?: genericNotes
            val mandatory = data["mandatory"]?.trim()?.equals("true", ignoreCase = true) ?: false

            val rawCert = data["signingCertSha256"]?.trim()?.replace(":", "")?.lowercase().orEmpty()
            val signingCert = rawCert.takeIf { SHA256_REGEX.matches(it) }

            val rawRoute = data["targetRoute"]?.trim().orEmpty()
            val safeRoute = if (rawRoute.equals("updates", ignoreCase = true) || rawRoute.isBlank()) {
                ShareFlowRoutes.UPDATES
            } else {
                ShareFlowRoutes.UPDATES
            }

            return FcmUpdatePayload(
                versionCode = versionCode,
                versionName = versionName,
                apkDownloadUrl = apkUrl,
                sha256 = sha256,
                fileSizeBytes = fileSizeBytes,
                publishedAt = publishedAt,
                releaseNotesAr = notesAr,
                releaseNotesEn = notesEn,
                mandatory = mandatory,
                signingCertSha256 = signingCert,
                targetRoute = safeRoute,
                notificationTitle = (data["title"]?.trim()?.ifBlank { fallbackTitle } ?: fallbackTitle)
                    ?.filterNot { it.isISOControl() }?.take(120),
                notificationBody = (data["body"]?.trim()?.ifBlank { fallbackBody } ?: fallbackBody)
                    ?.filterNot { it.isISOControl() }?.take(400)
            )
        }
    }
}
