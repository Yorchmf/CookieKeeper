package eu.cookiekeeper.common

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.logback.SentryAppender
import jakarta.annotation.PreDestroy
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.context.annotation.Configuration
import java.net.URI

/**
 * Wires Sentry error tracking (ADR-15). A deliberately small, framework-agnostic integration: once all
 * singletons exist (so Boot's logging system is fully configured) we [Sentry.init] the SDK from
 * [CookieKeeperProperties.Observability.Sentry] and attach a [SentryAppender] to the Logback root logger, so
 * any unhandled `ERROR`-level log (Spring's uncaught-exception logging, scheduled/async job failures,
 * the scanner worker) is captured as a Sentry event. We do NOT use `sentry-spring-boot-4`, whose Spring
 * Boot 4 tracing needs the OpenTelemetry Java agent — unnecessary weight for MVP error capture.
 *
 * Because this is a *log-appender* integration (not `sentry-spring`), the event fields that carry PII
 * are the ones Logback fills — the formatted message and the throwable chain — NOT `request`/`user`
 * (which this integration never populates). The scrub below is built around that reality.
 *
 * Three invariants, all enforced here:
 *  1. **Off by default.** A blank DSN (local, or any environment without `SENTRY_DSN_BACKEND`) skips
 *     init and the appender entirely — Sentry stays a no-op, nothing is sent.
 *  2. **EU data residency (CLAUDE.md #2).** A configured DSN MUST resolve to a host under Sentry's EU
 *     region (`*.de.sentry.io`); [requireEuResidency] parses the DSN host (ignoring userinfo/path, which
 *     an attacker-shaped DSN could stuff the marker into) and fails startup fast otherwise.
 *  3. **No PII (CLAUDE.md #4).** `send-default-pii` is off, breadcrumbs are dropped (there is no
 *     request-scoped hub, so breadcrumbs would otherwise bleed across requests on reused Tomcat
 *     threads), and [scrubPii] redacts emails / opaque tokens out of the message and exception values
 *     of every event before it is sent. Redaction is best-effort defense-in-depth, not a licence to log
 *     PII: the primary control is still "never put personal data in a log/exception message".
 */
@Configuration
class SentryConfig(
    private val properties: CookieKeeperProperties,
) : SmartInitializingSingleton {
    private val log = LoggerFactory.getLogger(SentryConfig::class.java)

    /**
     * Initialise Sentry after the container has created every singleton (logging system configured),
     * but still during context refresh — a thrown [requireEuResidency] here aborts startup (fail-closed
     * on a non-EU DSN). Kept out of the constructor so bean construction has no global side effects.
     */
    override fun afterSingletonsInstantiated() {
        val cfg = properties.observability.sentry
        if (cfg.dsn.isBlank()) {
            log.info("Sentry disabled — no cookiekeeper.observability.sentry.dsn configured")
            return
        }
        requireEuResidency(cfg.dsn)
        Sentry.init { options ->
            options.dsn = cfg.dsn
            options.environment = cfg.environment
            cfg.release.takeIf { it.isNotBlank() }?.let { options.release = it }
            options.tracesSampleRate = cfg.tracesSampleRate
            options.isSendDefaultPii = false
            options.beforeSend = SentryOptions.BeforeSendCallback { event, _ -> scrubPii(event) }
            // Transactions carry their own request context and bypass beforeSend; scrub them too so a
            // future SENTRY_TRACES_SAMPLE_RATE>0 can't leak request URLs/query strings.
            options.beforeSendTransaction =
                SentryOptions.BeforeSendTransactionCallback { txn, _ ->
                    txn.request = null
                    txn.user = null
                    txn
                }
            // No per-request scope exists (no sentry-spring), so accumulated breadcrumbs would attach to
            // the wrong request on a reused worker thread — drop them entirely.
            options.beforeBreadcrumb = SentryOptions.BeforeBreadcrumbCallback { _, _ -> null }
        }
        attachLogbackAppender()
        log.info("Sentry enabled — environment={}, EU region", cfg.environment)
    }

    /**
     * Attach a [SentryAppender] to the Logback root logger so `ERROR` logs become Sentry events. Done
     * programmatically (rather than a custom `logback-spring.xml`) so Spring Boot's default console
     * logging is left completely untouched — we only ADD a capture sink, gated on a live DSN.
     */
    private fun attachLogbackAppender() {
        val factory = LoggerFactory.getILoggerFactory()
        if (factory !is LoggerContext) {
            log.warn("SLF4J backend is not Logback ({}); Sentry error-capture appender not attached", factory::class.java)
            return
        }
        val appender =
            SentryAppender().apply {
                name = SENTRY_APPENDER_NAME
                context = factory
                // Only ERROR promotes to a Sentry event. Breadcrumbs are disabled at the SDK level
                // (beforeBreadcrumb -> null); OFF here also stops the appender from building them at all.
                setMinimumEventLevel(Level.ERROR)
                setMinimumBreadcrumbLevel(Level.OFF)
                start()
            }
        factory.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender)
    }

    /**
     * Detach the appender and close the SDK on context shutdown so nothing leaks onto the shared global
     * Logback [LoggerContext] / Sentry static across context lifecycles (test contexts, devtools). Both
     * calls are no-ops if Sentry was never initialised (blank DSN).
     */
    @PreDestroy
    fun shutdown() {
        (LoggerFactory.getILoggerFactory() as? LoggerContext)
            ?.getLogger(Logger.ROOT_LOGGER_NAME)
            ?.detachAppender(SENTRY_APPENDER_NAME)
        Sentry.close()
    }

    companion object {
        // Sentry's EU data-region ingest host. A DSN whose host isn't under this violates EU residency.
        private const val EU_INGEST_HOST = "de.sentry.io"
        private const val SENTRY_APPENDER_NAME = "SENTRY"

        // Emails and long opaque tokens/keys are the PII/secret shapes most likely to end up in a log or
        // exception message. TOKEN stays long (>=24) so it clobbers base64/hex secrets and JWTs without
        // eating ordinary words. EMAIL is applied first so a long local-part isn't half-redacted by TOKEN.
        private val EMAIL_PATTERN = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
        private val TOKEN_PATTERN = Regex("[A-Za-z0-9_\\-]{24,}")

        /**
         * Refuse a non-EU Sentry DSN at startup (ADR-15, CLAUDE.md #2). Error events can carry PII in
         * stack traces/messages, so they must only ever reach Sentry's EU region. The residency decision
         * is made on the parsed DSN **host** — a substring test would pass a DSN that merely contains the
         * marker in its userinfo (`https://de.sentry.io@o0.ingest.us.sentry.io/0`) or path.
         */
        fun requireEuResidency(dsn: String) {
            val host = runCatching { URI(dsn).host }.getOrNull()?.lowercase()
            require(host != null && (host == EU_INGEST_HOST || host.endsWith(".$EU_INGEST_HOST"))) {
                "complyr.observability.sentry.dsn (SENTRY_DSN_BACKEND) must be a Sentry EU-region DSN " +
                    "(host *.$EU_INGEST_HOST) for EU data residency (ADR-15, CLAUDE.md #2). A non-EU " +
                    "(or malformed) DSN would send error events to a non-EU processor."
            }
        }

        /** Redact emails and opaque tokens from a free-text field carried into a Sentry event. */
        fun redact(text: String): String =
            text
                .replace(EMAIL_PATTERN, "[redacted-email]")
                .replace(TOKEN_PATTERN, "[redacted]")

        /**
         * Strip/redact PII before an event is sent. Nulls the request/user context (belt-and-suspenders;
         * this integration doesn't populate them) and — the field that actually matters for a
         * log-appender integration — redacts the formatted message and every exception value, where a
         * throwable's text (scanned URLs, DB constraint details, "Unknown token: …") could otherwise
         * carry personal data or secrets.
         */
        fun scrubPii(event: SentryEvent): SentryEvent {
            event.request = null
            event.user = null
            event.message?.let { message ->
                message.formatted = message.formatted?.let(::redact)
                message.message = message.message?.let(::redact)
            }
            event.exceptions?.forEach { exception ->
                exception.value = exception.value?.let(::redact)
            }
            return event
        }
    }
}
