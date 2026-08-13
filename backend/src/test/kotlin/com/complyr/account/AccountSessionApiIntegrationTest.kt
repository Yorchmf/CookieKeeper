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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * `POST /api/v1/account/sessions/revoke-all` — the "sign out of all devices" control on `/settings/security`.
 * Covers the authenticated envelope, re-authentication with the current password (wrong → 403 without
 * revoking), that this browser's cookies are expired in the response, and that every refresh token is killed
 * (the pre-revoke token no longer refreshes) while the password itself is untouched (it still logs in).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, RecordingEmailConfig::class)
class AccountSessionApiIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var emailSender: RecordingEmailSender

    private companion object {
        const val PASSWORD = "s3cret-password"
    }

    /** A verified, logged-in account plus the cookies its login issued. */
    private data class Session(
        val email: String,
        val accessCookie: Cookie,
        val refreshCookie: Cookie,
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
        return login(email, PASSWORD).let { setCookies ->
            Session(
                email = email,
                accessCookie = cookieFrom(setCookies, "cmplyr_at"),
                refreshCookie = cookieFrom(setCookies, "cmplyr_rt"),
            )
        }
    }

    /** Performs a login and returns its `Set-Cookie` headers; asserts the login itself succeeded. */
    private fun login(
        email: String,
        password: String,
    ): List<String> =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"$email","password":"$password"}"""),
            ).andExpect(status().isOk)
            .andReturn()
            .response
            .getHeaders("Set-Cookie")

    private fun cookieFrom(
        setCookies: List<String>,
        name: String,
    ): Cookie {
        val header = setCookies.first { it.startsWith("$name=") }
        return Cookie(name, header.substringAfter("=").substringBefore(";"))
    }

    private fun revokeAll(
        cookie: Cookie,
        body: String,
    ) = mockMvc.perform(
        post("/api/v1/account/sessions/revoke-all")
            .cookie(cookie)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body),
    )

    @Test
    fun `requires authentication`() {
        revokeAll(
            Cookie("cmplyr_at", "not-a-token"),
            """{"currentPassword":"$PASSWORD"}""",
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `revokes every session and clears this browser's cookies, leaving the password intact`() {
        val session = registerAndLogin()

        revokeAll(
            session.accessCookie,
            """{"currentPassword":"$PASSWORD"}""",
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            // This browser's session cookies are expired in the same response.
            .andExpect(cookie().maxAge("cmplyr_at", 0))
            .andExpect(cookie().maxAge("cmplyr_rt", 0))

        // Signing out of all devices must not touch the credential — the password still logs in.
        login(session.email, PASSWORD)
    }

    @Test
    fun `revokes the pre-revoke refresh token`() {
        val session = registerAndLogin()

        revokeAll(
            session.accessCookie,
            """{"currentPassword":"$PASSWORD"}""",
        ).andExpect(status().isOk)

        // The refresh token minted before the revoke must be dead, not merely rotated.
        mockMvc
            .perform(post("/api/v1/auth/refresh").cookie(session.refreshCookie))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `rejects a wrong current password without revoking anything`() {
        val session = registerAndLogin()

        revokeAll(
            session.accessCookie,
            """{"currentPassword":"wrong-password"}""",
        ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("CURRENT_PASSWORD_INCORRECT"))

        // Nothing was revoked — the pre-request refresh token still works.
        mockMvc
            .perform(post("/api/v1/auth/refresh").cookie(session.refreshCookie))
            .andExpect(status().isOk)
    }

    @Test
    fun `rejects a blank current password`() {
        val session = registerAndLogin()

        revokeAll(
            session.accessCookie,
            """{"currentPassword":""}""",
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
    }
}
