package com.complyr.policy

import com.complyr.banner.BannerConfigService
import com.complyr.common.ComplyrProperties
import com.complyr.policy.dto.PolicyCurrentResponse
import com.complyr.policy.dto.PolicyGenerationRequest
import com.complyr.policy.dto.PolicyGenerationResponse
import com.complyr.policy.dto.PublicPolicyResponse
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
    private val policyReadService: PolicyReadService,
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
        // never contends for it, and distinct sites never block each other. Held across the debounce
        // compare-and-save below so a concurrent generate can't slip a version in between.
        // Correctness assumes the default READ COMMITTED isolation: the version reads below run AFTER the
        // lock is granted, each taking a fresh snapshot that sees the prior holder's committed version.
        // Under REPEATABLE READ/SERIALIZABLE the snapshot would pin at the transaction's first statement
        // and these reads could go stale — do not raise the isolation on this path without revisiting.
        policyRepository.acquireSiteGenerationLock(advisoryLockKey(site.id))

        val context = contextBuilder.build(site.id, details)
        val rendered = languages.associateWith { PolicyRenderer.render(it, context) }

        // Debounce a no-op regenerate: if this render is byte-identical to the site's current version
        // (same language set, same HTML each — the HTML embeds the "last updated" date, so this skips a
        // same-day re-run with no changes while a genuine edit or a new day still produces a version), do
        // not append an identical version. Without this a customer looping the endpoint can inflate their
        // audit-referenced `policies` table unbounded (the per-user rate limit only slows it). We COMPARE
        // only — never UPDATE or DELETE existing versions, which consent events reference (docs §4.5) — so
        // append-only audit semantics hold.
        currentIfUnchanged(site.id, rendered)?.let { return it }

        val settings = upsertSettings(site.id, details)
        val version = nextVersion(site.id)
        val now = clock.instant()
        rendered.forEach { (language, html) ->
            policyRepository.save(
                PolicyEntity(
                    siteId = site.id,
                    version = version,
                    language = language,
                    html = html,
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

    /**
     * Returns the current version's response when [rendered] is byte-identical to it (same languages,
     * same HTML each), so [generate] can no-op instead of appending an identical row set; null when there
     * is no current version, it differs, or its settings are somehow absent (fall through to a fresh
     * publish). Read-only — never mutates the stored, audit-referenced versions.
     */
    private fun currentIfUnchanged(
        siteId: UUID,
        rendered: Map<String, String>,
    ): PolicyGenerationResponse? {
        // "Latest" here (unpublished-inclusive) matches nextVersion's; it coincides with current()'s
        // latest-PUBLISHED because generate always stamps publishedAt. If a draft version is ever
        // introduced, reconcile these two notions of "latest" so the debounce can't return a draft.
        val latestVersion = policyRepository.findFirstBySiteIdOrderByVersionDesc(siteId)?.version ?: return null
        val current = policyRepository.findBySiteIdAndVersion(siteId, latestVersion).associate { it.language to it.html }
        // Compare before touching settings so a genuine change short-circuits without the extra read.
        return if (current != rendered) {
            null
        } else {
            policySettingsRepository.findById(siteId).orElse(null)?.let { settings ->
                PolicyGenerationResponse(
                    version = latestVersion,
                    publicId = settings.publicId.toString(),
                    hostedUrl = hostedUrl(settings.publicId),
                    languages = rendered.keys.toList(),
                )
            }
        }
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

    /**
     * The owner's preview of their current published policy — the same payload the hosted page serves,
     * but reached by site id behind the JWT instead of by public id.
     *
     * It exists because the hosted page is gated on domain verification (ADR-17) while the preview must
     * not be: a customer has to see exactly what they are about to publish *before* they can prove they
     * control the domain, and [PolicyCurrentResponse] carries no HTML. Ownership is the gate here, so a
     * foreign site id is the usual [SiteNotFoundException] 404 and the verification state is irrelevant.
     */
    @Transactional(readOnly = true)
    fun preview(
        userId: UUID,
        siteId: UUID,
        language: String?,
    ): PublicPolicyResponse {
        requireOwnedSite(userId, siteId)
        val settings = policySettingsRepository.findById(siteId).orElseThrow { PolicyNotFoundException() }
        return policyReadService.readBySite(settings, language)
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
