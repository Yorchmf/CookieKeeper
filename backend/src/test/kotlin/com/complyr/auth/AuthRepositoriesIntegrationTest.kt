package com.complyr.auth

import com.complyr.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration::class)
class AuthRepositoriesIntegrationTest {
    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var refreshTokenRepository: RefreshTokenRepository

    @Autowired
    private lateinit var authTokenRepository: AuthTokenRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private fun newUser(email: String = "user-${UUID.randomUUID()}@example.com"): UserEntity =
        userRepository.saveAndFlush(UserEntity(email = email, passwordHash = "hash", locale = "en"))

    @Test
    fun `findByEmail returns saved user and null for unknown email`() {
        val user = newUser("alice@example.com")

        assertEquals(user.id, userRepository.findByEmail("alice@example.com")?.id)
        assertNull(userRepository.findByEmail("nobody@example.com"))
    }

    @Test
    fun `auth token round-trips purpose enum and is found by hash and purpose`() {
        val user = newUser()
        val saved =
            authTokenRepository.saveAndFlush(
                AuthTokenEntity(
                    userId = user.id,
                    tokenHash = "hash-1",
                    purpose = TokenPurpose.EMAIL_VERIFICATION,
                    expiresAt = Instant.now().plusSeconds(3600),
                ),
            )

        val found = authTokenRepository.findByTokenHashAndPurpose("hash-1", TokenPurpose.EMAIL_VERIFICATION)
        assertEquals(saved.id, found?.id)
        assertEquals(TokenPurpose.EMAIL_VERIFICATION, found?.purpose)
        assertNull(authTokenRepository.findByTokenHashAndPurpose("hash-1", TokenPurpose.PASSWORD_RESET))
    }

    @Test
    fun `auth token hash uniqueness constraint fires`() {
        val user = newUser()
        val token =
            AuthTokenEntity(
                userId = user.id,
                tokenHash = "duplicate-hash",
                purpose = TokenPurpose.EMAIL_VERIFICATION,
                expiresAt = Instant.now().plusSeconds(3600),
            )
        authTokenRepository.saveAndFlush(token)

        assertThrows<DataIntegrityViolationException> {
            authTokenRepository.saveAndFlush(token.copy(id = UUID.randomUUID()))
        }
    }

    @Test
    fun `auth token purpose check constraint rejects unknown purposes`() {
        val user = newUser()

        assertThrows<org.springframework.dao.DataAccessException> {
            jdbcTemplate.update(
                "INSERT INTO auth_tokens (user_id, token_hash, purpose, expires_at) VALUES (?, ?, ?, now())",
                user.id,
                "bad-purpose-hash",
                "not_a_purpose",
            )
        }
    }

    @Test
    fun `revokeAllByUserId revokes only that user's active tokens`() {
        val alice = newUser()
        val bob = newUser()
        val now = Instant.now()
        val aliceToken =
            refreshTokenRepository.saveAndFlush(
                RefreshTokenEntity(userId = alice.id, tokenHash = "rt-alice", expiresAt = now.plusSeconds(60)),
            )
        val bobToken =
            refreshTokenRepository.saveAndFlush(
                RefreshTokenEntity(userId = bob.id, tokenHash = "rt-bob", expiresAt = now.plusSeconds(60)),
            )

        val revoked = refreshTokenRepository.revokeAllByUserId(alice.id, now)
        refreshTokenRepository.flush()

        assertEquals(1, revoked)
        assertNotNull(refreshTokenRepository.findById(aliceToken.id).orElseThrow().revokedAt)
        assertNull(refreshTokenRepository.findById(bobToken.id).orElseThrow().revokedAt)
    }
}
