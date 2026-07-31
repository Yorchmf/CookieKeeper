package com.complyr.scan

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * A row of the seeded signature DB (`cookie_signatures`, V8). Read-only reference data the scanner
 * classifies against — never written by application code, only by the seed migration. [category] is a
 * canonical [com.complyr.banner.ConsentCategory] key, pinned by a CHECK constraint in the migration.
 */
@Entity
@Table(name = "cookie_signatures")
data class CookieSignatureEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "name_pattern", nullable = false)
    val namePattern: String,
    @Column(name = "is_wildcard", nullable = false)
    val isWildcard: Boolean = false,
    @Column(nullable = false)
    val provider: String,
    @Column(nullable = false)
    val category: String,
    @Column
    val description: String? = null,
) {
    /** Project to the JPA-free value the matcher works with. */
    fun toSignature(): CookieSignature = CookieSignature(namePattern, isWildcard, provider, category)
}

interface CookieSignatureRepository : JpaRepository<CookieSignatureEntity, UUID>
