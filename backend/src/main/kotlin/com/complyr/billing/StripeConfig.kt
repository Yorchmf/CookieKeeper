package com.complyr.billing

import com.complyr.common.ComplyrProperties
import com.stripe.StripeClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers the shared [StripeClient] singleton. The client is immutable and thread-safe, so one
 * bean serves every request. The secret key comes from [ComplyrProperties.Billing.stripeSecretKey],
 * which is env-bound per environment (test-mode `sk_test_…` for local/dev, live `sk_live_…` for prd)
 * — never hard-coded.
 */
@Configuration
class StripeConfig {
    @Bean
    fun stripeClient(properties: ComplyrProperties): StripeClient = StripeClient(properties.billing.stripeSecretKey)
}
