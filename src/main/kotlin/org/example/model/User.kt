package org.example.model

import jakarta.persistence.*

@Entity
@Table(name = "users")
data class User(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String = "",

    @Column(unique = true, nullable = false)
    val username: String = "",

    @Column(nullable = false)
    val passwordHash: String = "",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val role: Role = Role.RIDER,

    // Rating as a rider — null until first rating received
    val avgRating: Double? = null,

    val ratingCount: Int = 0
)
