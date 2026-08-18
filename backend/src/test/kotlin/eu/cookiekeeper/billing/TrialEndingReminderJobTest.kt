package eu.cookiekeeper.billing

import eu.cookiekeeper.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The trial-ending reminder against real Postgres. The trial has no end-date column — it is derived
 * from `users.created_at + trial-period` — so the thing worth testing is that the job's inverted
 * `created_at` window really does select exactly the accounts whose derived end lands in the lead
 * window, and nobody else.
 *
 * Asserted on the `trial_ending_email_sent_at` marker rather than on a delivered email: the marker IS
 * the send-once guarantee (the email event is published only when the compare-and-set wins), and it
 * commits before the async listener ever runs.
 *
 * A 14-day trial with a 3-day lead is pinned here rather than inherited from defaults, so a future
 * change to the shipped trial length can't silently invert what these dates mean. The cron is disabled
 * so only the explicit calls below run. Other test classes leave users behind in this shared database;
 * every assertion is scoped to a user id seeded by this test, never to a global count.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@TestPropertySource(
    properties = [
        "cookiekeeper.billing.trial-reminder-cron=-",
        "cookiekeeper.billing.trial-period=14d",
        "cookiekeeper.billing.trial-reminder-lead-time=3d",
    ],
)
class TrialEndingReminderJobTest {
    @Autowired
    private lateinit var job: TrialEndingReminderJob

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `an account whose trial ends inside the lead window is reminded`() {
        // Signed up 12 days into a 14-day trial: 2 days left, inside the 3-day lead.
        val userId = insertUser(signedUpDaysAgo = 12)

        job.sendDueTrialReminders()

        assertNotNull(remindedAt(userId), "an account 2 days from lapsing is due for its reminder")
    }

    @Test
    fun `an account whose trial ends beyond the lead window is left alone`() {
        // 9 days left — the nudge would arrive so early it reads as a dunning email, not a heads-up.
        val userId = insertUser(signedUpDaysAgo = 5)

        job.sendDueTrialReminders()

        assertNull(remindedAt(userId), "9 days out is outside the 3-day lead window")
    }

    /**
     * The exclusive lower bound. Reminding someone their trial ends "today, in the past" is worse than
     * saying nothing — they already hit the frozen dashboard.
     */
    @Test
    fun `an account whose trial already lapsed is never reminded`() {
        val userId = insertUser(signedUpDaysAgo = 20)

        job.sendDueTrialReminders()

        assertNull(remindedAt(userId), "a lapsed trial gets no ending-soon reminder")
    }

    @Test
    fun `an account that already subscribed is not reminded`() {
        val userId = insertUser(signedUpDaysAgo = 12)
        insertSubscription(userId, status = "active")

        job.sendDueTrialReminders()

        assertNull(remindedAt(userId), "someone who already paid must not be asked to choose a plan")
    }

    @Test
    fun `an account still inside Stripe's own trial is not reminded`() {
        val userId = insertUser(signedUpDaysAgo = 12)
        insertSubscription(userId, status = "trialing")

        job.sendDueTrialReminders()

        assertNull(remindedAt(userId), "'trialing' is an active subscription — the card is already on file")
    }

    /**
     * An unverified signup never activated the product, so a "your trial is ending" mail to that address
     * is unsolicited mail to someone who never confirmed they wanted any.
     */
    @Test
    fun `an account that never verified its email is not reminded`() {
        val userId = insertUser(signedUpDaysAgo = 12, verified = false)

        job.sendDueTrialReminders()

        assertNull(remindedAt(userId), "unverified signups are excluded")
    }

    @Test
    fun `an account already reminded is not reminded again`() {
        val alreadySentAt = Instant.now().minus(Duration.ofDays(1))
        val userId = insertUser(signedUpDaysAgo = 12, remindedAt = alreadySentAt)

        job.sendDueTrialReminders()

        assertEquals(
            alreadySentAt.epochSecond,
            remindedAt(userId)?.epochSecond,
            "the existing marker must be left untouched — the reminder goes out at most once",
        )
    }

    /** Two runs the same night (a redeploy, an overlapping replica) must still be one reminder. */
    @Test
    fun `two runs mark the account exactly once`() {
        val userId = insertUser(signedUpDaysAgo = 12)

        job.sendDueTrialReminders()
        val afterFirstRun = remindedAt(userId)
        job.sendDueTrialReminders()

        assertNotNull(afterFirstRun, "the first run should claim the reminder")
        assertEquals(
            afterFirstRun.epochSecond,
            remindedAt(userId)?.epochSecond,
            "the second run's compare-and-set finds a non-null marker and does nothing",
        )
    }

    private fun insertUser(
        signedUpDaysAgo: Long,
        verified: Boolean = true,
        remindedAt: Instant? = null,
    ): UUID {
        val id = UUID.randomUUID()
        val createdAt = Instant.now().minus(Duration.ofDays(signedUpDaysAgo))
        jdbcTemplate.update(
            """
            INSERT INTO users (id, email, password_hash, created_at, verified_at, trial_ending_email_sent_at)
            VALUES (?, ?, 'x', ?, ?, ?)
            """.trimIndent(),
            id,
            "trial-$id@example.com",
            at(createdAt),
            if (verified) at(createdAt) else null,
            remindedAt?.let(::at),
        )
        return id
    }

    private fun insertSubscription(
        userId: UUID,
        status: String,
    ) {
        jdbcTemplate.update(
            "INSERT INTO subscriptions (id, user_id, plan, status) VALUES (?, ?, ?, ?)",
            UUID.randomUUID(),
            userId,
            Plan.PRO.name,
            status,
        )
    }

    private fun remindedAt(userId: UUID): Instant? =
        jdbcTemplate
            .queryForObject(
                "SELECT trial_ending_email_sent_at FROM users WHERE id = ?",
                OffsetDateTime::class.java,
                userId,
            )?.toInstant()

    private fun at(instant: Instant): OffsetDateTime = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)
}
