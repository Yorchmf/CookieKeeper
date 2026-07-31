package com.complyr.scan

import com.complyr.common.ApiException
import org.springframework.http.HttpStatus

/**
 * Covers a missing scan and a scan that belongs to another site (ownership + anti-enumeration in one),
 * mirroring [com.complyr.site.SiteNotFoundException]. Ownership of the parent site is checked first,
 * so this only ever surfaces for the owner requesting an id that isn't theirs.
 */
class ScanNotFoundException : ApiException(HttpStatus.NOT_FOUND, code = "SCAN_NOT_FOUND", message = "Scan not found")
