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
            rateLimit = ComplyrProperties.RateLimit(authPerMinute = 2, consentPerMinute = 3, publicScanPerMinute = 2),
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
    fun `consent ingestion uses its own generous tier independent of the auth limit`() {
        val chain = mockk<FilterChain>(relaxed = true)

        // Auth tier (2/min) is unaffected by consent traffic and vice-versa: the consent
        // tier allows 3 before the 4th is throttled.
        repeat(3) {
            val response = MockHttpServletResponse()
            filter.doFilter(request("/api/v1/consent"), response, chain)
            assertEquals(200, response.status)
        }

        val limited = MockHttpServletResponse()
        filter.doFilter(request("/api/v1/consent"), limited, chain)
        assertEquals(429, limited.status)
        assertTrue(limited.contentAsString.contains("\"RATE_LIMITED\""), limited.contentAsString)
    }

    @Test
    fun `the public-scan endpoint has its own tier independent of auth and consent`() {
        val chain = mockk<FilterChain>(relaxed = true)

        // PUBLIC_SCAN tier allows 2/min; the 3rd is throttled. Its own bucket, so it neither
        // borrows from nor drains the auth (2/min) or consent (3/min) tiers.
        repeat(2) {
            val response = MockHttpServletResponse()
            filter.doFilter(request("/api/v1/public-scan"), response, chain)
            assertEquals(200, response.status)
        }

        val limited = MockHttpServletResponse()
        filter.doFilter(request("/api/v1/public-scan"), limited, chain)
        assertEquals(429, limited.status)
        assertTrue(limited.contentAsString.contains("\"RATE_LIMITED\""), limited.contentAsString)
    }

    @Test
    fun `the email-gated report write shares the public-scan tier`() {
        val chain = mockk<FilterChain>(relaxed = true)

        // The report POST is throttled under the same PUBLIC_SCAN tier (2/min in this test), matched
        // by its `/report` suffix without the token value; the 3rd is refused.
        repeat(2) {
            val response = MockHttpServletResponse()
            filter.doFilter(request("/api/v1/public-scan/tok123/report"), response, chain)
            assertEquals(200, response.status)
        }

        val limited = MockHttpServletResponse()
        filter.doFilter(request("/api/v1/public-scan/tok123/report"), limited, chain)
        assertEquals(429, limited.status)
        assertTrue(limited.contentAsString.contains("\"RATE_LIMITED\""), limited.contentAsString)
    }

    @Test
    fun `the polled teaser read is not rate limited`() {
        val chain = mockk<FilterChain>(relaxed = true)

        // `/api/v1/public-scan/{token}` is the read path the funnel polls; it must not fall under any
        // tight tier, so repeated reads sail through (token-gating + edge are its controls).
        repeat(5) {
            val response = MockHttpServletResponse()
            filter.doFilter(request("/api/v1/public-scan/tok123"), response, chain)
            assertEquals(200, response.status)
        }

        verify(exactly = 5) { chain.doFilter(any(), any()) }
    }

    @Test
    fun `CORS preflight OPTIONS on a public endpoint is never counted`() {
        val chain = mockk<FilterChain>(relaxed = true)

        repeat(10) {
            val preflight =
                MockHttpServletRequest("OPTIONS", "/api/v1/consent").apply {
                    remoteAddr = "203.0.113.10"
                    requestURI = "/api/v1/consent"
                }
            filter.doFilter(preflight, MockHttpServletResponse(), chain)
        }

        // All 3 real-POST slots remain, proving preflight consumed none of them.
        repeat(3) {
            val response = MockHttpServletResponse()
            filter.doFilter(request("/api/v1/consent"), response, chain)
            assertEquals(200, response.status)
        }
    }

    @Test
    fun `a matrix-parameter suffix cannot slip a public endpoint past tier matching`() {
        val chain = mockk<FilterChain>(relaxed = true)

        // `/api/v1/consent;x=1` must still be treated as the consent path: the tier is
        // resolved from the path with matrix params stripped, so the 3/min cap applies and
        // the 4th request is throttled rather than sailing through unlimited.
        repeat(3) {
            val response = MockHttpServletResponse()
            filter.doFilter(request("/api/v1/consent;jsessionid=abc"), response, chain)
            assertEquals(200, response.status)
        }

        val limited = MockHttpServletResponse()
        filter.doFilter(request("/api/v1/consent;jsessionid=abc"), limited, chain)
        assertEquals(429, limited.status)
    }

    @Test
    fun `a percent-encoded auth path cannot slip past the auth throttle`() {
        val chain = mockk<FilterChain>(relaxed = true)

        // `/api/v1/auth/l%6fgin` (%6f = 'o') decodes to the real login route Spring dispatches on, so
        // the AUTH tier (2/min) must apply — otherwise an attacker sidesteps the login brute-force cap
        // by encoding one character. The 3rd request is throttled.
        repeat(2) {
            val response = MockHttpServletResponse()
            filter.doFilter(request("/api/v1/auth/l%6fgin"), response, chain)
            assertEquals(200, response.status)
        }

        val limited = MockHttpServletResponse()
        filter.doFilter(request("/api/v1/auth/l%6fgin"), limited, chain)
        assertEquals(429, limited.status)
        assertTrue(limited.contentAsString.contains("\"RATE_LIMITED\""), limited.contentAsString)
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
