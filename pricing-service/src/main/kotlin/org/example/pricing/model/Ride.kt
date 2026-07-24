package org.example.pricing.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table

// Read-only projection onto the `rides` table that uber_clone owns and migrates —
// only the columns the surge calculation needs.
@Entity
@Table(name = "rides")
data class Ride(
    @Id
    val id: String = "",

    @Column(nullable = false)
    val pickupLat: Double = 0.0,

    @Column(nullable = false)
    val pickupLng: Double = 0.0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val status: RideStatus = RideStatus.REQUESTED
)
