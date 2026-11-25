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
 * Parses GitHub Releases API JSON responses (`/releases/latest` and `/releases`) into validated
 * Share Flow update models without trusting unvalidated URLs or draft/prerelease artifacts.
 */
object GitHubReleasesParser {

    private val SHA256_HEX_REGEX = Regex("^[0-9a-fA-F]{64}$")
    private val SHA256_INLINE_REGEX = Regex("""(?:sha256[:=\s]+)?([0-9a-fA-F]{64})""", RegexOption.IGNORE_CASE)
    private val VERSION_CODE_INLINE_REGEX = Regex(
        """(?:versionCode|build(?:\s+number)?|البناء)\s*[:=#]?\s*(\d{1,9})""",
        RegexOption.IGNORE_CASE
    )
    private val SEMVER_EXTRACT_REGEX = Regex("""[vV]?(\d+)\.(\d+)\.(\d+)(?:[-+][0-9A-Za-z.-]+)?""")

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
     * Converts a validated stable [GitHubReleaseRecord] into a [CloudUpdateManifest].
     * Uses [sidecarManifest] (`version.json`) when attached to the release for exact `versionCode`,
     * `signingCertSha256`, bilingual notes, and `mandatory` flag; otherwise safely derives them
     * from the GitHub Release JSON and native asset SHA-256 digest / `.sha256` sidecar.
     */
    fun toCloudUpdateManifest(
        release: GitHubReleaseRecord,
        sidecarManifest: CloudUpdateManifest? = null,
        sidecarSha256Text: String? = null,
        currentVersionCode: Int,
        currentVersionName: String
    ): CloudUpdateManifest? {
        if (release.isDraft || release.isPrerelease) return null

        val apkAsset = release.apkAsset
        val resolvedApkUrl = sidecarManifest?.apkDownloadUrl
            ?.takeIf { CloudUpdateValidator.isValidPublicHttpsUrl(it) }
            ?: apkAsset?.browserDownloadUrl
            ?: return null

        if (!CloudUpdateValidator.isValidPublicHttpsUrl(resolvedApkUrl)) return null

        val resolvedSha256 = sidecarManifest?.sha256
            ?.takeIf { SHA256_HEX_REGEX.matches(it) }
            ?: apkAsset?.sha256Digest
            ?: extractSha256FromText(sidecarSha256Text.orEmpty())
            ?: extractSha256FromText(release.body)
            ?: return null

        val resolvedVersionName = sidecarManifest?.versionName?.ifBlank { release.versionName }
            ?: release.versionName
        val resolvedVersionCode = sidecarManifest?.versionCode
            ?: extractVersionCodeFromBody(release.body)
            ?: deriveSyntheticVersionCode(
                remoteVersionName = resolvedVersionName,
                currentVersionName = currentVersionName,
                currentVersionCode = currentVersionCode
            )

        val resolvedSize = sidecarManifest?.fileSizeBytes?.takeIf { it > 0L }
            ?: apkAsset?.sizeBytes
            ?: 0L

        val notesAr = sidecarManifest?.releaseNotesAr?.ifBlank { release.body } ?: release.body
        val notesEn = sidecarManifest?.releaseNotesEn?.ifBlank { release.body } ?: release.body

        return CloudUpdateManifest(
            versionCode = resolvedVersionCode,
            versionName = resolvedVersionName,
            apkDownloadUrl = resolvedApkUrl,
            sha256 = resolvedSha256.lowercase(),
            fileSizeBytes = resolvedSize,
            minSupportedAndroidSdk = sidecarManifest?.minSupportedAndroidSdk ?: 24,
            mandatory = sidecarManifest?.mandatory ?: false,
            releaseNotesAr = notesAr.take(2000),
            releaseNotesEn = notesEn.take(2000),
            portalUrl = sidecarManifest?.portalUrl
                ?: release.htmlUrl.ifBlank { CloudUpdateManager.OFFICIAL_RELEASES_PAGE_URL },
            publishedAt = sidecarManifest?.publishedAt?.ifBlank { release.publishedAt }
                ?: release.publishedAt,
            signingCertSha256 = sidecarManifest?.signingCertSha256
        )
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
            "${match.groupValues[1]}.${match.groupValues[2]}.${match.groupValues[3]}"
        }
        if (!fromTag.isNullOrBlank()) return fromTag

        val fromName = SEMVER_EXTRACT_REGEX.find(releaseName)?.let { match ->
            "${match.groupValues[1]}.${match.groupValues[2]}.${match.groupValues[3]}"
        }
        if (!fromName.isNullOrBlank()) return fromName

        return tagName.trim().removePrefix("v").removePrefix("V").take(32)
    }

    /**
     * Compares two semantic version strings (e.g. "1.0.19" vs "1.0.18", "V1.0.12" vs "v1.0.11").
     * Returns > 0 if [remote] is newer than [installed], 0 if equal, < 0 if older.
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

    fun isRemoteReleaseNewer(
        remoteVersionCode: Int,
        remoteVersionName: String,
        installedVersionCode: Int,
        installedVersionName: String
    ): Boolean {
        if (remoteVersionCode > installedVersionCode) return true
        if (remoteVersionCode < installedVersionCode) return false
        return compareSemanticVersions(remoteVersionName, installedVersionName) > 0
    }

    fun extractSha256FromText(text: String): String? {
        if (text.isBlank()) return null
        val match = SHA256_INLINE_REGEX.find(text) ?: return null
        val candidate = match.groupValues[1].trim().lowercase()
        return candidate.takeIf { SHA256_HEX_REGEX.matches(it) }
    }

    private fun extractDigestHex(digest: String): String? {
        if (digest.isBlank()) return null
        val cleaned = digest.removePrefix("sha256:").removePrefix("SHA256:").trim().lowercase()
        return cleaned.takeIf { SHA256_HEX_REGEX.matches(it) }
    }

    private fun extractVersionCodeFromBody(body: String): Int? {
        if (body.isBlank()) return null
        val match = VERSION_CODE_INLINE_REGEX.find(body) ?: return null
        return match.groupValues[1].toIntOrNull()?.takeIf { it > 0 }
    }

    private fun deriveSyntheticVersionCode(
        remoteVersionName: String,
        currentVersionName: String,
        currentVersionCode: Int
    ): Int {
        val cmp = compareSemanticVersions(remoteVersionName, currentVersionName)
        return when {
            cmp > 0 -> currentVersionCode + 1
            cmp == 0 -> currentVersionCode
            else -> (currentVersionCode - 1).coerceAtLeast(1)
        }
    }

    private fun parseSemverParts(raw: String): IntArray {
        val match = SEMVER_EXTRACT_REGEX.find(raw.trim())
        if (match != null) {
            return intArrayOf(
                match.groupValues[1].toIntOrNull() ?: 0,
                match.groupValues[2].toIntOrNull() ?: 0,
                match.groupValues[3].toIntOrNull() ?: 0
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
