package com.complyr.site

import com.complyr.auth.EmailNotVerifiedException
import com.complyr.auth.UserRepository
import com.complyr.common.ComplyrProperties
import com.complyr.common.UnauthenticatedException
import com.complyr.site.dto.SiteDetailResponse
import com.complyr.site.dto.SiteResponse
import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.util.UUID

/**
 * Site management. Every read/write is scoped by `(id, userId)` in a single query —
 * ownership enforcement and anti-enumeration in one: foreign ids look like true misses.
 */
@Service
class SiteService(
    private val siteRepository: SiteRepository,
    private val userRepository: UserRepository,
    private val properties: ComplyrProperties,
    private val clock: Clock,
) {
    private val random = SecureRandom()

    fun list(
        userId: UUID,
        status: SiteStatus,
    ): List<SiteResponse> = siteRepository.findAllByUserIdAndStatus(userId, status).map(SiteResponse::from)

    @Transactional
    fun create(
        userId: UUID,
        rawDomain: String,
    ): SiteResponse {
        val user = userRepository.findById(userId).orElseThrow { UnauthenticatedException() }
        if (user.verifiedAt == null) throw EmailNotVerifiedException()
        val domain = DomainValidator.normalize(rawDomain)
        ensureDomainAvailable(userId, domain)
        val site =
            saveEnsuringDomainUniqueness(
                SiteEntity(
                    userId = userId,
                    domain = domain,
                    siteKey = generateSiteKey(),
                    createdAt = clock.instant(),
                    updatedAt = clock.instant(),
                ),
            )
        return SiteResponse.from(site)
    }

    fun get(
        userId: UUID,
        siteId: UUID,
    ): SiteDetailResponse = detail(owned(userId, siteId))

    @Transactional
    fun update(
        userId: UUID,
        siteId: UUID,
        newDomain: String?,
    ): SiteDetailResponse {
        val site = owned(userId, siteId)
        val updated = newDomain?.let { changeDomain(site, it) } ?: site
        return detail(updated)
    }

    /** Soft archive only — sites are never hard-deleted. */
    @Transactional
    fun archive(
        userId: UUID,
        siteId: UUID,
    ) {
        val site = owned(userId, siteId)
        if (site.status == SiteStatus.ARCHIVED) return
        siteRepository.save(site.copy(status = SiteStatus.ARCHIVED, updatedAt = clock.instant()))
    }

    private fun changeDomain(
        site: SiteEntity,
        newDomain: String,
    ): SiteEntity {
        val domain = DomainValidator.normalize(newDomain)
        if (domain == site.domain) return site
        ensureDomainAvailable(site.userId, domain)
        // Ownership proof does not transfer between domains: verification restarts.
        return saveEnsuringDomainUniqueness(site.copy(domain = domain, verifiedAt = null, updatedAt = clock.instant()))
    }

    /**
     * Persists (with flush) so a concurrent write racing past [ensureDomainAvailable] is decided
     * by the `uq_sites_user_domain_active` partial unique index and surfaces as 409 — any other
     * integrity violation (e.g. a site-key collision) is rethrown and becomes a 500.
     */
    private fun saveEnsuringDomainUniqueness(site: SiteEntity): SiteEntity =
        try {
            siteRepository.saveAndFlush(site)
        } catch (ex: DataIntegrityViolationException) {
            if (violatedConstraint(ex) == UNIQUE_USER_DOMAIN_CONSTRAINT) throw DomainAlreadyRegisteredException()
            throw ex
        }

    private fun violatedConstraint(ex: DataIntegrityViolationException): String? =
        generateSequence(ex.cause) { it.cause }
            .filterIsInstance<ConstraintViolationException>()
            .firstOrNull()
            ?.constraintName
            ?.trim('"')
            ?.lowercase()

    private fun owned(
        userId: UUID,
        siteId: UUID,
    ): SiteEntity = siteRepository.findByIdAndUserId(siteId, userId) ?: throw SiteNotFoundException()

    private fun ensureDomainAvailable(
        userId: UUID,
        domain: String,
    ) {
        if (siteRepository.existsByUserIdAndDomainAndStatus(userId, domain, SiteStatus.ACTIVE)) {
            throw DomainAlreadyRegisteredException()
        }
    }

    private fun detail(site: SiteEntity): SiteDetailResponse = SiteDetailResponse.from(site, embedSnippet(site.siteKey))

    private fun embedSnippet(siteKey: String): String =
        """<script async src="${properties.cdnBaseUrl}/v1.js" data-complyr="$siteKey"></script>"""

    private fun generateSiteKey(): String =
        buildString(SITE_KEY_PREFIX.length + SITE_KEY_LENGTH) {
            append(SITE_KEY_PREFIX)
            repeat(SITE_KEY_LENGTH) { append(SITE_KEY_ALPHABET[random.nextInt(SITE_KEY_ALPHABET.length)]) }
        }

    companion object {
        private const val UNIQUE_USER_DOMAIN_CONSTRAINT = "uq_sites_user_domain_active"
        private const val SITE_KEY_PREFIX = "pk_"
        private const val SITE_KEY_LENGTH = 32
        private const val SITE_KEY_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    }
}
