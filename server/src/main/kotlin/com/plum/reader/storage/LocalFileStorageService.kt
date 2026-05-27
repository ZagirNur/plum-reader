package com.plum.reader.storage

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.exists

/**
 * On-disk blob storage. Key layout: `<aa>/<bb>/<sha256>` (2+2 hex sharding to
 * keep directory fan-out reasonable). Same sha256 → same key → idempotent put.
 */
@Service
class LocalFileStorageService(props: StorageProperties) : StorageService {
    private val log = LoggerFactory.getLogger(javaClass)
    private val root: Path = Path.of(props.root).toAbsolutePath().normalize()

    init {
        Files.createDirectories(root)
        log.info("storage.local root={}", root)
    }

    override fun put(bytes: ByteArray, sha256: String): String {
        val key = keyFor(sha256)
        val target = root.resolve(key)
        if (target.exists()) return key
        Files.createDirectories(target.parent)
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp-" + UUID.randomUUID())
        Files.write(tmp, bytes)
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return key
    }

    override fun put(input: InputStream, size: Long, sha256: String): String {
        val key = keyFor(sha256)
        val target = root.resolve(key)
        if (target.exists()) return key
        Files.createDirectories(target.parent)
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp-" + UUID.randomUUID())
        Files.copy(input, tmp, StandardCopyOption.REPLACE_EXISTING)
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return key
    }

    override fun resolve(storageKey: String): Path {
        val resolved = root.resolve(storageKey).normalize()
        // path-traversal guard: reject anything that escapes root.
        require(resolved.startsWith(root)) { "storage key escapes root: $storageKey" }
        return resolved
    }

    override fun openRead(storageKey: String): InputStream =
        Files.newInputStream(resolve(storageKey))

    override fun exists(storageKey: String): Boolean = resolve(storageKey).exists()

    private fun keyFor(sha256: String): String {
        require(sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' }) {
            "sha256 must be 64 lowercase hex chars"
        }
        return "${sha256.substring(0, 2)}/${sha256.substring(2, 4)}/$sha256"
    }
}

@Configuration
@EnableConfigurationProperties(StorageProperties::class)
class StorageConfig
