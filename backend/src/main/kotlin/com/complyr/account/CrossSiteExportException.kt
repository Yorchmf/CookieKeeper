package com.complyr.account

import com.complyr.common.ApiException
import org.springframework.http.HttpStatus

/**
 * The account export was requested from another site's page. 403: the session is valid and the caller is
 * genuinely who they say they are — it is the *origin* of the request that is refused, so logging the
 * customer out (401) would be both wrong and exactly the disruption the attacking page wanted.
 */
class CrossSiteExportException :
    ApiException(
        HttpStatus.FORBIDDEN,
        code = "CROSS_SITE_REQUEST",
        message = "This endpoint may not be requested from another site",
    )
