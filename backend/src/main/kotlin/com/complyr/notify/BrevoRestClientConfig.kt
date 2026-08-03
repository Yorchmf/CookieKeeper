package com.complyr.notify

import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Scope
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * Supplies the [RestClient.Builder] that [BrevoEmailSender] consumes. Boot 4 no longer auto-configures a
 * `RestClient.Builder` bean unless the `spring-boot-restclient` module is on the classpath (it is not
 * here), so we provide one explicitly — and use that single wiring point to bound the connect/read
 * timeouts. Without them the JDK default is effectively infinite: a Brevo endpoint that accepts the
 * connection then stalls would pin the tiny mail executor (`AsyncConfig`, corePoolSize=1) indefinitely
 * and silently stop all transactional email. This mirrors the SMTP path's explicit timeouts.
 *
 * Only created when `complyr.mail.provider=brevo` (the sender is too), so the SMTP/local profile carries
 * no unused builder. Prototype-scoped so the sender's `baseUrl(...)` mutation never touches shared state.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "complyr.mail", name = ["provider"], havingValue = "brevo")
class BrevoRestClientConfig {
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    fun brevoRestClientBuilder(): RestClient.Builder =
        RestClient.builder().requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(CONNECT_TIMEOUT)
                setReadTimeout(READ_TIMEOUT)
            },
        )

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val READ_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
