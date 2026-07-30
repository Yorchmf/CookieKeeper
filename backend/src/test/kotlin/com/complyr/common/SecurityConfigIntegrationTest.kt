package com.complyr.common

import com.complyr.TestcontainersConfiguration
import com.complyr.auth.TokenService
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class SecurityConfigIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var tokenService: TokenService

    @Test
    fun `protected endpoint without token returns 401 envelope`() {
        mockMvc
            .perform(get("/api/v1/protected-probe"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.data").doesNotExist())
            .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
    }

    @Test
    fun `tampered bearer token returns 401 envelope`() {
        val tampered = tokenService.issueAccessToken(UUID.randomUUID(), emailVerified = true).dropLast(4) + "AAAA"

        mockMvc
            .perform(get("/api/v1/protected-probe").header("Authorization", "Bearer $tampered"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
    }

    @Test
    fun `valid access token in cmplyr_at cookie authenticates the request`() {
        val token = tokenService.issueAccessToken(UUID.randomUUID(), emailVerified = true)

        // Authenticated but nonexistent route: passes security (404), never 401.
        mockMvc
            .perform(get("/api/v1/protected-probe").cookie(Cookie(AuthCookies.ACCESS_TOKEN, token)))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `valid access token in Authorization header authenticates the request`() {
        val token = tokenService.issueAccessToken(UUID.randomUUID(), emailVerified = true)

        mockMvc
            .perform(get("/api/v1/protected-probe").header("Authorization", "Bearer $token"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `actuator health is public`() {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk)
    }

    @Test
    fun `widget config and consent ingestion stay public`() {
        // No controllers yet (W3) — public matchers must yield 404, not 401.
        mockMvc.perform(get("/api/v1/widget-config/pk_test")).andExpect(status().isNotFound)
        mockMvc.perform(post("/api/v1/consent")).andExpect(status().isNotFound)
    }
}
