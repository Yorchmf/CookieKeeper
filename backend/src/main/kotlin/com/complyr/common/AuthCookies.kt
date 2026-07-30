package com.complyr.common

/** Cookie names/paths shared between the token resolver and the auth controller. */
object AuthCookies {
    const val ACCESS_TOKEN = "cmplyr_at"
    const val REFRESH_TOKEN = "cmplyr_rt"

    /** Refresh cookie is scoped to the auth endpoints only. */
    const val REFRESH_PATH = "/api/v1/auth"
}
