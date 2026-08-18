package eu.cookiekeeper

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class CookieKeeperBackendApplicationTests {
    @Test
    fun contextLoads() {
        // Boots the full Spring context against a Testcontainers Postgres.
    }
}
