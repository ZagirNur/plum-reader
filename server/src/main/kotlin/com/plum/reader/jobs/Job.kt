package com.plum.reader.jobs

import java.time.Instant

data class Job(
    val id: Long,
    val kind: String,
    val bookId: Long,
    val state: String,
    val attempts: Int,
    val error: String?,
    val lockedBy: String?,
    val lockedUntil: Instant?,
    val scheduledAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class JobState(val value: String) {
    PENDING("pending"),
    RUNNING("running"),
    DONE("done"),
    FAILED("failed"),
    CANCELLED("cancelled"),
}
