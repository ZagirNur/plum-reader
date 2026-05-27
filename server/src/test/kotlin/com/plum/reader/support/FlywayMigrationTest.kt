package com.plum.reader.support

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlywayMigrationTest : AbstractIntegrationTest() {

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `V1 creates expected tables`() {
        val tables = jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
            String::class.java,
        ).toSet()

        val expected = setOf(
            "assets", "books", "flyway_schema_history",
            "pages", "processing_jobs", "user_books", "users",
        )
        assertEquals(expected, tables)
    }

    @Test
    fun `processing_jobs queue partial index exists`() {
        val exists = jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_processing_jobs_queue')",
            Boolean::class.java,
        )
        assertTrue(exists == true)
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
