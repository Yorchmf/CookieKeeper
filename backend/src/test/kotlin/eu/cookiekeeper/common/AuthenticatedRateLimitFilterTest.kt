package eu.cookiekeeper.common

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
        CookieKeeperProperties(
            auth =
                CookieKeeperProperties.Auth(
                    jwtSecret = "unit-test-jwt-secret-0123456789-abcdefghijklmnop",
                    accessTokenTtl = Duration.ofMinutes(15),
                    refreshTokenTtl = Duration.ofDays(30),
                    verificationTokenTtl = Duration.ofHours(24),
                    resetTokenTtl = Duration.ofHours(1),
                ),
            rateLimit =
                CookieKeeperProperties.RateLimit(
                    authBillingPerMinute = 2,
                    authVerifyPerMinute = 1,
                    authExportPerMinute = 2,
                    authContactPerMinute = 2,
                    authPolicyPerMinute = 2,
                    authGeneralPerMinute = 3,
                ),
            appBaseUrl = "http://localhost:3000",
            cdnBaseUrl = "http://localhost:8081",
            mailFrom = "support@cookiekeeper.eu",
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
    fun `domain verification is throttled on its own tightest tier`() {
        val chain = mockk<FilterChain>(relaxed = true)
        authenticate("13131313-1313-1313-1313-131313131313")
        val siteId = "22222222-2222-2222-2222-222222222222"

        // Verify (1/min here) is the only authed endpoint that dials a customer-supplied host on a
        // request thread, so it must not fall through to the generous GENERAL tier.
        val allowed = MockHttpServletResponse()
        filter.doFilter(request("/api/v1/sites/$siteId/verify"), allowed, chain)
        assertEquals(200, allowed.status)

        val limited = MockHttpServletResponse()
        filter.doFilter(request("/api/v1/sites/$siteId/verify"), limited, chain)
        assertEquals(429, limited.status)
        assertTrue(limited.contentAsString.contains("\"RATE_LIMITED\""), limited.contentAsString)
    }

    @Test
    fun `the evidence-pack export is throttled on its own tight export tier`() {
        val chain = mockk<FilterChain>(relaxed = true)
        authenticate("15151515-1515-1515-1515-151515151515")
        val siteId = "44444444-4444-4444-4444-444444444444"

        // The pack streams the full 30-day consent log + every policy language per request, so it must
        // not fall through to the generous GENERAL tier (3/min here) — it is capped at 2/min.
        repeat(2) {
            val response = MockHttpServletResponse()
            filter.doFilter(request("/api/v1/sites/$siteId/analytics/evidence-pack.zip", method = "GET"), response, chain)
            assertEquals(200, response.status)
        }

        val limited = MockHttpServletResponse()
        filter.doFilter(request("/api/v1/sites/$siteId/analytics/evidence-pack.zip", method = "GET"), limited, chain)
        assertEquals(429, limited.status)
        assertTrue(limited.contentAsString.contains("\"RATE_LIMITED\""), limited.contentAsString)
    }

    @Test
    fun `the csv export shares the export tier with the evidence pack`() {
        val chain = mockk<FilterChain>(relaxed = true)
        authenticate("16161616-1616-1616-1616-161616161616")
        val siteId = "55555555-5555-5555-5555-555555555555"

        // Both bulk-export downloads key onto the same EXPORT tier (2/min here): one CSV call plus one
        // pack call exhausts the budget, so the third export of either kind is refused.
        val csv = MockHttpServletResponse()
        filter.doFilter(request("/api/v1/sites/$siteId/analytics/export.csv", method = "GET"), csv, chain)
        assertEquals(200, csv.status)

        val pack = MockHttpServletResponse()
        filter.doFilter(request("/api/v1/sites/$siteId/analytics/evidence-pack.zip", method = "GET"), pack, chain)
        assertEquals(200, pack.status)

        val limited = MockHttpServletResponse()
        filter.doFilter(request("/api/v1/sites/$siteId/analytics/export.csv", method = "GET"), limited, chain)
        assertEquals(429, limited.status)
        assertTrue(limited.contentAsString.contains("\"RATE_LIMITED\""), limited.contentAsString)
    }

    @Test
    fun `the export tier and the general tier are independent`() {
        val chain = mockk<FilterChain>(relaxed = true)
        authenticate("17171717-1717-1717-1717-171717171717")
        val siteId = "66666666-6666-6666-6666-666666666666"

        // Drain the export tier (2/min); the 3rd export is refused.
        repeat(2) {
            filter.doFilter(
                request("/api/v1/sites/$siteId/analytics/evidence-pack.zip", method = "GET"),
                MockHttpServletResponse(),
                chain,
            )
        }
        val exportLimited = MockHttpServletResponse()
        filter.doFilter(request("/api/v1/sites/$siteId/analytics/evidence-pack.zip", method = "GET"), exportLimited, chain)
        assertEquals(429, exportLimited.status)

        // The general tier (3/min) still has its full allowance — it did not borrow from export.
        repeat(3) {
            val response = MockHttpServletResponse()
            filter.doFilter(request("/api/v1/sites/$siteId/analytics", method = "GET"), response, chain)
            assertEquals(200, response.status)
        }
    }

    @Test
    fun `a matrix-parameter suffix cannot slip an export endpoint onto the general tier`() {
        val chain = mockk<FilterChain>(relaxed = true)
        authenticate("19191919-1919-1919-1919-191919191919")
        val siteId = "88888888-8888-8888-8888-888888888888"

        // The whole threat model is a routable path desyncing from the matcher. Matrix params are
        // stripped (removeSemicolonContent) before suffix matching, so `.../evidence-pack.zip;x=1`
        // must still classify EXPORT (2/min here) rather than sliding onto the generous GENERAL tier.
        repeat(2) {
            val response = MockHttpServletResponse()
            filter.doFilter(request("/api/v1/sites/$siteId/analytics/evidence-pack.zip;jsessionid=abc", method = "GET"), response, chain)
            assertEquals(200, response.status)
        }

        val limited = MockHttpServletResponse()
        filter.doFilter(request("/api/v1/sites/$siteId/analytics/evidence-pack.zip;jsessionid=abc", method = "GET"), limited, chain)
        assertEquals(429, limited.status)
    }

    @Test
    fun `the analytics summary read stays on the general tier`() {
        val chain = mockk<FilterChain>(relaxed = true)
        authenticate("18181818-1818-1818-1818-181818181818")
        val siteId = "77777777-7777-7777-7777-777777777777"

        // A sibling under the same `/analytics` path that is NOT an export suffix must stay on the
        // generous GENERAL tier (3/min) — the dashboard reads it on every range change.
        repeat(3) {
            val response = MockHttpServletResponse()
            filter.doFilter(request("/api/v1/sites/$siteId/analytics", method = "GET"), response, chain)
            assertEquals(200, response.status)
        }
        val limited = MockHttpServletResponse()
        filter.doFilter(request("/api/v1/sites/$siteId/analytics", method = "GET"), limited, chain)
        assertEquals(429, limited.status)
    }

    @Test
    fun `the scan list a site page polls every 3s stays on the general tier`() {
        val chain = mockk<FilterChain>(relaxed = true)
        authenticate("14141414-1414-1414-1414-141414141414")
        val siteId = "33333333-3333-3333-3333-333333333333"

        // Tier matching is method- and suffix-based, so a sibling sub-resource under the same
        // `/api/v1/sites/{id}/` prefix must not be dragged onto the 1/min verify tier — the open site
        // page polls this every 3 seconds and would be throttled out of the UI within one minute.
        filter.doFilter(request("/api/v1/sites/$siteId/verify"), MockHttpServletResponse(), chain)
        repeat(3) {
            val response = MockHttpServletResponse()
            filter.doFilter(request("/api/v1/sites/$siteId/scans", method = "GET"), response, chain)
            assertEquals(200, response.status)
        }
    }

    @Test
    fun `the support contact form is throttled on its own tight contact tier`() {
        val chain = mockk<FilterChain>(relaxed = true)
        authenticate("21212121-2121-2121-2121-212121212121")

        // Each accepted call sends an email to our support inbox, so it must not fall through to the
        // generous GENERAL tier (3/min here) — it is capped at 2/min.
        repeat(2) {
            val response = MockHttpServletResponse()
            filter.doFilter(request("/api/v1/support/contact"), response, chain)
            assertEquals(200, response.status)
        }

        val limited = MockHttpServletResponse()
        filter.doFilter(request("/api/v1/support/contact"), limited, chain)
        assertEquals(429, limited.status)
        assertTrue(limited.contentAsString.contains("\"RATE_LIMITED\""), limited.contentAsString)
    }

    @Test
    fun `the contact tier and the general tier are independent`() {
        val chain = mockk<FilterChain>(relaxed = true)
        authenticate("23232323-2323-2323-2323-232323232323")

        // Drain the contact tier (2/min); the 3rd contact call is refused.
        repeat(2) { filter.doFilter(request("/api/v1/support/contact"), MockHttpServletResponse(), chain) }
        val contactLimited = MockHttpServletResponse()
        filter.doFilter(request("/api/v1/support/contact"), contactLimited, chain)
        assertEquals(429, contactLimited.status)

        // The general tier (3/min) still has its full allowance — it did not borrow from contact.
        repeat(3) {
            val response = MockHttpServletResponse()
            filter.doFilter(request("/api/v1/sites"), response, chain)
            assertEquals(200, response.status)
        }
    }

    @Test
    fun `policy generation is throttled on its own tight policy tier`() {
        val chain = mockk<FilterChain>(relaxed = true)
        authenticate("24242424-2424-2424-2424-242424242424")
        val siteId = "99999999-9999-9999-9999-999999999999"

        // POST /policy renders every language and mints a new versioned document behind a per-site
        // advisory lock, so it must not fall through to the generous GENERAL tier (3/min here) — it is
        // capped at 2/min. The 3rd generate is refused.
        repeat(2) {
            val response = MockHttpServletResponse()
            filter.doFilter(request("/api/v1/sites/$siteId/policy"), response, chain)
            assertEquals(200, response.status)
        }

        val limited = MockHttpServletResponse()
        filter.doFilter(request("/api/v1/sites/$siteId/policy"), limited, chain)
        assertEquals(429, limited.status)
        assertTrue(limited.contentAsString.contains("\"RATE_LIMITED\""), limited.contentAsString)
    }

    @Test
    fun `the current-policy GET read stays on the general tier`() {
        val chain = mockk<FilterChain>(relaxed = true)
        authenticate("25252525-2525-2525-2525-252525252525")
        val siteId = "abababab-abab-abab-abab-abababababab"

        // The policy tier is uniquely method-scoped: the *same* path serves both the heavy POST generate
        // (2/min) and the cheap GET current-policy read the policy page hits on every view. The read must
        // stay on the generous GENERAL tier (3/min) — three GETs all pass, where the tight policy cap
        // (2/min) would have refused the third.
        repeat(3) {
            val response = MockHttpServletResponse()
            filter.doFilter(request("/api/v1/sites/$siteId/policy", method = "GET"), response, chain)
            assertEquals(200, response.status)
        }
        val limited = MockHttpServletResponse()
        filter.doFilter(request("/api/v1/sites/$siteId/policy", method = "GET"), limited, chain)
        assertEquals(429, limited.status)
    }

    @Test
    fun `the policy tier and the general tier are independent`() {
        val chain = mockk<FilterChain>(relaxed = true)
        authenticate("26262626-2626-2626-2626-262626262626")
        val siteId = "cdcdcdcd-cdcd-cdcd-cdcd-cdcdcdcdcdcd"

        // Drain the policy tier (2/min); the 3rd generate is refused.
        repeat(2) { filter.doFilter(request("/api/v1/sites/$siteId/policy"), MockHttpServletResponse(), chain) }
        val policyLimited = MockHttpServletResponse()
        filter.doFilter(request("/api/v1/sites/$siteId/policy"), policyLimited, chain)
        assertEquals(429, policyLimited.status)

        // The general tier (3/min) still has its full allowance — it did not borrow from policy.
        repeat(3) {
            val response = MockHttpServletResponse()
            filter.doFilter(request("/api/v1/sites"), response, chain)
            assertEquals(200, response.status)
        }
    }

    @Test
    fun `draining the policy tier with POSTs does not lock the current-policy GET read out`() {
        val chain = mockk<FilterChain>(relaxed = true)
        authenticate("27272727-2727-2727-2727-272727272727")
        val siteId = "efefefef-efef-efef-efef-efefefefefef"

        // The precise reason POLICY is method-scoped: a POST flood of the heavy generate must not lock the
        // dashboard out of the cheap same-path GET current-policy read. Drain the POLICY bucket (2/min) with
        // POSTs — the 3rd POST is refused — then the GET on the identical path still returns 200 because it
        // rides the separate, generous GENERAL tier. A future refactor dropping the POST guard (or merging
        // the branches) would flip this GET to 429.
        repeat(2) { filter.doFilter(request("/api/v1/sites/$siteId/policy"), MockHttpServletResponse(), chain) }
        val postLimited = MockHttpServletResponse()
        filter.doFilter(request("/api/v1/sites/$siteId/policy"), postLimited, chain)
        assertEquals(429, postLimited.status)

        val getRead = MockHttpServletResponse()
        filter.doFilter(request("/api/v1/sites/$siteId/policy", method = "GET"), getRead, chain)
        assertEquals(200, getRead.status)
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
