package com.complyr.common

import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RateLimitFilterTest {
    private val properties =
        ComplyrProperties(
            auth =
                ComplyrProperties.Auth(
                    jwtSecret = "unit-test-jwt-secret-0123456789-abcdefghijklmnop",
                    accessTokenTtl = Duration.ofMinutes(15),
                    refreshTokenTtl = Duration.ofDays(30),
                    verificationTokenTtl = Duration.ofHours(24),
                    resetTokenTtl = Duration.ofHours(1),
                ),
            rateLimit = ComplyrProperties.RateLimit(authPerMinute = 2),
            appBaseUrl = "http://localhost:3000",
            cdnBaseUrl = "http://localhost:8081",
            mailFrom = "no-reply@complyr.eu",
        )

    private val filter = RateLimitFilter(properties, JsonMapper.builder().build())

    private fun request(
        uri: String,
        ip: String = "203.0.113.10",
    ): MockHttpServletRequest =
        MockHttpServletRequest("POST", uri).apply {
            remoteAddr = ip
            requestURI = uri
        }

    @Test
    fun `requests over the per-IP limit get a 429 envelope`() {
        val chain = mockk<FilterChain>(relaxed = true)

        repeat(2) {
            val response = MockHttpServletResponse()
            filter.doFilter(request("/api/v1/auth/login"), response, chain)
            assertEquals(200, response.status)
        }

        val limited = MockHttpServletResponse()
        filter.doFilter(request("/api/v1/auth/login"), limited, chain)

        assertEquals(429, limited.status)
        assertTrue(limited.contentAsString.contains("\"RATE_LIMITED\""), limited.contentAsString)
        assertTrue(limited.contentAsString.contains("\"success\":false"), limited.contentAsString)
        verify(exactly = 2) { chain.doFilter(any(), any()) }
    }

    @Test
    fun `limits are tracked per client IP`() {
        val chain = mockk<FilterChain>(relaxed = true)
        repeat(2) { filter.doFilter(request("/api/v1/auth/login", ip = "203.0.113.1"), MockHttpServletResponse(), chain) }

        val otherClient = MockHttpServletResponse()
        filter.doFilter(request("/api/v1/auth/login", ip = "203.0.113.2"), otherClient, chain)

        assertEquals(200, otherClient.status)
    }

    @Test
    fun `non-auth endpoints are not rate limited`() {
        val chain = mockk<FilterChain>(relaxed = true)

        repeat(5) {
            val response = MockHttpServletResponse()
            filter.doFilter(request("/api/v1/sites"), response, chain)
            assertEquals(200, response.status)
        }

        verify(exactly = 5) { chain.doFilter(any(), any()) }
    }

    @Test
    fun `authenticated session endpoints me and logout are not rate limited`() {
        val chain = mockk<FilterChain>(relaxed = true)

        repeat(5) {
            filter.doFilter(request("/api/v1/auth/me"), MockHttpServletResponse(), chain)
            filter.doFilter(request("/api/v1/auth/logout"), MockHttpServletResponse(), chain)
        }

        verify(exactly = 10) { chain.doFilter(any(), any()) }
    }
}
