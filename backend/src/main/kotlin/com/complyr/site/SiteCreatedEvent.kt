package com.complyr.site

import java.util.UUID

/**
 * Published by [SiteService.create] when a new site is registered. Consumed synchronously, in the
 * same transaction, by the scan feature ([com.complyr.scan.ScanEnqueueListener]) to enqueue the
 * site's first scan — so the enqueue commits atomically with the site (a rolled-back site enqueues
 * nothing) without the site package depending on the scan package.
 */
data class SiteCreatedEvent(
    val siteId: UUID,
)
