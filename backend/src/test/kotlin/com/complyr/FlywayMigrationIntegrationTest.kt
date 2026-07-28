package com.complyr

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class FlywayMigrationIntegrationTest {
    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `flyway baseline migration applied successfully`() {
        val failedMigrations =
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = false",
                Long::class.java,
            )
        assertEquals(0L, failedMigrations, "No Flyway migration should have failed")

        val appliedVersions =
            jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true",
                String::class.java,
            )
        assertTrue("1" in appliedVersions, "V1__baseline should be applied")
    }

    @Test
    fun `baseline creates all core tables`() {
        val expectedTables =
            setOf(
                "users",
                "refresh_tokens",
                "sites",
                "subscriptions",
                "banner_configs",
                "scans",
                "scan_cookies",
                "cookie_overrides",
                "policies",
                "consent_events",
                "jobs",
            )

        val actualTables =
            jdbcTemplate
                .queryForList(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                    String::class.java,
                ).toSet()

        val missing = expectedTables - actualTables
        assertTrue(missing.isEmpty(), "Missing tables from baseline migration: $missing")
    }
}
