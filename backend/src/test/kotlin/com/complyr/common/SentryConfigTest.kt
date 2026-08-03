package com.complyr.common

import io.sentry.SentryEvent
import io.sentry.protocol.Message
import io.sentry.protocol.Request
import io.sentry.protocol.SentryException
import io.sentry.protocol.User
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for the security-relevant pure logic behind [SentryConfig] — PII scrubbing/redaction and
 * the EU-residency DSN guard. The Sentry SDK init / logback appender wiring is a startup side effect
 * exercised implicitly by the app-context tests; here we pin the behaviours that protect our GDPR
 * obligations (CLAUDE.md #2 residency, #4 no PII).
 */
class SentryConfigTest {
    @Test
    fun `scrubPii removes request and user context before send`() {
        val event =
            SentryEvent().apply {
                request =
                    Request().apply {
                        url = "https://app.complyr.eu/billing?email=owner@example.com"
                        cookies = "session=secret"
                    }
                user =
                    User().apply {
                        email = "owner@example.com"
                        ipAddress = "203.0.113.7"
                    }
            }

        val scrubbed = SentryConfig.scrubPii(event)

        assertSame(event, scrubbed, "scrubPii returns the same event instance (mutated in place)")
        assertNull(scrubbed.request, "HTTP request context (URL query, cookies) must be dropped")
        assertNull(scrubbed.user, "user identity (email, IP) must be dropped")
    }

    @Test
    fun `scrubPii redacts email and token from the message and exception values`() {
        // A log-appender integration builds events from the log message + throwable — the real PII
        // surface. An email in the message and a token-like value in an exception must be redacted.
        val event =
            SentryEvent().apply {
                message = Message().apply { formatted = "checkout failed for owner@example.com" }
                exceptions =
                    listOf(
                        SentryException().apply {
                            value = "Unknown token abcdefghijklmnopqrstuvwxyz012345"
                        },
                    )
            }

        val scrubbed = SentryConfig.scrubPii(event)

        val scrubbedMessage = requireNotNull(scrubbed.message?.formatted)
        assertFalse(scrubbedMessage.contains("owner@example.com"), "email must be redacted from message")
        assertTrue(scrubbedMessage.contains("[redacted-email]"), "email replaced with a marker")
        val scrubbedException = requireNotNull(scrubbed.exceptions?.firstOrNull()?.value)
        assertFalse(
            scrubbedException.contains("abcdefghijklmnopqrstuvwxyz012345"),
            "opaque token must be redacted from the exception value",
        )
    }

    @Test
    fun `scrubPii is null-safe on an event with no message or exceptions`() {
        val scrubbed = SentryConfig.scrubPii(SentryEvent())

        assertNull(scrubbed.request)
        assertNull(scrubbed.user)
    }

    @Test
    fun `requireEuResidency accepts a Sentry EU-region DSN`() {
        // Must not throw — the parsed host is under the EU ingest domain.
        SentryConfig.requireEuResidency("https://examplePublicKey@o0.ingest.de.sentry.io/0")
    }

    @Test
    fun `requireEuResidency rejects a US-region DSN`() {
        assertThrows<IllegalArgumentException> {
            SentryConfig.requireEuResidency("https://examplePublicKey@o0.ingest.us.sentry.io/0")
        }
    }

    @Test
    fun `requireEuResidency rejects a region-less sentry_io DSN`() {
        // The default (US) Sentry ingest host has no region marker — must be refused for EU residency.
        assertThrows<IllegalArgumentException> {
            SentryConfig.requireEuResidency("https://examplePublicKey@o0.ingest.sentry.io/0")
        }
    }

    @Test
    fun `requireEuResidency rejects a DSN with the EU marker only in the userinfo`() {
        // Host is the US ingest endpoint; the EU marker sits in the public-key/userinfo. A substring
        // check would pass this and ship data to the US — the host parse must reject it.
        assertThrows<IllegalArgumentException> {
            SentryConfig.requireEuResidency("https://de.sentry.io@o0.ingest.us.sentry.io/0")
        }
    }

    @Test
    fun `requireEuResidency rejects a spoofed host that merely ends with the EU marker as a prefix`() {
        assertThrows<IllegalArgumentException> {
            SentryConfig.requireEuResidency("https://key@de.sentry.io.attacker.example/0")
        }
    }

    @Test
    fun `requireEuResidency rejects a malformed DSN`() {
        assertThrows<IllegalArgumentException> {
            SentryConfig.requireEuResidency("not-a-valid-dsn")
        }
    }
}
