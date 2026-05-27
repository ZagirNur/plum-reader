package com.plum.reader.storage

import java.io.InputStream
import java.nio.file.Path

/**
 * Append-only blob storage abstraction. The book/asset rows in the database
 * hold an opaque [storageKey]; the storage module resolves it to actual bytes.
 *
 * Implementations must be idempotent: writing the same content (same sha256)
 * twice produces the same key and leaves the existing blob intact.
 */
interface StorageService {

    /**
     * Persist [bytes] under a key derived from [sha256] (lowercase hex).
     * Returns the resulting [storageKey] (relative path within the store).
     */
    fun put(bytes: ByteArray, sha256: String): String

    /**
     * Stream [size] bytes from [input] into a blob keyed by [sha256].
     * Returns the resulting [storageKey].
     */
    fun put(input: InputStream, size: Long, sha256: String): String

    /** Resolve [storageKey] back to a filesystem [Path] (for local impl). */
    fun resolve(storageKey: String): Path

    /** Open [storageKey] for reading. */
    fun openRead(storageKey: String): InputStream

    /** Whether a blob with the given key currently exists. */
    fun exists(storageKey: String): Boolean
}
