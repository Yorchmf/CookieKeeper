package eu.cookiekeeper.site

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import eu.cookiekeeper.common.CookieKeeperProperties
import eu.cookiekeeper.scan.ScanFailureReason
import eu.cookiekeeper.scan.ScanTargetException
import eu.cookiekeeper.scan.ScanTargetValidator
import io.mockk.every
import io.mockk.mockk
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises [SiteVerificationFetcher] against a real loopback HTTP server, because the parts that matter
 * here — body truncation, redirect handling, and timeouts — are socket behaviour that a mocked client
 * would only pretend to reproduce.
 *
 * The [ScanTargetValidator] is mocked to permit `127.0.0.1` (it would otherwise refuse loopback, which is
 * the whole point of it), and the fetch enters through [SiteVerificationFetcher.fetch] so the target can
 * be a plain-HTTP ephemeral port. Every other guard is the production one. The last test puts the *real*
 * validator back to prove the loopback refusal is genuine.
 */
class SiteVerificationFetcherIntegrationTest {
    private lateinit var server: HttpServer
    private val requests = AtomicInteger()
    private val validator = mockk<ScanTargetValidator>()

    private val port: Int get() = server.address.port
    private val domain: String get() = "127.0.0.1"

    @BeforeEach
    fun startServer() {
        every { validator.validate(any()) } returns Unit
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newFixedThreadPool(2)
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    private fun fetcher(
        verification: CookieKeeperProperties.Verification = CookieKeeperProperties.Verification(),
        targetValidator: ScanTargetValidator = validator,
    ) = SiteVerificationFetcher(targetValidator, VerificationTestProperties.properties(verification), Clock.systemUTC())

    /**
     * Runs the production redirect loop against the loopback server, entering on plain HTTP. The
     * ephemeral port has to be allowed explicitly — that allowance is the only guard tests relax, and
     * production never passes it (see [SiteVerificationFetcher.fetch]).
     */
    private fun fetch(verification: CookieKeeperProperties.Verification = CookieKeeperProperties.Verification()): String? =
        fetcher(verification).fetch(URI("http://127.0.0.1:$port/"), domain, allowedPort = port)

    private fun handle(
        path: String,
        handler: (HttpExchange) -> Unit,
    ) {
        server.createContext(path) { exchange ->
            requests.incrementAndGet()
            try {
                handler(exchange)
            } catch (ignored: IOException) {
                // The client closes early on truncation and cancellation; the test asserts on the client.
            } finally {
                exchange.close()
            }
        }
    }

    private fun respondHtml(
        exchange: HttpExchange,
        body: ByteArray,
    ) {
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.write(body)
    }

    private fun redirect(
        exchange: HttpExchange,
        location: String,
    ) {
        exchange.responseHeaders.add("Location", location)
        exchange.sendResponseHeaders(302, -1)
    }

    @Test
    fun `returns the page body for a plain 200 html response`() {
        handle("/") { respondHtml(it, "<html><body>hello</body></html>".toByteArray()) }

        assertEquals("<html><body>hello</body></html>", fetch())
    }

    @Test
    fun `truncates an oversized body at the cap instead of buffering it all`() {
        val cap = 64 * 1024
        // 4MB from a hostile host must never reach the heap in full.
        handle("/") { respondHtml(it, ByteArray(4 * 1024 * 1024) { 'a'.code.toByte() }) }

        val body = fetch(CookieKeeperProperties.Verification(maxBodyBytes = cap))

        assertEquals(cap, body?.length, "the body must be cut at maxBodyBytes")
    }

    @Test
    fun `follows a redirect that stays within the domain`() {
        handle("/") { redirect(it, "http://127.0.0.1:$port/home") }
        handle("/home") { respondHtml(it, "<html>home</html>".toByteArray()) }

        assertEquals("<html>home</html>", fetch())
    }

    @Test
    fun `abandons a redirect that leaves the domain`() {
        // Following an arbitrary Location would hand target selection to the customer's server.
        handle("/") { redirect(it, "http://other.example.com:$port/") }

        assertNull(fetch())
        assertEquals(1, requests.get(), "the off-domain hop must never be dialled")
    }

    @Test
    fun `refuses a redirect to a non-default port on the same host (the SSRF port sweep)`() {
        handle("/") { redirect(it, "http://127.0.0.1:9200/") }

        assertNull(fetch())
        assertEquals(1, requests.get())
    }

    @Test
    fun `refuses a redirect to a non-http scheme`() {
        handle("/") { redirect(it, "file:///etc/passwd") }

        assertNull(fetch())
        assertEquals(1, requests.get())
    }

    @Test
    fun `refuses a relative redirect rather than resolving it`() {
        handle("/") { redirect(it, "/home") }

        assertNull(fetch())
        assertEquals(1, requests.get())
    }

    @Test
    fun `gives up on a redirect loop after the configured number of hops`() {
        handle("/") { redirect(it, "http://127.0.0.1:$port/") }

        assertNull(fetch(CookieKeeperProperties.Verification(maxRedirects = 3)))
        assertEquals(4, requests.get(), "the initial request plus exactly maxRedirects hops")
    }

    @Test
    fun `revalidates every redirect hop, not just the first`() {
        val hopValidator = mockk<ScanTargetValidator>()
        every { hopValidator.validate("127.0.0.1") } returns Unit
        every { hopValidator.validate("www.127.0.0.1") } throws
            ScanTargetException(ScanFailureReason.BLOCKED_TARGET, "blocked")
        handle("/") { redirect(it, "http://www.127.0.0.1:$port/") }

        // `www.<domain>` passes the host-family check, so only the per-hop validator can stop it.
        assertNull(
            fetcher(targetValidator = hopValidator).fetch(URI("http://127.0.0.1:$port/"), domain, allowedPort = port),
        )
        assertEquals(1, requests.get())
    }

    @Test
    fun `refuses an explicit port by default, which is the only posture production ever runs in`() {
        // Production calls fetchHomepage, so allowedPort is never passed and guard 1 is "80 and 443 only".
        // Omitting it here proves that default is closed rather than merely undocumented.
        handle("/") { respondHtml(it, "<html>should never be served</html>".toByteArray()) }

        assertNull(fetcher().fetch(URI("http://127.0.0.1:$port/"), domain))
        assertEquals(0, requests.get(), "an explicit port must be refused before the socket is opened")
    }

    @Test
    fun `gives up on a server that answers with headers and then stalls mid-body`() {
        // The dangerous shape: a valid `200 text/html` with a promised Content-Length, then silence. The
        // response timer is already cancelled, so only the body watchdog can end this.
        handle("/") {
            it.responseHeaders.add("Content-Type", "text/html")
            it.sendResponseHeaders(200, MEGABYTE)
            it.responseBody.write("<html>".toByteArray())
            it.responseBody.flush()
            Thread.sleep(STALL_MILLIS)
        }

        val started = System.nanoTime()
        val body = fetch(stallingConfig())
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertNull(body)
        // Bounded by requestTimeout, not merely by the 2s totalBudget — the older code passed the loose
        // assertion while the read itself was never bounded at all.
        assertTrue(
            elapsedMillis < TIMEOUT_MILLIS * 10,
            "the body read must be bounded by requestTimeout (was ${elapsedMillis}ms)",
        )
    }

    @Test
    fun `leaves no thread parked in the body read after it gives up`() {
        // The regression test for the real defect: cancelling an exchange that already completed at header
        // time is a no-op, so the reader stayed WAITING in readNBytes forever, holding a thread, a socket
        // and an FD — once per hostile verification, with no self-healing. Closing the stream is what
        // actually unparks it, so the body must be read by a thread that still holds the handle.
        handle("/") {
            it.responseHeaders.add("Content-Type", "text/html")
            it.sendResponseHeaders(200, MEGABYTE)
            it.responseBody.write("<html>".toByteArray())
            it.responseBody.flush()
            Thread.sleep(STALL_MILLIS)
        }

        repeat(STALLED_ATTEMPTS) { assertNull(fetch(stallingConfig())) }

        await().atMost(Duration.ofSeconds(2)).until { threadsParkedInBodyRead() == 0 }
    }

    private fun stallingConfig() =
        CookieKeeperProperties.Verification(
            // connectTimeout has to come down with the budget: Verification requires each timeout to fit
            // inside totalBudget, since a connect alone must never be able to outlive the whole operation.
            connectTimeout = Duration.ofMillis(TIMEOUT_MILLIS),
            requestTimeout = Duration.ofMillis(TIMEOUT_MILLIS),
            totalBudget = Duration.ofSeconds(2),
        )

    /** Threads sitting inside a JDK HTTP response body stream — i.e. leaked readers. */
    private fun threadsParkedInBodyRead(): Int =
        Thread
            .getAllStackTraces()
            .filterKeys { it != Thread.currentThread() }
            .count { (_, frames) -> frames.any { it.className.contains("HttpResponseInputStream") } }

    @Test
    fun `treats a non-2xx status as a miss`() {
        handle("/") { it.sendResponseHeaders(500, -1) }

        assertNull(fetch())
    }

    @Test
    fun `treats a non-html content type as a miss`() {
        handle("/") {
            it.responseHeaders.add("Content-Type", "application/json")
            val body = """{"data":"<script src=\"https://cdn.cookiekeeper.eu/v1.js\"></script>"}""".toByteArray()
            it.sendResponseHeaders(200, body.size.toLong())
            it.responseBody.write(body)
        }

        assertNull(fetch())
    }

    @Test
    fun `the real validator refuses a loopback target before any connection is made`() {
        handle("/") { respondHtml(it, "<html>should never be served</html>".toByteArray()) }

        // No mock: ScanTargetValidator resolves `localhost` to 127.0.0.1 and fails closed. Aimed at the
        // port the server is actually on — targeting :443 would pass whether or not the guard exists.
        assertNull(
            fetcher(targetValidator = ScanTargetValidator())
                .fetch(URI("http://localhost:$port/"), "localhost", allowedPort = port),
        )
        assertEquals(0, requests.get(), "the SSRF guard must run before the socket is opened")
    }

    @Test
    fun `a domain that is not even URI-legal is a miss, not an exception`() {
        // Every other outcome collapses to null; a 500 here would be the one distinguishable response.
        assertNull(fetcher().fetchHomepage("not a domain"))
    }

    private companion object {
        const val MEGABYTE = 1024L * 1024L
        const val STALL_MILLIS = 5_000L
        const val TIMEOUT_MILLIS = 300L
        const val STALLED_ATTEMPTS = 5
    }
}
