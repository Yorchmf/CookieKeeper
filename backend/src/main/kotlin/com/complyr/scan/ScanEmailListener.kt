package com.complyr.scan

import com.complyr.common.AsyncConfig
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Dispatches the scan-complete email AFTER the terminal scan transaction commits and asynchronously
 * on the shared mail executor. [ScanCompletionNotifier] guarantees failures never propagate, so a
 * broken mail provider can only ever cost a log line — never a re-crawled site.
 *
 * AFTER_COMMIT is also what makes the notifier's reads correct: the `done` status, the page/tracker
 * counts and the `scan_cookies` rows (written during the crawl, before the transition) are all
 * visible to the fresh transactions the notifier's repository calls open.
 */
@Component
class ScanEmailListener(
    private val notifier: ScanCompletionNotifier,
) {
    @Async(AsyncConfig.EMAIL_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onScanCompleted(event: ScanCompleted) {
        notifier.sendScanCompleted(event.scanId, event.siteId, event.trigger)
    }
}
