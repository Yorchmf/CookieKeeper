package com.complyr.auth

import com.complyr.TestcontainersConfiguration
import com.complyr.notify.EmailSender
import jakarta.servlet.http.Cookie
import org.awaitility.Awaitility
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

data class RecordedEmail(
    val to: String,
    val subject: String,
    val htmlBody: String,
)

/** Thread-safe recording fake: emails arrive asynchronously after the publishing transaction commits. */
class RecordingEmailSender : EmailSender {
    private val sent = CopyOnWriteArrayList<RecordedEmail>()

    override fun send(
        to: String,
        subject: String,
        htmlBody: String,
    ) {
        sent.add(RecordedEmail(to, subject, htmlBody))
    }

    fun clear() {
        sent.clear()
    }

    /** Awaits async delivery of the [expectedCount]-th email addressed to [to] and returns the latest one. */
    fun awaitEmailFor(
        to: String,
        expectedCount: Int = 1,
    ): RecordedEmail {
        Awaitility
            .await()
            .atMost(Duration.ofSeconds(AWAIT_SECONDS))
            .until { sent.count { it.to == to } >= expectedCount }
        return sent.last { it.to == to }
    }

    fun lastTokenFor(
        to: String,
        expectedCount: Int = 1,
    ): String {
        val body = awaitEmailFor(to, expectedCount).htmlBody
        val match = Regex("token=([A-Za-z0-9_-]+)").find(body)
        return requireNotNull(match) { "no token link found in email body: $body" }.groupValues[1]
    }

    companion object {
        private const val AWAIT_SECONDS = 10L
    }
}

@TestConfiguration(proxyBeanMethods = false)
class RecordingEmailConfig {
    @Bean
    @Primary
    fun recordingEmailSender(): RecordingEmailSender = RecordingEmailSender()
}

/**
 * Full-wire auth lifecycle against Testcontainers Postgres:
 * signup → verify (token captured from the recorded email) → login → me →
 * refresh rotation → reuse rejection → logout. Asserts envelope shape and cookie attributes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, RecordingEmailConfig::class)
class AuthFlowIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var emailSender: RecordingEmailSender

    private lateinit var email: String

    @BeforeEach
    fun freshUser() {
        email = "user-${UUID.randomUUID()}@example.com"
        emailSender.clear()
    }

    private fun postJson(
        path: String,
        body: String,
        vararg cookies: Cookie,
    ): MvcResult {
        val request =
            post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        if (cookies.isNotEmpty()) request.cookie(*cookies)
        return mockMvc.perform(request).andReturn()
    }

    private fun signup(): MvcResult = postJson("/api/v1/auth/signup", """{"email":"$email","password":"s3cret-password","locale":"de"}""")

    private fun login(): MvcResult = postJson("/api/v1/auth/login", """{"email":"$email","password":"s3cret-password"}""")

    private fun setCookies(result: MvcResult): Map<String, String> =
        result.response
            .getHeaders("Set-Cookie")
            .associate { header -> header.substringBefore("=") to header }

    private fun cookieValue(header: String): String = header.substringAfter("=").substringBefore(";")

    @Test
    fun `signup, verify, login, me, refresh rotation, reuse rejection and logout`() {
        // --- signup: 201 envelope, unverified user, verification email sent -------------
        val signupResult = signup()
        assertTrue(signupResult.response.status == 201, "signup should return 201")
        mockMvc
            .perform(
                post("/api/v1/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"$email","password":"s3cret-password","locale":"de"}"""),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("EMAIL_IN_USE"))

        // --- verify email using the token captured from the (German) email --------------
        val verificationToken = emailSender.lastTokenFor(email)
        assertTrue(
            emailSender.awaitEmailFor(email).htmlBody.contains("/de/verify-email?token="),
            "verification link must be locale-prefixed",
        )
        mockMvc
            .perform(
                post("/api/v1/auth/verify-email")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"token":"$verificationToken"}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.verifiedAt").isNotEmpty)

        // Reusing the single-use verification token fails generically.
        mockMvc
            .perform(
                post("/api/v1/auth/verify-email")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"token":"$verificationToken"}"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"))

        // --- login: envelope + both cookies with the right attributes -------------------
        val loginResult = login()
        assertTrue(loginResult.response.status == 200)
        val cookies = setCookies(loginResult)
        val accessCookie = assertNotNull(cookies["cmplyr_at"], "login must set cmplyr_at")
        val refreshCookie = assertNotNull(cookies["cmplyr_rt"], "login must set cmplyr_rt")
        assertTrue(accessCookie.contains("Path=/;") && accessCookie.contains("HttpOnly"))
        assertTrue(accessCookie.contains("SameSite=Lax") && accessCookie.contains("Secure"))
        assertTrue(refreshCookie.contains("Path=/api/v1/auth") && refreshCookie.contains("HttpOnly"))

        // --- me with the access cookie ---------------------------------------------------
        mockMvc
            .perform(get("/api/v1/auth/me").cookie(Cookie("cmplyr_at", cookieValue(accessCookie))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.email").value(email))
            .andExpect(jsonPath("$.data.verifiedAt").isNotEmpty)

        // --- refresh: rotates both cookies ----------------------------------------------
        val oldRefresh = cookieValue(refreshCookie)
        val refreshResult = postJson("/api/v1/auth/refresh", "", Cookie("cmplyr_rt", oldRefresh))
        assertTrue(refreshResult.response.status == 200)
        val rotated = setCookies(refreshResult)
        val newRefresh = cookieValue(assertNotNull(rotated["cmplyr_rt"]))
        assertTrue(newRefresh != oldRefresh, "refresh token must rotate")

        // --- reusing the old refresh token is rejected and clears cookies ---------------
        val reuseResult = postJson("/api/v1/auth/refresh", "", Cookie("cmplyr_rt", oldRefresh))
        assertTrue(reuseResult.response.status == 401)
        assertTrue(setCookies(reuseResult).values.all { it.contains("Max-Age=0") }, "cookies must be cleared")

        // Reuse detection revoked the whole family: the rotated token is dead too.
        val familyResult = postJson("/api/v1/auth/refresh", "", Cookie("cmplyr_rt", newRefresh))
        assertTrue(familyResult.response.status == 401, "family revocation must kill the successor token")

        // --- logout expires cookies; me without cookie is 401 ---------------------------
        val secondLogin = login()
        val secondCookies = setCookies(secondLogin)
        val logoutResult =
            postJson(
                "/api/v1/auth/logout",
                "",
                Cookie("cmplyr_at", cookieValue(assertNotNull(secondCookies["cmplyr_at"]))),
                Cookie("cmplyr_rt", cookieValue(assertNotNull(secondCookies["cmplyr_rt"]))),
            )
        assertTrue(logoutResult.response.status == 200)
        assertTrue(setCookies(logoutResult).values.all { it.contains("Max-Age=0") })

        mockMvc
            .perform(get("/api/v1/auth/me"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
    }

    @Test
    fun `password reset revokes existing sessions and enables the new password`() {
        signup()
        postJson("/api/v1/auth/verify-email", """{"token":"${emailSender.lastTokenFor(email)}"}""")
        val loginResult = login()
        val refreshToken = cookieValue(assertNotNull(setCookies(loginResult)["cmplyr_rt"]))

        mockMvc
            .perform(
                post("/api/v1/auth/forgot-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"$email"}"""),
            ).andExpect(status().isOk)
        // Email #1 was the signup verification; the reset email is #2 (async, after commit).
        val resetToken = emailSender.lastTokenFor(email, expectedCount = 2)

        mockMvc
            .perform(
                post("/api/v1/auth/reset-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"token":"$resetToken","newPassword":"brand-new-password"}"""),
            ).andExpect(status().isOk)

        // Old refresh token was revoked by the reset.
        val refreshResult = postJson("/api/v1/auth/refresh", "", Cookie("cmplyr_rt", refreshToken))
        assertTrue(refreshResult.response.status == 401)

        // Old password is dead, new password works (identical error to unknown email).
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"$email","password":"s3cret-password"}"""),
            ).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"$email","password":"brand-new-password"}"""),
            ).andExpect(status().isOk)
    }

    @Test
    fun `logout works without a valid access token and always clears both cookies`() {
        // A user with an expired access JWT (or none at all) must still be able to log out.
        val result = postJson("/api/v1/auth/logout", "")

        assertTrue(result.response.status == 200, "logout must be public")
        val cleared = setCookies(result)
        assertNotNull(cleared["cmplyr_at"])
        assertNotNull(cleared["cmplyr_rt"])
        assertTrue(cleared.values.all { it.contains("Max-Age=0") }, "both cookies must be expired")
    }

    @Test
    fun `logout with only the refresh cookie revokes it`() {
        signup()
        postJson("/api/v1/auth/verify-email", """{"token":"${emailSender.lastTokenFor(email)}"}""")
        val refreshToken = cookieValue(assertNotNull(setCookies(login())["cmplyr_rt"]))

        // No access cookie on purpose: the expired-JWT dashboard-lockout scenario.
        val logoutResult = postJson("/api/v1/auth/logout", "", Cookie("cmplyr_rt", refreshToken))
        assertTrue(logoutResult.response.status == 200)

        // The refresh token presented at logout is dead.
        val refreshResult = postJson("/api/v1/auth/refresh", "", Cookie("cmplyr_rt", refreshToken))
        assertTrue(refreshResult.response.status == 401)
    }

    @Test
    fun `forgot-password and resend-verification never reveal whether an email exists`() {
        mockMvc
            .perform(
                post("/api/v1/auth/forgot-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"ghost-${UUID.randomUUID()}@example.com"}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))

        mockMvc
            .perform(
                post("/api/v1/auth/resend-verification")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"ghost-${UUID.randomUUID()}@example.com"}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
    }

    @Test
    fun `signup validation rejects short passwords and unsupported locales`() {
        mockMvc
            .perform(
                post("/api/v1/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"$email","password":"short","locale":"de"}"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))

        mockMvc
            .perform(
                post("/api/v1/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"$email","password":"s3cret-password","locale":"pt"}"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
    }
}
