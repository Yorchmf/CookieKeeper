package com.complyr.common

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

/**
 * Security skeleton for the stateless REST API.
 *
 * Public (unauthenticated) endpoints: actuator health, widget config reads, consent ingestion.
 * Everything else requires authentication.
 *
 * TODO(W2): add JWT auth — access token (15 min) + rotating refresh tokens (hashed at rest),
 *  bcrypt passwords, and a JWT authentication filter / resource-server configuration.
 * TODO(W3): rate limiting (Bucket4j) and CORS for the public widget endpoints.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {
    @Bean
    fun apiSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { csrf -> csrf.disable() }
            .sessionManagement { session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers("/actuator/health")
                    .permitAll()
                    .requestMatchers("/api/v1/widget-config/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/consent")
                    .permitAll()
                    .anyRequest()
                    .authenticated()
            }.httpBasic { basic -> basic.disable() }
            .formLogin { form -> form.disable() }
        return http.build()
    }
}
