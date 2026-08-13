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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * `GET`/`PUT /api/v1/account/notifications` — the email-preferences half of `/settings/notifications`.
 * Covers the authenticated envelope, the all-on default for an account that never changed one, that a PUT
 * round-trips through both the response and a follow-up GET, that an omitted flag is a 400 (never a silent
 * opt-out), and that an Art. 17 erasure removes the materialized row.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, RecordingEmailConfig::class)
class AccountNotificationApiIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var emailSender: RecordingEmailSender

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    private val password = "s3cret-password"

    private data class Session(
        val cookie: Cookie,
        val userId: UUID,
    )

    private fun registeredUser(): Session {
        val email = "user-${UUID.randomUUID()}@example.com"
        mockMvc
            .perform(
                post("/api/v1/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"$email","password":"$password","locale":"en"}"""),
            ).andExpect(status().isCreated)
        mockMvc
            .perform(
                post("/api/v1/auth/verify-email")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"token":"${emailSender.lastTokenFor(email)}"}"""),
            ).andExpect(status().isOk)
        val login =
            mockMvc
                .perform(
                    post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"email":"$email","password":"$password"}"""),
                ).andExpect(status().isOk)
                .andReturn()
        val accessHeader =
            assertNotNull(login.response.getHeaders("Set-Cookie").firstOrNull { it.startsWith("cmplyr_at=") })
        val cookie = Cookie("cmplyr_at", accessHeader.substringAfter("=").substringBefore(";"))

        val me =
            mockMvc
                .perform(get("/api/v1/auth/me").cookie(cookie))
                .andReturn()
                .response.contentAsString
        val idMatch = assertNotNull(Regex("\"id\":\"([0-9a-f-]+)\"").find(me))
        return Session(cookie = cookie, userId = UUID.fromString(idMatch.groupValues[1]))
    }

    private fun putPreferences(
        cookie: Cookie,
        body: String,
    ) = mockMvc.perform(
        put("/api/v1/account/notifications")
            .cookie(cookie)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body),
    )

    private fun rowCount(userId: UUID): Int =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM notification_preferences WHERE user_id = ?",
            Int::class.java,
            userId,
        ) ?: 0

    @Test
    fun `requires authentication`() {
        mockMvc
            .perform(get("/api/v1/account/notifications").cookie(Cookie("cmplyr_at", "not-a-token")))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `an untouched account reads as the all-on default without materializing a row`() {
        val session = registeredUser()

        mockMvc
            .perform(get("/api/v1/account/notifications").cookie(session.cookie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.scanComplete").value(true))
            .andExpect(jsonPath("$.data.scanChanges").value(true))

        assertEquals(0, rowCount(session.userId), "a read must not create a preferences row")
    }

    @Test
    fun `a put round-trips through the response and a follow-up get`() {
        val session = registeredUser()

        putPreferences(session.cookie, """{"scanComplete":false,"scanChanges":true}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.scanComplete").value(false))
            .andExpect(jsonPath("$.data.scanChanges").value(true))

        mockMvc
            .perform(get("/api/v1/account/notifications").cookie(session.cookie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.scanComplete").value(false))
            .andExpect(jsonPath("$.data.scanChanges").value(true))

        assertEquals(1, rowCount(session.userId), "the first change materializes exactly one row")
    }

    @Test
    fun `a second put updates the same row rather than adding another`() {
        val session = registeredUser()
        putPreferences(session.cookie, """{"scanComplete":false,"scanChanges":false}""").andExpect(status().isOk)

        putPreferences(session.cookie, """{"scanComplete":true,"scanChanges":false}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.scanComplete").value(true))
            .andExpect(jsonPath("$.data.scanChanges").value(false))

        assertEquals(1, rowCount(session.userId), "the row is upserted, never duplicated")
    }

    @Test
    fun `an omitted flag is a 400, never a silent opt-out`() {
        val session = registeredUser()

        putPreferences(session.cookie, """{"scanComplete":true}""")
            .andExpect(status().isBadRequest)

        assertEquals(0, rowCount(session.userId), "a rejected request must not persist anything")
    }

    @Test
    fun `erasing the account removes the materialized preferences row`() {
        val session = registeredUser()
        putPreferences(session.cookie, """{"scanComplete":false,"scanChanges":false}""").andExpect(status().isOk)
        assertEquals(1, rowCount(session.userId))

        mockMvc
            .perform(
                post("/api/v1/account/delete")
                    .cookie(session.cookie)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"password":"$password"}"""),
            ).andExpect(status().isOk)

        assertEquals(0, rowCount(session.userId), "Art. 17 erasure must leave no preferences row behind")
    }
}
