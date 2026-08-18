package eu.cookiekeeper.common

import eu.cookiekeeper.auth.UserRepository
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Ends the session of an Art. 17 erased account (ADR-20), for every authenticated endpoint at once.
 *
 * Access tokens are stateless and live 15 minutes, so one minted moments before an erasure stays
 * cryptographically valid afterwards — the erasure deletes every *refresh* token, which stops renewal but
 * cannot retract what is already in the browser. Without this filter that residual token still proves
 * ownership everywhere ownership is proved by `findByIdAndUserId` alone: it could read the account's
 * retained consent evidence, or write fresh policy-settings PII onto a site that now has no owner and can
 * never be erased again.
 *
 * The alternative — an `isErased` check at each call site — was tried first and is exactly how the gap
 * appeared: it is a whitelist, and the next endpoint added forgets it. One check in front of everything
 * costs a primary-key lookup per authenticated request and cannot drift. Requests with no JWT principal
 * (public endpoints, absent/expired token) never reach the database: unauthenticated traffic is the
 * security layer's concern, not this one's.
 *
 * Ordered *after* [AuthenticatedRateLimitFilter] so the lookup it performs is itself throttled per user.
 * The per-service `isErased` guards are kept as defence in depth, not because this filter is optional.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class ErasedAccountFilter(
    private val userRepository: UserRepository,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = HttpMethod.OPTIONS.matches(request.method)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val userId = authenticatedUserId()
        if (userId != null && userRepository.existsByIdAndDeletedAtIsNotNull(userId)) {
            // Same envelope the security layer returns for an absent token: to every caller a tombstone
            // is simply not an account. The id is not logged — an erasure must not leave a trail of the
            // account it destroyed (CLAUDE.md #4).
            writeUnauthenticated(response)
            return
        }
        filterChain.doFilter(request, response)
    }

    /** The authenticated user's JWT subject, or null when there is no authenticated JWT principal. */
    private fun authenticatedUserId(): UUID? {
        val authentication = SecurityContextHolder.getContext().authentication ?: return null
        if (!authentication.isAuthenticated) return null
        val subject = (authentication.principal as? Jwt)?.subject ?: return null
        return try {
            UUID.fromString(subject)
        } catch (_: IllegalArgumentException) {
            // A non-UUID subject cannot name a user row; CurrentUser rejects it downstream anyway.
            null
        }
    }

    private fun writeUnauthenticated(response: HttpServletResponse) {
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(
            objectMapper.writeValueAsString(
                ApiResponse.error(code = "UNAUTHENTICATED", message = "Authentication required"),
            ),
        )
    }
}
