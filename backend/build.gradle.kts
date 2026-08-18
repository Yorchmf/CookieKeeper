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
version = "0.0.1-SNAPSHOT"

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

// 80% line coverage gate scoped to the Week-2 business-logic classes (CLAUDE.md testing
// convention: service-layer coverage target — DTOs/entities/config are excluded).
val coverageClassPatterns =
    listOf(
        "com/complyr/auth/AuthService*",
        "com/complyr/auth/TokenService*",
        "com/complyr/site/SiteService*",
        "com/complyr/site/DomainValidator*",
        "com/complyr/scan/ScanQueue*",
        "com/complyr/scan/ScanQueryService*",
        // Security-critical SSRF range logic — must stay well covered. The Playwright crawler itself
        // needs a real browser, so it is exercised via integration/manual runs, not this unit gate.
        "com/complyr/scan/ScanTargetValidator*",
        // Cookie classification (W4 slice 3): pure matcher + the classifier that drives it.
        "com/complyr/scan/CookieSignatureMatcher*",
        "com/complyr/scan/CookieClassifier*",
        // Compliance report: pure scoring/issue logic over a completed scan's classified cookies.
        "com/complyr/scan/ComplianceAnalyzer*",
        // Third-party marketing tracker detection: pure host matcher + the classifier that counts distinct
        // marketing signatures from the crawl's observed off-site hosts (drives the third_party_trackers finding).
        "com/complyr/scan/TrackerSignatureMatcher*",
        "com/complyr/scan/TrackerClassifier*",
        // Domain verification (ADR-17). The fetcher is the only app-initiated outbound request to a
        // customer-controlled host, and the matcher is what an attacker would try to forge — both are
        // security-critical enough that a coverage regression should fail the build.
        "com/complyr/site/SiteVerificationFetcher*",
        "com/complyr/site/SnippetMatcher*",
        "com/complyr/site/DnsTxtLookup*",
        "com/complyr/site/SiteVerificationService*",
        "com/complyr/site/CdnHost*",
        // On-demand re-scan: the entitlement gate and the one-live-scan-per-site throttle are the whole
        // protection on a customer-triggered crawl, so a coverage regression should fail the build.
        "com/complyr/scan/ScanRequestService*",
        // The hosted policy page is public and gated only here: this is what stops an unverified
        // customer publishing a Complyr-hosted page for a domain they don't control (ADR-17).
        "com/complyr/policy/PolicyReadService*",
        "com/complyr/policy/PolicyVersionSelector*",
        // Scheduled re-scan: the job is what makes every plan's rescanFrequency real, so a Starter site
        // is never stuck with its single signup scan. Its due/skip logic (skip Expired, per-plan cadence)
        // is the whole correctness surface and must stay covered.
        "com/complyr/scan/ScheduledRescanJob*",
    )

tasks.jacocoTestReport {
    dependsOn(tasks.test)
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
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
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
