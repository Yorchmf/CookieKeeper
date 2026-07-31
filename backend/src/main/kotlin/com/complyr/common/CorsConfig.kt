package com.complyr.common

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * Credential-less CORS for the public widget endpoints (the consent ingestion and
 * widget-config read paths), wired into the filter chain by [SecurityConfig] via the
 * `publicWidgetCorsSource` bean.
 *
 * The policy is driven by the `complyr.cors` tree ([ComplyrProperties.Cors]) — when those
 * properties are set (e.g. in `application-local.yml`) they are used, otherwise the
 * all-origins, credential-less defaults apply. Not profile-scoped: the same bean serves
 * every environment, and each environment tunes it through configuration alone.
 *
 * Origins are applied as `allowedOriginPatterns`, so a wildcard remains valid; credentials
 * default to off, so an open origin list can never expose an authenticated endpoint.
 */
@Configuration
class CorsConfig {
    @Bean
    fun publicWidgetCorsSource(properties: ComplyrProperties): CorsConfigurationSource {
        val cors = properties.cors
        val config =
            CorsConfiguration().apply {
                allowedOriginPatterns = cors.allowedOrigins
                allowedMethods = cors.allowedMethods
                allowedHeaders = cors.allowedHeaders
                allowCredentials = cors.allowCredentials
                maxAge = cors.maxAge.seconds
            }
        return UrlBasedCorsConfigurationSource().apply {
            cors.paths.forEach { path -> registerCorsConfiguration(path, config) }
        }
    }
}
