package com.complyr.consent

import com.complyr.common.ComplyrProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * Builds the singleton [ConsentOriginToken] signer from `complyr.consent.*`. Kept as a plain
 * (non-`@Component`) class constructed here so it stays trivially unit-testable with a fixed
 * [Clock] and an inline secret, while the real bean's secret comes from configuration and its
 * length is validated at startup (the constructor throws on a secret shorter than 32 bytes).
 */
@Configuration
class ConsentTokenConfig {
    @Bean
    fun consentOriginToken(
        properties: ComplyrProperties,
        clock: Clock,
    ): ConsentOriginToken =
        ConsentOriginToken(
            secret = properties.consent.originTokenSecret,
            ttl = properties.consent.originTokenTtl,
            clock = clock,
        )
}
