package com.plum.reader.auth

import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class UserRepository(private val jdbc: JdbcTemplate) {

    fun findByEmail(email: String): User? = try {
        jdbc.queryForObject(
            "SELECT id, email, password_hash, name, created_at FROM users WHERE email = ?",
            { rs, _ -> mapRow(rs) },
            email,
        )
    } catch (_: EmptyResultDataAccessException) {
        null
    }

    fun findById(id: Long): User? = try {
        jdbc.queryForObject(
            "SELECT id, email, password_hash, name, created_at FROM users WHERE id = ?",
            { rs, _ -> mapRow(rs) },
            id,
        )
    } catch (_: EmptyResultDataAccessException) {
        null
    }

    fun create(email: String, passwordHash: String, name: String?): User {
        val id = jdbc.queryForObject(
            "INSERT INTO users(email, password_hash, name) VALUES (?, ?, ?) RETURNING id",
            Long::class.java,
            email, passwordHash, name,
        ) ?: error("INSERT ... RETURNING id returned null")
        return findById(id) ?: error("user $id disappeared right after insert")
    }

    private fun mapRow(rs: ResultSet): User = User(
        id = rs.getLong("id"),
        email = rs.getString("email"),
        passwordHash = rs.getString("password_hash"),
        name = rs.getString("name"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
    )
}
