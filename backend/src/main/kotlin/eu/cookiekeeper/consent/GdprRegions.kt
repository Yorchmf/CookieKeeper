package eu.cookiekeeper.consent

/**
 * Answers the only geographic question the widget ever asks: *is this visitor somewhere a consent
 * banner is legally required?* The answer rides on the existing consent-token response (ADR-13) and
 * drives the optional `data-complyr-regions="gdpr"` embed flag — see ARCHITECTURE.md §4.3.
 *
 * **Why a bucket and not a country code.** We know the country only because Cloudflare puts it on the
 * request (`CF-IPCountry`); handing it to the page would disclose more about the visitor than the
 * feature needs. Two buckets are exactly sufficient, so that is all that crosses the wire — and
 * nothing here is ever persisted (hard constraint #4: no visitor PII at rest).
 *
 * **Why the list errs wide.** The classification is used to *suppress* a banner, so an omission is a
 * compliance failure while an over-inclusion is only a banner someone did not strictly need to see.
 * Every doubt is therefore resolved towards [IN_SCOPE]: the EU 27 and their outermost regions, the
 * rest of the EEA, the UK and its Crown Dependencies (UK GDPR / equivalent local law), and
 * Switzerland (revised FADP). An unrecognised, missing or Cloudflare-unknown code yields `null`,
 * which the widget treats as "show the banner".
 *
 * Kept deliberately in step with `GDPR_REGIONS` in `widget/src/consent-mode.ts`, which feeds the same
 * countries to Google Consent Mode's `region` parameter. Change one, change the other.
 */
object GdprRegions {
    /** Visitor is somewhere prior consent is required — show the banner, deny by default. */
    const val IN_SCOPE = "gdpr"

    /** Visitor is outside those regions — the site owner may opt out of showing them a banner. */
    const val OUT_OF_SCOPE = "other"

    /** Cloudflare's placeholder when it cannot geolocate the client (`T1` is Tor, caught by the shape check). */
    private const val UNKNOWN_COUNTRY = "XX"

    private const val COUNTRY_CODE_LENGTH = 2

    private val IN_SCOPE_COUNTRIES =
        (
            // EU 27
            "AT BE BG HR CY CZ DK EE FI FR DE GR HU IE IT LV LT LU MT NL PL PT RO SK SI ES SE " +
                // EU territories Cloudflare reports under their own code rather than the member state's
                "AX GF GP MQ RE YT MF " +
                // Rest of the EEA
                "IS LI NO SJ " +
                // UK GDPR and the Crown Dependencies / Gibraltar, which mirror it
                "GB GG JE IM GI " +
                // Switzerland — revised FADP
                "CH"
        ).split(' ').toSet()

    /**
     * [IN_SCOPE], [OUT_OF_SCOPE], or null when [countryHeader] is absent, malformed or explicitly
     * unknown. Null is not an error: it means "we could not tell", and the widget fails open.
     */
    fun classify(countryHeader: String?): String? {
        val code = countryHeader?.trim()?.uppercase() ?: return null
        if (code.length != COUNTRY_CODE_LENGTH || code == UNKNOWN_COUNTRY) return null
        if (!code.all { it in 'A'..'Z' }) return null
        return if (code in IN_SCOPE_COUNTRIES) IN_SCOPE else OUT_OF_SCOPE
    }
}
