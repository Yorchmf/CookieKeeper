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
 * `POST /api/v1/account/password` — the change-password half of `/settings/profile`. Covers the
 * authenticated envelope, re-authentication with the current password (wrong → 403), the "new must differ"
 * and length rules, that the swap actually takes (old fails, new works), and that the change revokes every
 * session — this one's cookies are expired and the pre-change refresh token no longer refreshes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, RecordingEmailConfig::class)
class AccountPasswordApiIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var emailSender: RecordingEmailSender

    private companion object {
        const val CURRENT_PASSWORD = "s3cret-password"
        const val NEW_PASSWORD = "n3w-s3cret-password"
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
                    .content("""{"email":"$email","password":"$CURRENT_PASSWORD","locale":"en"}"""),
            ).andExpect(status().isCreated)
        mockMvc
            .perform(
                post("/api/v1/auth/verify-email")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"token":"${emailSender.lastTokenFor(email)}"}"""),
            ).andExpect(status().isOk)
        return login(email, CURRENT_PASSWORD).let { setCookies ->
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

    private fun changePassword(
        cookie: Cookie,
        body: String,
    ) = mockMvc.perform(
        post("/api/v1/account/password")
            .cookie(cookie)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body),
    )

    @Test
    fun `requires authentication`() {
        changePassword(
            Cookie("cmplyr_at", "not-a-token"),
            """{"currentPassword":"$CURRENT_PASSWORD","newPassword":"$NEW_PASSWORD"}""",
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `changes the password, clears the session, and swaps which password logs in`() {
        val session = registerAndLogin()

        changePassword(
            session.accessCookie,
            """{"currentPassword":"$CURRENT_PASSWORD","newPassword":"$NEW_PASSWORD"}""",
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            // Session cookies are expired in the same response.
            .andExpect(cookie().maxAge("cmplyr_at", 0))
            .andExpect(cookie().maxAge("cmplyr_rt", 0))

        // The old password no longer authenticates; the new one does.
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"${session.email}","password":"$CURRENT_PASSWORD"}"""),
            ).andExpect(status().isUnauthorized)
        login(session.email, NEW_PASSWORD)
    }

    @Test
    fun `revokes the pre-change refresh token`() {
        val session = registerAndLogin()

        changePassword(
            session.accessCookie,
            """{"currentPassword":"$CURRENT_PASSWORD","newPassword":"$NEW_PASSWORD"}""",
        ).andExpect(status().isOk)

        // The refresh token minted before the change must be dead, not merely rotated.
        mockMvc
            .perform(post("/api/v1/auth/refresh").cookie(session.refreshCookie))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `rejects a wrong current password without changing anything`() {
        val session = registerAndLogin()

        changePassword(
            session.accessCookie,
            """{"currentPassword":"wrong-password","newPassword":"$NEW_PASSWORD"}""",
        ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("CURRENT_PASSWORD_INCORRECT"))

        // The current password still works — the failed attempt did not rotate it.
        login(session.email, CURRENT_PASSWORD)
    }

    @Test
    fun `rejects a new password identical to the current one`() {
        val session = registerAndLogin()

        changePassword(
            session.accessCookie,
            """{"currentPassword":"$CURRENT_PASSWORD","newPassword":"$CURRENT_PASSWORD"}""",
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("NEW_PASSWORD_SAME_AS_CURRENT"))
    }

    @Test
    fun `rejects a new password below the length policy`() {
        val session = registerAndLogin()

        changePassword(
            session.accessCookie,
            """{"currentPassword":"$CURRENT_PASSWORD","newPassword":"short"}""",
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
    }

    @Test
    fun `rejects a blank current password`() {
        val session = registerAndLogin()

        changePassword(
            session.accessCookie,
            """{"currentPassword":"","newPassword":"$NEW_PASSWORD"}""",
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
    }
}
