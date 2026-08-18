package eu.cookiekeeper.notify

import eu.cookiekeeper.common.CookieKeeperProperties
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient
import java.time.Duration

class BrevoEmailSenderTest {
    private fun props(
        apiKey: String = "xkeysib-test",
        baseUrl: String = "https://api.brevo.test",
    ) = CookieKeeperProperties(
        auth =
            CookieKeeperProperties.Auth(
                jwtSecret = "test-only-jwt-secret-0123456789-abcdefghij",
                accessTokenTtl = Duration.ofMinutes(15),
                refreshTokenTtl = Duration.ofDays(30),
                verificationTokenTtl = Duration.ofHours(24),
                resetTokenTtl = Duration.ofHours(1),
            ),
        appBaseUrl = "http://localhost:3000",
        cdnBaseUrl = "http://localhost:8081",
        mailFrom = "support@cookiekeeper.eu",
        mail =
            CookieKeeperProperties.Mail(
                provider = "brevo",
                brevo = CookieKeeperProperties.Mail.Brevo(apiKey = apiKey, baseUrl = baseUrl, senderName = "Complyr"),
            ),
    )

    @Test
    fun `posts the message to the brevo transactional endpoint`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val sender = BrevoEmailSender(builder, props())

        server
            .expect(requestTo("https://api.brevo.test/v3/smtp/email"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("api-key", "xkeysib-test"))
            .andExpect(header("Content-Type", containsString(MediaType.APPLICATION_JSON_VALUE)))
            .andExpect(jsonPath("$.sender.email").value("support@cookiekeeper.eu"))
            .andExpect(jsonPath("$.sender.name").value("Complyr"))
            .andExpect(jsonPath("$.to[0].email").value("alice@example.com"))
            .andExpect(jsonPath("$.subject").value("Subject line"))
            .andExpect(jsonPath("$.htmlContent").value("<p>Hello</p>"))
            .andRespond(
                withStatus(HttpStatus.CREATED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"messageId":"<abc@brevo>"}"""),
            )

        sender.send("alice@example.com", "Subject line", "<p>Hello</p>")

        server.verify()
    }

    @Test
    fun `includes reply-to only when supplied`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val sender = BrevoEmailSender(builder, props())

        server
            .expect(requestTo("https://api.brevo.test/v3/smtp/email"))
            .andExpect(jsonPath("$.replyTo.email").value("customer@example.com"))
            .andRespond(withStatus(HttpStatus.CREATED))

        sender.send("support@cookiekeeper.eu", "Subject line", "<p>Hello</p>", replyTo = "customer@example.com")

        server.verify()
    }

    @Test
    fun `omits reply-to from the payload for a transactional send`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val sender = BrevoEmailSender(builder, props())

        server
            .expect(requestTo("https://api.brevo.test/v3/smtp/email"))
            .andExpect(jsonPath("$.replyTo").doesNotExist())
            .andRespond(withStatus(HttpStatus.CREATED))

        sender.send("alice@example.com", "Subject line", "<p>Hello</p>")

        server.verify()
    }

    @Test
    fun `maps a non-2xx response to EmailDeliveryException`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val sender = BrevoEmailSender(builder, props())

        server
            .expect(requestTo("https://api.brevo.test/v3/smtp/email"))
            .andRespond(
                withStatus(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"code":"unauthorized","message":"Key not found"}"""),
            )

        assertThrows<EmailDeliveryException> {
            sender.send("alice@example.com", "Subject line", "<p>Hello</p>")
        }
        server.verify()
    }

    @Test
    fun `maps a transport failure to EmailDeliveryException`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val sender = BrevoEmailSender(builder, props())

        server
            .expect(requestTo("https://api.brevo.test/v3/smtp/email"))
            .andRespond { throw java.io.IOException("connection reset") }

        assertThrows<EmailDeliveryException> {
            sender.send("alice@example.com", "Subject line", "<p>Hello</p>")
        }
        server.verify()
    }

    @Test
    fun `refuses to construct without an api key when brevo is selected`() {
        val builder = RestClient.builder()
        MockRestServiceServer.bindTo(builder).build()

        assertThrows<IllegalArgumentException> {
            BrevoEmailSender(builder, props(apiKey = "  "))
        }
    }
}
