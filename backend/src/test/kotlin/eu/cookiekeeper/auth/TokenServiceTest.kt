package eu.cookiekeeper.auth

import com.nimbusds.jose.jwk.source.ImmutableSecret
import eu.cookiekeeper.common.CookieKeeperProperties
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TokenServiceTest {
    private val secret = "unit-test-jwt-secret-0123456789-abcdefghijklmnop"
    private val secretKey = SecretKeySpec(secret.toByteArray(), "HmacSHA256")
    private val jwtEncoder = NimbusJwtEncoder(ImmutableSecret(secretKey))
    private val jwtDecoder = NimbusJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS256).build()

    private val properties =
        CookieKeeperProperties(
            auth =
                CookieKeeperProperties.Auth(
                    jwtSecret = secret,
                    accessTokenTtl = Duration.ofMinutes(15),
                    refreshTokenTtl = Duration.ofDays(30),
                    verificationTokenTtl = Duration.ofHours(24),
                    resetTokenTtl = Duration.ofHours(1),
                ),
            appBaseUrl = "http://localhost:3000",
            cdnBaseUrl = "http://localhost:8081",
            mailFrom = "support@cookiekeeper.eu",
        )

    // Fixed at the real current instant (JWT timestamps have second precision and the
    // Nimbus decoder validates expiry against wall-clock time).
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val refreshTokenRepository = mockk<RefreshTokenRepository>()
    private val transactionManager = mockk<org.springframework.transaction.PlatformTransactionManager>(relaxed = true)
    private val service = TokenService(jwtEncoder, refreshTokenRepository, properties, clock, transactionManager)

    private fun stubSave() {
        every { refreshTokenRepository.save(any()) } answers { firstArg() }
    }

    @Test
    fun `access token carries subject, email_verified claim and 15 minute expiry`() {
        val userId = UUID.randomUUID()

        val token = service.issueAccessToken(userId, emailVerified = true)

        val jwt = jwtDecoder.decode(token)
        assertEquals(userId.toString(), jwt.subject)
        assertEquals(true, jwt.getClaim(TokenService.CLAIM_EMAIL_VERIFIED))
        assertEquals(now.plus(Duration.ofMinutes(15)), jwt.expiresAt)
    }

    @Test
    fun `tampered access token is rejected by the decoder`() {
        val token = service.issueAccessToken(UUID.randomUUID(), emailVerified = false)
        val tampered = token.dropLast(4) + "AAAA"

        assertThrows<JwtException> { jwtDecoder.decode(tampered) }
    }

    @Test
    fun `issued refresh token is stored hashed, never raw`() {
        stubSave()
        val userId = UUID.randomUUID()
        val saved = slot<RefreshTokenEntity>()

        val issued = service.issueRefreshToken(userId)

        verify { refreshTokenRepository.save(capture(saved)) }
        assertEquals(userId, saved.captured.userId)
        assertNotEquals(issued.rawToken, saved.captured.tokenHash)
        assertEquals(OpaqueTokens.sha256(issued.rawToken), saved.captured.tokenHash)
        assertEquals(now.plus(Duration.ofDays(30)), saved.captured.expiresAt)
    }

    @Test
    fun `rotation revokes the presented token and issues a successor linked via rotatedFrom`() {
        stubSave()
        val userId = UUID.randomUUID()
        val raw = OpaqueTokens.generate()
        val existing =
            RefreshTokenEntity(
                userId = userId,
                tokenHash = OpaqueTokens.sha256(raw),
                expiresAt = now.plusSeconds(3600),
            )
        every { refreshTokenRepository.findByTokenHash(existing.tokenHash) } returns existing

        val rotated = service.rotateRefreshToken(raw)

        assertEquals(userId, rotated.userId)
        assertNotEquals(raw, rotated.rawToken)
        verify { refreshTokenRepository.save(match { it.id == existing.id && it.revokedAt == now }) }
        verify { refreshTokenRepository.save(match { it.rotatedFrom == existing.id && it.revokedAt == null }) }
    }

    @Test
    fun `expired refresh token is rejected`() {
        val raw = OpaqueTokens.generate()
        val expired =
            RefreshTokenEntity(
                userId = UUID.randomUUID(),
                tokenHash = OpaqueTokens.sha256(raw),
                expiresAt = now.minusSeconds(1),
            )
        every { refreshTokenRepository.findByTokenHash(expired.tokenHash) } returns expired

        assertThrows<InvalidRefreshTokenException> { service.rotateRefreshToken(raw) }
    }

    @Test
    fun `unknown refresh token is rejected`() {
        every { refreshTokenRepository.findByTokenHash(any()) } returns null

        assertThrows<InvalidRefreshTokenException> { service.rotateRefreshToken("nope") }
    }

    @Test
    fun `reusing a revoked token outside the grace window revokes the whole family`() {
        val userId = UUID.randomUUID()
        val raw = OpaqueTokens.generate()
        val revoked =
            RefreshTokenEntity(
                userId = userId,
                tokenHash = OpaqueTokens.sha256(raw),
                expiresAt = now.plusSeconds(3600),
                revokedAt = now.minusSeconds(60),
            )
        every { refreshTokenRepository.findByTokenHash(revoked.tokenHash) } returns revoked
        every { refreshTokenRepository.revokeAllByUserId(userId, now) } returns 3

        assertThrows<InvalidRefreshTokenException> { service.rotateRefreshToken(raw) }

        verify(exactly = 1) { refreshTokenRepository.revokeAllByUserId(userId, now) }
    }

    @Test
    fun `reusing a just-rotated token within the grace window issues a new token without family revocation`() {
        stubSave()
        val justRevoked = revokedToken(revokedAt = now.minusSeconds(5))
        every { refreshTokenRepository.findByTokenHash(justRevoked.entity.tokenHash) } returns justRevoked.entity
        // A successor exists: the presented token was revoked by rotation (multi-tab race).
        every { refreshTokenRepository.existsByRotatedFrom(justRevoked.entity.id) } returns true

        val rotated = service.rotateRefreshToken(justRevoked.raw)

        assertNotNull(rotated.rawToken)
        verify(exactly = 0) { refreshTokenRepository.revokeAllByUserId(any(), any()) }
    }

    @Test
    fun `replaying a logged-out token within the grace window is rejected and triggers reuse handling`() {
        // Logout revocation produces no successor — the grace must not resurrect the session.
        val loggedOut = revokedToken(revokedAt = now.minusSeconds(5))
        every { refreshTokenRepository.findByTokenHash(loggedOut.entity.tokenHash) } returns loggedOut.entity
        every { refreshTokenRepository.existsByRotatedFrom(loggedOut.entity.id) } returns false
        every { refreshTokenRepository.revokeAllByUserId(loggedOut.entity.userId, now) } returns 1

        assertThrows<InvalidRefreshTokenException> { service.rotateRefreshToken(loggedOut.raw) }

        verify(exactly = 1) { refreshTokenRepository.revokeAllByUserId(loggedOut.entity.userId, now) }
    }

    @Test
    fun `replaying a token revoked by revokeAllForUser within the grace window is rejected`() {
        // Password-reset revocation (revoke-all) produces no successor — a stolen token
        // replayed right after the reset must stay dead.
        val resetRevoked = revokedToken(revokedAt = now.minusSeconds(2))
        every { refreshTokenRepository.findByTokenHash(resetRevoked.entity.tokenHash) } returns resetRevoked.entity
        every { refreshTokenRepository.existsByRotatedFrom(resetRevoked.entity.id) } returns false
        every { refreshTokenRepository.revokeAllByUserId(resetRevoked.entity.userId, now) } returns 0

        assertThrows<InvalidRefreshTokenException> { service.rotateRefreshToken(resetRevoked.raw) }
    }

    private data class RevokedToken(
        val raw: String,
        val entity: RefreshTokenEntity,
    )

    private fun revokedToken(revokedAt: Instant): RevokedToken {
        val raw = OpaqueTokens.generate()
        return RevokedToken(
            raw = raw,
            entity =
                RefreshTokenEntity(
                    userId = UUID.randomUUID(),
                    tokenHash = OpaqueTokens.sha256(raw),
                    expiresAt = now.plusSeconds(3600),
                    revokedAt = revokedAt,
                ),
        )
    }

    @Test
    fun `logout revocation is idempotent and ignores unknown tokens`() {
        stubSave()
        val raw = OpaqueTokens.generate()
        every { refreshTokenRepository.findByTokenHash(OpaqueTokens.sha256(raw)) } returns null

        service.revokeRefreshToken(raw)

        verify(exactly = 0) { refreshTokenRepository.save(any()) }
    }

    @Test
    fun `revokeAllForUser delegates to bulk revocation`() {
        val userId = UUID.randomUUID()
        every { refreshTokenRepository.revokeAllByUserId(userId, now) } returns 2

        service.revokeAllForUser(userId)

        verify { refreshTokenRepository.revokeAllByUserId(userId, now) }
    }

    @Test
    fun `opaque tokens are base64url and long enough`() {
        val raw = OpaqueTokens.generate()
        assertTrue(raw.matches(Regex("^[A-Za-z0-9_-]{43}$")), "expected 32 bytes base64url-encoded, got: $raw")
    }
}
