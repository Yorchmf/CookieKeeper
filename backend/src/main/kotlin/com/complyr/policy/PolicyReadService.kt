package com.complyr.policy

import com.complyr.policy.dto.PublicPolicyResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Public, unauthenticated read side of the hosted policy page (`/p/{publicId}`). Addressed only by the
 * stable opaque public id — never by site id — and returns the current published version's rendered
 * HTML for the requested language.
 *
 * Language resolution is forgiving so the page always renders something sensible: the requested
 * language if present in this version, else the default language, else any available language. An
 * unknown or unpublished public id yields one identical [PolicyNotFoundException] (404) so the id is
 * not an existence oracle.
 */
@Service
class PolicyReadService(
    private val policyRepository: PolicyRepository,
    private val policySettingsRepository: PolicySettingsRepository,
) {
    @Transactional(readOnly = true)
    fun read(
        publicId: UUID,
        requestedLanguage: String?,
    ): PublicPolicyResponse {
        val settings = policySettingsRepository.findByPublicId(publicId) ?: throw PolicyNotFoundException()
        val latest =
            policyRepository.findFirstBySiteIdAndPublishedAtIsNotNullOrderByVersionDesc(settings.siteId)
                ?: throw PolicyNotFoundException()

        val versionRows = policyRepository.findBySiteIdAndVersion(settings.siteId, latest.version)
        val available = versionRows.map(PolicyEntity::language).sorted()
        // `latest` is always one of `versionRows` (same version), so this fallback is total — no third
        // not-found path is needed, which also keeps the read within detekt's ThrowsCount budget.
        val chosen = chooseRow(versionRows, requestedLanguage) ?: latest

        return PublicPolicyResponse(
            version = latest.version,
            language = chosen.language,
            availableLanguages = available,
            companyName = settings.details.companyName,
            html = chosen.html,
            publishedAt = chosen.publishedAt,
        )
    }

    private fun chooseRow(
        rows: List<PolicyEntity>,
        requestedLanguage: String?,
    ): PolicyEntity? {
        if (rows.isEmpty()) return null
        val byLanguage = rows.associateBy(PolicyEntity::language)
        val normalizedRequest = requestedLanguage?.let(PolicyLanguages::normalizeOrNull)
        return byLanguage[normalizedRequest]
            ?: byLanguage[PolicyLanguages.DEFAULT]
            ?: rows.minByOrNull(PolicyEntity::language)
    }
}
