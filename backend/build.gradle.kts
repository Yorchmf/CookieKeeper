plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    kotlin("plugin.jpa") version "2.3.21"
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    jacoco
}

group = "eu.cookiekeeper"

// The repo-root VERSION file is the single source of truth for the release number; release-dev.yml
// bumps it and mirrors it into the two package.json files. Reading it here means the jar, the
// container tag and the git tag can never disagree about which release they are.
//
// The Docker build context is backend/, so ../VERSION is not visible inside the image build — the
// Dockerfile passes it through as -PappVersion instead. The literal fallback only applies to an
// exotic checkout with no VERSION file at all.
version =
    (findProperty("appVersion") as String?)?.takeIf { it.isNotBlank() }
        ?: file("../VERSION").takeIf { it.isFile }?.readText()?.trim()
        ?: "0.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("com.bucket4j:bucket4j_jdk17-core:8.19.0")
    // Scanner crawler (scanner profile only). Pinned to match the browser binaries baked into
    // Dockerfile.scanner's mcr.microsoft.com/playwright/java:v1.61.0-noble base image — the library
    // and the bundled Chromium must be the same Playwright version.
    implementation("com.microsoft.playwright:playwright:1.61.0")
    implementation("com.stripe:stripe-java:33.2.0")
    // Error tracking (ADR-15). Framework-agnostic logback integration: SentryConfig attaches a
    // SentryAppender to the root logger so unhandled ERROR-level logs become Sentry events. Chosen
    // over sentry-spring-boot-4, which for Spring Boot 4 requires the OpenTelemetry Java agent
    // (-javaagent + SENTRY_AUTO_INIT=false) — unneeded weight for MVP error capture. logback-classic
    // is already on the classpath via the Spring Boot logging starter.
    implementation("io.sentry:sentry-logback:8.51.0")
    // Domain verification (ADR-17). A spec-compliant HTML5 tokenizer for SnippetMatcher, which decides
    // whether a customer's homepage really carries our embed snippet. A hand-rolled tag scan was tried
    // first and rejected: without an HTML context model it accepted the snippet from inside comments,
    // JSON islands, <title>/<textarea>/<noscript>/CDATA and other elements' attribute values, any of
    // which an attacker can plant as user-generated content on a domain they do not own. Matching a
    // browser's parse is the whole security property, so we use a parser browsers agree with rather
    // than re-implement the tokenizer. Zero transitive dependencies, MIT.
    implementation("org.jsoup:jsoup:1.21.1")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("org.awaitility:awaitility:4.3.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}

// detekt 1.23.x is compiled against Kotlin 2.0.21; pin its own classpath to that version so it
// does not pick up the project's newer Kotlin (https://detekt.dev/docs/gettingstarted/gradle#dependencies).
configurations.matching { it.name == "detekt" }.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            useVersion(
                io.gitlab.arturbosch.detekt
                    .getSupportedKotlinVersion(),
            )
        }
    }
}

// The plain (non-executable) jar is not needed; keeps Docker COPY globs unambiguous.
tasks.named<Jar>("jar") {
    enabled = false
}

tasks.withType<Test> {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

// 80% line coverage gate scoped to the security- and business-critical classes (CLAUDE.md testing
// convention: service-layer coverage target — DTOs/entities/config are excluded).
//
// These are JVM class-file paths, so they must use the *package* root `eu/cookiekeeper`, not the
// product name. A stale `com/complyr` prefix here matches nothing, and an empty classDirectories set
// makes the 80% rule pass vacuously — the gate reports success while verifying nothing. If this list
// ever needs to be emptied, delete the gate rather than leaving it green over zero classes.
val coverageClassPatterns =
    listOf(
        "eu/cookiekeeper/auth/AuthService*",
        "eu/cookiekeeper/auth/TokenService*",
        "eu/cookiekeeper/site/SiteService*",
        "eu/cookiekeeper/site/DomainValidator*",
        "eu/cookiekeeper/scan/ScanQueue*",
        "eu/cookiekeeper/scan/ScanQueryService*",
        // Security-critical SSRF range logic — must stay well covered. The Playwright crawler itself
        // needs a real browser, so it is exercised via integration/manual runs, not this unit gate.
        "eu/cookiekeeper/scan/ScanTargetValidator*",
        // Cookie classification (W4 slice 3): pure matcher + the classifier that drives it.
        "eu/cookiekeeper/scan/CookieSignatureMatcher*",
        "eu/cookiekeeper/scan/CookieClassifier*",
        // Compliance report: pure scoring/issue logic over a completed scan's classified cookies.
        "eu/cookiekeeper/scan/ComplianceAnalyzer*",
        // Third-party marketing tracker detection: pure host matcher + the classifier that counts distinct
        // marketing signatures from the crawl's observed off-site hosts (drives the third_party_trackers finding).
        "eu/cookiekeeper/scan/TrackerSignatureMatcher*",
        "eu/cookiekeeper/scan/TrackerClassifier*",
        // Domain verification (ADR-17). The fetcher is the only app-initiated outbound request to a
        // customer-controlled host, and the matcher is what an attacker would try to forge — both are
        // security-critical enough that a coverage regression should fail the build.
        "eu/cookiekeeper/site/SiteVerificationFetcher*",
        "eu/cookiekeeper/site/SnippetMatcher*",
        "eu/cookiekeeper/site/DnsTxtLookup*",
        "eu/cookiekeeper/site/SiteVerificationService*",
        "eu/cookiekeeper/site/CdnHost*",
        // On-demand re-scan: the entitlement gate and the one-live-scan-per-site throttle are the whole
        // protection on a customer-triggered crawl, so a coverage regression should fail the build.
        "eu/cookiekeeper/scan/ScanRequestService*",
        // The hosted policy page is public and gated only here: this is what stops an unverified
        // customer publishing a Complyr-hosted page for a domain they don't control (ADR-17).
        "eu/cookiekeeper/policy/PolicyReadService*",
        "eu/cookiekeeper/policy/PolicyVersionSelector*",
        // Scheduled re-scan: the job is what makes every plan's rescanFrequency real, so a Starter site
        // is never stuck with its single signup scan. Its due/skip logic (skip Expired, per-plan cadence)
        // is the whole correctness surface and must stay covered.
        "eu/cookiekeeper/scan/ScheduledRescanJob*",
    )

// Both jacoco tasks read `classDirectories`, i.e. the outputs of compileKotlin, compileJava and
// processResources. dependsOn(test) normally implies those transitively — but `-x test` severs that
// edge, and Gradle then fails the build for an undeclared implicit dependency rather than risking
// an ordering-dependent result. Depending on `classes` directly holds under any invocation.
tasks.jacocoTestReport {
    dependsOn(tasks.test, tasks.named("classes"))
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test, tasks.named("classes"))
    classDirectories.setFrom(
        files(
            classDirectories.files.map { dir ->
                fileTree(dir) { include(coverageClassPatterns.map { "$it.class" }) }
            },
        ),
    )
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
    // JaCoCo treats "no classes to analyse" as a pass, so a pattern that stops matching — a renamed
    // package, a moved class — silently disables the gate instead of failing it. Assert the set is
    // non-empty so the failure mode is a red build, not a green one that checked nothing.
    doFirst {
        require(classDirectories.files.isNotEmpty()) {
            "Coverage gate matched no class files. Check coverageClassPatterns against the real package layout."
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
