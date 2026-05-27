package com.plum.reader.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.plum.reader.testsupport.AbstractIntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.BeforeTest
import kotlin.test.Test

@AutoConfigureMockMvc
class AuthFlowIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @BeforeTest
    fun cleanUsers() {
        jdbcTemplate.update("DELETE FROM users")
    }

    @Test
    fun `register then login then access me`() {
        val email = "alice@example.com"
        val password = "supersecret1"

        val registerJson = mockMvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"$password","name":"Alice"}""")
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.token").isNotEmpty)
            .andExpect(jsonPath("$.user.email").value(email))
            .andExpect(jsonPath("$.user.name").value("Alice"))
            .andReturn().response.contentAsString

        val registerToken = objectMapper.readTree(registerJson).get("token").asText()

        // /me with the register-token
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer $registerToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.email").value(email))
            .andExpect(jsonPath("$.name").value("Alice"))

        // login issues a fresh token that also works
        val loginJson = mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"$password"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.token").isNotEmpty)
            .andReturn().response.contentAsString
        val loginToken = objectMapper.readTree(loginJson).get("token").asText()

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer $loginToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.email").value(email))
    }

    @Test
    fun `me without token returns 401`() {
        mockMvc.perform(get("/api/v1/me"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `me with garbage token returns 401`() {
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer not-a-jwt"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `register with duplicate email returns 409`() {
        val body = """{"email":"bob@example.com","password":"supersecret1"}"""
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated)
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("email_already_taken"))
    }

    @Test
    fun `register with short password returns 400`() {
        mockMvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"c@example.com","password":"short"}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("validation_failed"))
            .andExpect(jsonPath("$.details.password").exists())
    }

    @Test
    fun `login with wrong password returns 401`() {
        mockMvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"d@example.com","password":"correct-password-123"}""")
        ).andExpect(status().isCreated)

        mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"d@example.com","password":"wrong-pass-1234"}""")
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("invalid_credentials"))
    }

    @Test
    fun `login with unknown email returns 401 invalid_credentials (no enumeration)`() {
        mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"nobody@example.com","password":"any-password-123"}""")
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("invalid_credentials"))
    }
}
