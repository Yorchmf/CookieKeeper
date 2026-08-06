package com.complyr.scan

import com.complyr.common.ApiException
import org.springframework.http.HttpStatus

/**
 * The site already has a queued or running scan — 409. This is the whole re-scan throttle: a site can
 * never hold two live scans, so a caller looping the endpoint just collects these instead of filling the
 * queue with duplicate crawls of one domain.
 *
 * Informational rather than a failure from the user's point of view — the thing they asked for (a fresh
 * scan) is already happening — so the dashboard renders it as a notice, not an error.
 */
class ScanAlreadyInProgressException :
    ApiException(
        HttpStatus.CONFLICT,
        code = "SCAN_ALREADY_IN_PROGRESS",
        message = "A scan for this site is already in progress",
    )
