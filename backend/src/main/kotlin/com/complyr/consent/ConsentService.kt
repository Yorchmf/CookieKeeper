package com.complyr.consent

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

/** Request-scoped network metadata, kept out of the DTO so it can never come from the request body. */
data class ConsentRequestMeta(
    val clientIp: String?,
    val userAgent: String?,
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
    private val ipHasher: IpHasher,
    private val clock: Clock,
) {
    @Transactional
    fun record(
        request: ConsentEventRequest,
        meta: ConsentRequestMeta,
    ) {
        val site =
            siteRepository.findBySiteKeyAndStatus(request.siteKey, SiteStatus.ACTIVE)
                ?: throw UnknownSiteException()
        val action =
            ConsentAction.fromClient(request.action)
                ?: throw InvalidConsentPayloadException("Unsupported action")

        consentEventRepository.save(
            ConsentEventEntity(
                siteId = site.id,
                visitorId = resolveVisitorId(request.vid),
                action = action.clientValue,
                categories = sanitizeCategories(request.categories),
                bannerVersion = request.bannerVersion,
                policyVersion = request.policyVersion,
                lang = normalizeLang(request.lang),
                ipHash = ipHasher.hash(meta.clientIp),
                ua = trimUserAgent(meta.userAgent),
                createdAt = clock.instant(),
            ),
        )
    }

    /** Reuse the visitor's cookie id when it is a valid UUID; otherwise mint a fresh one. */
    private fun resolveVisitorId(vid: String?): UUID =
        vid
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: UUID.randomUUID()

    private fun sanitizeCategories(categories: Map<String, Boolean>): Map<String, Boolean> {
        if (categories.keys.any { it.length > ConsentEventRequest.MAX_CATEGORY_KEY_LENGTH }) {
            throw InvalidConsentPayloadException("Category key too long")
        }
        return categories
    }

    private fun normalizeLang(lang: String?): String? = lang?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    private fun trimUserAgent(userAgent: String?): String? = userAgent?.trim()?.take(MAX_UA_LENGTH)?.takeIf { it.isNotEmpty() }

    private companion object {
        const val MAX_UA_LENGTH = 256
    }
}
