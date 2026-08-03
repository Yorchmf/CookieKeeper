package com.complyr.auth

import com.complyr.TestcontainersConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * End-to-end proof of the per-account login lockout against a real Postgres. Threshold is lowered to 3
 * so the test is short. The load-bearing assertion is that after the threshold is reached a login with
 * the CORRECT password is still rejected: that can only pass if each failed attempt's counter increment
 * COMMITTED even though `login` threw [InvalidCredentialsException] and rolled its own transaction back
 * — the whole reason [LoginAttemptService.recordFailure] runs in `REQUIRES_NEW`. A mock-based unit test
 * cannot cover this; only the real transaction manager can.
 */
@SpringBootTest(properties = ["complyr.auth.max-failed-login-attempts=3", "complyr.auth.login-lockout-duration=15m"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, RecordingEmailConfig::class)
class LoginLockoutIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    private lateinit var email: String

    @BeforeEach
    fun freshUser() {
        email = "lockout-${UUID.randomUUID()}@example.com"
        mockMvc
            .perform(
                post("/api/v1/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"$email","password":"s3cret-password","locale":"en"}"""),
            ).andExpect(status().isCreated)
    }

    private fun login(password: String) =
        mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"$password"}"""),
        )

    @Test
    fun `the account locks after the threshold and then rejects even the correct password`() {
        // Three wrong-password attempts: each rolls back login's own transaction, but the counter it
        // increments in a new transaction must persist across all three to reach the lock threshold.
        repeat(3) {
            login("wrong-password")
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
        }

        // Locked: the correct password now yields the SAME generic 401, never a lockout-specific signal
        // (which would disclose the account exists). Proves the increments committed despite rollback.
        login("s3cret-password")
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
    }

    @Test
    fun `concurrent wrong-password bursts still leave the account locked`() {
        // Regression against a real Postgres for the concurrency bug: several failed attempts fire at
        // once, all reading the pre-lock snapshot before any lock is set, so they all reach
        // recordFailure. The advisory lock serializes their writes and the already-locked no-op must
        // leave the window intact — a straggler must not clear the lock. If it did, the correct password
        // below would succeed (200). Firing well past the threshold of 3 makes the interleaving robust.
        val burst = 8
        val pool = Executors.newFixedThreadPool(burst)
        try {
            val ready = CountDownLatch(burst)
            val go = CountDownLatch(1)
            val futures =
                (1..burst).map {
                    pool.submit {
                        ready.countDown()
                        go.await()
                        login("wrong-password").andExpect(status().isUnauthorized)
                    }
                }
            ready.await() // all threads parked at the barrier…
            go.countDown() // …released together to maximize the concurrent window
            futures.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }

        // Locked: even the correct password yields the generic 401. Fails (200) if any straggler unlocked.
        login("s3cret-password").andExpect(status().isUnauthorized)
    }

    @Test
    fun `a successful login before the threshold clears the counter so it never locks`() {
        // Two failures (below the threshold of 3)…
        repeat(2) { login("wrong-password").andExpect(status().isUnauthorized) }

        // …a success resets the counter…
        login("s3cret-password").andExpect(status().isOk)

        // …so two more failures still don't reach the threshold, and a final success works.
        repeat(2) { login("wrong-password").andExpect(status().isUnauthorized) }
        login("s3cret-password").andExpect(status().isOk)
    }
}
