package org.example.model

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(
    name = "ratings",
    uniqueConstraints = [UniqueConstraint(columnNames = ["rideId", "fromUserId"])]
)
data class Rating(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String = "",

    @Column(nullable = false)
    val rideId: String = "",

    @Column(nullable = false)
    val fromUserId: String = "",

    @Column(nullable = false)
    val toUserId: String = "",

    // 1–5 stars
    @Column(nullable = false)
    val score: Int = 0,

    val comment: String? = null,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now()
)
