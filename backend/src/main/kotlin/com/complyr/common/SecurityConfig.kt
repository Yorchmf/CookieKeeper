package com.complyr.common

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.web.cors.CorsConfigurationSource
import tools.jackson.databind.ObjectMapper

/**
 * Stateless JWT resource-server security for the REST API.
 *
 * Public (unauthenticated) endpoints: actuator health, widget config reads, consent
 * ingestion, and the auth entry endpoints. Everything else requires a valid access JWT,
 * read from the `cmplyr_at` cookie or the `Authorization: Bearer` header.
 * Auth failures are returned as the standard `{ success, data, error, meta }` envelope.
 *
 * CORS is opened (credential-less) only for the public widget endpoints — see
 * [CorsConfig], whose `publicWidgetCorsSource` bean is injected here. Everything else has no
 * CORS config, so the browser blocks cross-origin calls; the dashboard reaches the API
 * same-origin through its Next proxy.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val objectMapper: ObjectMapper,
) {
    @Bean
    fun apiSecurityFilterChain(
        http: HttpSecurity,
        jwtDecoder: JwtDecoder,
        publicWidgetCorsSource: CorsConfigurationSource,
    ): SecurityFilterChain {
        http
            .cors { cors -> cors.configurationSource(publicWidgetCorsSource) }
            // CSRF tokens are deliberately not used: auth cookies are SameSite=Lax, every
            // state-changing endpoint is POST/PATCH/DELETE, and there are no state-changing GETs.
            // SameSite=Lax is the load-bearing layer — it withholds the cookie from every cross-site
            // POST, including the bodyless ones (`/sites/{id}/verify`, `/sites/{id}/scans`) that a
            // cross-site form could otherwise shape. The JSON-only content type of the body-carrying
            // endpoints (a cross-site form can't send `application/json`) is a second layer on top.
            .csrf { csrf -> csrf.disable() }
            .sessionManagement { session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers("/actuator/health")
                    .permitAll()
                    .requestMatchers("/api/v1/widget-config/**")
                    .permitAll()
                    // The CDN-hosted widget config the embedded banner fetches (ADR-19). Same read,
                    // same public/cacheable contract as /api/v1/widget-config — a different URL only
                    // because Caddy's cdn. vhost proxies /cfg/* here and the widget parses it unenveloped.
                    .requestMatchers(HttpMethod.GET, "/cfg/*")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/consent")
                    .permitAll()
                    // Stateless origin-token mint for the consent path (ADR-13). Unauthenticated and
                    // CORS-open like consent; rate-limited on the CONSENT tier by RateLimitFilter.
                    .requestMatchers(HttpMethod.GET, "/api/v1/consent-token/*")
                    .permitAll()
                    // Anonymous marketing-funnel free scan (docs ADR-12). Abuse controls (per-IP
                    // rate-limit tier, honeypot, concurrent-scan cap) live in the RateLimitFilter and
                    // PublicScanService. The result is read by its opaque token only: the teaser GET
                    // and the email-gated report POST are token-scoped, not owner-scoped.
                    .requestMatchers(HttpMethod.POST, "/api/v1/public-scan")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/public-scan/*")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/public-scan/*/report")
                    .permitAll()
                    // Hosted cookie-policy read (docs §4.5). Addressed only by an opaque public id;
                    // read-only, cacheable (Cloudflare), rate-limited on the PUBLIC_POLICY tier.
                    .requestMatchers(HttpMethod.GET, "/api/v1/public/policy/*")
                    .permitAll()
                    // Stripe webhook: unauthenticated by construction (Stripe cannot send a JWT). Its
                    // gate is the per-request Stripe signature verified in StripeApiGateway, plus a body-
                    // size cap — no session/JWT applies. Everything else under /billing stays authed.
                    .requestMatchers(HttpMethod.POST, "/api/v1/billing/webhook")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, *PUBLIC_AUTH_ENDPOINTS)
                    .permitAll()
                    .anyRequest()
                    .authenticated()
            }.oauth2ResourceServer { resourceServer ->
                resourceServer
                    .bearerTokenResolver(cookieOrHeaderBearerTokenResolver())
                    .jwt { jwt -> jwt.decoder(jwtDecoder) }
                    .authenticationEntryPoint(unauthenticatedEntryPoint())
            }.exceptionHandling { exceptions ->
                exceptions
                    .authenticationEntryPoint(unauthenticatedEntryPoint())
                    .accessDeniedHandler(forbiddenHandler())
            }.httpBasic { basic -> basic.disable() }
            .formLogin { form -> form.disable() }
        return http.build()
    }

    /** Reads the bearer token from the Authorization header or the `cmplyr_at` cookie. */
    private fun cookieOrHeaderBearerTokenResolver(): BearerTokenResolver {
        val headerResolver = DefaultBearerTokenResolver()
        return BearerTokenResolver { request: HttpServletRequest ->
            headerResolver.resolve(request)
                ?: request.cookies?.firstOrNull { it.name == AuthCookies.ACCESS_TOKEN }?.value
        }
    }

    private fun unauthenticatedEntryPoint() =
        AuthenticationEntryPoint { _, response, _ ->
            writeEnvelope(
                response,
                HttpStatus.UNAUTHORIZED,
                code = "UNAUTHENTICATED",
                message = "Authentication required",
            )
        }

    private fun forbiddenHandler() =
        AccessDeniedHandler { _, response, _ ->
            writeEnvelope(response, HttpStatus.FORBIDDEN, code = "FORBIDDEN", message = "Access denied")
        }

    private fun writeEnvelope(
        response: HttpServletResponse,
        status: HttpStatus,
        code: String,
        message: String,
    ) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(objectMapper.writeValueAsString(ApiResponse.error(code, message)))
    }

    companion object {
        private val PUBLIC_AUTH_ENDPOINTS =
            arrayOf(
                "/api/v1/auth/signup",
                "/api/v1/auth/login",
                "/api/v1/auth/refresh",
                // Public so a user whose access JWT already expired can still clear cookies —
                // it only revokes the refresh token presented in the HttpOnly cookie.
                "/api/v1/auth/logout",
                "/api/v1/auth/verify-email",
                "/api/v1/auth/resend-verification",
                "/api/v1/auth/forgot-password",
                "/api/v1/auth/reset-password",
                // Public: the clicker holds the mailed email_change token, not necessarily a session, and
                // the token itself is the proof (verify-new-first). See AuthController.confirmEmailChange.
                "/api/v1/auth/confirm-email-change",
            )
    }
}
