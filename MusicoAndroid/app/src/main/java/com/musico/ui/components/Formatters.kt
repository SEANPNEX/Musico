package com.musico.ui.components

import android.text.format.DateUtils
import java.util.Locale
import kotlin.math.floor

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
