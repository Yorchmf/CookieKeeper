package com.complyr.common

import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthenticatedRateLimitFilterTest {
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
            rateLimit = ComplyrProperties.RateLimit(authBillingPerMinute = 2, authGeneralPerMinute = 3),
            appBaseUrl = "http://localhost:3000",
            cdnBaseUrl = "http://localhost:8081",
            mailFrom = "no-reply@complyr.eu",
        )

    private val filter = AuthenticatedRateLimitFilter(properties, JsonMapper.builder().build())

    @AfterEach
    fun clearContext() {
        SecurityContextHolder.clearContext()
    }

    private fun authenticate(userId: String) {
        // Two-arg constructor (with authorities) mirrors what JwtAuthenticationProvider produces at
        // runtime: it sets isAuthenticated=true. The one-arg form leaves it false.
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt(userId), emptyList())
    }

    private fun jwt(subject: String): Jwt =
        Jwt
            .withTokenValue("token")
            .header("alg", "none")
            .subject(subject)
            .claim("sub", subject)
            .build()

    private fun request(
        uri: String,
        method: String = "POST",
    ): MockHttpServletRequest =
        MockHttpServletRequest(method, uri).apply {
            requestURI = uri
        }

    @Test
    fun `billing calls over the per-user limit get a 429 envelope`() {
        val chain = mockk<FilterChain>(relaxed = true)
        authenticate("11111111-1111-1111-1111-111111111111")

        repeat(2) {
            val response = MockHttpServletResponse()
            filter.doFilter(request("/api/v1/billing/checkout-session"), response, chain)
            assertEquals(200, response.status)
        }

        val limited = MockHttpServletResponse()
        filter.doFilter(request("/api/v1/billing/checkout-session"), limited, chain)

        assertEquals(429, limited.status)
        assertTrue(limited.contentAsString.contains("\"RATE_LIMITED\""), limited.contentAsString)
        assertTrue(limited.contentAsString.contains("\"success\":false"), limited.contentAsString)
        verify(exactly = 2) { chain.doFilter(any(), any()) }
    }

    @Test
    fun `limits are tracked per authenticated user`() {
        val chain = mockk<FilterChain>(relaxed = true)

        authenticate("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        repeat(2) { filter.doFilter(request("/api/v1/billing/portal-session"), MockHttpServletResponse(), chain) }

        // A different user has their own bucket and is unaffected by the first user's spend.
        authenticate("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        val otherUser = MockHttpServletResponse()
        filter.doFilter(request("/api/v1/billing/portal-session"), otherUser, chain)

        assertEquals(200, otherUser.status)
    }

    @Test
    fun `the billing tier and the general tier are independent`() {
        val chain = mockk<FilterChain>(relaxed = true)
        authenticate("cccccccc-cccc-cccc-cccc-cccccccccccc")

        // Drain the billing tier (2/min); the 3rd billing call is refused.
        repeat(2) { filter.doFilter(request("/api/v1/billing/checkout-session"), MockHttpServletResponse(), chain) }
        val billingLimited = MockHttpServletResponse()
        filter.doFilter(request("/api/v1/billing/checkout-session"), billingLimited, chain)
        assertEquals(429, billingLimited.status)

        // The general tier (3/min) still has its full allowance — it did not borrow from billing.
        repeat(3) {
            val response = MockHttpServletResponse()
            filter.doFilter(request("/api/v1/sites"), response, chain)
            assertEquals(200, response.status)
        }
    }

    @Test
    fun `general authed endpoints are throttled on their own tier`() {
        val chain = mockk<FilterChain>(relaxed = true)
        authenticate("dddddddd-dddd-dddd-dddd-dddddddddddd")

        // POST /api/v1/sites (each enqueues a scan) is capped on the GENERAL tier (3/min here).
        repeat(3) {
            val response = MockHttpServletResponse()
            filter.doFilter(request("/api/v1/sites"), response, chain)
            assertEquals(200, response.status)
        }

        val limited = MockHttpServletResponse()
        filter.doFilter(request("/api/v1/sites"), limited, chain)
        assertEquals(429, limited.status)
        assertTrue(limited.contentAsString.contains("\"RATE_LIMITED\""), limited.contentAsString)
    }

    @Test
    fun `unauthenticated requests are passed through untouched`() {
        val chain = mockk<FilterChain>(relaxed = true)

        // No SecurityContext principal: the filter must not throttle — well past the billing cap,
        // every request still reaches the chain (the security layer, not this filter, gates it).
        repeat(5) {
            val response = MockHttpServletResponse()
            filter.doFilter(request("/api/v1/billing/checkout-session"), response, chain)
            assertEquals(200, response.status)
        }

        verify(exactly = 5) { chain.doFilter(any(), any()) }
    }

    @Test
    fun `the stripe webhook is never throttled even with a principal present`() {
        val chain = mockk<FilterChain>(relaxed = true)
        // Even if a JWT is somehow present, the unauthenticated webhook is excluded from tier matching.
        authenticate("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")

        repeat(5) {
            val response = MockHttpServletResponse()
            filter.doFilter(request("/api/v1/billing/webhook"), response, chain)
            assertEquals(200, response.status)
        }

        verify(exactly = 5) { chain.doFilter(any(), any()) }
    }

    @Test
    fun `non-api paths are not throttled`() {
        val chain = mockk<FilterChain>(relaxed = true)
        authenticate("ffffffff-ffff-ffff-ffff-ffffffffffff")

        repeat(5) {
            val response = MockHttpServletResponse()
            filter.doFilter(request("/actuator/health", method = "GET"), response, chain)
            assertEquals(200, response.status)
        }

        verify(exactly = 5) { chain.doFilter(any(), any()) }
    }

    @Test
    fun `CORS preflight OPTIONS is never counted`() {
        val chain = mockk<FilterChain>(relaxed = true)
        authenticate("12121212-1212-1212-1212-121212121212")

        repeat(10) {
            filter.doFilter(request("/api/v1/billing/checkout-session", method = "OPTIONS"), MockHttpServletResponse(), chain)
        }

        // All 2 real billing slots remain, proving preflight consumed none of them.
        repeat(2) {
            val response = MockHttpServletResponse()
            filter.doFilter(request("/api/v1/billing/checkout-session"), response, chain)
            assertEquals(200, response.status)
        }
        val limited = MockHttpServletResponse()
        filter.doFilter(request("/api/v1/billing/checkout-session"), limited, chain)
        assertEquals(429, limited.status)
    }

    @Test
    fun `a matrix-parameter suffix cannot slip a billing endpoint past tier matching`() {
        val chain = mockk<FilterChain>(relaxed = true)
        authenticate("34343434-3434-3434-3434-343434343434")

        // `/api/v1/billing/portal-session;x=1` must still match the BILLING tier (2/min), so the
        // 3rd request is throttled rather than sliding into the more generous general tier.
        repeat(2) {
            val response = MockHttpServletResponse()
            filter.doFilter(request("/api/v1/billing/portal-session;jsessionid=abc"), response, chain)
            assertEquals(200, response.status)
        }

        val limited = MockHttpServletResponse()
        filter.doFilter(request("/api/v1/billing/portal-session;jsessionid=abc"), limited, chain)
        assertEquals(429, limited.status)
    }

    @Test
    fun `a percent-encoded billing path stays on the tight billing tier`() {
        val chain = mockk<FilterChain>(relaxed = true)
        authenticate("56565656-5656-5656-5656-565656565656")

        // `/api/v1/%62illing/...` (%62 = 'b') decodes to the real billing route Spring dispatches on,
        // so it must be classified BILLING (2/min here), not downgraded to the generous GENERAL tier.
        // The 3rd request is throttled.
        repeat(2) {
            val response = MockHttpServletResponse()
            filter.doFilter(request("/api/v1/%62illing/checkout-session"), response, chain)
            assertEquals(200, response.status)
        }

        val limited = MockHttpServletResponse()
        filter.doFilter(request("/api/v1/%62illing/checkout-session"), limited, chain)
        assertEquals(429, limited.status)
        assertTrue(limited.contentAsString.contains("\"RATE_LIMITED\""), limited.contentAsString)
    }

    @Test
    fun `a percent-encoded api prefix is still throttled, not skipped`() {
        val chain = mockk<FilterChain>(relaxed = true)
        authenticate("78787878-7878-7878-7878-787878787878")

        // `/%61pi/v1/billing/...` (%61 = 'a') decodes to `/api/v1/billing/...`. Matching on the raw
        // URI would classify it as no tier and skip the filter entirely (full bypass); decoding keeps
        // it on the BILLING tier so the 3rd request is refused.
        repeat(2) {
            val response = MockHttpServletResponse()
            filter.doFilter(request("/%61pi/v1/billing/checkout-session"), response, chain)
            assertEquals(200, response.status)
        }

        val limited = MockHttpServletResponse()
        filter.doFilter(request("/%61pi/v1/billing/checkout-session"), limited, chain)
        assertEquals(429, limited.status)
    }

    @Test
    fun `a blank JWT subject is not keyed and passes through`() {
        val chain = mockk<FilterChain>(relaxed = true)
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt(""), emptyList())

        // A blank subject must not become a shared empty bucket key — the filter passes it through
        // (the security layer, not this filter, owns malformed principals). Well past the billing cap,
        // every request still reaches the chain.
        repeat(5) {
            val response = MockHttpServletResponse()
            filter.doFilter(request("/api/v1/billing/checkout-session"), response, chain)
            assertEquals(200, response.status)
        }

        verify(exactly = 5) { chain.doFilter(any(), any()) }
    }

    @Test
    fun `an anonymous principal passes through untouched`() {
        val chain = mockk<FilterChain>(relaxed = true)
        // AnonymousAuthenticationToken is isAuthenticated=true but its principal is the String
        // "anonymousUser", not a Jwt — the safe cast yields null, so it is never throttled.
        SecurityContextHolder.getContext().authentication =
            AnonymousAuthenticationToken(
                "key",
                "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"),
            )

        repeat(5) {
            val response = MockHttpServletResponse()
            filter.doFilter(request("/api/v1/billing/checkout-session"), response, chain)
            assertEquals(200, response.status)
        }

        verify(exactly = 5) { chain.doFilter(any(), any()) }
    }

    @Test
    fun `an unauthenticated JWT token passes through untouched`() {
        val chain = mockk<FilterChain>(relaxed = true)
        // One-arg JwtAuthenticationToken leaves isAuthenticated=false; the guard must skip throttling.
        SecurityContextHolder.getContext().authentication =
            JwtAuthenticationToken(jwt("90909090-9090-9090-9090-909090909090"))

        repeat(5) {
            val response = MockHttpServletResponse()
            filter.doFilter(request("/api/v1/billing/checkout-session"), response, chain)
            assertEquals(200, response.status)
        }

        verify(exactly = 5) { chain.doFilter(any(), any()) }
    }
}
