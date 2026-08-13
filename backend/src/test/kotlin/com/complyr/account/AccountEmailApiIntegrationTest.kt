package com.complyr.account

import com.complyr.TestcontainersConfiguration
import com.complyr.auth.RecordingEmailConfig
import com.complyr.auth.RecordingEmailSender
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import kotlin.test.assertTrue

/**
 * `POST /api/v1/account/email` + `POST /api/v1/auth/confirm-email-change` — the "verify the new address
 * first" change flow (ADR-20). Covers the authenticated request envelope, that the request only PARKS the
 * new address (login email untouched, confirmation link mailed to the NEW address), that redeeming the link
 * swaps the login email and mails a heads-up to the OLD one, and the re-auth / same-as-current / collision
 * guards. The unit-level branch coverage lives in AccountEmailServiceTest + AuthServiceTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, RecordingEmailConfig::class)
class AccountEmailApiIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var emailSender: RecordingEmailSender

    private companion object {
        const val PASSWORD = "s3cret-password"
    }

    /** A verified, logged-in account and its access cookie. */
    private data class Session(
        val email: String,
        val accessCookie: Cookie,
    )

    private fun registerAndLogin(): Session {
        val email = "user-${UUID.randomUUID()}@example.com"
        mockMvc
            .perform(
                post("/api/v1/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"$email","password":"$PASSWORD","locale":"en"}"""),
            ).andExpect(status().isCreated)
        mockMvc
            .perform(
                post("/api/v1/auth/verify-email")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"token":"${emailSender.lastTokenFor(email)}"}"""),
            ).andExpect(status().isOk)
        // Signup + first verification produce two async emails to this address (verification + welcome).
        // Await both so a stray one can't leak into the change-flow assertions after clear().
        emailSender.awaitEmailFor(email, expectedCount = 2)
        val setCookies =
            mockMvc
                .perform(
                    post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"email":"$email","password":"$PASSWORD"}"""),
                ).andExpect(status().isOk)
                .andReturn()
                .response
                .getHeaders("Set-Cookie")
        return Session(email = email, accessCookie = cookieFrom(setCookies, "cmplyr_at"))
    }

    private fun cookieFrom(
        setCookies: List<String>,
        name: String,
    ): Cookie {
        val header = setCookies.first { it.startsWith("$name=") }
        return Cookie(name, header.substringAfter("=").substringBefore(";"))
    }

    private fun requestEmailChange(
        cookie: Cookie,
        body: String,
    ) = mockMvc.perform(
        post("/api/v1/account/email")
            .cookie(cookie)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body),
    )

    private fun login(
        email: String,
        password: String,
    ) = mockMvc.perform(
        post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"email":"$email","password":"$password"}"""),
    )

    @Test
    fun `requires authentication`() {
        requestEmailChange(
            Cookie("cmplyr_at", "not-a-token"),
            """{"newEmail":"new-${UUID.randomUUID()}@example.com","currentPassword":"$PASSWORD"}""",
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `parks the new address, then confirming swaps the login email and notifies the old address`() {
        val session = registerAndLogin()
        val newEmail = "new-${UUID.randomUUID()}@example.com"
        emailSender.clear()

        // --- request: 200, the login email is unchanged, the new address is parked ---------
        requestEmailChange(
            session.accessCookie,
            """{"newEmail":"$newEmail","currentPassword":"$PASSWORD"}""",
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.email").value(session.email))
            .andExpect(jsonPath("$.data.pendingEmail").value(newEmail))

        // The confirmation link is mailed to the NEW address and is locale-prefixed.
        val confirmEmail = emailSender.awaitEmailFor(newEmail)
        assertTrue(
            confirmEmail.htmlBody.contains("/en/confirm-email-change?token="),
            "confirmation link must point at the locale-prefixed confirm page",
        )
        val confirmToken = emailSender.lastTokenFor(newEmail)

        // The old address still logs in while the change is only pending.
        login(session.email, PASSWORD).andExpect(status().isOk)

        // --- confirm: 200, the login email swaps and the pending address clears ------------
        mockMvc
            .perform(
                post("/api/v1/auth/confirm-email-change")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"token":"$confirmToken"}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.email").value(newEmail))
            .andExpect(jsonPath("$.data.pendingEmail").doesNotExist())

        // A security heads-up lands on the OLD address, which just lost control of the account.
        emailSender.awaitEmailFor(session.email)

        // The swap is authoritative: the new address logs in, the old one no longer does.
        login(newEmail, PASSWORD).andExpect(status().isOk)
        login(session.email, PASSWORD)
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
    }

    @Test
    fun `rejects a wrong current password without parking anything`() {
        val session = registerAndLogin()

        requestEmailChange(
            session.accessCookie,
            """{"newEmail":"new-${UUID.randomUUID()}@example.com","currentPassword":"wrong-password"}""",
        ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("CURRENT_PASSWORD_INCORRECT"))
    }

    @Test
    fun `rejects the current address as the new one`() {
        val session = registerAndLogin()

        requestEmailChange(
            session.accessCookie,
            """{"newEmail":"${session.email}","currentPassword":"$PASSWORD"}""",
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("NEW_EMAIL_SAME_AS_CURRENT"))
    }

    @Test
    fun `rejects an address already registered to another live account`() {
        val other = registerAndLogin()
        val session = registerAndLogin()

        requestEmailChange(
            session.accessCookie,
            """{"newEmail":"${other.email}","currentPassword":"$PASSWORD"}""",
        ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("EMAIL_IN_USE"))
    }

    @Test
    fun `confirm rejects an unknown token generically`() {
        mockMvc
            .perform(
                post("/api/v1/auth/confirm-email-change")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"token":"not-a-real-token"}"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"))
    }

    @Test
    fun `rejects a blank new email`() {
        val session = registerAndLogin()

        requestEmailChange(
            session.accessCookie,
            """{"newEmail":"","currentPassword":"$PASSWORD"}""",
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
    }
}
