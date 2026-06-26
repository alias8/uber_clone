package org.example.model

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(name = "rides")
data class Ride(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String = "",

    @Column(nullable = false)
    val riderId: String = "",

    // Null until a driver accepts
    val driverId: String? = null,

    @Column(nullable = false)
    val pickupLat: Double = 0.0,

    @Column(nullable = false)
    val pickupLng: Double = 0.0,

    @Column(nullable = false)
    val dropoffLat: Double = 0.0,

    @Column(nullable = false)
    val dropoffLng: Double = 0.0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val status: RideStatus = RideStatus.REQUESTED,

    // Null until ride completes
    val fare: BigDecimal? = null,

    @Column(nullable = false)
    val requestedAt: Instant = Instant.now(),

    val completedAt: Instant? = null,

    @Version
    val version: Long = 0
)
