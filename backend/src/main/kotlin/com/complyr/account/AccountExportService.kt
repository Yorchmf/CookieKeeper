package com.complyr.account

import com.complyr.account.dto.AccountExport
import com.complyr.account.dto.AccountSection
import com.complyr.account.dto.SubscriptionSection
import com.complyr.auth.UserRepository
import com.complyr.billing.SubscriptionRepository
import com.complyr.common.UnauthenticatedException
import com.complyr.site.SiteRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * "Export my data" — GDPR Art. 20 data portability (ADR-20).
 *
 * Assembles [AccountExport] in one read-only transaction so the document is a consistent snapshot rather
 * than a set of reads racing a concurrent scan or policy generation. Fully materialized in memory: the
 * per-site scan cap in [SiteExportAssembler] is what keeps that bounded, and the endpoint is rate-limited
 * like every other authenticated route.
 *
 * See [AccountExport]'s docs for what is deliberately left out and why.
 */
@Service
class AccountExportService(
    private val userRepository: UserRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val siteRepository: SiteRepository,
    private val siteExportAssembler: SiteExportAssembler,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun export(userId: UUID): AccountExport {
        val user = userRepository.findById(userId).orElseThrow { UnauthenticatedException() }
        // An erased account has nothing left to port; treat the tombstone as a non-existent user rather
        // than handing back the synthetic values the erasure wrote (see AccountDeletionService).
        if (user.isErased) throw UnauthenticatedException()

        val allSites = siteRepository.findAllByUserId(userId)
        val subscription =
            subscriptionRepository.findByUserId(userId)?.let {
                SubscriptionSection(
                    plan = it.plan.name,
                    status = it.status,
                    periodEnd = it.periodEnd,
                    createdAt = it.createdAt,
                )
            }

        return AccountExport(
            exportedAt = clock.instant(),
            account =
                AccountSection(
                    id = user.id,
                    email = user.email,
                    name = user.name,
                    locale = user.locale,
                    createdAt = user.createdAt,
                    verifiedAt = user.verifiedAt,
                ),
            subscription = subscription,
            sites =
                allSites
                    .sortedBy { it.createdAt }
                    .take(MAX_SITES)
                    .map(siteExportAssembler::assemble),
            siteCount = allSites.size,
        )
    }

    private companion object {
        /**
         * Oldest-first cap on the exported sites, mirroring [SiteExportAssembler]'s per-scan cap.
         *
         * The plan's site limit is a create-time predicate counted over ACTIVE sites only, so an account
         * that repeatedly creates and archives can accumulate site rows without bound — and each one costs
         * this export several queries and a fully materialized subtree. `siteCount` reports the real total
         * so the truncation is never silent; a customer past this cap has a portability claim we serve
         * from support rather than a self-service endpoint.
         */
        const val MAX_SITES = 200
    }
}
