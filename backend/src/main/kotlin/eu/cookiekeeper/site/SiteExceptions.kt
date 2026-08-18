package eu.cookiekeeper.site

import eu.cookiekeeper.common.ApiException
import org.springframework.http.HttpStatus

class InvalidDomainException :
    ApiException(HttpStatus.BAD_REQUEST, code = "INVALID_DOMAIN", message = "Domain is not a valid public hostname")

class DomainAlreadyRegisteredException :
    ApiException(
        HttpStatus.CONFLICT,
        code = "DOMAIN_ALREADY_REGISTERED",
        message = "Domain is already registered for this account",
    )

/** Covers both true misses and other users' sites (ownership + anti-enumeration in one). */
class SiteNotFoundException : ApiException(HttpStatus.NOT_FOUND, code = "SITE_NOT_FOUND", message = "Site not found")
