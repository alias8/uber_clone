package org.example.model

import jakarta.persistence.*

@Entity
@Table(name = "drivers")
data class Driver(
    // Same ID as the User — one Driver profile per User account
    @Id
    val userId: String = "",

    @Column(nullable = false)
    val vehicleType: String = "",

    @Column(nullable = false)
    val licensePlate: String = "",

    val isAvailable: Boolean = false,

    // Rating as a driver — null until first rating received
    val avgRating: Double? = null,

    val ratingCount: Int = 0
)
