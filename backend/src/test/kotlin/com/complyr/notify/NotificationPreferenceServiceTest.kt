package com.complyr.notify

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The read/write rules for notification preferences. The load-bearing behaviour is the missing-row case:
 * an account that never touched the settings page must read as all-on ([NotificationPreferences.DEFAULT])
 * and must NOT have a row conjured for it on read, so the common path stays a single indexed miss.
 */
class NotificationPreferenceServiceTest {
    private val repository = mockk<NotificationPreferenceRepository>()
    private val now: Instant = Instant.parse("2026-08-13T10:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val service = NotificationPreferenceService(repository, clock)

    private val userId: UUID = UUID.randomUUID()

    // ---- get ---------------------------------------------------------------------------------

    @Test
    fun `an account with no row reads as the all-on default`() {
        every { repository.findByUserId(userId) } returns Optional.empty()

        assertEquals(NotificationPreferences.DEFAULT, service.get(userId))
    }

    @Test
    fun `get never creates a row`() {
        every { repository.findByUserId(userId) } returns Optional.empty()

        service.get(userId)

        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `get returns the stored values when a row exists`() {
        every { repository.findByUserId(userId) } returns
            Optional.of(row(scanComplete = false, scanChanges = true))

        val preferences = service.get(userId)

        assertFalse(preferences.scanComplete)
        assertTrue(preferences.scanChanges)
    }

    // ---- update ------------------------------------------------------------------------------

    @Test
    fun `the first change materializes a new row stamped with both timestamps`() {
        every { repository.findByUserId(userId) } returns Optional.empty()
        val saved = slot<NotificationPreferenceEntity>()
        every { repository.save(capture(saved)) } answers { saved.captured }

        val result = service.update(userId, NotificationPreferences(scanComplete = false, scanChanges = true))

        assertEquals(userId, saved.captured.userId)
        assertFalse(saved.captured.scanComplete)
        assertTrue(saved.captured.scanChanges)
        assertEquals(now, saved.captured.createdAt, "a first write stamps createdAt")
        assertEquals(now, saved.captured.updatedAt)
        assertEquals(NotificationPreferences(scanComplete = false, scanChanges = true), result)
    }

    @Test
    fun `a later change updates the existing row and preserves its createdAt`() {
        val created = now.minusSeconds(3_600)
        every { repository.findByUserId(userId) } returns
            Optional.of(row(scanComplete = true, scanChanges = true, createdAt = created, updatedAt = created))
        val saved = slot<NotificationPreferenceEntity>()
        every { repository.save(capture(saved)) } answers { saved.captured }

        service.update(userId, NotificationPreferences(scanComplete = true, scanChanges = false))

        assertTrue(saved.captured.scanComplete)
        assertFalse(saved.captured.scanChanges)
        assertEquals(created, saved.captured.createdAt, "an update must not move createdAt")
        assertEquals(now, saved.captured.updatedAt, "an update stamps updatedAt with the clock")
    }

    private fun row(
        scanComplete: Boolean,
        scanChanges: Boolean,
        createdAt: Instant = now,
        updatedAt: Instant = now,
    ) = NotificationPreferenceEntity(
        userId = userId,
        scanComplete = scanComplete,
        scanChanges = scanChanges,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
