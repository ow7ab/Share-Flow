package com.example.shareflow.data.update

import com.example.shareflow.core.common.AppResult
import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Response returned by an HTTP Range probe (`bytes=...`) against a remote APK asset.
 */
data class RemoteByteRangeResponse(
    val bytes: ByteArray,
    val totalLength: Long?,
    val rangeStart: Long,
    val isPartialContent: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RemoteByteRangeResponse) return false
        return bytes.contentEquals(other.bytes) &&
            totalLength == other.totalLength &&
            rangeStart == other.rangeStart &&
            isPartialContent == other.isPartialContent
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + (totalLength?.hashCode() ?: 0)
        result = 31 * result + rangeStart.hashCode()
        result = 31 * result + isPartialContent.hashCode()
        return result
    }
}

/**
 * Authoritative package & version metadata decoded directly from an APK's binary
 * `AndroidManifest.xml` (AXML) without trusting release titles or tag names.
 */
data class RemoteApkManifestMetadata(
    val packageName: String,
    val versionCode: Int,
    val versionName: String,
    val minSdkVersion: Int? = null
)

sealed class RemoteApkManifestResult {
    data class Success(val metadata: RemoteApkManifestMetadata) : RemoteApkManifestResult()
    data class Failure(val detail: String) : RemoteApkManifestResult()
}

/**
 * Extracts real `packageName`, `android:versionCode`, `android:versionName`, and
 * `android:minSdkVersion` from a remote (or in-memory) Android APK by reading only the
 * ZIP End-of-Central-Directory, Central Directory, and `AndroidManifest.xml` entry via
 * bounded HTTP Range requests, then decoding Android Binary XML (AXML).
 *
 * Security properties:
 * - Never downloads the entire APK during version checks (typically < 150 KB total).
 * - Enforces strict upper bounds on Central Directory and decompressed AXML sizes (anti-zip-bomb).
 * - Never fabricates or guesses `versionCode` from release tags or filenames.
 */
object RemoteApkManifestReader {

    internal const val TAIL_PROBE_BYTES = 65_557L // 22-byte EOCD + max 65,535-byte ZIP comment
    internal const val MAX_CENTRAL_DIR_BYTES = 2 * 1024 * 1024 // 2 MB cap
    internal const val MAX_MANIFEST_COMPRESSED_BYTES = 512 * 1024 // 512 KB cap
    internal const val MAX_MANIFEST_UNCOMPRESSED_BYTES = 2 * 1024 * 1024 // 2 MB cap
    private const val LOCAL_HEADER_INITIAL_SLACK_BYTES = 256L

    private const val SIG_EOCD = 0x06054b50
    private const val SIG_CENTRAL_DIR_FILE_HEADER = 0x02014b50
    private const val SIG_LOCAL_FILE_HEADER = 0x04034b50

    private const val ZIP_METHOD_STORED = 0
    private const val ZIP_METHOD_DEFLATED = 8

    // Android Binary XML (AXML) chunk & attribute constants
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_TYPE = 0x0003
    private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102

    private const val ATTR_TYPE_STRING = 0x03
    private const val ATTR_TYPE_INT_DEC = 0x10
    private const val ATTR_TYPE_INT_HEX = 0x11

    private const val ANDROID_RES_ID_VERSION_CODE = 0x0101021b
    private const val ANDROID_RES_ID_VERSION_NAME = 0x0101021c
    private const val ANDROID_RES_ID_MIN_SDK_VERSION = 0x0101020c
    private const val ANDROID_RES_ID_VERSION_CODE_MAJOR = 0x01010576

    internal data class ZipEocdInfo(
        val centralDirOffset: Long,
        val centralDirSize: Int
    )

    internal data class ZipManifestEntry(
        val compressionMethod: Int,
        val compressedSize: Int,
        val uncompressedSize: Int,
        val localHeaderOffset: Long
    )

    suspend fun readFromUrl(
        apkUrl: String,
        httpClient: CloudUpdateHttpClient
    ): RemoteApkManifestResult {
        if (!CloudUpdateValidator.isValidPublicHttpsUrl(apkUrl)) {
            return RemoteApkManifestResult.Failure("APK URL is not a valid public HTTPS endpoint.")
        }

        // 1) Fetch the ZIP tail (up to 65,557 bytes) to locate the End-of-Central-Directory record.
        val tailResp = when (val res = httpClient.fetchByteRange(url = apkUrl, suffixBytes = TAIL_PROBE_BYTES)) {
            is AppResult.Success -> res.data
            is AppResult.Failure -> {
                return RemoteApkManifestResult.Failure(
                    "Unable to probe APK ZIP footer: ${res.error.message}"
                )
            }
        }

        // If the server returned the entire small APK in a single 200 OK response, parse directly!
        if (!tailResp.isPartialContent && tailResp.rangeStart == 0L && tailResp.bytes.size >= 22) {
            val directAttempt = parseApkBytes(tailResp.bytes)
            if (directAttempt is RemoteApkManifestResult.Success) {
                return directAttempt
            }
        }

        val eocd = findEocd(tailResp.bytes)
            ?: return RemoteApkManifestResult.Failure("APK ZIP End-of-Central-Directory record not found.")

        if (eocd.centralDirSize <= 0 || eocd.centralDirSize > MAX_CENTRAL_DIR_BYTES) {
            return RemoteApkManifestResult.Failure(
                "APK Central Directory size (${eocd.centralDirSize} bytes) exceeds safe bounds."
            )
        }

        // 2) Extract or fetch the Central Directory bytes.
        val cdEndExclusive = eocd.centralDirOffset + eocd.centralDirSize.toLong()
        val tailStartOffset = tailResp.rangeStart
        val tailEndExclusive = tailStartOffset + tailResp.bytes.size.toLong()

        val centralDirBytes: ByteArray = if (
            tailStartOffset >= 0L &&
            eocd.centralDirOffset >= tailStartOffset &&
            cdEndExclusive <= tailEndExclusive
        ) {
            val sliceStart = (eocd.centralDirOffset - tailStartOffset).toInt()
            tailResp.bytes.copyOfRange(sliceStart, sliceStart + eocd.centralDirSize)
        } else {
            val cdResp = when (
                val res = httpClient.fetchByteRange(
                    url = apkUrl,
                    startByte = eocd.centralDirOffset,
                    endByte = cdEndExclusive - 1L
                )
            ) {
                is AppResult.Success -> res.data
                is AppResult.Failure -> {
                    return RemoteApkManifestResult.Failure(
                        "Unable to fetch APK Central Directory: ${res.error.message}"
                    )
                }
            }
            if (cdResp.bytes.size < eocd.centralDirSize) {
                return RemoteApkManifestResult.Failure("APK Central Directory response was truncated.")
            }
            if (cdResp.bytes.size == eocd.centralDirSize) {
                cdResp.bytes
            } else if (!cdResp.isPartialContent && cdResp.rangeStart == 0L && cdResp.bytes.size >= cdEndExclusive) {
                // Server ignored Range and sent full APK
                return parseApkBytes(cdResp.bytes)
            } else {
                cdResp.bytes.copyOfRange(0, eocd.centralDirSize)
            }
        }

        // 3) Find AndroidManifest.xml in the Central Directory.
        val entry = locateAndroidManifestInCentralDirectory(centralDirBytes)
            ?: return RemoteApkManifestResult.Failure("APK does not contain an AndroidManifest.xml entry.")

        if (entry.compressedSize <= 0 || entry.compressedSize > MAX_MANIFEST_COMPRESSED_BYTES) {
            return RemoteApkManifestResult.Failure(
                "APK AndroidManifest.xml compressed size (${entry.compressedSize} bytes) is out of safe range."
            )
        }
        if (entry.uncompressedSize <= 0 || entry.uncompressedSize > MAX_MANIFEST_UNCOMPRESSED_BYTES) {
            return RemoteApkManifestResult.Failure(
                "APK AndroidManifest.xml uncompressed size (${entry.uncompressedSize} bytes) is out of safe range."
            )
        }

        // 4) Fetch Local File Header + compressed AndroidManifest.xml bytes.
        val initialFetchEnd = entry.localHeaderOffset + 30L + LOCAL_HEADER_INITIAL_SLACK_BYTES +
            entry.compressedSize.toLong() - 1L
        val clampedEnd = tailResp.totalLength?.let { total ->
            minOf(initialFetchEnd, total - 1L)
        } ?: initialFetchEnd

        val localResp = when (
            val res = httpClient.fetchByteRange(
                url = apkUrl,
                startByte = entry.localHeaderOffset,
                endByte = clampedEnd
            )
        ) {
            is AppResult.Success -> res.data
            is AppResult.Failure -> {
                return RemoteApkManifestResult.Failure(
                    "Unable to fetch APK AndroidManifest.xml entry: ${res.error.message}"
                )
            }
        }

        val localBuf = if (!localResp.isPartialContent && localResp.rangeStart == 0L &&
            entry.localHeaderOffset < localResp.bytes.size
        ) {
            localResp.bytes.copyOfRange(entry.localHeaderOffset.toInt(), localResp.bytes.size)
        } else {
            localResp.bytes
        }

        if (localBuf.size < 30 || readInt32LE(localBuf, 0) != SIG_LOCAL_FILE_HEADER) {
            return RemoteApkManifestResult.Failure("Invalid ZIP local file header for AndroidManifest.xml.")
        }

        val localNameLen = readUInt16LE(localBuf, 26)
        val localExtraLen = readUInt16LE(localBuf, 28)
        val dataStartInLocal = 30 + localNameLen + localExtraLen
        val dataEndInLocal = dataStartInLocal + entry.compressedSize

        val compressedManifestBytes: ByteArray = if (dataEndInLocal <= localBuf.size) {
            localBuf.copyOfRange(dataStartInLocal, dataEndInLocal)
        } else {
            // Extra field exceeded initial slack — issue one exact follow-up range request.
            val exactStart = entry.localHeaderOffset + dataStartInLocal.toLong()
            val exactEnd = exactStart + entry.compressedSize.toLong() - 1L
            val exactResp = when (
                val res = httpClient.fetchByteRange(
                    url = apkUrl,
                    startByte = exactStart,
                    endByte = exactEnd
                )
            ) {
                is AppResult.Success -> res.data
                is AppResult.Failure -> {
                    return RemoteApkManifestResult.Failure(
                        "Unable to fetch APK AndroidManifest.xml payload: ${res.error.message}"
                    )
                }
            }
            if (exactResp.bytes.size < entry.compressedSize) {
                return RemoteApkManifestResult.Failure("APK AndroidManifest.xml payload was truncated.")
            }
            exactResp.bytes.copyOfRange(0, entry.compressedSize)
        }

        val axmlBytes = decompressManifestEntry(compressedManifestBytes, entry)
            ?: return RemoteApkManifestResult.Failure("Failed to decompress APK AndroidManifest.xml.")

        return parseBinaryAndroidManifest(axmlBytes)
    }

    /**
     * Parses an entire APK byte array directly (useful for local verification and unit tests).
     */
    fun parseApkBytes(apkBytes: ByteArray): RemoteApkManifestResult {
        if (apkBytes.size < 22) {
            return RemoteApkManifestResult.Failure("APK payload is too small to be a valid ZIP archive.")
        }
        val eocd = findEocd(apkBytes)
            ?: return RemoteApkManifestResult.Failure("APK ZIP End-of-Central-Directory record not found.")
        if (eocd.centralDirOffset < 0L ||
            eocd.centralDirSize <= 0 ||
            eocd.centralDirSize > MAX_CENTRAL_DIR_BYTES ||
            eocd.centralDirOffset + eocd.centralDirSize > apkBytes.size.toLong()
        ) {
            return RemoteApkManifestResult.Failure("APK Central Directory bounds are invalid.")
        }

        val cdStart = eocd.centralDirOffset.toInt()
        val centralDirBytes = apkBytes.copyOfRange(cdStart, cdStart + eocd.centralDirSize)
        val entry = locateAndroidManifestInCentralDirectory(centralDirBytes)
            ?: return RemoteApkManifestResult.Failure("APK does not contain an AndroidManifest.xml entry.")

        if (entry.compressedSize <= 0 || entry.compressedSize > MAX_MANIFEST_COMPRESSED_BYTES ||
            entry.uncompressedSize <= 0 || entry.uncompressedSize > MAX_MANIFEST_UNCOMPRESSED_BYTES
        ) {
            return RemoteApkManifestResult.Failure("APK AndroidManifest.xml entry size is out of safe bounds.")
        }

        val localOffset = entry.localHeaderOffset.toInt()
        if (localOffset < 0 || localOffset + 30 > apkBytes.size ||
            readInt32LE(apkBytes, localOffset) != SIG_LOCAL_FILE_HEADER
        ) {
            return RemoteApkManifestResult.Failure("Invalid ZIP local file header for AndroidManifest.xml.")
        }
        val localNameLen = readUInt16LE(apkBytes, localOffset + 26)
        val localExtraLen = readUInt16LE(apkBytes, localOffset + 28)
        val dataStart = localOffset + 30 + localNameLen + localExtraLen
        val dataEnd = dataStart + entry.compressedSize
        if (dataStart < 0 || dataEnd > apkBytes.size) {
            return RemoteApkManifestResult.Failure("APK AndroidManifest.xml data offsets exceed archive length.")
        }

        val compressed = apkBytes.copyOfRange(dataStart, dataEnd)
        val axmlBytes = decompressManifestEntry(compressed, entry)
            ?: return RemoteApkManifestResult.Failure("Failed to decompress APK AndroidManifest.xml.")

        return parseBinaryAndroidManifest(axmlBytes)
    }

    internal fun findEocd(bytes: ByteArray): ZipEocdInfo? {
        if (bytes.size < 22) return null
        val minPos = maxOf(0, bytes.size - 22 - 65_535)
        for (pos in (bytes.size - 22) downTo minPos) {
            if (readInt32LE(bytes, pos) == SIG_EOCD) {
                val commentLen = readUInt16LE(bytes, pos + 20)
                if (pos + 22 + commentLen == bytes.size) {
                    val cdSizeUnsigned = readUInt32LE(bytes, pos + 12)
                    val cdOffsetUnsigned = readUInt32LE(bytes, pos + 16)
                    if (cdSizeUnsigned == 0xFFFFFFFFL || cdOffsetUnsigned == 0xFFFFFFFFL) {
                        return null // ZIP64 not expected for Android APKs
                    }
                    if (cdSizeUnsigned <= 0L || cdSizeUnsigned > Int.MAX_VALUE.toLong()) {
                        return null
                    }
                    return ZipEocdInfo(
                        centralDirOffset = cdOffsetUnsigned,
                        centralDirSize = cdSizeUnsigned.toInt()
                    )
                }
            }
        }
        return null
    }

    internal fun locateAndroidManifestInCentralDirectory(centralDir: ByteArray): ZipManifestEntry? {
        var pos = 0
        while (pos + 46 <= centralDir.size) {
            if (readInt32LE(centralDir, pos) != SIG_CENTRAL_DIR_FILE_HEADER) {
                return null
            }
            val method = readUInt16LE(centralDir, pos + 10)
            val compressedSize = readUInt32LE(centralDir, pos + 20)
            val uncompressedSize = readUInt32LE(centralDir, pos + 24)
            val nameLen = readUInt16LE(centralDir, pos + 28)
            val extraLen = readUInt16LE(centralDir, pos + 30)
            val commentLen = readUInt16LE(centralDir, pos + 32)
            val localHeaderOffset = readUInt32LE(centralDir, pos + 42)

            val nameStart = pos + 46
            val nextPos = nameStart + nameLen + extraLen + commentLen
            if (nextPos > centralDir.size || nextPos <= pos) {
                return null
            }
            val entryName = String(centralDir, nameStart, nameLen, Charsets.UTF_8)
            if (entryName == "AndroidManifest.xml") {
                if (compressedSize > Int.MAX_VALUE || uncompressedSize > Int.MAX_VALUE) {
                    return null
                }
                return ZipManifestEntry(
                    compressionMethod = method,
                    compressedSize = compressedSize.toInt(),
                    uncompressedSize = uncompressedSize.toInt(),
                    localHeaderOffset = localHeaderOffset
                )
            }
            pos = nextPos
        }
        return null
    }

    private fun decompressManifestEntry(
        compressedBytes: ByteArray,
        entry: ZipManifestEntry
    ): ByteArray? {
        return when (entry.compressionMethod) {
            ZIP_METHOD_STORED -> compressedBytes
            ZIP_METHOD_DEFLATED -> {
                val inflater = Inflater(true)
                try {
                    inflater.setInput(compressedBytes)
                    val out = ByteArrayOutputStream(minOf(entry.uncompressedSize, 64 * 1024))
                    val buffer = ByteArray(4096)
                    var totalOut = 0
                    while (!inflater.finished()) {
                        val count = inflater.inflate(buffer)
                        if (count == 0) {
                            if (inflater.needsInput() || inflater.needsDictionary()) break
                        } else {
                            totalOut += count
                            if (totalOut > MAX_MANIFEST_UNCOMPRESSED_BYTES) {
                                return null
                            }
                            out.write(buffer, 0, count)
                        }
                    }
                    out.toByteArray()
                } catch (_: DataFormatException) {
                    null
                } finally {
                    inflater.end()
                }
            }
            else -> null
        }
    }

    /**
     * Decodes an Android Binary XML (AXML) `AndroidManifest.xml` stream to extract:
     * - `manifest@package`
     * - `manifest@android:versionCode` (plus `versionCodeMajor` check)
     * - `manifest@android:versionName`
     * - `uses-sdk@android:minSdkVersion`
     */
    fun parseBinaryAndroidManifest(axml: ByteArray): RemoteApkManifestResult {
        if (axml.size < 8) {
            return RemoteApkManifestResult.Failure("Binary AndroidManifest.xml is truncated.")
        }
        val rootType = readUInt16LE(axml, 0)
        val rootHeaderSize = readUInt16LE(axml, 2)
        val rootChunkSize = readUInt32LE(axml, 4)
        if (rootType != RES_XML_TYPE || rootHeaderSize < 8 || rootChunkSize < rootHeaderSize) {
            return RemoteApkManifestResult.Failure("AndroidManifest.xml is not valid Android Binary XML (AXML).")
        }

        val limit = minOf(axml.size.toLong(), rootChunkSize).toInt()
        var offset = rootHeaderSize

        var stringPool: List<String> = emptyList()
        var resourceMap: IntArray = IntArray(0)

        var packageName: String? = null
        var versionCode: Int? = null
        var versionCodeMajor = 0
        var versionName: String? = null
        var minSdkVersion: Int? = null

        while (offset + 8 <= limit) {
            val chunkType = readUInt16LE(axml, offset)
            val chunkHeaderSize = readUInt16LE(axml, offset + 2)
            val chunkSizeLong = readUInt32LE(axml, offset + 4)
            if (chunkSizeLong < 8L || offset + chunkSizeLong > limit) {
                break
            }
            val chunkSize = chunkSizeLong.toInt()

            when (chunkType) {
                RES_STRING_POOL_TYPE -> {
                    stringPool = parseStringPool(axml, offset, chunkHeaderSize, chunkSize)
                }

                RES_XML_RESOURCE_MAP_TYPE -> {
                    val count = maxOf(0, (chunkSize - chunkHeaderSize) / 4)
                    val map = IntArray(count)
                    var p = offset + chunkHeaderSize
                    for (i in 0 until count) {
                        if (p + 4 > offset + chunkSize) break
                        map[i] = readInt32LE(axml, p)
                        p += 4
                    }
                    resourceMap = map
                }

                RES_XML_START_ELEMENT_TYPE -> {
                    val extStart = offset + chunkHeaderSize
                    if (extStart + 20 <= offset + chunkSize) {
                        val nameIdx = readInt32LE(axml, extStart + 4)
                        val attrStart = readUInt16LE(axml, extStart + 8)
                        val attrSize = readUInt16LE(axml, extStart + 10)
                        val attrCount = readUInt16LE(axml, extStart + 12)
                        val tagName = stringPool.getOrNull(nameIdx)

                        if (tagName == "manifest" || tagName == "uses-sdk") {
                            val attrsBase = extStart + attrStart
                            if (attrSize >= 20 && attrsBase >= extStart) {
                                for (a in 0 until attrCount) {
                                    val attrOffset = attrsBase + a * attrSize
                                    if (attrOffset + 20 > offset + chunkSize) break

                                    val attrNameIdx = readInt32LE(axml, attrOffset + 4)
                                    val attrRawValueIdx = readInt32LE(axml, attrOffset + 8)
                                    val valueType = axml[attrOffset + 15].toInt() and 0xFF
                                    val valueData = readInt32LE(axml, attrOffset + 16)

                                    val resId = if (attrNameIdx in resourceMap.indices) {
                                        resourceMap[attrNameIdx]
                                    } else {
                                        0
                                    }
                                    val attrName = stringPool.getOrNull(attrNameIdx)

                                    if (tagName == "manifest") {
                                        when {
                                            attrName == "package" && resId == 0 -> {
                                                val value = resolveStringAttr(stringPool, attrRawValueIdx, valueType, valueData)
                                                if (!value.isNullOrBlank()) packageName = value.trim()
                                            }
                                            resId == ANDROID_RES_ID_VERSION_CODE || attrName == "versionCode" -> {
                                                val code = resolveIntAttr(stringPool, attrRawValueIdx, valueType, valueData)
                                                if (code != null && code > 0) versionCode = code
                                            }
                                            resId == ANDROID_RES_ID_VERSION_CODE_MAJOR || attrName == "versionCodeMajor" -> {
                                                val major = resolveIntAttr(stringPool, attrRawValueIdx, valueType, valueData)
                                                if (major != null && major > 0) versionCodeMajor = major
                                            }
                                            resId == ANDROID_RES_ID_VERSION_NAME || attrName == "versionName" -> {
                                                val name = resolveStringAttr(stringPool, attrRawValueIdx, valueType, valueData)
                                                if (!name.isNullOrBlank()) versionName = name.trim()
                                            }
                                        }
                                    } else if (tagName == "uses-sdk") {
                                        if (resId == ANDROID_RES_ID_MIN_SDK_VERSION || attrName == "minSdkVersion") {
                                            val sdk = resolveIntAttr(stringPool, attrRawValueIdx, valueType, valueData)
                                            if (sdk != null && sdk > 0) minSdkVersion = sdk
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            offset += chunkSize
        }

        if (versionCodeMajor != 0) {
            return RemoteApkManifestResult.Failure(
                "APK uses 64-bit versionCodeMajor ($versionCodeMajor) outside 32-bit versionCode bounds."
            )
        }
        val finalPackage = packageName?.takeIf { it.isNotBlank() }
            ?: return RemoteApkManifestResult.Failure("APK AndroidManifest.xml is missing package name.")
        val finalCode = versionCode?.takeIf { it > 0 }
            ?: return RemoteApkManifestResult.Failure("APK AndroidManifest.xml is missing a positive android:versionCode.")
        val finalName = versionName
            ?.let { GitHubReleasesParser.normalizeVersionName(it).ifBlank { it.trim() } }
            ?.takeIf { it.isNotBlank() }
            ?: return RemoteApkManifestResult.Failure("APK AndroidManifest.xml is missing android:versionName.")

        return RemoteApkManifestResult.Success(
            RemoteApkManifestMetadata(
                packageName = finalPackage,
                versionCode = finalCode,
                versionName = finalName,
                minSdkVersion = minSdkVersion
            )
        )
    }

    private fun parseStringPool(
        data: ByteArray,
        chunkOffset: Int,
        chunkHeaderSize: Int,
        chunkSize: Int
    ): List<String> {
        if (chunkHeaderSize < 28 || chunkOffset + 28 > data.size) return emptyList()
        val stringCount = readInt32LE(data, chunkOffset + 8)
        if (stringCount <= 0 || stringCount > 100_000) return emptyList()
        val flags = readInt32LE(data, chunkOffset + 16)
        val isUtf8 = (flags and (1 shl 8)) != 0
        val stringsStart = readInt32LE(data, chunkOffset + 20)
        val chunkEnd = chunkOffset + chunkSize
        val offsetsBase = chunkOffset + chunkHeaderSize
        val poolDataBase = chunkOffset + stringsStart
        if (poolDataBase < chunkOffset || poolDataBase >= chunkEnd) return emptyList()

        val result = ArrayList<String>(stringCount)
        for (i in 0 until stringCount) {
            val idxOffset = offsetsBase + i * 4
            if (idxOffset + 4 > chunkEnd) break
            val relOffset = readInt32LE(data, idxOffset)
            val strPos = poolDataBase + relOffset
            if (strPos < poolDataBase || strPos >= chunkEnd) {
                result.add("")
                continue
            }
            val decoded = if (isUtf8) {
                decodeUtf8PoolString(data, strPos, chunkEnd)
            } else {
                decodeUtf16PoolString(data, strPos, chunkEnd)
            }
            result.add(decoded)
        }
        return result
    }

    private fun decodeUtf8PoolString(data: ByteArray, startPos: Int, chunkEnd: Int): String {
        var p = startPos
        if (p >= chunkEnd) return ""
        // Skip UTF-16 character length varint (1 or 2 bytes)
        val charLenFirst = data[p++].toInt() and 0xFF
        if ((charLenFirst and 0x80) != 0) {
            if (p >= chunkEnd) return ""
            p++
        }
        if (p >= chunkEnd) return ""
        // Read UTF-8 byte length varint (1 or 2 bytes)
        val byteLenFirst = data[p++].toInt() and 0xFF
        val byteLen = if ((byteLenFirst and 0x80) != 0) {
            if (p >= chunkEnd) return ""
            ((byteLenFirst and 0x7F) shl 8) or (data[p++].toInt() and 0xFF)
        } else {
            byteLenFirst
        }
        if (byteLen < 0 || p + byteLen > chunkEnd) return ""
        return String(data, p, byteLen, Charsets.UTF_8)
    }

    private fun decodeUtf16PoolString(data: ByteArray, startPos: Int, chunkEnd: Int): String {
        var p = startPos
        if (p + 2 > chunkEnd) return ""
        val first = readUInt16LE(data, p)
        p += 2
        val charLen = if ((first and 0x8000) != 0) {
            if (p + 2 > chunkEnd) return ""
            val low = readUInt16LE(data, p)
            p += 2
            ((first and 0x7FFF) shl 16) or low
        } else {
            first
        }
        val byteLen = charLen * 2
        if (byteLen < 0 || p + byteLen > chunkEnd) return ""
        return String(data, p, byteLen, Charsets.UTF_16LE)
    }

    private fun resolveStringAttr(
        strings: List<String>,
        rawValueIdx: Int,
        valueType: Int,
        valueData: Int
    ): String? {
        if (rawValueIdx in strings.indices) {
            return strings[rawValueIdx]
        }
        if (valueType == ATTR_TYPE_STRING && valueData in strings.indices) {
            return strings[valueData]
        }
        return null
    }

    private fun resolveIntAttr(
        strings: List<String>,
        rawValueIdx: Int,
        valueType: Int,
        valueData: Int
    ): Int? {
        if (valueType == ATTR_TYPE_INT_DEC || valueType == ATTR_TYPE_INT_HEX) {
            return valueData
        }
        val str = resolveStringAttr(strings, rawValueIdx, valueType, valueData)
        return str?.trim()?.toIntOrNull()
    }

    private fun readUInt16LE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readInt32LE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readUInt32LE(data: ByteArray, offset: Int): Long {
        return readInt32LE(data, offset).toLong() and 0xFFFFFFFFL
    }
}
