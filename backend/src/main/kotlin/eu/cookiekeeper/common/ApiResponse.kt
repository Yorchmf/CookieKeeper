package eu.cookiekeeper.common

/**
 * Standard API envelope for every `/api/v1` response: `{ success, data, error, meta }`.
 */
data class ApiResponse<T>(
    val success: Boolean,
    val data: T?,
    val error: ApiError?,
    val meta: ApiMeta? = null,
) {
    companion object {
        fun <T> success(
            data: T,
            meta: ApiMeta? = null,
        ): ApiResponse<T> = ApiResponse(success = true, data = data, error = null, meta = meta)

        fun error(
            code: String,
            message: String,
        ): ApiResponse<Nothing> = ApiResponse(success = false, data = null, error = ApiError(code, message))
    }
}

data class ApiError(
    val code: String,
    val message: String,
)

data class ApiMeta(
    val total: Long? = null,
    val page: Int? = null,
    val limit: Int? = null,
    // Opaque keyset cursor for the next page; null when the current page is the last.
    // Used by cursor-paginated reads (e.g. the consent-event log) where OFFSET paging
    // over a large, append-only, partitioned table would be too costly.
    val nextCursor: String? = null,
)
