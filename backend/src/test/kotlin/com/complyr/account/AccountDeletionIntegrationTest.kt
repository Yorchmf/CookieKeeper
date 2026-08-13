package com.complyr.account

import com.complyr.TestcontainersConfiguration
import com.complyr.auth.AuthTokenEntity
import com.complyr.auth.AuthTokenRepository
import com.complyr.auth.RefreshTokenEntity
import com.complyr.auth.RefreshTokenRepository
import com.complyr.auth.TokenPurpose
import com.complyr.auth.UserEntity
import com.complyr.auth.UserRepository
import com.complyr.scan.JobEntity
import com.complyr.scan.JobRepository
import com.complyr.scan.PublicScanEntity
import com.complyr.scan.PublicScanRepository
import com.complyr.scan.ScanCookieEntity
import com.complyr.scan.ScanCookieRepository
import com.complyr.scan.ScanEntity
import com.complyr.scan.ScanRepository
import com.complyr.scan.ScanStatus
import com.complyr.scan.ScanTrigger
import com.complyr.site.SiteEntity
import com.complyr.site.SiteRepository
import com.complyr.site.SiteStatus
import com.complyr.site.VerificationMethod
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Art. 17 erasure against real Postgres (ADR-20). This is where the native bulk DML in
 * [AccountSiteErasureRepository] / [AccountIdentityErasureRepository] is actually decided — the
 * unit test can only check the orchestration around it.
 *
 * The scenario is the one that made this design necessary: an account with two sites, one of which has
 * recorded consent evidence. That site cannot be deleted (`consent_events.site_id` is ON DELETE RESTRICT
 * and CLAUDE.md #3 forbids touching the events), so it must survive as an anonymized tombstone while the
 * other is removed outright.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class AccountDeletionIntegrationTest {
    @Autowired private lateinit var deletionService: AccountDeletionService

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired private lateinit var userRepository: UserRepository

    @Autowired private lateinit var siteRepository: SiteRepository

    @Autowired private lateinit var scanRepository: ScanRepository

    @Autowired private lateinit var scanCookieRepository: ScanCookieRepository

    @Autowired private lateinit var jobRepository: JobRepository

    @Autowired private lateinit var authTokenRepository: AuthTokenRepository

    @Autowired private lateinit var refreshTokenRepository: RefreshTokenRepository

    @Autowired private lateinit var publicScanRepository: PublicScanRepository

    @Autowired private lateinit var passwordEncoder: PasswordEncoder

    @Test
    fun `erases everything the account owns, keeping only a tombstone for consent-bearing sites`() {
        val fixture = seedAccount()

        val result = deletionService.delete(fixture.userId, PASSWORD)

        assertEquals(1, result.sitesDeleted, "the site without consent evidence is removed outright")
        assertEquals(1, result.sitesAnonymized, "the site holding consent evidence survives, stripped")

        assertTrue(siteRepository.findById(fixture.plainSiteId).isEmpty, "plain site must be gone")

        val tombstoneSite = siteRepository.findById(fixture.consentSiteId).orElseThrow()
        assertFalse(tombstoneSite.domain.contains(DOMAIN_WITH_CONSENT), "the domain is personal data of the customer")
        assertFalse(tombstoneSite.siteKey.contains(SITE_KEY_WITH_CONSENT), "the embed key must not survive")
        assertEquals(SiteStatus.ARCHIVED, tombstoneSite.status, "a tombstone must never resolve for the widget")
        assertEquals(null, tombstoneSite.verifiedAt)
        assertEquals(null, tombstoneSite.verificationMethod)

        assertEquals(1, count("consent_events", "site_id", fixture.consentSiteId), "audit evidence is untouchable")

        assertEquals(0, count("scans", "site_id", fixture.consentSiteId))
        assertEquals(0, count("scan_cookies", "id", fixture.scanCookieId))
        assertEquals(0, count("jobs", "id", fixture.jobId))
        assertEquals(0, count("banner_configs", "site_id", fixture.consentSiteId))
        assertEquals(0, count("policies", "site_id", fixture.consentSiteId))
        assertEquals(0, count("policy_settings", "site_id", fixture.consentSiteId))
        assertEquals(0, count("cookie_overrides", "site_id", fixture.consentSiteId))
        assertEquals(0, count("auth_tokens", "user_id", fixture.userId))
        assertEquals(0, count("refresh_tokens", "user_id", fixture.userId))
        assertEquals(0, count("subscriptions", "user_id", fixture.userId))
        assertEquals(0, count("public_scans", "id", fixture.publicScanId), "the pre-signup lead shares the email")

        val user = userRepository.findById(fixture.userId).orElseThrow()
        assertTrue(user.isErased)
        assertNotNull(user.deletedAt)
        assertFalse(user.email.contains(EMAIL_LOCAL_PART), "the address must be destroyed, not merely flagged")
        assertFalse(passwordEncoder.matches(PASSWORD, user.passwordHash), "the credential must not survive")
    }

    @Test
    fun `an account with no consent evidence leaves no site rows at all`() {
        val userId = UUID.randomUUID()
        userRepository.save(
            UserEntity(id = userId, email = "clean-$userId@example.com", passwordHash = hashedPassword()),
        )
        val siteId = UUID.randomUUID()
        siteRepository.save(
            SiteEntity(id = siteId, userId = userId, domain = "clean-$siteId.example", siteKey = "key-$siteId"),
        )

        val result = deletionService.delete(userId, PASSWORD)

        assertEquals(1, result.sitesDeleted)
        assertEquals(0, result.sitesAnonymized)
        assertEquals(0, count("sites", "user_id", userId))
    }

    /**
     * Tenant isolation: erasing one account must not touch another's rows.
     *
     * The erasure is ten native `@Modifying` statements, several of which reach `sites` through a
     * subquery rather than a direct `user_id` column. A single dropped or mistyped predicate in any one of
     * them would silently destroy every tenant's data — the worst possible outcome for a product whose
     * entire value proposition is custody of other people's compliance evidence. This test exists so that
     * mistake fails here instead of in production, and so a future statement added to the sequence has
     * something to fail against.
     */
    @Test
    fun `leaves a bystander account completely untouched`() {
        val victim = seedAccount()
        val bystander = seedAccount()

        deletionService.delete(victim.userId, PASSWORD)

        val bystanderUser = userRepository.findById(bystander.userId).orElseThrow()
        assertFalse(bystanderUser.isErased, "another tenant's account must not be tombstoned")
        assertTrue(bystanderUser.email.contains(EMAIL_LOCAL_PART), "their address must be intact")

        assertEquals(2, count("sites", "user_id", bystander.userId))
        assertNotNull(siteRepository.findById(bystander.plainSiteId).orElse(null))
        assertEquals(1, count("consent_events", "site_id", bystander.consentSiteId))
        assertEquals(1, count("scans", "site_id", bystander.consentSiteId))
        assertEquals(1, count("scan_cookies", "id", bystander.scanCookieId))
        assertEquals(1, count("jobs", "id", bystander.jobId))
        assertEquals(1, count("banner_configs", "site_id", bystander.consentSiteId))
        assertEquals(1, count("policies", "site_id", bystander.consentSiteId))
        assertEquals(1, count("policy_settings", "site_id", bystander.consentSiteId))
        assertEquals(1, count("cookie_overrides", "site_id", bystander.consentSiteId))
        assertEquals(1, count("auth_tokens", "user_id", bystander.userId))
        assertEquals(1, count("refresh_tokens", "user_id", bystander.userId))
        assertEquals(1, count("subscriptions", "user_id", bystander.userId))
        assertEquals(1, count("public_scans", "id", bystander.publicScanId))
    }

    /**
     * The unprocessed-webhook scrub is a substring match on the account's email and Stripe ids — the only
     * handles those rows offer, since they carry no user id. Substring matching is exactly the kind of
     * predicate that over-reaches, so this pins both halves: the account's own pending body is redacted,
     * and a webhook naming nobody in particular is left for the handler to process normally.
     */
    @Test
    fun `redacts only the erased account's unprocessed webhook bodies`() {
        val fixture = seedAccount()
        val ownEventId = seedPendingStripeEvent("""{"customer_email":"${ownerEmail(fixture.userId)}"}""")
        val otherEventId = seedPendingStripeEvent("""{"customer_email":"someone-else@example.com"}""")

        deletionService.delete(fixture.userId, PASSWORD)

        assertEquals(null, payloadOf(ownEventId), "the body naming the erased account must be scrubbed")
        assertNotNull(processedAtOf(ownEventId), "stamping it processed is what makes a re-delivery dedupe")
        assertNotNull(payloadOf(otherEventId), "another customer's pending event must still be processable")
        assertEquals(null, processedAtOf(otherEventId))
    }

    private fun seedPendingStripeEvent(payload: String): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO stripe_events (id, stripe_event_id, type, payload) " +
                "VALUES (?, ?, 'checkout.session.completed', ?)",
            id,
            "evt_$id",
            payload,
        )
        return id
    }

    private fun payloadOf(eventId: UUID): String? =
        jdbcTemplate.queryForObject("SELECT payload FROM stripe_events WHERE id = ?", String::class.java, eventId)

    private fun processedAtOf(eventId: UUID): Instant? =
        jdbcTemplate.queryForObject("SELECT processed_at FROM stripe_events WHERE id = ?", Instant::class.java, eventId)

    private fun ownerEmail(userId: UUID): String = userRepository.findById(userId).orElseThrow().email

    private fun hashedPassword(): String = requireNotNull(passwordEncoder.encode(PASSWORD))

    private fun count(
        table: String,
        column: String,
        value: UUID,
    ): Int =
        // Table/column are test-authored literals, never request input; the value is bound.
        jdbcTemplate.queryForObject("SELECT count(*) FROM $table WHERE $column = ?", Int::class.java, value) ?: 0

    @Suppress("LongMethod")
    private fun seedAccount(): Fixture {
        val now = Instant.now()
        val userId = UUID.randomUUID()
        val email = "$EMAIL_LOCAL_PART-$userId@example.com"
        userRepository.save(UserEntity(id = userId, email = email, passwordHash = hashedPassword()))

        val consentSiteId = UUID.randomUUID()
        val plainSiteId = UUID.randomUUID()
        siteRepository.saveAll(
            listOf(
                SiteEntity(
                    id = consentSiteId,
                    userId = userId,
                    domain = "$DOMAIN_WITH_CONSENT-$consentSiteId.example",
                    siteKey = "$SITE_KEY_WITH_CONSENT-$consentSiteId",
                    verifiedAt = now,
                    verificationMethod = VerificationMethod.SNIPPET,
                ),
                SiteEntity(
                    id = plainSiteId,
                    userId = userId,
                    domain = "plain-$plainSiteId.example",
                    siteKey = "plain-key-$plainSiteId",
                ),
            ),
        )

        jdbcTemplate.update(
            "INSERT INTO consent_events (id, site_id, visitor_id, action, categories_jsonb, created_at) " +
                "VALUES (?, ?, ?, 'accept_all', '{}'::jsonb, ?)",
            UUID.randomUUID(),
            consentSiteId,
            UUID.randomUUID(),
            // Bound as a JDBC timestamp: pgjdbc cannot infer a SQL type for a bare java.time.Instant.
            Timestamp.from(now),
        )

        val scan =
            scanRepository.save(
                ScanEntity(
                    siteId = consentSiteId,
                    status = ScanStatus.DONE,
                    trigger = ScanTrigger.MANUAL,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        val scanCookie = scanCookieRepository.save(ScanCookieEntity(scanId = scan.id, name = "_ga"))
        val job =
            jobRepository.save(
                JobEntity(
                    type = "scan",
                    payload = mapOf("scanId" to scan.id.toString()),
                    maxAttempts = 3,
                    availableAt = now,
                    createdAt = now,
                    updatedAt = now,
                ),
            )

        seedSiteDocuments(consentSiteId)

        authTokenRepository.save(
            AuthTokenEntity(
                userId = userId,
                tokenHash = "hash-$userId",
                purpose = TokenPurpose.EMAIL_VERIFICATION,
                expiresAt = now.plusSeconds(3_600),
            ),
        )
        refreshTokenRepository.save(
            RefreshTokenEntity(userId = userId, tokenHash = "refresh-$userId", expiresAt = now.plusSeconds(3_600)),
        )
        jdbcTemplate.update(
            "INSERT INTO subscriptions (id, user_id, plan, status) VALUES (?, ?, 'STARTER', 'canceled')",
            UUID.randomUUID(),
            userId,
        )

        // Pre-signup free-scan lead: no FK to the account, linked only by the email it was left with.
        val publicScan =
            publicScanRepository.save(
                PublicScanEntity(
                    domain = "lead-$userId.example",
                    publicToken = "token-$userId",
                    email = email,
                    createdAt = now,
                    updatedAt = now,
                    expiresAt = now.plusSeconds(86_400),
                ),
            )

        return Fixture(
            userId = userId,
            consentSiteId = consentSiteId,
            plainSiteId = plainSiteId,
            scanCookieId = scanCookie.id,
            jobId = job.id,
            publicScanId = publicScan.id,
        )
    }

    /** jsonb/text documents seeded directly: the erasure only needs the rows to exist, not to be valid. */
    private fun seedSiteDocuments(siteId: UUID) {
        jdbcTemplate.update(
            "INSERT INTO banner_configs (id, site_id, version, config_jsonb) VALUES (?, ?, 1, '{}'::jsonb)",
            UUID.randomUUID(),
            siteId,
        )
        jdbcTemplate.update(
            "INSERT INTO policies (id, site_id, version, language, html) VALUES (?, ?, 1, 'en', '<p>x</p>')",
            UUID.randomUUID(),
            siteId,
        )
        jdbcTemplate.update("INSERT INTO policy_settings (site_id, details) VALUES (?, '{}'::jsonb)", siteId)
        jdbcTemplate.update(
            "INSERT INTO cookie_overrides (id, site_id, cookie_name, category) VALUES (?, ?, '_ga', 'analytics')",
            UUID.randomUUID(),
            siteId,
        )
    }

    private data class Fixture(
        val userId: UUID,
        val consentSiteId: UUID,
        val plainSiteId: UUID,
        val scanCookieId: UUID,
        val jobId: UUID,
        val publicScanId: UUID,
    )

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        const val EMAIL_LOCAL_PART = "erasure-owner"
        const val DOMAIN_WITH_CONSENT = "with-consent"
        const val SITE_KEY_WITH_CONSENT = "consent-key"
    }
}
