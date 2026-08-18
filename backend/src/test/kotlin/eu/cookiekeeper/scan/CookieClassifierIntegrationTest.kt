package eu.cookiekeeper.scan

import eu.cookiekeeper.TestcontainersConfiguration
import eu.cookiekeeper.banner.ConsentCategory
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end classification against the real V8 seed loaded by Flyway into a Testcontainers Postgres:
 * the seeded rows → [CookieSignatureRepository] → [CookieSignatureMatcher] → enriched [ScanCookieEntity].
 * Complements [CookieSignatureMatcherTest] (pure precedence) by proving the seed + wiring actually
 * classify the highest-frequency real trackers.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class CookieClassifierIntegrationTest {
    @Autowired
    private lateinit var classifier: CookieClassifier

    @Autowired
    private lateinit var signatureRepository: CookieSignatureRepository

    private val scanId = UUID.randomUUID()

    private fun cookie(name: String): ScanCookieEntity = ScanCookieEntity(scanId = scanId, name = name)

    @Test
    fun `every seeded category is a canonical ConsentCategory key`() {
        // The DB CHECK pins the four values; this guards the seed against drift from the app taxonomy.
        val seededCategories = signatureRepository.findAll().map { it.category }.toSet()

        assertTrue(seededCategories.isNotEmpty(), "V8 seed should have loaded")
        assertTrue(
            seededCategories.all { it in ConsentCategory.KEYS },
            "Seeded categories $seededCategories must all be ConsentCategory keys ${ConsentCategory.KEYS}",
        )
    }

    @Test
    fun `classifies an exact-match analytics cookie`() {
        val result = classifier.classify(listOf(cookie("_ga"))).single()

        assertTrue(result.isKnown)
        assertEquals("statistics", result.category)
        assertEquals("Google Analytics", result.provider)
    }

    @Test
    fun `classifies a GA4 wildcard-family cookie`() {
        // _ga_<container-id> only matches via the _ga_ wildcard prefix.
        val result = classifier.classify(listOf(cookie("_ga_XY12AB34CD"))).single()

        assertTrue(result.isKnown)
        assertEquals("statistics", result.category)
        assertEquals("Google Analytics", result.provider)
    }

    @Test
    fun `classifies a necessary session cookie and a marketing pixel`() {
        val results = classifier.classify(listOf(cookie("PHPSESSID"), cookie("_fbp")))

        val session = results.first { it.name == "PHPSESSID" }
        assertEquals("necessary", session.category)
        assertTrue(session.isKnown)

        val pixel = results.first { it.name == "_fbp" }
        assertEquals("marketing", pixel.category)
        assertEquals("Meta", pixel.provider)
    }

    @Test
    fun `leaves an unknown cookie unclassified for the needs-review bucket`() {
        val result = classifier.classify(listOf(cookie("myapp_feature_flag"))).single()

        assertFalse(result.isKnown)
        assertNull(result.category)
        assertNull(result.provider)
    }

    @Test
    fun `preserves the observed name and scan id while enriching`() {
        val original = cookie("_gid")

        val result = classifier.classify(listOf(original)).single()

        assertEquals(original.name, result.name)
        assertEquals(original.scanId, result.scanId)
        assertEquals(original.id, result.id)
    }
}
