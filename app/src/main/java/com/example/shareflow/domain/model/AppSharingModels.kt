package com.example.shareflow.domain.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Classifies whether an installed application's public APK installation file(s) can be safely
 * extracted for offline peer-to-peer sharing without accessing any private application data.
 */
enum class ApkExtractionStatus {
    /** Single standalone base APK is readable and ready for extraction. */
    EXTRACTABLE,

    /** App uses Split APKs (App Bundle); base and all split APK files are readable and can be exported together. */
    SPLIT_APK_EXTRACTABLE,

    /** Public APK file(s) cannot be accessed or read under Android security/package restrictions. */
    UNAVAILABLE
}

/**
 * Represents a real installed application discovered via Android's [android.content.pm.PackageManager]
 * within Android package visibility rules. Contains strictly public package metadata and public APK
 * paths — never private data directories, credentials, or databases.
 */
data class ShareableAppInfo(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val baseApkPath: String?,
    val splitApkPaths: List<String> = emptyList(),
    val totalApkSizeBytes: Long?,
    val isSystemApp: Boolean,
    val isSplitApk: Boolean,
    val extractionStatus: ApkExtractionStatus,
    val unavailableReason: String? = null,
    val categoryLabel: String = "",
    val installedAtEpochMillis: Long = 0L
) {
    val isExtractable: Boolean
        get() = extractionStatus != ApkExtractionStatus.UNAVAILABLE

    val apkPartCount: Int
        get() = (if (!baseApkPath.isNullOrBlank()) 1 else 0) + splitApkPaths.size

    fun toInstalledAppInfo(): InstalledAppInfo = InstalledAppInfo(
        packageName = packageName,
        appName = appName,
        versionName = versionName,
        versionCode = versionCode,
        apkSizeBytes = totalApkSizeBytes ?: 0L,
        isSystemApp = isSystemApp
    )
}

/**
 * JSON manifest emitted alongside split APK bundles so the receiver can verify
 * completeness, package identity consistency, and per-part SHA-256 checksums before staging
 * a multi-split Android PackageInstaller.Session.
 */
data class ShareFlowApkPartManifest(
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val isBase: Boolean
)

data class ShareFlowAppBundleManifest(
    val formatVersion: Int = 1,
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val baseApkFileName: String,
    val parts: List<ShareFlowApkPartManifest>
) {
    fun toJsonString(): String {
        val partsArray = JSONArray()
        parts.forEach { part ->
            partsArray.put(
                JSONObject().apply {
                    put("fileName", part.fileName)
                    put("sizeBytes", part.sizeBytes)
                    put("sha256", part.sha256)
                    put("isBase", part.isBase)
                }
            )
        }
        return JSONObject().apply {
            put("formatVersion", formatVersion)
            put("packageName", packageName)
            put("appName", appName)
            put("versionName", versionName)
            put("versionCode", versionCode)
            put("baseApkFileName", baseApkFileName)
            put("parts", partsArray)
        }.toString()
    }

    companion object {
        fun fromJsonString(raw: String): ShareFlowAppBundleManifest {
            val obj = JSONObject(raw)
            val partsArray = obj.optJSONArray("parts") ?: JSONArray()
            val parts = buildList {
                for (i in 0 until partsArray.length()) {
                    val p = partsArray.getJSONObject(i)
                    add(
                        ShareFlowApkPartManifest(
                            fileName = p.getString("fileName"),
                            sizeBytes = p.optLong("sizeBytes", 0L),
                            sha256 = p.getString("sha256"),
                            isBase = p.optBoolean("isBase", false)
                        )
                    )
                }
            }
            return ShareFlowAppBundleManifest(
                formatVersion = obj.optInt("formatVersion", 1),
                packageName = obj.getString("packageName"),
                appName = obj.optString("appName", ""),
                versionName = obj.optString("versionName", "1.0"),
                versionCode = obj.optLong("versionCode", 0L),
                baseApkFileName = obj.optString("baseApkFileName", "base.apk"),
                parts = parts
            )
        }
    }
}

/**
 * Represents an extracted public APK file (or split APK part / bundle manifest) staged in
 * ShareFlow's scoped export cache with a verified streaming SHA-256 digest.
 */
data class ExtractedApkArtifact(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val fileName: String,
    val relativePath: String,
    val localFilePath: String,
    val uriString: String,
    val sizeBytes: Long,
    val sha256Checksum: String,
    val isSplitPart: Boolean,
    val isBundleManifest: Boolean = false
)

/**
 * Result of verifying a received APK file on disk before offering Android's system installation UI.
 */
enum class ReceivedApkIntegrityStatus {
    /** File exists on disk, SHA-256 matches recorded transfer digest, and Android PackageManager confirms a valid APK archive. */
    VERIFIED_INSTALLABLE,

    /** Transfer record references an APK file that no longer exists or is empty on disk. */
    FILE_MISSING,

    /** Actual file bytes on disk do not match the SHA-256 checksum verified during transfer. */
    CHECKSUM_MISMATCH,

    /** File exists on disk but fails Android APK archive parsing (corrupted, truncated, or not a valid APK). */
    INVALID_APK_ARCHIVE,

    /** Valid APK file present in the received directory without a matching ShareFlow transfer checksum record. */
    UNVERIFIED_EXTERNAL
}

/**
 * Verified metadata and integrity verdict for a received APK file or split APK bundle.
 */
data class ReceivedApkInfo(
    val id: String,
    val fileName: String,
    val filePath: String,
    val fileSizeBytes: Long,
    val packageName: String?,
    val appLabel: String?,
    val versionName: String?,
    val versionCode: Long?,
    val expectedSha256: String?,
    val actualSha256: String?,
    val integrityStatus: ReceivedApkIntegrityStatus,
    val statusDetail: String,
    val receivedAtEpochMillis: Long,
    val peerDeviceName: String? = null,
    val isSplitBundle: Boolean = false,
    val splitBundleApkPaths: List<String> = emptyList(),
    val bundleManifestPath: String? = null
) {
    val canLaunchSystemInstaller: Boolean
        get() = integrityStatus == ReceivedApkIntegrityStatus.VERIFIED_INSTALLABLE
}
