package eu.cookiekeeper.policy

/**
 * The customer's business details that fill the policy template, serialized into
 * `policy_settings.details` (jsonb). A value document, not an entity — mirrors
 * [eu.cookiekeeper.banner.BannerConfigDocument]. Persisted so a republish after a fresh scan reuses the
 * same details without the customer re-entering them. Every field is HTML-escaped at render time;
 * the service validates/normalizes them at the boundary before they land here.
 */
data class PolicyDetails(
    val companyName: String,
    val contactEmail: String,
    val websiteUrl: String,
    val address: String? = null,
)
