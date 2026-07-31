package com.complyr.common

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Enables Spring's `@Scheduled` support process-wide. Kept separate from [AsyncConfig] so the
 * scheduling concern is discoverable on its own; the default single-threaded scheduler is fine
 * for our current cron jobs (only the lightweight consent-idempotency reaper today).
 *
 * SINGLE-INSTANCE ASSUMPTION: every `@Scheduled` job here fires on EVERY running instance. The
 * v1 deployment runs one backend container per compose project, so that's a non-issue today. But
 * before scaling to >1 replica, any job that mutates shared state (the consent-idempotency reaper)
 * MUST get a leader guard — ShedLock or `pg_try_advisory_lock` — or all replicas will run it in
 * lockstep (a daily N-way lock convoy on the same rows; idempotent but wasteful). This note is
 * about inter-instance duplication, not intra-JVM threads.
 */
@Configuration
@EnableScheduling
class SchedulingConfig
