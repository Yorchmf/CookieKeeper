package com.complyr.common

import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import java.util.UUID

/**
 * Resolves the authenticated user's id (JWT `sub` claim) from the security context.
 * Throws [UnauthenticatedException] (mapped to a 401 envelope) when absent or malformed.
 */
object CurrentUser {
    fun id(): UUID {
        val authentication = SecurityContextHolder.getContext().authentication
        val jwt = authentication?.principal as? Jwt
        val parsed =
            jwt?.subject?.let { subject ->
                try {
                    UUID.fromString(subject)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        return parsed ?: throw UnauthenticatedException()
    }
}
