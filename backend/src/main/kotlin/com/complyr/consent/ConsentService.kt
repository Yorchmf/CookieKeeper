package com.complyr.consent

import com.complyr.banner.BannerConfigService
import com.complyr.banner.ConsentCategory
import com.complyr.common.ApiException
import com.complyr.common.IpHasher
import com.complyr.consent.dto.ConsentEventRequest
import com.complyr.site.SiteRepository
import com.complyr.site.SiteStatus
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/** The audit actions a consent event can record. String form matches the widget payload. */
enum class ConsentAction(
    val clientValue: String,
) {
    ACCEPT_ALL("accept_all"),
    REJECT_ALL("reject_all"),
    CUSTOM("custom"),
    ;

    companion object {
        fun fromClient(value: String): ConsentAction? = entries.firstOrNull { it.clientValue == value }
    }
}

/** Unknown or archived site key on a public widget request — 404, no enumeration risk (keys are public). */
class UnknownSiteException : ApiException(HttpStatus.NOT_FOUND, code = "SITE_NOT_FOUND", message = "Unknown site key")

/** The consent payload parsed past bean validation but failed a value-level check (client-safe message). */
class InvalidConsentPayloadException(
    message: String,
) : ApiException(HttpStatus.BAD_REQUEST, code = "INVALID_CONSENT_PAYLOAD", message = message)

/**
 * A consent request carried an origin token that was malformed, expired, signed for a different site
 * key, or minted for a different origin — 400. Only ever thrown when a token is PRESENT: a tokenless
 * request is always recorded, so this can never drop legitimate audit evidence (see [ConsentOriginToken]).
 */
class InvalidConsentTokenException :
    ApiException(HttpStatus.BAD_REQUEST, code = "INVALID_CONSENT_TOKEN", message = "Invalid or expired consent token")

/**
 * The token-mint endpoint received a site-key path segment longer than any real key — 400, nothing is
 * signed. Distinct from [InvalidConsentTokenException] (which is a consent-path concept): the mint path
 * issues tokens and receives none, so a bad *request* here is not a bad *token*.
 */
class MalformedSiteKeyException : ApiException(HttpStatus.BAD_REQUEST, code = "INVALID_SITE_KEY", message = "Malformed site key")

/** Request-scoped network metadata, kept out of the DTO so it can never come from the request body. */
data class ConsentRequestMeta(
    val clientIp: String?,
    val userAgent: String?,
    /** The request's `Origin` header, verified against a present origin token; null when absent. */
    val origin: String? = null,
)

/**
 * Records visitor consent choices as append-only audit evidence (CLAUDE.md constraint #3).
 * Every event is a fresh INSERT — the service never reads-then-updates a prior row.
 *
 * Privacy (constraint #4): the raw IP is one-way hashed with a rotating salt ([IpHasher]) and
 * the user agent is length-trimmed before persistence; neither the raw IP nor the full UA is
 * stored or logged. The durable per-visitor link is the cookie-minted [ConsentEventRequest.vid].
 */
@Service
class ConsentService(
    private val siteRepository: SiteRepository,
    private val consentEventRepository: ConsentEventRepository,
    private val consentIdempotencyRepository: ConsentIdempotencyRepository,
    private val bannerConfigService: BannerConfigService,
    private val ipHasher: IpHasher,
    private val consentOriginToken: ConsentOriginToken,
    private val clock: Clock,
) {
    @Transactional
    fun record(
        request: ConsentEventRequest,
        meta: ConsentRequestMeta,
    ) {
        verifyOriginToken(request, meta)

        val site =
            siteRepository.findBySiteKeyAndStatus(request.siteKey, SiteStatus.ACTIVE)
                ?: throw UnknownSiteException()
        val action =
            ConsentAction.fromClient(request.action)
                ?: throw InvalidConsentPayloadException("Unsupported action")
        val categories = validateCategories(request.categories, categoryRules(site.id))

        // De-dupe replayed widget retries: claim the client idempotency key before writing.
        // A losing claim means this exact event is already on file — skip the second append.
        // Runs after validation so only legitimate payloads ever consume a key; the shared
        // transaction rolls the claim back if the consent insert below fails.
        if (!claimIdempotencyKey(request.eventKey)) return

        consentEventRepository.save(
            ConsentEventEntity(
                siteId = site.id,
                visitorId = resolveVisitorId(request.vid),
                action = action.clientValue,
                categories = categories,
                // Recorded verbatim by design (D3): the version reflects the banner/policy the visitor
                // actually saw — possibly a cached one lagging a fresh republish — so it is honest audit
                // metadata, not something to overwrite with the server's current version. Only the
                // category set is server-validated above.
                bannerVersion = request.bannerVersion,
                policyVersion = request.policyVersion,
                lang = normalizeLang(request.lang),
                ipHash = ipHasher.hash(meta.clientIp),
                ua = trimUserAgent(meta.userAgent),
                createdAt = clock.instant(),
            ),
        )
    }

    /**
     * Optional anti-replay control: enforced only when a NON-BLANK token is present, so a tokenless
     * post (old widget, privacy browser, delayed localStorage retry) — or a blank/whitespace token a
     * proxy or serializer might emit — still records. Losing audit evidence is worse than the residual
     * that an attacker can mint-then-forge within the TTL. Throws only for a present, malformed token.
     */
    private fun verifyOriginToken(
        request: ConsentEventRequest,
        meta: ConsentRequestMeta,
    ) {
        request.originToken?.takeIf { it.isNotBlank() }?.let { token ->
            if (!consentOriginToken.isValid(token, request.siteKey, meta.origin)) {
                throw InvalidConsentTokenException()
            }
        }
    }

    /**
     * Reserve the client idempotency key; false only when it was already used (a replayed retry
     * to skip). Absent or malformed keys are not de-duped — the event is still recorded, since a
     * rare duplicate is a lesser evil than dropping audit evidence over a missing/garbled key.
     */
    private fun claimIdempotencyKey(eventKey: String?): Boolean {
        val key = eventKey?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return true
        return consentIdempotencyRepository.claim(key) == 1
    }

    /** Reuse the visitor's cookie id when it is a valid UUID; otherwise mint a fresh one. */
    private fun resolveVisitorId(vid: String?): UUID =
        vid
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: UUID.randomUUID()

    /** The allow-list and mandatory categories a consent payload is validated against. */
    private data class CategoryRules(
        val allowed: Set<String>,
        val required: Set<String>,
    )

    /**
     * The category rules a consent event is validated against: derived from the site's current
     * published banner config, falling back to the full [ConsentCategory] taxonomy when no config
     * is published yet (e.g. a race during site creation). This is the D3 anti-forgery source —
     * a tampered widget cannot invent categories the site never offered, nor reject a mandatory one.
     */
    private fun categoryRules(siteId: UUID): CategoryRules {
        val configured = bannerConfigService.currentPublished(siteId)?.config?.categories
        if (configured != null) {
            return CategoryRules(
                allowed = configured.map { it.key }.toSet(),
                required = configured.filter { it.required }.map { it.key }.toSet(),
            )
        }
        return CategoryRules(
            allowed = ConsentCategory.KEYS,
            required =
                ConsentCategory.entries
                    .filter { it.required }
                    .map { it.key }
                    .toSet(),
        )
    }

    private fun validateCategories(
        categories: Map<String, Boolean>,
        rules: CategoryRules,
    ): Map<String, Boolean> {
        categoryViolation(categories, rules)?.let { throw InvalidConsentPayloadException(it) }
        return categories
    }

    /** The first D3 rule the payload breaks, or null if it is valid audit evidence. */
    private fun categoryViolation(
        categories: Map<String, Boolean>,
        rules: CategoryRules,
    ): String? =
        when {
            categories.keys.any { it.length > ConsentEventRequest.MAX_CATEGORY_KEY_LENGTH } -> "Category key too long"
            (categories.keys - rules.allowed).isNotEmpty() -> "Unknown consent category"
            // GDPR invariant (ConsentCategory.required): strictly-necessary categories can never be
            // rejected — a forged payload that omits or denies one is not valid audit evidence.
            rules.required.any { categories[it] != true } -> "Required category cannot be rejected"
            else -> null
        }

    private fun normalizeLang(lang: String?): String? = lang?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    private fun trimUserAgent(userAgent: String?): String? = userAgent?.trim()?.take(MAX_UA_LENGTH)?.takeIf { it.isNotEmpty() }

    private companion object {
        const val MAX_UA_LENGTH = 256
    }
}
