package eu.cookiekeeper.common

import com.nimbusds.jose.jwk.source.ImmutableSecret
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import java.time.Clock
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Symmetric-key (HS256) JWT encoder/decoder wired from `cookiekeeper.auth.jwt-secret`,
 * plus the password encoder and the clock used for all token timestamps.
 */
@Configuration
class JwtConfig {
    @Bean
    fun jwtSecretKey(properties: CookieKeeperProperties): SecretKey =
        SecretKeySpec(properties.auth.jwtSecret.toByteArray(Charsets.UTF_8), "HmacSHA256")

    @Bean
    fun jwtEncoder(jwtSecretKey: SecretKey): JwtEncoder = NimbusJwtEncoder(ImmutableSecret(jwtSecretKey))

    @Bean
    fun jwtDecoder(jwtSecretKey: SecretKey): JwtDecoder =
        NimbusJwtDecoder
            .withSecretKey(jwtSecretKey)
            .macAlgorithm(MacAlgorithm.HS256)
            .build()

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder(BCRYPT_STRENGTH)

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    companion object {
        const val BCRYPT_STRENGTH = 12
    }
}
