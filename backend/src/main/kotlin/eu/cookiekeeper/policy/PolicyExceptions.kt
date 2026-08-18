package eu.cookiekeeper.policy

import eu.cookiekeeper.common.ApiException
import org.springframework.http.HttpStatus

/**
 * No published policy for the requested site or public id. Used for both the authenticated
 * "current policy" read (site has never generated one) and the public hosted-page read (unknown
 * or unpublished public id) — one identical 404 either way, so the public id is not an oracle.
 */
class PolicyNotFoundException : ApiException(HttpStatus.NOT_FOUND, code = "POLICY_NOT_FOUND", message = "Policy not found")

/** No requested language resolves to a supported one (all five are offered — see [PolicyLanguages]). */
class UnsupportedPolicyLanguageException :
    ApiException(
        HttpStatus.BAD_REQUEST,
        code = "UNSUPPORTED_POLICY_LANGUAGE",
        message = "No supported language among the requested ones",
    )
