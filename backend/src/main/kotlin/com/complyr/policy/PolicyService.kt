package com.complyr.policy

import com.complyr.banner.BannerConfigService
import com.complyr.common.ComplyrProperties
import com.complyr.policy.dto.PolicyCurrentResponse
import com.complyr.policy.dto.PolicyGenerationRequest
import com.complyr.policy.dto.PolicyGenerationResponse
import com.complyr.site.SiteEntity
import com.complyr.site.SiteNotFoundException
import com.complyr.site.SiteRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Authenticated policy generation. Every call is scoped by `(siteId, userId)` so a foreign site id is
 * indistinguishable from a true miss (mirrors [com.complyr.scan.ScanQueryService]).
 *
 * Generating appends a NEW version — one `policies` row per language, all sharing the next version
 * number and published atomically — rather than mutating existing HTML, because consent events
 * reference the policy version active at consent time (audit requirement, docs §4.5). The customer's
 * business details are persisted to `policy_settings` so a later republish (e.g. after a fresh scan)
 * reuses them, and the stable public id there keeps the hosted URL constant across versions.
 */
@Service
class PolicyService(
    private val siteRepository: SiteRepository,
    private val policyRepository: PolicyRepository,
    private val policySettingsRepository: PolicySettingsRepository,
    private val bannerConfigService: BannerConfigService,
    private val contextBuilder: PolicyContextBuilder,
    private val properties: ComplyrProperties,
    private val clock: Clock,
) {
    @Transactional
    fun generate(
        userId: UUID,
        siteId: UUID,
        request: PolicyGenerationRequest,
    ): PolicyGenerationResponse {
        val site = requireOwnedSite(userId, siteId)
        val details = request.toDetails(site)
        val languages = resolveLanguages(siteId, request)

        // Serialize concurrent generation for this site so two parallel calls can't both read the same
        // max version and then collide on the (site, version, language) unique constraint (a raw 500).
        // The advisory lock is transaction-scoped and released at commit; the read-only validation above
        // never contends for it, and distinct sites never block each other.
        policyRepository.acquireSiteGenerationLock(advisoryLockKey(site.id))
        val settings = upsertSettings(site.id, details)
        val version = nextVersion(site.id)
        val context = contextBuilder.build(site.id, details)
        val now = clock.instant()
        languages.forEach { language ->
            policyRepository.save(
                PolicyEntity(
                    siteId = site.id,
                    version = version,
                    language = language,
                    html = PolicyRenderer.render(language, context),
                    publishedAt = now,
                ),
            )
        }
        return PolicyGenerationResponse(
            version = version,
            publicId = settings.publicId.toString(),
            hostedUrl = hostedUrl(settings.publicId),
            languages = languages,
        )
    }

    /** The site's currently published policy, or [PolicyNotFoundException] if none has been generated. */
    @Transactional(readOnly = true)
    fun current(
        userId: UUID,
        siteId: UUID,
    ): PolicyCurrentResponse {
        requireOwnedSite(userId, siteId)
        val settings = policySettingsRepository.findById(siteId).orElseThrow { PolicyNotFoundException() }
        val latest =
            policyRepository.findFirstBySiteIdAndPublishedAtIsNotNullOrderByVersionDesc(siteId)
                ?: throw PolicyNotFoundException()
        val languages =
            policyRepository
                .findBySiteIdAndVersion(siteId, latest.version)
                .map(PolicyEntity::language)
                .sorted()
        return PolicyCurrentResponse(
            version = latest.version,
            publicId = settings.publicId.toString(),
            hostedUrl = hostedUrl(settings.publicId),
            languages = languages,
            publishedAt = latest.publishedAt,
        )
    }

    private fun requireOwnedSite(
        userId: UUID,
        siteId: UUID,
    ): SiteEntity = siteRepository.findByIdAndUserId(siteId, userId) ?: throw SiteNotFoundException()

    private fun PolicyGenerationRequest.toDetails(site: SiteEntity): PolicyDetails =
        PolicyDetails(
            companyName = companyName.trim(),
            contactEmail = contactEmail.trim(),
            // Default the site's own domain (https) when the customer leaves the URL blank.
            websiteUrl = websiteUrl?.trim()?.takeIf { it.isNotBlank() } ?: "https://${site.domain}",
            address = address?.trim()?.takeIf { it.isNotBlank() },
        )

    /**
     * Which languages to render: an explicit non-empty request wins (unsupported entries dropped; if
     * none survive it is a 400, not a silent empty publish); otherwise the site's banner languages, and
     * failing that all five supported languages.
     */
    private fun resolveLanguages(
        siteId: UUID,
        request: PolicyGenerationRequest,
    ): List<String> {
        if (request.languages.isNullOrEmpty()) return defaultLanguages(siteId)
        val normalized = request.languages.mapNotNull(PolicyLanguages::normalizeOrNull).distinct()
        return normalized.ifEmpty { throw UnsupportedPolicyLanguageException() }
    }

    private fun defaultLanguages(siteId: UUID): List<String> {
        val bannerLanguages =
            bannerConfigService
                .currentPublished(siteId)
                ?.config
                ?.languages
                ?.mapNotNull(PolicyLanguages::normalizeOrNull)
                ?.distinct()
                .orEmpty()
        return bannerLanguages.ifEmpty { PolicyLanguages.SUPPORTED }
    }

    private fun upsertSettings(
        siteId: UUID,
        details: PolicyDetails,
    ): PolicySettingsEntity {
        val now = clock.instant()
        val existing = policySettingsRepository.findById(siteId).orElse(null)
        val entity =
            existing?.copy(details = details, updatedAt = now)
                ?: PolicySettingsEntity(siteId = siteId, details = details, createdAt = now, updatedAt = now)
        return policySettingsRepository.save(entity)
    }

    private fun nextVersion(siteId: UUID): Int = (policyRepository.findFirstBySiteIdOrderByVersionDesc(siteId)?.version ?: 0) + 1

    // Fold the 128-bit site id into the 64-bit key `pg_advisory_xact_lock` takes; a rare collision only
    // briefly serializes two unrelated sites' generations, which is harmless.
    private fun advisoryLockKey(siteId: UUID): Long = siteId.mostSignificantBits xor siteId.leastSignificantBits

    private fun hostedUrl(publicId: UUID): String = "${properties.appBaseUrl}/p/$publicId"
}
