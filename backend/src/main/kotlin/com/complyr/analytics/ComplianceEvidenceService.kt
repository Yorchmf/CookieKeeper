package com.complyr.analytics

import com.complyr.auth.UserRepository
import com.complyr.billing.EntitlementService
import com.complyr.common.UnauthenticatedException
import com.complyr.site.SiteNotFoundException
import com.complyr.site.SiteRepository
import org.springframework.stereotype.Service
import java.io.OutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Prepares a site's **compliance evidence pack** (Track 4): a single ZIP a customer can hand a regulator or
 * DPO, bundling the published cookie policy, the trailing 30 days of consent audit evidence, the latest
 * scan's compliance summary, and a manifest tying them together.
 *
 * Business-plan feature, gated on the same [EntitlementService.requireCsvExport] as the consent-log CSV it
 * embeds — a plan that cannot export the consent log cannot export a pack built around it.
 *
 * This service owns only the *gates and identity*; [EvidencePackAssembler] owns the ZIP assembly. [prepare]
 * resolves entitlement (403) then ownership (404), loads and tombstone-checks the account, and stamps
 * `bundledAt` **eagerly**, returning a [PreparedEvidencePack] whose [PreparedEvidencePack.write] streams the
 * pack later. That keeps the two invariants a streamed download needs: a denial is decided before the first
 * byte (a clean JSON error, not a truncated 200), and the filename's timestamp is the same instant the
 * manifest reports.
 *
 * Purely a read: it never touches the consent-ingestion path (CLAUDE.md #3) and never mutates.
 */
@Service
class ComplianceEvidenceService(
    private val siteRepository: SiteRepository,
    private val userRepository: UserRepository,
    private val entitlementService: EntitlementService,
    private val assembler: EvidencePackAssembler,
    private val clock: Clock,
) {
    /**
     * Resolve the gates + account and stamp the bundle instant, returning the download's filename and a
     * deferred writer. Entitlement is checked before ownership so a non-Business account gets a 403 rather
     * than an ownership probe. The account is loaded and tombstone-checked here (not mid-stream) so an erased
     * account is rejected before any byte flushes — mirrors [com.complyr.account.AccountExportService].
     */
    fun prepare(
        userId: UUID,
        siteId: UUID,
    ): PreparedEvidencePack {
        entitlementService.requireCsvExport(userId)
        val site = siteRepository.findByIdAndUserId(siteId, userId) ?: throw SiteNotFoundException()
        val user = userRepository.findById(userId).orElseThrow { UnauthenticatedException() }
        // An erased account is a tombstone with no evidence to bundle; a still-valid access token must not
        // reach it (ADR-20). Reject rather than emit the synthetic values erasure wrote.
        if (user.isErased) throw UnauthenticatedException()
        val now = clock.instant()
        return PreparedEvidencePack(fileName(site.domain, now)) { out -> assembler.write(user, site, now, out) }
    }

    /**
     * `evidence-pack-{domain}-{yyyyMMdd-HHmmss}.zip`. The domain is reduced to `[A-Za-z0-9.-]` so nothing in
     * it can break out of the quoted `Content-Disposition` filename, and the UTC stamp avoids ':' (illegal in
     * Windows filenames).
     */
    private fun fileName(
        domain: String,
        at: Instant,
    ): String {
        val safeDomain = domain.replace(UNSAFE_FILENAME_CHARS, "-")
        return "evidence-pack-$safeDomain-${FILENAME_STAMP.format(at)}.zip"
    }

    companion object {
        private val FILENAME_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
        private val UNSAFE_FILENAME_CHARS = Regex("[^A-Za-z0-9.-]")
    }
}

/**
 * A resolved evidence pack: the download [filename] (fixed at [ComplianceEvidenceService.prepare] time, from
 * the same instant the manifest reports) and a [write] that assembles the ZIP into the response stream when
 * invoked. Split this way so the controller sets headers and streams the body without re-reading the clock or
 * re-checking the gates.
 */
data class PreparedEvidencePack(
    val filename: String,
    val write: (OutputStream) -> Unit,
)
