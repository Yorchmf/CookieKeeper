package eu.cookiekeeper.common

/** Cookie names/paths shared between the token resolver and the auth controller. */
object AuthCookies {
    const val ACCESS_TOKEN = "cmplyr_at"
    const val REFRESH_TOKEN = "cmplyr_rt"

    /** Refresh cookie is scoped to the auth endpoints only. */
    const val REFRESH_PATH = "/api/v1/auth"

    /**
     * Non-secret session marker at Path=/, living as long as the refresh token. It carries no
     * value beyond "a refreshable session exists" — the refresh cookie itself is path-scoped to
     * [REFRESH_PATH] and so is invisible to the dashboard middleware on a plain page navigation.
     * The marker lets that middleware distinguish "logged out" from "access expired but
     * refreshable", so an idle user is not bounced to login on a hard navigation.
     */
    const val SESSION_MARKER = "cmplyr_session"

    /** Opaque, non-sensitive: presence is the only signal; the value is never read. */
    const val SESSION_MARKER_VALUE = "1"
}
