package eu.cookiekeeper.common

import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException

/**
 * Name of the database constraint this violation breached, normalized (unquoted, lowercased),
 * or null if it cannot be determined.
 *
 * Lets a service map a specific unique-index race to a typed 4xx instead of letting the raw
 * [DataIntegrityViolationException] fall through to the generic 500 logger — whose Postgres
 * detail message can contain the offending value (e.g. an email address), which would breach
 * the "no PII in application logs" constraint.
 */
fun DataIntegrityViolationException.violatedConstraint(): String? =
    generateSequence(cause) { it.cause }
        .filterIsInstance<ConstraintViolationException>()
        .firstOrNull()
        ?.constraintName
        ?.trim('"')
        ?.lowercase()
