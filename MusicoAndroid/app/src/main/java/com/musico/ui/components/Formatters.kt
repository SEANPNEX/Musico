package com.musico.ui.components

import android.text.format.DateUtils
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

fun formatTime(seconds: Double): String {
    if (!seconds.isFinite() || seconds <= 0.0) return "0:00"
    val total = floor(seconds).toLong()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, secs)
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.US, "%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}

fun formatTransferSpeed(bytesPerSecond: Double): String {
    if (!bytesPerSecond.isFinite() || bytesPerSecond <= 0.0) return "0 B/s"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytesPerSecond >= gb -> String.format(Locale.US, "%.2f GB/s", bytesPerSecond / gb)
        bytesPerSecond >= mb -> String.format(Locale.US, "%.1f MB/s", bytesPerSecond / mb)
        bytesPerSecond >= 100.0 * kb -> String.format(Locale.US, "%.0f KB/s", bytesPerSecond / kb)
        bytesPerSecond >= kb -> String.format(Locale.US, "%.1f KB/s", bytesPerSecond / kb)
        else -> "${bytesPerSecond.roundToInt()} B/s"
    }
}

fun formatPercentage(fraction: Float): String {
    val clamped = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
    val pct = (clamped * 100f).roundToInt().coerceIn(0, 100)
    return "$pct%"
}

fun formatListeningTime(totalSeconds: Double): String {
    if (!totalSeconds.isFinite() || totalSeconds <= 0.0) return "0 min"
    val minutes = (totalSeconds / 60.0).toLong()
    return if (minutes >= 60) {
        "${minutes / 60} h ${minutes % 60} min"
    } else {
        "$minutes min"
    }
}

fun formatRelativeTime(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    return DateUtils.getRelativeTimeSpanString(
        epochMs,
        nowMs,
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()
}
