package com.complyr.support

import com.complyr.TestcontainersConfiguration
import com.complyr.auth.RecordingEmailConfig
import com.complyr.auth.RecordingEmailSender
import com.complyr.common.ComplyrProperties
import com.complyr.support.dto.SupportContactRequest
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * HTTP-boundary tests for `POST /api/v1/support/contact`. [SupportContactServiceTest] and
 * [ContactEmailComposerTest] cover the service/compose logic in isolation; this exercises the
 * load-bearing controls that only exist on the real filter chain + `@Valid` boundary:
 *  - the endpoint is behind auth (no `permitAll` matcher) → a token-less call is 401, never reaches the body;
 *  - the DTO's `@NotBlank` / `@Size` caps reject blank and oversized `subject`/`message` with 400 before the
 *    service runs (they bound both the email we send ourselves and the request body we accept);
 *  - a valid message resolves the Reply-To from the JWT-authenticated account (never the body) and addresses
 *    our own support inbox — a regression that leaked a customer address into `To`, or dropped the Reply-To,
 *    would be caught here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, RecordingEmailConfig::class)
class SupportContactApiIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var emailSender: RecordingEmailSender

    @Autowired
    private lateinit var properties: ComplyrProperties

    /** Signs up, verifies, and logs in; returns the access-token cookie paired with the account email. */
    private fun registeredUser(): Pair<Cookie, String> {
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
        return Cookie("cmplyr_at", accessHeader.substringAfter("=").substringBefore(";")) to email
    }

    private fun contactBody(
        subject: String,
        message: String,
    ): String = """{"subject":"$subject","message":"$message"}"""

    @Test
    fun `a token-less contact request is rejected with 401 before the body is read`() {
        mockMvc
            .perform(
                post("/api/v1/support/contact")
                    .contentType(MediaType.APPLICATION_JSON)
                    // A blank body that would fail validation — proving auth fires first, not @Valid.
                    .content(contactBody(subject = "", message = "")),
            ).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
    }

    @Test
    fun `blank and oversized fields are rejected with 400 before any email is sent`() {
        val (cookie, _) = registeredUser()
        val overSubject = "x".repeat(SupportContactRequest.MAX_SUBJECT_LENGTH + 1)
        val overMessage = "x".repeat(SupportContactRequest.MAX_MESSAGE_LENGTH + 1)

        val invalidBodies =
            listOf(
                contactBody(subject = "", message = "Hi there"),
                contactBody(subject = "Question", message = ""),
                contactBody(subject = overSubject, message = "Hi there"),
                contactBody(subject = "Question", message = overMessage),
            )

        for (body in invalidBodies) {
            mockMvc
                .perform(
                    post("/api/v1/support/contact")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
        }
    }

    @Test
    fun `a valid message emails the support inbox with the account as Reply-To`() {
        val (cookie, email) = registeredUser()

        mockMvc
            .perform(
                post("/api/v1/support/contact")
                    .cookie(cookie)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(contactBody(subject = "Billing question", message = "How do I upgrade my plan?")),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))

        val delivered = emailSender.awaitEmailFor(properties.supportInbox)
        assertEquals(properties.supportInbox, delivered.to, "support message must go to our own inbox, not the customer")
        assertEquals(email, delivered.replyTo, "Reply-To must be the JWT-resolved account email so a reply reaches them")
        assertTrue(delivered.htmlBody.contains("Billing question"), "the customer's subject belongs in the body: ${delivered.htmlBody}")
    }
}
