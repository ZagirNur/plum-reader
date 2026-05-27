package com.plum.reader.storage

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "plum.storage")
data class StorageProperties(
    /** Root directory under which all blobs are stored. */
    val root: String = "./var/storage",
)
