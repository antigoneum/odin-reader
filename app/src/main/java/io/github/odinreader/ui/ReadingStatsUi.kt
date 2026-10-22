package io.github.odinreader.ui

import io.github.odinreader.data.ReadingRecord

/** Display helpers are independent of clocks, rendering and database access. */
internal fun readingFraction(record: ReadingRecord?): Double = when {
    record?.progress?.completed == true -> 1.0
    record == null || !record.progressFraction.isFinite() -> 0.0
    else -> record.progressFraction.coerceIn(0.0, 1.0)
}

internal fun readingPercent(record: ReadingRecord?): Int = (readingFraction(record) * 100.0).toInt()

internal fun readingStatusLabel(record: ReadingRecord?): String = when {
    readingFraction(record) >= 1.0 -> "已读完"
    record == null || (record.lastReadAt <= 0L && readingFraction(record) == 0.0 && record.totalReadingMillis == 0L) -> "未开始"
    else -> "继续阅读"
}

internal fun readingTimeLabel(record: ReadingRecord?): String =
    "已读 ${formatReadingDuration(record?.totalReadingMillis ?: 0L)}"

internal fun remainingTimeLabel(record: ReadingRecord?): String {
    if (readingFraction(record) >= 1.0) return "已读完"
    val remaining = record?.estimatedRemainingMillis?.takeIf { it >= 0L } ?: return "预计剩余：待估算"
    if (remaining < 60_000L) return "预计剩余：不足1分钟"
    // An estimate rounds up to whole minutes; avoid pretending to know seconds.
    val minutes = remaining / 60_000L + if (remaining % 60_000L > 0L) 1L else 0L
    return "预计剩余约 ${formatReadingMinutes(minutes)}"
}

internal fun formatReadingDuration(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1_000L
    if (seconds < 60L) return "${seconds}秒"
    return formatReadingMinutes(seconds / 60L)
}

private fun formatReadingMinutes(minutes: Long): String = when {
    minutes < 60L -> "${minutes}分钟"
    minutes < 24L * 60L -> {
        val rest = minutes % 60L
        "${minutes / 60L}小时" + if (rest > 0L) "${rest}分" else ""
    }
    else -> {
        val hours = (minutes / 60L) % 24L
        "${minutes / (24L * 60L)}天" + if (hours > 0L) "${hours}小时" else ""
    }
}
