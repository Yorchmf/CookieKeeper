package com.complyr.notify

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.web.client.RestClient

/**
 * Guards the wiring gap that unit tests can't see: Boot 4 does not auto-configure a `RestClient.Builder`
 * bean here, so [BrevoEmailSender] (which injects one) would fail to start under `provider=brevo` unless
 * [BrevoRestClientConfig] supplies it. This verifies the conditional bean exists for brevo and is absent
 * otherwise, so the SMTP/local profile stays free of an unused builder.
 */
class BrevoRestClientConfigTest {
    private val contextRunner =
        ApplicationContextRunner().withUserConfiguration(BrevoRestClientConfig::class.java)

    @Test
    fun `supplies a RestClient Builder bean when brevo is the provider`() {
        contextRunner
            .withPropertyValues("complyr.mail.provider=brevo")
            .run { context ->
                assertThat(context).hasSingleBean(RestClient.Builder::class.java)
            }
    }

    @Test
    fun `supplies no builder for the default smtp provider`() {
        contextRunner
            .withPropertyValues("complyr.mail.provider=smtp")
            .run { context ->
                assertThat(context).doesNotHaveBean(RestClient.Builder::class.java)
            }
    }
}
