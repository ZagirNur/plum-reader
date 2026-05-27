package com.plum.reader.jobs

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "plum.worker")
data class WorkerProperties(
    /** Whether background workers run in this JVM. Set false in tests. */
    val enabled: Boolean = true,
    /** How often to poll for pending jobs. */
    val pollInterval: Duration = Duration.ofSeconds(2),
    /** How long to hold a row's `locked_until` while processing. */
    val lockTimeout: Duration = Duration.ofMinutes(5),
    /** How often to sweep expired locks back into `pending`. */
    val staleLockSweepInterval: Duration = Duration.ofMinutes(1),
    /** Terminal-fail after this many attempts. */
    val maxAttempts: Int = 5,
)
