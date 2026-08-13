package com.complyr.account

import com.complyr.TestcontainersConfiguration
import com.complyr.auth.RecordingEmailConfig
import com.complyr.auth.RecordingEmailSender
import com.complyr.auth.UserRepository
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `PATCH /api/v1/account/profile` — the display-name half of `/settings/profile`. Covers the authenticated
 * envelope, that the name round-trips through both the response and `GET /me`, whitespace normalization,
 * blank-clears-to-null, and the length bound.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, RecordingEmailConfig::class)
class AccountProfileApiIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var emailSender: RecordingEmailSender

    @Autowired private lateinit var userRepository: UserRepository

    private fun registeredUser(): Cookie {
        val email = "user-${UUID.randomUUID()}@example.com"
        mockMvc
            .perform(
                post("/api/v1/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"$email","password":"s3cret-password","locale":"en"}"""),
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
                        .content("""{"email":"$email","password":"s3cret-password"}"""),
                ).andExpect(status().isOk)
                .andReturn()
        val accessHeader =
            assertNotNull(login.response.getHeaders("Set-Cookie").firstOrNull { it.startsWith("cmplyr_at=") })
        return Cookie("cmplyr_at", accessHeader.substringAfter("=").substringBefore(";"))
    }

    private fun patchName(
        cookie: Cookie,
        body: String,
    ) = mockMvc.perform(
        patch("/api/v1/account/profile")
            .cookie(cookie)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body),
    )

    @Test
    fun `requires authentication`() {
        patchName(Cookie("cmplyr_at", "not-a-token"), """{"name":"Ada"}""")
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `sets the name and reflects it in the response and me`() {
        val cookie = registeredUser()

        patchName(cookie, """{"name":"Ada Lovelace"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.name").value("Ada Lovelace"))

        mockMvc
            .perform(get("/api/v1/auth/me").cookie(cookie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.name").value("Ada Lovelace"))
    }

    @Test
    fun `trims surrounding whitespace before storing`() {
        val cookie = registeredUser()

        patchName(cookie, """{"name":"   Grace Hopper   "}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.name").value("Grace Hopper"))
    }

    @Test
    fun `a blank name clears the display name to null`() {
        val cookie = registeredUser()
        patchName(cookie, """{"name":"Alan Turing"}""").andExpect(status().isOk)

        patchName(cookie, """{"name":"   "}""")
            .andExpect(status().isOk)
            // Jackson drops nulls: "no name" is absent, never an empty string.
            .andExpect(jsonPath("$.data.name").doesNotExist())
    }

    @Test
    fun `a null name clears the display name`() {
        val cookie = registeredUser()
        patchName(cookie, """{"name":"Katherine Johnson"}""").andExpect(status().isOk)

        patchName(cookie, """{"name":null}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.name").doesNotExist())
    }

    @Test
    fun `rejects a name over the length bound`() {
        val cookie = registeredUser()

        patchName(cookie, """{"name":"${"x".repeat(121)}"}""")
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `accepts a name exactly at the length bound`() {
        val cookie = registeredUser()

        patchName(cookie, """{"name":"${"x".repeat(120)}"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.name").value("x".repeat(120)))
    }

    @Test
    fun `strips control and bidi characters before storing`() {
        val cookie = registeredUser()

        // Embedded newline (header-injection risk) + bidi override (display spoofing) must not survive
        // to the transactional-email greeting sink. ‮ is RIGHT-TO-LEFT OVERRIDE.
        patchName(cookie, """{"name":"Ada\nLovelace‮"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.name").value("AdaLovelace"))
    }

    @Test
    fun `persists the normalized name on the user row`() {
        val cookie = registeredUser()
        patchName(cookie, """{"name":"  Edsger  "}""").andExpect(status().isOk)

        val me =
            mockMvc
                .perform(get("/api/v1/auth/me").cookie(cookie))
                .andReturn()
                .response.contentAsString
        val idMatch = assertNotNull(Regex("\"id\":\"([0-9a-f-]+)\"").find(me))
        val id = UUID.fromString(idMatch.groupValues[1])
        val stored = assertNotNull(userRepository.findById(id).orElse(null))
        assertEquals("Edsger", stored.name)

        patchName(cookie, """{"name":""}""").andExpect(status().isOk)
        assertNull(assertNotNull(userRepository.findById(id).orElse(null)).name)
    }
}
