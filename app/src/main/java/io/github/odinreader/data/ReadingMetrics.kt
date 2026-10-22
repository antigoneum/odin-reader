package io.github.odinreader.data

import kotlin.math.max
import kotlin.math.roundToLong

data class ReadingPositionMetrics(val positionUnits: Double, val totalUnits: Double, val fraction: Double)

/** Deterministic estimates: characters for reflowable books, original pages for PDF. */
object ReadingMetrics {
    fun position(content: BookContent, progress: ReadingProgress): ReadingPositionMetrics {
        if (content.pdfFile != null || content.book.format == "PDF") {
            val total = content.book.chapterCount.coerceAtLeast(0).toDouble()
            if (total == 0.0) return ReadingPositionMetrics(0.0, 0.0, 0.0)
            val units = if (progress.completed) total else
                (progress.pageIndex.toLong() + 1L).coerceIn(1L, total.toLong()).toDouble()
            return ReadingPositionMetrics(units, total, (units / total).coerceIn(0.0, 1.0))
        }
        val total = content.chapters.sumOf { it.text.length.toLong() }.toDouble()
        if (total == 0.0 || content.chapters.isEmpty()) {
            return ReadingPositionMetrics(0.0, total, if (progress.completed) 1.0 else 0.0)
        }
        val chapter = progress.chapterIndex.coerceIn(content.chapters.indices)
        val preceding = content.chapters.take(chapter).sumOf { it.text.length.toLong() }
        val offset = progress.charOffset.coerceIn(0, content.chapters[chapter].text.length)
        val units = if (progress.completed) total else (preceding + offset).toDouble()
        return ReadingPositionMetrics(units, total, (units / total).coerceIn(0.0, 1.0))
    }

    /** Fills cached metrics after opening a book or migrating an old character bookmark. */
    fun initialize(content: BookContent, record: ReadingRecord): ReadingRecord {
        val position = position(content, record.progress)
        return record.copy(
            progressFraction = position.fraction,
            furthestUnits = max(record.furthestUnits, position.positionUnits),
            totalUnits = position.totalUnits,
            estimatedRemainingMillis = remainingMillis(
                content.book.format, position.positionUnits, position.totalUnits,
                record.speedUnits, record.speedReadingMillis,
            ),
        )
    }

    fun withProgress(
        content: BookContent,
        record: ReadingRecord,
        progress: ReadingProgress,
        activeMillisSincePosition: Long,
    ): ReadingRecord {
        val before = position(content, record.progress)
        val after = position(content, progress)
        val advanced = after.positionUnits - before.positionUnits
        val atFrontier = before.positionUnits >= record.furthestUnits - 0.001
        val sample = atFrontier && after.positionUnits > record.furthestUnits &&
            usefulSample(content.book.format, record.progress, progress, advanced, activeMillisSincePosition)
        val units = record.speedUnits + if (sample) advanced else 0.0
        val millis = saturatingAdd(record.speedReadingMillis, if (sample) activeMillisSincePosition else 0L)
        return record.copy(
            progress = progress,
            progressFraction = after.fraction,
            furthestUnits = max(record.furthestUnits, after.positionUnits),
            totalUnits = after.totalUnits,
            speedUnits = units,
            speedReadingMillis = millis,
            estimatedRemainingMillis = remainingMillis(content.book.format, after.positionUnits, after.totalUnits, units, millis),
        )
    }

    private fun usefulSample(
        format: String,
        before: ReadingProgress,
        after: ReadingProgress,
        units: Double,
        millis: Long,
    ): Boolean {
        if (!units.isFinite() || units <= 0.0) return false
        val pdf = format == "PDF"
        val minimum = if (pdf) 5_000L else 3_000L
        val maximum = if (pdf) 30L * 60_000 else 10L * 60_000
        if (millis !in minimum..maximum) return false
        val sequential = if (pdf) after.pageIndex == before.pageIndex + 1 else
            after.chapterIndex == before.chapterIndex && after.pageIndex == before.pageIndex + 1 &&
                after.charOffset > before.charOffset
        if (!sequential) return false
        // Rapid scanning, arbitrary jumps and time left idle on a page must not dominate the estimate.
        return if (pdf) units <= 1.001 else units <= 20_000.0 && units * 60_000 / millis <= 3_000.0
    }

    fun remainingMillis(
        format: String,
        positionUnits: Double,
        totalUnits: Double,
        speedUnits: Double = 0.0,
        speedReadingMillis: Long = 0L,
    ): Long? {
        if (!positionUnits.isFinite() || !totalUnits.isFinite() || totalUnits <= 0.0) return null
        val remaining = (totalUnits - positionUnits).coerceAtLeast(0.0)
        if (remaining == 0.0) return 0L
        val pdf = format == "PDF"
        val defaultMillisPerUnit = if (pdf) 90_000.0 else 60_000.0 / 500.0
        val measured = speedUnits.isFinite() && speedUnits > 0.0 && speedReadingMillis >= 30_000L
        val millisPerUnit = if (measured) {
            (speedReadingMillis / speedUnits).coerceIn(
                if (pdf) 15_000.0 else 30.0,
                if (pdf) 900_000.0 else 1_000.0,
            )
        } else defaultMillisPerUnit
        return (remaining * millisPerUnit).coerceAtMost(Long.MAX_VALUE.toDouble()).roundToLong()
    }

    fun saturatingAdd(first: Long, second: Long): Long {
        val safeFirst = first.coerceAtLeast(0L)
        val safeSecond = second.coerceAtLeast(0L)
        return if (Long.MAX_VALUE - safeFirst < safeSecond) Long.MAX_VALUE else safeFirst + safeSecond
    }
}

/** A monotonic clock: changing wall-clock time or pausing never counts background time twice. */
class ActiveReadingClock {
    var active: Boolean = false
        private set
    private var lastMonotonicMillis: Long? = null

    fun advance(now: Long): Long {
        val previous = lastMonotonicMillis
        lastMonotonicMillis = max(previous ?: now, now)
        return if (active && previous != null && now >= previous) now - previous else 0L
    }

    fun setActive(value: Boolean, now: Long): Long {
        val elapsed = advance(now)
        if (!active && value) lastMonotonicMillis = now
        active = value
        return elapsed
    }
}
