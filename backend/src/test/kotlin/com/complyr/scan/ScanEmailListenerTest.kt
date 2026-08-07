package com.complyr.scan

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID

class ScanEmailListenerTest {
    private val notifier = mockk<ScanCompletionNotifier>()
    private val listener = ScanEmailListener(notifier)

    private val scanId: UUID = UUID.randomUUID()
    private val siteId: UUID = UUID.randomUUID()

    /**
     * The trigger is forwarded rather than re-read: it is a fact about why this scan ran, and
     * [ScanCompletionNotifier] needs it to make the send/skip decision.
     */
    @Test
    fun `scan-completed events are dispatched to the notifier with their trigger`() {
        every { notifier.sendScanCompleted(scanId, siteId, ScanTrigger.SCHEDULED) } just runs

        listener.onScanCompleted(ScanCompleted(scanId, siteId, ScanTrigger.SCHEDULED))

        verify(exactly = 1) { notifier.sendScanCompleted(scanId, siteId, ScanTrigger.SCHEDULED) }
    }
}
