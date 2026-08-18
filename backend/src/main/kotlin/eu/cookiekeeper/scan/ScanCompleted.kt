package eu.cookiekeeper.scan

import java.util.UUID

/**
 * Published by [ScanQueue.markSucceeded] when a crawl reaches `done`. Like the auth and billing
 * emails, delivery happens AFTER the completion transaction commits and on the shared mail executor
 * ([ScanEmailListener]) — a slow or broken mail provider must never roll back the terminal
 * transition, because a rolled-back `markSucceeded` leaves the job leased and lets the worker
 * re-crawl a site that was already scanned.
 *
 * Carries ids only. The domain, the cookie counts, the recipient address and the recipient locale
 * are all resolved fresh at send time ([ScanCompletionNotifier]) — none of it is captured here.
 * [trigger] is the one exception: it is what the event *means* (why this scan ran), and it decides
 * whether an email is owed at all.
 */
data class ScanCompleted(
    val scanId: UUID,
    val siteId: UUID,
    val trigger: ScanTrigger,
)
