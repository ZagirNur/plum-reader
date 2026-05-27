package com.plum.reader.health

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.Test

@WebMvcTest(HealthController::class)
class HealthControllerTests(@Autowired val mockMvc: MockMvc) {

    @Test
    fun `health endpoint returns ok`() {
        mockMvc.perform(get("/api/health"))
            .andExpect(status().isOk)
            .andExpect(content().json("""{"status":"ok"}"""))
    }
}
