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

    // Rating as a rider — null until first rating received
    val avgRating: Double? = null
)
