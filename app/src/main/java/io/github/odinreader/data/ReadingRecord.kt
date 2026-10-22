package io.github.odinreader.data

/** Durable, per-book reading position and statistics. All durations use milliseconds. */
data class ReadingRecord(
    val bookId: String,
    val progress: ReadingProgress = ReadingProgress(),
    val progressFraction: Double = 0.0,
    val totalReadingMillis: Long = 0L,
    val estimatedRemainingMillis: Long? = null,
    val lastReadAt: Long = 0L,
    val speedUnits: Double = 0.0,
    val speedReadingMillis: Long = 0L,
    val furthestUnits: Double = 0.0,
    val totalUnits: Double = 0.0,
)

/** An active-reading session is updated under a stable ID as its duration grows. */
data class ReadingSession(
    val id: String,
    val bookId: String,
    val startedAt: Long,
    val endedAt: Long,
    val durationMillis: Long,
)

/** Used by backups/tests to restore one book without touching any other history. */
data class ReadingHistorySnapshot(
    val record: ReadingRecord?,
    val sessions: List<ReadingSession>,
)
