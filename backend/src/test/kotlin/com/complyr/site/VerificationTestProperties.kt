package com.complyr.site

import com.complyr.common.ComplyrProperties
import java.time.Duration

/**
 * Shared [ComplyrProperties] fixture for the domain-verification unit tests, so each test tunes only the
 * bound it is actually exercising instead of restating the whole config tree.
 */
object VerificationTestProperties {
    fun properties(verification: ComplyrProperties.Verification = ComplyrProperties.Verification()): ComplyrProperties =
        ComplyrProperties(
            auth =
                ComplyrProperties.Auth(
                    jwtSecret = "unit-test-jwt-secret-0123456789-abcdefghijklmnop",
                    accessTokenTtl = Duration.ofMinutes(15),
                    refreshTokenTtl = Duration.ofDays(30),
                    verificationTokenTtl = Duration.ofHours(24),
                    resetTokenTtl = Duration.ofHours(1),
                ),
            verification = verification,
            appBaseUrl = "http://localhost:3000",
            cdnBaseUrl = "https://cdn.complyr.eu",
            mailFrom = "no-reply@complyr.eu",
        )
}
