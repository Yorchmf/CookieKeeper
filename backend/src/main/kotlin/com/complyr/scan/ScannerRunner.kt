package com.complyr.scan

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * Entry point for the scanner runtime role (Spring profile `scanner`, no web server).
 *
 * TODO(W4): poll the Postgres `jobs` queue with FOR UPDATE SKIP LOCKED, run Playwright-for-Java
 *  crawls against verified customer domains (SSRF-hardened), classify cookies against the
 *  signature DB, and write scan results. No Playwright dependency yet — this is a stub.
 */
@Component
@Profile("scanner")
class ScannerRunner : ApplicationRunner {
    private val log = LoggerFactory.getLogger(ScannerRunner::class.java)

    override fun run(args: ApplicationArguments) {
        log.info("Scanner profile active — job polling not implemented yet (W4)")
    }
}
