package com.complyr.common

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Enables Spring's `@Scheduled` support process-wide. Kept separate from [AsyncConfig] so the
 * scheduling concern is discoverable on its own; the default single-threaded scheduler is fine
 * for our current cron jobs (only the lightweight consent-idempotency reaper today).
 *
 * MULTI-INSTANCE NOTE: every `@Scheduled` job here fires on EVERY running instance. Any job that
 * mutates shared state MUST leader-guard itself, or all replicas run it in lockstep (an N-way
 * lock convoy on the same rows; idempotent but wasteful). The consent-idempotency reaper already
 * does this via a transaction-scoped `pg_try_advisory_xact_lock` (see [ConsentIdempotencyReaper]);
 * any new shared-state job added here must follow the same pattern — ShedLock is the alternative
 * if a future job needs cross-database coordination. This note is about inter-instance
 * duplication, not intra-JVM threads.
 */
@Configuration
@EnableScheduling
class SchedulingConfig
