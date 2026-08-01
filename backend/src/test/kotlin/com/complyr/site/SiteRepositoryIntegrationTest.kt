package com.complyr.site

import com.complyr.TestcontainersConfiguration
import com.complyr.auth.UserEntity
import com.complyr.auth.UserRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
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
    fun `acquireUserSiteLock executes against Postgres and returns a mappable result`() {
        // The native pg_advisory_xact_lock query must run without error; the wrapping count(*) yields 1.
        assertEquals(1L, siteRepository.acquireUserSiteLock(UUID.randomUUID().let { it.mostSignificantBits xor it.leastSignificantBits }))
    }
}
