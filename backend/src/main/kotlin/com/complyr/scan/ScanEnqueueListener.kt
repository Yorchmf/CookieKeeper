package com.complyr.scan

import com.complyr.site.SiteCreatedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Enqueues a site's first scan when it is created. A plain (non-`@TransactionalEventListener`)
 * synchronous listener on purpose: it runs inside the publishing site-creation transaction, so the
 * scan+job rows commit or roll back atomically with the site (ADR-4's transactional-enqueue win) —
 * unlike the auth email listener, whose side effect must fire only after commit.
 */
@Component
class ScanEnqueueListener(
    private val scanQueue: ScanQueue,
    private val clock: Clock,
) {
    @EventListener
    fun onSiteCreated(event: SiteCreatedEvent) {
        // Claimable immediately: the first scan is what the customer is watching for right after signup.
        scanQueue.enqueue(event.siteId, ScanTrigger.SITE_ADDED, clock.instant())
    }
}
