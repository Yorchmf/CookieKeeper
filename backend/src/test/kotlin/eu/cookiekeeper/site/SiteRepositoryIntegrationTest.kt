package eu.cookiekeeper.site

import eu.cookiekeeper.TestcontainersConfiguration
import eu.cookiekeeper.auth.UserEntity
import eu.cookiekeeper.auth.UserRepository
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration::class)
class SiteRepositoryIntegrationTest {
    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var siteRepository: SiteRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private fun newUser(): UserEntity =
        userRepository.saveAndFlush(
            UserEntity(email = "user-${UUID.randomUUID()}@example.com", passwordHash = "hash"),
        )

    private fun newSite(
        userId: UUID,
        domain: String,
        status: SiteStatus = SiteStatus.ACTIVE,
    ): SiteEntity =
        siteRepository.saveAndFlush(
            SiteEntity(userId = userId, domain = domain, siteKey = "pk_${UUID.randomUUID()}", status = status),
        )

    @Test
    fun `duplicate active domain for same user violates partial unique index`() {
        val user = newUser()
        newSite(user.id, "example.com")

        assertThrows<DataIntegrityViolationException> { newSite(user.id, "example.com") }
    }

    @Test
    fun `archived domain can be re-registered as active`() {
        val user = newUser()
        newSite(user.id, "example.com", status = SiteStatus.ARCHIVED)

        val active = newSite(user.id, "example.com")

        assertEquals(SiteStatus.ACTIVE, active.status)
        assertEquals(2, siteRepository.count())
    }

    @Test
    fun `same active domain is allowed for different users`() {
        newSite(newUser().id, "example.com")
        newSite(newUser().id, "example.com")

        assertEquals(2, siteRepository.count())
    }

    @Test
    fun `finder methods scope by user and status`() {
        val alice = newUser()
        val bob = newUser()
        val aliceSite = newSite(alice.id, "alice.com")
        newSite(alice.id, "old.alice.com", status = SiteStatus.ARCHIVED)
        newSite(bob.id, "bob.com")

        val activeSites = siteRepository.findAllByUserIdAndStatus(alice.id, SiteStatus.ACTIVE)
        assertEquals(listOf("alice.com"), activeSites.map { it.domain })

        assertEquals(aliceSite.id, siteRepository.findByIdAndUserId(aliceSite.id, alice.id)?.id)
        assertNull(siteRepository.findByIdAndUserId(aliceSite.id, bob.id), "cross-user lookup must miss")

        assertTrue(siteRepository.existsByUserIdAndDomainAndStatus(alice.id, "alice.com", SiteStatus.ACTIVE))
        assertFalse(siteRepository.existsByUserIdAndDomainAndStatus(alice.id, "old.alice.com", SiteStatus.ACTIVE))
    }

    @Test
    fun `countByUserIdAndStatus counts only the user's sites in that status`() {
        val alice = newUser()
        val bob = newUser()
        newSite(alice.id, "one.alice.com")
        newSite(alice.id, "two.alice.com")
        newSite(alice.id, "old.alice.com", status = SiteStatus.ARCHIVED)
        newSite(bob.id, "bob.com")

        // Scoped by user and status: archived rows and other users' sites are excluded.
        assertEquals(2, siteRepository.countByUserIdAndStatus(alice.id, SiteStatus.ACTIVE))
        assertEquals(1, siteRepository.countByUserIdAndStatus(alice.id, SiteStatus.ARCHIVED))
        assertEquals(0, siteRepository.countByUserIdAndStatus(UUID.randomUUID(), SiteStatus.ACTIVE))
    }

    @Test
    fun `verification method round-trips through the converter for every enum value`() {
        val user = newUser()
        val verifiedAt = Instant.parse("2026-08-04T09:00:00Z")

        VerificationMethod.entries.forEachIndexed { index, method ->
            val saved =
                siteRepository.saveAndFlush(
                    SiteEntity(
                        userId = user.id,
                        domain = "site-$index.example.com",
                        siteKey = "pk_${UUID.randomUUID()}",
                        verifiedAt = verifiedAt,
                        verificationMethod = method,
                    ),
                )
            siteRepository.flush()
            assertEquals(method, siteRepository.findById(saved.id).orElseThrow().verificationMethod)
        }
    }

    @Test
    fun `a half-set verification pair is rejected by the paired CHECK`() {
        val user = newUser()

        // verified_at without a method...
        assertThrows<DataIntegrityViolationException> {
            siteRepository.saveAndFlush(
                SiteEntity(
                    userId = user.id,
                    domain = "no-method.example.com",
                    siteKey = "pk_${UUID.randomUUID()}",
                    verifiedAt = Instant.parse("2026-08-04T09:00:00Z"),
                ),
            )
        }
    }

    @Test
    fun `a verification method without a timestamp is rejected by the paired CHECK`() {
        val user = newUser()

        // ...and a method without verified_at. Both halves move together or not at all.
        assertThrows<DataIntegrityViolationException> {
            siteRepository.saveAndFlush(
                SiteEntity(
                    userId = user.id,
                    domain = "no-timestamp.example.com",
                    siteKey = "pk_${UUID.randomUUID()}",
                    verificationMethod = VerificationMethod.DNS_TXT,
                ),
            )
        }
    }

    @Test
    fun `findStatusById re-queries past the identity map that would make a second findById stale`() {
        // This is the whole reason ScanRequestService / ScheduledRescanJob re-check the status via a
        // projection and not a second entity find. Load the entity once (populating this persistence
        // context's identity map), then commit a status change OUT OF BAND on the shared connection
        // (mirroring a concurrent account erasure archiving the site). A second findById would be served
        // the cached ACTIVE instance from the identity map; findStatusById must issue a real SELECT and
        // observe the ARCHIVED write.
        val user = newUser()
        val site = newSite(user.id, "race.example.com")

        // First load — same as the request's ownership check. Establishes the managed instance.
        assertEquals(SiteStatus.ACTIVE, siteRepository.findByIdAndUserId(site.id, user.id)?.status)

        // Out-of-band archive, straight to the row, bypassing the persistence context entirely.
        jdbcTemplate.update("UPDATE sites SET status = 'archived' WHERE id = ?", site.id)

        // The identity-map hazard the projection exists to dodge: findById is answered from the L1 cache.
        assertEquals(
            SiteStatus.ACTIVE,
            siteRepository.findById(site.id).orElseThrow().status,
            "findById is served from the identity map and cannot see the out-of-band archive",
        )

        // The fix: a projection query has no identity-map short-circuit, so it sees the committed archive.
        assertEquals(
            SiteStatus.ARCHIVED,
            siteRepository.findStatusById(site.id),
            "findStatusById must re-query and observe the archive a concurrent erasure committed",
        )
    }

    @Test
    fun `acquireUserSiteLock executes against Postgres and returns a mappable result`() {
        // The native pg_advisory_xact_lock query must run without error; the wrapping count(*) yields 1.
        assertEquals(1L, siteRepository.acquireUserSiteLock(UUID.randomUUID().let { it.mostSignificantBits xor it.leastSignificantBits }))
    }
}
