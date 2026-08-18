package eu.cookiekeeper.site

import eu.cookiekeeper.common.CookieKeeperProperties
import java.time.Duration

/**
 * Shared [CookieKeeperProperties] fixture for the domain-verification unit tests, so each test tunes only the
 * bound it is actually exercising instead of restating the whole config tree.
 */
object VerificationTestProperties {
    fun properties(verification: CookieKeeperProperties.Verification = CookieKeeperProperties.Verification()): CookieKeeperProperties =
        CookieKeeperProperties(
            auth =
                CookieKeeperProperties.Auth(
                    jwtSecret = "unit-test-jwt-secret-0123456789-abcdefghijklmnop",
                    accessTokenTtl = Duration.ofMinutes(15),
                    refreshTokenTtl = Duration.ofDays(30),
                    verificationTokenTtl = Duration.ofHours(24),
                    resetTokenTtl = Duration.ofHours(1),
                ),
            verification = verification,
            appBaseUrl = "http://localhost:3000",
            cdnBaseUrl = "https://cdn.cookiekeeper.eu",
            mailFrom = "no-reply@complyr.eu",
        )
}
