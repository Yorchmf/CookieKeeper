package com.complyr.account

import com.complyr.auth.UserEntity
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/**
 * The site-side half of an Art. 17 account erasure (ADR-20): everything hanging off the account's
 * `sites` rows, plus the sites themselves.
 *
 * All statements are native bulk DML keyed on `:userId` — no entity is loaded, so the erasure cost is
 * independent of how much the account accumulated. Children are deleted EXPLICITLY rather than left to
 * the `ON DELETE CASCADE` rules, because a GDPR erasure path must state what it removes instead of
 * depending on an implicit schema rule that a later migration could quietly change.
 *
 * The order below is the dependency order and must be preserved: jobs reference a scan by id inside
 * their JSON payload (no FK), scan cookies reference scans, everything else references the site.
 *
 * Split from [AccountIdentityErasureRepository] only to keep each interface small; both are driven by
 * [AccountDeletionService] inside one transaction.
 */
interface AccountSiteErasureRepository : Repository<UserEntity, UUID> {
    /**
     * The same transaction-scoped per-user advisory lock the site-create cap guard takes
     * ([com.complyr.site.SiteRepository.acquireUserSiteLock], and through it
     * [com.complyr.billing.EntitlementService.requireCanAddSite]) — declared again here so the erasure
     * does not have to pull in the whole `SiteRepository` for one call.
     *
     * The key MUST be folded from the user id identically on both sides or the two would take locks in
     * different key spaces and never serialize, which is the entire point: without it a site creation can
     * commit an ACTIVE site moments after this erasure swept the table, leaving a live site owned by a
     * tombstone that no dashboard can reach and no second erasure will ever find.
     */
    @Query(
        value = "SELECT count(*) FROM (SELECT pg_advisory_xact_lock(:key)) AS _lock",
        nativeQuery = true,
    )
    fun acquireUserSiteLock(
        @Param("key") key: Long,
    ): Long

    /**
     * Queue rows for the account's scans. `jobs` carries NO foreign key — it references its scan through
     * `payload_jsonb->>'scanId'` — so a cascade would never reach these and they would linger as orphans
     * the worker then dead-letters. The scan id is cast to text (never the other direction): a malformed
     * payload value must not raise a cast error, it must simply fail to match.
     */
    @Modifying
    @Query(
        value = """
            DELETE FROM jobs j
            USING scans sc, sites s
            WHERE sc.id::text = j.payload_jsonb ->> 'scanId'
              AND sc.site_id = s.id
              AND s.user_id = :userId
        """,
        nativeQuery = true,
    )
    fun deleteJobs(
        @Param("userId") userId: UUID,
    ): Int

    @Modifying
    @Query(
        value = """
            DELETE FROM scan_cookies c
            USING scans sc, sites s
            WHERE c.scan_id = sc.id AND sc.site_id = s.id AND s.user_id = :userId
        """,
        nativeQuery = true,
    )
    fun deleteScanCookies(
        @Param("userId") userId: UUID,
    ): Int

    @Modifying
    @Query(
        value = "DELETE FROM scans sc USING sites s WHERE sc.site_id = s.id AND s.user_id = :userId",
        nativeQuery = true,
    )
    fun deleteScans(
        @Param("userId") userId: UUID,
    ): Int

    @Modifying
    @Query(
        value = "DELETE FROM cookie_overrides o USING sites s WHERE o.site_id = s.id AND s.user_id = :userId",
        nativeQuery = true,
    )
    fun deleteCookieOverrides(
        @Param("userId") userId: UUID,
    ): Int

    @Modifying
    @Query(
        value = "DELETE FROM policies p USING sites s WHERE p.site_id = s.id AND s.user_id = :userId",
        nativeQuery = true,
    )
    fun deletePolicies(
        @Param("userId") userId: UUID,
    ): Int

    /** Also destroys the `public_id` that addressed the hosted `/p/{publicId}` page, so it 404s at once. */
    @Modifying
    @Query(
        value = "DELETE FROM policy_settings ps USING sites s WHERE ps.site_id = s.id AND s.user_id = :userId",
        nativeQuery = true,
    )
    fun deletePolicySettings(
        @Param("userId") userId: UUID,
    ): Int

    @Modifying
    @Query(
        value = "DELETE FROM banner_configs b USING sites s WHERE b.site_id = s.id AND s.user_id = :userId",
        nativeQuery = true,
    )
    fun deleteBannerConfigs(
        @Param("userId") userId: UUID,
    ): Int

    /**
     * Sites that never recorded a consent event are removed outright — an account that never served a
     * banner must leave nothing behind at all. Expressed as a correlated `NOT EXISTS` rather than a
     * Kotlin-side id list so it stays one statement and can never degenerate into an empty `IN ()`.
     */
    @Modifying
    @Query(
        value = """
            DELETE FROM sites s
            WHERE s.user_id = :userId
              AND NOT EXISTS (SELECT 1 FROM consent_events e WHERE e.site_id = s.id)
        """,
        nativeQuery = true,
    )
    fun deleteSitesWithoutConsentEvidence(
        @Param("userId") userId: UUID,
    ): Int

    /**
     * Strips every surviving site — i.e. exactly those still holding consent evidence — to a tombstone.
     * What is left is derived from the site's own random UUID and carries no customer data: the domain
     * and site key are destroyed, verification is cleared (both columns together, as
     * `ck_sites_verification_method_pairs` requires), and the row is archived so no read path serves it
     * (`findBySiteKeyAndStatus`/`findRescanCandidates` are ACTIVE-only, so the widget stops resolving and
     * no NEW consent event can ever be attributed to it).
     *
     * The row exists solely to satisfy `fk_consent_events_site` (ON DELETE RESTRICT); the evidence itself
     * ages out on the tenant-blind 3-year partition schedule (ADR-16). `uq_sites_user_domain_active` is
     * partial on `status = 'active'`, so archived tombstones never collide with each other.
     */
    @Modifying
    @Query(
        value = """
            UPDATE sites
            SET domain               = 'erased-' || id::text,
                site_key             = 'erased_' || replace(id::text, '-', ''),
                status               = 'archived',
                verified_at          = NULL,
                verification_method  = NULL,
                plan_limits_snapshot = NULL,
                updated_at           = :now
            WHERE user_id = :userId
        """,
        nativeQuery = true,
    )
    fun tombstoneRemainingSites(
        @Param("userId") userId: UUID,
        @Param("now") now: Instant,
    ): Int
}

/**
 * The identity-side half of an Art. 17 account erasure (ADR-20): rows keyed directly on the user.
 *
 * These all have `ON DELETE CASCADE` from `users`, but the `users` row itself SURVIVES as a tombstone
 * (the consent-bearing sites still reference it), so nothing cascades and each must be deleted here.
 */
interface AccountIdentityErasureRepository : Repository<UserEntity, UUID> {
    /** Outstanding email-verification / password-reset links. */
    @Modifying
    @Query(value = "DELETE FROM auth_tokens WHERE user_id = :userId", nativeQuery = true)
    fun deleteAuthTokens(
        @Param("userId") userId: UUID,
    ): Int

    /**
     * Every refresh token, which is what actually ends the account's live sessions everywhere. The
     * self-referencing `rotated_from` FK is ON DELETE SET NULL, so deleting a whole rotation chain in one
     * statement is safe regardless of the order rows come out.
     */
    @Modifying
    @Query(value = "DELETE FROM refresh_tokens WHERE user_id = :userId", nativeQuery = true)
    fun deleteRefreshTokens(
        @Param("userId") userId: UUID,
    ): Int

    /**
     * The account's email notification preferences (V25). Its FK to `users` is ON DELETE CASCADE, but the
     * erasure tombstones the `users` row rather than deleting it, so the cascade never fires — the row is
     * removed explicitly here, in keeping with this path stating exactly what it erases. The row holds no
     * PII (booleans keyed by user id), so this is tidiness rather than a leak fix.
     */
    @Modifying
    @Query(value = "DELETE FROM notification_preferences WHERE user_id = :userId", nativeQuery = true)
    fun deleteNotificationPreferences(
        @Param("userId") userId: UUID,
    ): Int

    /**
     * The local mirror of the Stripe subscription, including the Stripe customer/subscription ids. The
     * subscription is cancelled at Stripe BEFORE this transaction opens (see [AccountDeletionService]) —
     * dropping the row first would lose the ids and leave a deleted account silently still billed.
     */
    @Modifying
    @Query(value = "DELETE FROM subscriptions WHERE user_id = :userId", nativeQuery = true)
    fun deleteSubscriptions(
        @Param("userId") userId: UUID,
    ): Int

    /**
     * Anonymous free-scan leads captured under the same email address (ADR-12). These rows are NOT linked
     * to the account — the funnel runs before signup — so nothing else would ever reach them; email is the
     * only link we have, and it is the same data subject as far as we can know. Cookies cascade. They also
     * carry a 7-day TTL, so this only shortens a horizon that was already short.
     */
    @Modifying
    @Query(value = "DELETE FROM public_scans WHERE email = :email", nativeQuery = true)
    fun deletePublicScanLeads(
        @Param("email") email: String,
    ): Int

    /**
     * Nulls the raw body of any still-unprocessed Stripe webhook mentioning [handle] (the account's email,
     * Stripe customer id, or subscription id) and stamps it processed, so the erasure leaves no verbatim
     * `customer_email` behind in the webhook inbox. Rows already processed were redacted when they were
     * applied (V13) and are skipped by the `payload IS NOT NULL` predicate.
     *
     * `position(… in payload)` rather than `LIKE`: an email address may legitimately contain `_` and `%`,
     * which `LIKE` would read as wildcards. There is no index for this — `stripe_events` holds only the
     * short unprocessed tail plus a retention window, and this runs once per account erasure, never on a
     * request path.
     */
    @Modifying
    @Query(
        value = """
            UPDATE stripe_events
            SET payload = NULL, processed_at = :now
            WHERE processed_at IS NULL
              AND payload IS NOT NULL
              AND position(:handle IN payload) > 0
        """,
        nativeQuery = true,
    )
    fun redactPendingStripeEvents(
        @Param("handle") handle: String,
        @Param("now") now: Instant,
    ): Int
}
