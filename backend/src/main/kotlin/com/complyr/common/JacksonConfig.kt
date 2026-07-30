package com.complyr.common

import org.springframework.boot.jackson.autoconfigure.JsonFactoryBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.core.StreamReadConstraints

/**
 * Hardens JSON parsing against malformed / hostile payloads (defense-in-depth for the public,
 * unauthenticated, CORS-open consent endpoint). Bean-validation `@Size` caps on the DTO only
 * apply AFTER Jackson has fully materialised the body, so an oversized or deeply-nested document
 * could exhaust memory before validation ever runs. These stream-level limits reject such input
 * while it is still being read.
 *
 * The limits are global (all inbound JSON) but generous — every endpoint in this app exchanges
 * small, flat JSON. The tight per-request byte cap for the hot public path lives at the edge
 * (Caddy `request_body max_size` on `/api/v1/consent`); this is the in-process backstop.
 */
@Configuration
class JacksonConfig {
    @Bean
    fun streamReadConstraintsCustomizer(): JsonFactoryBuilderCustomizer =
        JsonFactoryBuilderCustomizer { builder ->
            builder.streamReadConstraints(
                StreamReadConstraints
                    .builder()
                    .maxDocumentLength(MAX_DOCUMENT_BYTES)
                    .maxNestingDepth(MAX_NESTING_DEPTH)
                    .maxStringLength(MAX_STRING_LENGTH)
                    .maxNameLength(MAX_NAME_LENGTH)
                    .build(),
            )
        }

    private companion object {
        /** 256 KB: orders of magnitude above any legitimate request body this API accepts. */
        const val MAX_DOCUMENT_BYTES = 256L * 1024

        /** Our payloads are shallow (an object with a flat category map); real requests use <5. */
        const val MAX_NESTING_DEPTH = 20

        /** No single JSON string value here is anywhere near this; Jackson's default is 20 MB. */
        const val MAX_STRING_LENGTH = 100_000

        /** Property/key names (incl. attacker-controlled category keys) are short. */
        const val MAX_NAME_LENGTH = 1_000
    }
}
