package com.example.shareflow.data.update

import org.json.JSONArray
import org.json.JSONObject

/**
 * Structured representation of a GitHub Release returned by the official GitHub Releases API.
 */
data class GitHubReleaseRecord(
    val id: Long,
    val tagName: String,
    val versionName: String,
    val name: String,
    val publishedAt: String,
    val body: String,
    val htmlUrl: String,
    val isDraft: Boolean,
    val isPrerelease: Boolean,
    val apkAsset: GitHubReleaseAsset?,
    val versionJsonAssetUrl: String?,
    val sha256SidecarAssetUrl: String?
)

data class GitHubReleaseAsset(
    val name: String,
    val browserDownloadUrl: String,
    val sizeBytes: Long,
    val sha256Digest: String?
)

/**
 * Lightweight display model for the Previous Releases History list on the Updates screen.
 */
data class ReleaseHistoryItem(
    val tagName: String,
    val versionName: String,
    val title: String,
    val publishedAt: String,
    val releaseNotes: String,
    val fileSizeBytes: Long,
    val htmlUrl: String,
    val isPrerelease: Boolean,
    val hasApkAsset: Boolean
)

/**
 * Strict, `versionCode`-driven version precedence outcome.
 *
 * - `versionCode` is ALWAYS the sole authoritative source for update precedence.
 * - `versionName` is NEVER used to override an equal or lower `versionCode` into an update;
 *   it is used only for user display and for detecting conflicting/duplicate release metadata.
 */
enum class VersionPrecedenceDecision {
    /** `remoteVersionCode > installedVersionCode`: genuine newer build eligible for update. */
    UPDATE_AVAILABLE,

    /** `remoteVersionCode == installedVersionCode` and version names match: already up to date. */
    ALREADY_INSTALLED,

    /**
     * `remoteVersionCode == installedVersionCode` while `remoteVersionName != installedVersionName`
     * (e.g., installed `1.0.19 (20)` vs available `1.0.20 (20)`): duplicate build number conflict.
     * MUST NOT be offered as an update and MUST NOT be misclassified as a downgrade.
     */
    DUPLICATE_VERSION_CONFLICT,

    /** `remoteVersionCode < installedVersionCode`: true downgrade attempt — must be blocked. */
    DOWNGRADE_BLOCKED,

    /** Missing, zero, or negative `versionCode` in either installed or remote metadata. */
    UNREADABLE_VERSION_METADATA
}

/**
 * Detailed outcome when resolving a [GitHubReleaseRecord] into a [CloudUpdateManifest].
 * Never fabricates a synthetic `versionCode` from a GitHub tag name.
 */
sealed class GitHubManifestResolution {
    data class Resolved(val manifest: CloudUpdateManifest) : GitHubManifestResolution()
    data class MissingVersionCode(val release: GitHubReleaseRecord) : GitHubManifestResolution()
    data class MissingApkOrChecksum(val release: GitHubReleaseRecord) : GitHubManifestResolution()
    data class MetadataConflict(
        val release: GitHubReleaseRecord,
        val detail: String
    ) : GitHubManifestResolution()

    data object SkippedNonStable : GitHubManifestResolution()
}

/**
 * Parses GitHub Releases API JSON responses (`/releases/latest` and `/releases`) into validated
 * Share Flow update models without trusting unvalidated URLs, synthetic version codes, or
 * draft/prerelease artifacts.
 */
object GitHubReleasesParser {

    private val SHA256_HEX_REGEX = Regex("^[0-9a-fA-F]{64}$")
    private val SHA256_INLINE_REGEX = Regex("""(?:sha256[:=\s]+)?([0-9a-fA-F]{64})""", RegexOption.IGNORE_CASE)
    private val VERSION_CODE_INLINE_REGEX = Regex(
        """(?:version[_\s-]?code|build(?:\s+number)?|رقم\s+البناء|كود\s+الإصدار|البناء)\s*[:=#-]?\s*(\d{1,9})""",
        RegexOption.IGNORE_CASE
    )
    private val SEMVER_PAREN_BUILD_REGEX = Regex(
        """[vV]?\s*\d+\.\d+(?:\.\d+)?\s*\(\s*(\d{1,9})\s*\)"""
    )
    private val SEMVER_EXTRACT_REGEX = Regex("""(?:release[-_\s]*)?[vV]?\s*(\d+)\.(\d+)(?:\.(\d+))?(?:[-+][0-9A-Za-z.-]+)?""", RegexOption.IGNORE_CASE)

    fun parseSingleRelease(rawJson: String): GitHubReleaseRecord? {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null
        return runCatching { parseReleaseObject(JSONObject(trimmed)) }.getOrNull()
    }

    fun parseReleasesArray(rawJson: String): List<GitHubReleaseRecord> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("[")) return emptyList()
        return runCatching {
            val array = JSONArray(trimmed)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val record = parseReleaseObject(obj)
                    if (record != null && !record.isDraft) {
                        add(record)
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun parseReleaseObject(json: JSONObject): GitHubReleaseRecord? {
        val tagName = json.optString("tag_name", "").trim()
        if (tagName.isBlank()) return null

        val isDraft = json.optBoolean("draft", false)
        val isPrerelease = json.optBoolean("prerelease", false)
        val name = json.optString("name", "").trim().ifBlank { tagName }
        val publishedAt = json.optString("published_at", "").trim()
            .ifBlank { json.optString("created_at", "").trim() }
            .take(64)
        val body = json.optString("body", "").trim().take(4000)
        val rawHtmlUrl = json.optString("html_url", "").trim()
        val htmlUrl = rawHtmlUrl.takeIf { CloudUpdateValidator.isValidPublicHttpsUrl(it) }.orEmpty()
        val id = json.optLong("id", 0L)

        val assetsArray = json.optJSONArray("assets")
        var preferredApk: GitHubReleaseAsset? = null
        var versionJsonAssetUrl: String? = null
        var sha256SidecarAssetUrl: String? = null

        if (assetsArray != null) {
            val apkCandidates = mutableListOf<GitHubReleaseAsset>()
            for (i in 0 until assetsArray.length()) {
                val assetObj = assetsArray.optJSONObject(i) ?: continue
                val assetName = assetObj.optString("name", "").trim()
                val downloadUrl = assetObj.optString("browser_download_url", "").trim()
                if (assetName.isBlank() || !CloudUpdateValidator.isValidPublicHttpsUrl(downloadUrl)) continue

                val size = assetObj.optLong("size", 0L).coerceAtLeast(0L)
                val digestRaw = assetObj.optString("digest", "").trim()
                val sha256Digest = extractDigestHex(digestRaw)

                when {
                    assetName.endsWith(".apk", ignoreCase = true) -> {
                        apkCandidates.add(
                            GitHubReleaseAsset(
                                name = assetName,
                                browserDownloadUrl = downloadUrl,
                                sizeBytes = size,
                                sha256Digest = sha256Digest
                            )
                        )
                    }
                    assetName.equals("version.json", ignoreCase = true) -> {
                        versionJsonAssetUrl = downloadUrl
                    }
                    assetName.endsWith(".sha256", ignoreCase = true) ||
                        assetName.equals("SHA256SUMS.txt", ignoreCase = true) -> {
                        sha256SidecarAssetUrl = downloadUrl
                    }
                }
            }

            // Prefer canonical Share-Flow.apk or signed release APK over debug/unsigned builds
            preferredApk = apkCandidates.firstOrNull {
                it.name.equals("Share-Flow.apk", ignoreCase = true)
            } ?: apkCandidates.firstOrNull {
                !it.name.contains("debug", ignoreCase = true) &&
                    !it.name.contains("unsigned", ignoreCase = true)
            } ?: apkCandidates.firstOrNull()
        }

        val normalizedVersionName = normalizeVersionName(tagName, name)

        return GitHubReleaseRecord(
            id = id,
            tagName = tagName,
            versionName = normalizedVersionName,
            name = name,
            publishedAt = publishedAt,
            body = body,
            htmlUrl = htmlUrl,
            isDraft = isDraft,
            isPrerelease = isPrerelease,
            apkAsset = preferredApk,
            versionJsonAssetUrl = versionJsonAssetUrl,
            sha256SidecarAssetUrl = sha256SidecarAssetUrl
        )
    }

    /**
     * Resolves a validated stable [GitHubReleaseRecord] into a [CloudUpdateManifest] using ONLY
     * authentic `versionCode` metadata:
     * 1. Unified `version.json` sidecar/manifest
     * 2. Real binary `AndroidManifest.xml` extracted from the release's `Share-Flow.apk` ([remoteApkMetadata])
     * 3. Explicit numeric `versionCode` / `Build` number in release notes or title
     *
     * Never fabricates or guesses a synthetic `versionCode` from a GitHub tag or release name.
     */
    fun resolveCloudUpdateManifest(
        release: GitHubReleaseRecord,
        sidecarManifest: CloudUpdateManifest? = null,
        sidecarSha256Text: String? = null,
        remoteApkMetadata: RemoteApkManifestMetadata? = null,
        expectedPackageName: String? = null
    ): GitHubManifestResolution {
        if (release.isDraft || release.isPrerelease) {
            return GitHubManifestResolution.SkippedNonStable
        }

        val apkAsset = release.apkAsset
        val resolvedApkUrl = sidecarManifest?.apkDownloadUrl
            ?.takeIf { CloudUpdateValidator.isValidPublicHttpsUrl(it) }
            ?: apkAsset?.browserDownloadUrl
            ?: return GitHubManifestResolution.MissingApkOrChecksum(release)

        if (!CloudUpdateValidator.isValidPublicHttpsUrl(resolvedApkUrl)) {
            return GitHubManifestResolution.MissingApkOrChecksum(release)
        }

        val resolvedSha256 = sidecarManifest?.sha256
            ?.takeIf { SHA256_HEX_REGEX.matches(it) }
            ?: apkAsset?.sha256Digest
            ?: extractSha256FromText(sidecarSha256Text.orEmpty())
            ?: extractSha256FromText(release.body)
            ?: return GitHubManifestResolution.MissingApkOrChecksum(release)

        // Cross-check real APK binary manifest metadata whenever available
        if (remoteApkMetadata != null) {
            if (!expectedPackageName.isNullOrBlank() && remoteApkMetadata.packageName != expectedPackageName) {
                return GitHubManifestResolution.MetadataConflict(
                    release = release,
                    detail = "Release ${release.tagName} APK package '${remoteApkMetadata.packageName}' does not match installed package '$expectedPackageName'."
                )
            }
            if (sidecarManifest != null && sidecarManifest.versionCode != remoteApkMetadata.versionCode) {
                return GitHubManifestResolution.MetadataConflict(
                    release = release,
                    detail = "Release ${release.tagName} version.json declares versionCode ${sidecarManifest.versionCode}, but Share-Flow.apk contains versionCode ${remoteApkMetadata.versionCode}."
                )
            }
            val normApkName = normalizeVersionName(remoteApkMetadata.versionName)
            val normReleaseName = normalizeVersionName(release.versionName)
            if (normApkName.isNotBlank() &&
                normReleaseName.isNotBlank() &&
                !normApkName.equals(normReleaseName, ignoreCase = true)
            ) {
                return GitHubManifestResolution.MetadataConflict(
                    release = release,
                    detail = "GitHub release tag ${release.tagName} (v$normReleaseName) conflicts with Share-Flow.apk versionName v$normApkName (versionCode ${remoteApkMetadata.versionCode})."
                )
            }
        }

        val sidecarVersionName = sidecarManifest?.versionName?.trim().orEmpty()
        if (sidecarVersionName.isNotBlank() && release.versionName.isNotBlank()) {
            val normalizedSidecar = normalizeVersionName(sidecarVersionName)
            val normalizedRelease = normalizeVersionName(release.versionName)
            if (normalizedSidecar.isNotBlank() &&
                normalizedRelease.isNotBlank() &&
                !normalizedSidecar.equals(normalizedRelease, ignoreCase = true)
            ) {
                return GitHubManifestResolution.MetadataConflict(
                    release = release,
                    detail = "GitHub release tag ${release.tagName} (v$normalizedRelease) conflicts with version.json versionName v$normalizedSidecar (versionCode ${sidecarManifest?.versionCode})."
                )
            }
        }

        val resolvedVersionName = normalizeVersionName(
            sidecarVersionName
                .ifBlank { remoteApkMetadata?.versionName.orEmpty() }
                .ifBlank { release.versionName }
        ).ifBlank { release.versionName }

        val resolvedVersionCode = sidecarManifest?.versionCode?.takeIf { it > 0 }
            ?: remoteApkMetadata?.versionCode?.takeIf { it > 0 }
            ?: extractVersionCodeFromText(release.body)
            ?: extractVersionCodeFromText(release.name)
            ?: return GitHubManifestResolution.MissingVersionCode(release)

        val resolvedSize = sidecarManifest?.fileSizeBytes?.takeIf { it > 0L }
            ?: apkAsset?.sizeBytes
            ?: 0L

        val resolvedMinSdk = sidecarManifest?.minSupportedAndroidSdk
            ?: remoteApkMetadata?.minSdkVersion?.coerceAtLeast(21)
            ?: 24

        val notesAr = sidecarManifest?.releaseNotesAr?.ifBlank { release.body } ?: release.body
        val notesEn = sidecarManifest?.releaseNotesEn?.ifBlank { release.body } ?: release.body

        return GitHubManifestResolution.Resolved(
            CloudUpdateManifest(
                versionCode = resolvedVersionCode,
                versionName = resolvedVersionName,
                apkDownloadUrl = resolvedApkUrl,
                sha256 = resolvedSha256.lowercase(),
                fileSizeBytes = resolvedSize,
                minSupportedAndroidSdk = resolvedMinSdk,
                mandatory = sidecarManifest?.mandatory ?: false,
                releaseNotesAr = notesAr.take(2000),
                releaseNotesEn = notesEn.take(2000),
                portalUrl = sidecarManifest?.portalUrl
                    ?: release.htmlUrl.ifBlank { CloudUpdateManager.OFFICIAL_RELEASES_PAGE_URL },
                publishedAt = sidecarManifest?.publishedAt?.ifBlank { release.publishedAt }
                    ?: release.publishedAt,
                signingCertSha256 = sidecarManifest?.signingCertSha256
            )
        )
    }

    /**
     * Backwards-compatible convenience wrapper returning a [CloudUpdateManifest] when all
     * authentic version and integrity fields are present and consistent, or `null` otherwise.
     */
    fun toCloudUpdateManifest(
        release: GitHubReleaseRecord,
        sidecarManifest: CloudUpdateManifest? = null,
        sidecarSha256Text: String? = null,
        @Suppress("UNUSED_PARAMETER") currentVersionCode: Int = 0,
        @Suppress("UNUSED_PARAMETER") currentVersionName: String = ""
    ): CloudUpdateManifest? = when (
        val resolution = resolveCloudUpdateManifest(
            release = release,
            sidecarManifest = sidecarManifest,
            sidecarSha256Text = sidecarSha256Text
        )
    ) {
        is GitHubManifestResolution.Resolved -> resolution.manifest
        else -> null
    }

    fun toHistoryItem(release: GitHubReleaseRecord): ReleaseHistoryItem = ReleaseHistoryItem(
        tagName = release.tagName,
        versionName = release.versionName,
        title = release.name,
        publishedAt = release.publishedAt,
        releaseNotes = release.body,
        fileSizeBytes = release.apkAsset?.sizeBytes ?: 0L,
        htmlUrl = release.htmlUrl.ifBlank { CloudUpdateManager.OFFICIAL_RELEASES_PAGE_URL },
        isPrerelease = release.isPrerelease,
        hasApkAsset = release.apkAsset != null
    )

    fun normalizeVersionName(tagName: String, releaseName: String = ""): String {
        val fromTag = SEMVER_EXTRACT_REGEX.find(tagName)?.let { match ->
            val patch = match.groupValues[3].ifBlank { "0" }
            "${match.groupValues[1]}.${match.groupValues[2]}.$patch"
        }
        if (!fromTag.isNullOrBlank()) return fromTag

        val fromName = SEMVER_EXTRACT_REGEX.find(releaseName)?.let { match ->
            val patch = match.groupValues[3].ifBlank { "0" }
            "${match.groupValues[1]}.${match.groupValues[2]}.$patch"
        }
        if (!fromName.isNullOrBlank()) return fromName

        return tagName.trim().removePrefix("v").removePrefix("V").take(32)
    }

    /**
     * Compares two semantic version strings (e.g. "1.0.19" vs "1.0.18", "V1.0.12" vs "v1.0.11")
     * for display/sorting/metadata-consistency checks ONLY — never as a substitute for `versionCode`.
     */
    fun compareSemanticVersions(remote: String, installed: String): Int {
        val rParts = parseSemverParts(remote)
        val iParts = parseSemverParts(installed)
        for (idx in 0 until 3) {
            val diff = rParts[idx].compareTo(iParts[idx])
            if (diff != 0) return diff
        }
        return 0
    }

    /**
     * Authoritative version precedence evaluation strictly anchored on integer `versionCode`.
     */
    fun evaluateVersionPrecedence(
        remoteVersionCode: Int,
        remoteVersionName: String,
        installedVersionCode: Int,
        installedVersionName: String
    ): VersionPrecedenceDecision {
        if (remoteVersionCode <= 0 || installedVersionCode <= 0) {
            return VersionPrecedenceDecision.UNREADABLE_VERSION_METADATA
        }
        if (remoteVersionCode > installedVersionCode) {
            return VersionPrecedenceDecision.UPDATE_AVAILABLE
        }
        if (remoteVersionCode < installedVersionCode) {
            return VersionPrecedenceDecision.DOWNGRADE_BLOCKED
        }
        val normRemote = normalizeVersionName(remoteVersionName)
        val normInstalled = normalizeVersionName(installedVersionName)
        if (normRemote.isNotBlank() &&
            normInstalled.isNotBlank() &&
            !normRemote.equals(normInstalled, ignoreCase = true)
        ) {
            return VersionPrecedenceDecision.DUPLICATE_VERSION_CONFLICT
        }
        return VersionPrecedenceDecision.ALREADY_INSTALLED
    }

    /**
     * Returns `true` ONLY when [remoteVersionCode] is strictly greater than [installedVersionCode]
     * (and both are valid positive integers). Never falls back to `versionName` comparison.
     */
    fun isRemoteReleaseNewer(
        remoteVersionCode: Int,
        remoteVersionName: String,
        installedVersionCode: Int,
        installedVersionName: String
    ): Boolean = evaluateVersionPrecedence(
        remoteVersionCode = remoteVersionCode,
        remoteVersionName = remoteVersionName,
        installedVersionCode = installedVersionCode,
        installedVersionName = installedVersionName
    ) == VersionPrecedenceDecision.UPDATE_AVAILABLE

    fun extractSha256FromText(text: String): String? {
        if (text.isBlank()) return null
        val match = SHA256_INLINE_REGEX.find(text) ?: return null
        val candidate = match.groupValues[1].trim().lowercase()
        return candidate.takeIf { SHA256_HEX_REGEX.matches(it) }
    }

    fun extractVersionCodeFromText(text: String): Int? {
        if (text.isBlank()) return null
        val explicitMatch = VERSION_CODE_INLINE_REGEX.find(text)
        if (explicitMatch != null) {
            return explicitMatch.groupValues[1].toIntOrNull()?.takeIf { it > 0 }
        }
        val parenBuildMatch = SEMVER_PAREN_BUILD_REGEX.find(text)
        return parenBuildMatch?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
    }

    private fun extractDigestHex(digest: String): String? {
        if (digest.isBlank()) return null
        val cleaned = digest.removePrefix("sha256:").removePrefix("SHA256:").trim().lowercase()
        return cleaned.takeIf { SHA256_HEX_REGEX.matches(it) }
    }

    private fun parseSemverParts(raw: String): IntArray {
        val match = SEMVER_EXTRACT_REGEX.find(raw.trim())
        if (match != null) {
            return intArrayOf(
                match.groupValues[1].toIntOrNull() ?: 0,
                match.groupValues[2].toIntOrNull() ?: 0,
                match.groupValues[3].ifBlank { "0" }.toIntOrNull() ?: 0
            )
        }
        val pieces = raw.trim().removePrefix("v").removePrefix("V").split('.')
        return intArrayOf(
            pieces.getOrNull(0)?.filter { it.isDigit() }?.toIntOrNull() ?: 0,
            pieces.getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull() ?: 0,
            pieces.getOrNull(2)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
        )
    }
}
