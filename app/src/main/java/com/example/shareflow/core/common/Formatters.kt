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

    fun formatBytes(bytes: Long, locale: Locale = Locale.US): String {
        val units = if (locale.language == "ar") arabicUnits else latinUnits
        val safeBytes = bytes.coerceAtLeast(0L)
        if (safeBytes < 1024L) {
            return "${formatInteger(safeBytes, locale)} ${units[0]}"
        }
        val exp = (ln(safeBytes.toDouble()) / ln(1024.0)).toInt().coerceAtMost(units.lastIndex)
        val value = safeBytes / 1024.0.pow(exp.toDouble())
        val numberFormatter = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
            isGroupingUsed = false
        }
        return "${numberFormatter.format(value)} ${units[exp]}"
    }

    fun formatTransferSpeed(bytesPerSecond: Long, locale: Locale = Locale.US): String {
        val safeSpeed = bytesPerSecond.coerceAtLeast(0L)
        val suffix = if (locale.language == "ar") "/ث" else "/s"
        return "${formatBytes(safeSpeed, locale)}$suffix"
    }

    fun formatInteger(value: Long, locale: Locale = Locale.US): String {
        val formatter = NumberFormat.getIntegerInstance(locale).apply {
            isGroupingUsed = false
        }
        return formatter.format(value)
    }

    fun formatCount(value: Int, locale: Locale = Locale.US): String =
        NumberFormat.getIntegerInstance(locale).format(value)

    fun formatPercentage(fraction: Float, locale: Locale = Locale.US): String {
        val clamped = fraction.coerceIn(0f, 1f).toDouble()
        val formatter = NumberFormat.getPercentInstance(locale).apply {
            maximumFractionDigits = 0
        }
        return formatter.format(clamped)
    }

    fun formatShortHash(sha256: String): String {
        val clean = sha256.trim()
        if (clean.length <= 12) return clean
        return "${clean.take(8)}…${clean.takeLast(4)}"
    }

    fun formatTimestamp(epochMillis: Long, locale: Locale = Locale.US): String {
        if (epochMillis <= 0L) return "—"
        val formatter = java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.MEDIUM,
            java.text.DateFormat.SHORT,
            locale
        )
        return formatter.format(java.util.Date(epochMillis))
    }
}
