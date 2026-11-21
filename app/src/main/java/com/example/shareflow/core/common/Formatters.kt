package com.example.shareflow.core.common

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

/**
 * Pure formatting helpers shared across presentation and domain layers.
 * Supports locale-aware Arabic and English number formatting while preserving deterministic defaults.
 */
object ShareFlowFormatters {
    private val latinUnits = arrayOf("B", "KB", "MB", "GB", "TB")
    private val arabicUnits = arrayOf("بايت", "ك.ب", "م.ب", "ج.ب", "ت.ب")

    private val oneDecimalFormatterCache = ThreadLocal.withInitial { HashMap<String, NumberFormat>(4) }
    private val integerNoGroupCache = ThreadLocal.withInitial { HashMap<String, NumberFormat>(4) }
    private val integerGroupedCache = ThreadLocal.withInitial { HashMap<String, NumberFormat>(4) }
    private val percentFormatterCache = ThreadLocal.withInitial { HashMap<String, NumberFormat>(4) }
    private val dateTimeFormatterCache = ThreadLocal.withInitial { HashMap<String, java.text.DateFormat>(4) }

    private fun localeKey(locale: Locale): String = locale.toLanguageTag().ifBlank { locale.language }

    private fun oneDecimalFormatter(locale: Locale): NumberFormat {
        val map = oneDecimalFormatterCache.get() ?: HashMap<String, NumberFormat>(4).also { oneDecimalFormatterCache.set(it) }
        return map.getOrPut(localeKey(locale)) {
            NumberFormat.getNumberInstance(locale).apply {
                minimumFractionDigits = 1
                maximumFractionDigits = 1
                isGroupingUsed = false
            }
        }
    }

    private fun integerNoGroupFormatter(locale: Locale): NumberFormat {
        val map = integerNoGroupCache.get() ?: HashMap<String, NumberFormat>(4).also { integerNoGroupCache.set(it) }
        return map.getOrPut(localeKey(locale)) {
            NumberFormat.getIntegerInstance(locale).apply {
                isGroupingUsed = false
            }
        }
    }

    private fun integerGroupedFormatter(locale: Locale): NumberFormat {
        val map = integerGroupedCache.get() ?: HashMap<String, NumberFormat>(4).also { integerGroupedCache.set(it) }
        return map.getOrPut(localeKey(locale)) {
            NumberFormat.getIntegerInstance(locale)
        }
    }

    private fun percentFormatter(locale: Locale): NumberFormat {
        val map = percentFormatterCache.get() ?: HashMap<String, NumberFormat>(4).also { percentFormatterCache.set(it) }
        return map.getOrPut(localeKey(locale)) {
            NumberFormat.getPercentInstance(locale).apply {
                maximumFractionDigits = 0
            }
        }
    }

    private fun dateTimeFormatter(locale: Locale): java.text.DateFormat {
        val map = dateTimeFormatterCache.get() ?: HashMap<String, java.text.DateFormat>(4).also { dateTimeFormatterCache.set(it) }
        return map.getOrPut(localeKey(locale)) {
            java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.MEDIUM,
                java.text.DateFormat.SHORT,
                locale
            )
        }
    }

    fun formatBytes(bytes: Long, locale: Locale = Locale.US): String {
        val units = if (locale.language == "ar") arabicUnits else latinUnits
        val safeBytes = bytes.coerceAtLeast(0L)
        if (safeBytes < 1024L) {
            return "${formatInteger(safeBytes, locale)} ${units[0]}"
        }
        val exp = (ln(safeBytes.toDouble()) / ln(1024.0)).toInt().coerceAtMost(units.lastIndex)
        val value = safeBytes / 1024.0.pow(exp.toDouble())
        val formatted = runCatching { oneDecimalFormatter(locale).format(value) }
            .getOrElse { String.format(Locale.US, "%.1f", value) }
        return "$formatted ${units[exp]}"
    }

    fun formatTransferSpeed(bytesPerSecond: Long, locale: Locale = Locale.US): String {
        val safeSpeed = bytesPerSecond.coerceAtLeast(0L)
        val suffix = if (locale.language == "ar") "/ث" else "/s"
        return "${formatBytes(safeSpeed, locale)}$suffix"
    }

    fun formatInteger(value: Long, locale: Locale = Locale.US): String =
        runCatching { integerNoGroupFormatter(locale).format(value) }.getOrElse { value.toString() }

    fun formatCount(value: Int, locale: Locale = Locale.US): String =
        runCatching { integerGroupedFormatter(locale).format(value) }.getOrElse { value.toString() }

    fun formatPercentage(fraction: Float, locale: Locale = Locale.US): String {
        val clamped = fraction.coerceIn(0f, 1f).toDouble()
        return runCatching { percentFormatter(locale).format(clamped) }
            .getOrElse { "${(clamped * 100.0).toInt()}%" }
    }

    fun formatShortHash(sha256: String): String {
        val clean = sha256.trim()
        if (clean.length <= 12) return clean
        return "${clean.take(8)}…${clean.takeLast(4)}"
    }

    fun formatTimestamp(epochMillis: Long, locale: Locale = Locale.US): String {
        if (epochMillis <= 0L) return "—"
        return runCatching { dateTimeFormatter(locale).format(java.util.Date(epochMillis)) }
            .getOrElse { "—" }
    }

    fun formatRemainingTime(remainingSeconds: Long?, locale: Locale = Locale.US): String {
        if (remainingSeconds == null || remainingSeconds < 0L) return "—"
        val isArabic = locale.language == "ar"
        val hours = remainingSeconds / 3600L
        val minutes = (remainingSeconds % 3600L) / 60L
        val seconds = remainingSeconds % 60L
        return when {
            hours > 0L -> if (isArabic) {
                "${formatInteger(hours, locale)} س ${formatInteger(minutes, locale)} د"
            } else {
                "${formatInteger(hours, locale)}h ${formatInteger(minutes, locale)}m"
            }
            minutes > 0L -> if (isArabic) {
                "${formatInteger(minutes, locale)} د ${formatInteger(seconds, locale)} ث"
            } else {
                "${formatInteger(minutes, locale)}m ${formatInteger(seconds, locale)}s"
            }
            else -> if (isArabic) {
                "${formatInteger(seconds, locale)} ث"
            } else {
                "${formatInteger(seconds, locale)}s"
            }
        }
    }
}
