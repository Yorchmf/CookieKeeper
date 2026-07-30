package com.complyr.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users")
data class UserEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(nullable = false)
    val email: String,
    @Column(name = "password_hash", nullable = false)
    val passwordHash: String,
    @Column(nullable = false)
    val locale: String = "en",
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "verified_at")
    val verifiedAt: Instant? = null,
)

interface UserRepository : JpaRepository<UserEntity, UUID> {
    fun findByEmail(email: String): UserEntity?
}
