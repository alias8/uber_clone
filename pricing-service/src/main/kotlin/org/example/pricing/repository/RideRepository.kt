package org.example.pricing.repository

import org.example.pricing.model.Ride
import org.example.pricing.model.RideStatus
import org.springframework.data.jpa.repository.JpaRepository

interface RideRepository : JpaRepository<Ride, String> {
    fun countByStatusAndPickupLatBetweenAndPickupLngBetween(
        status: RideStatus,
        latMin: Double, latMax: Double,
        lngMin: Double, lngMax: Double
    ): Long
}
