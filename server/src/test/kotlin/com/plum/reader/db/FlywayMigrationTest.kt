package com.plum.reader.db

import com.plum.reader.testsupport.AbstractIntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlywayMigrationTest : AbstractIntegrationTest() {

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `V1 creates the contract tables`() {
        val tables = jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
            String::class.java,
        ).toSet()

        val v1Tables = setOf(
            "users", "books", "user_books",
            "pages", "assets", "processing_jobs",
        )
        assertTrue(
            tables.containsAll(v1Tables),
            "Missing V1 tables: ${v1Tables - tables}; got: $tables",
        )
    }

    @Test
    fun `processing_jobs has both partial indexes`() {
        val indexes = jdbcTemplate.queryForList(
            "SELECT indexname FROM pg_indexes WHERE tablename = 'processing_jobs'",
            String::class.java,
        ).toSet()

        assertTrue("idx_processing_jobs_queue" in indexes)
        assertTrue("idx_processing_jobs_locked_until" in indexes)
    }

    @Test
    fun `books status CHECK rejects unknown value`() {
        val ex = runCatching {
            jdbcTemplate.update(
                "INSERT INTO books(storage_key, size_bytes, sha256, status) VALUES (?, ?, ?, ?)",
                "k", 1L, "0".repeat(64), "qwerty",
            )
        }.exceptionOrNull()
        assertTrue(ex != null, "CHECK constraint must reject status='qwerty'")
    }

    @Test
    fun `V1 is recorded in flyway_schema_history as success`() {
        val row = jdbcTemplate.queryForMap(
            "SELECT version, success FROM flyway_schema_history WHERE version = '1'"
        )
        assertEquals("1", row["version"])
        assertEquals(true, row["success"])
    }
}
