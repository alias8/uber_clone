package org.example.repository

import org.example.model.Ride
import org.example.model.RideStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface RideRepository : JpaRepository<Ride, String> {
    fun findByRiderIdOrderByRequestedAtDesc(riderId: String, pageable: Pageable): Page<Ride>
    fun findByDriverIdOrderByRequestedAtDesc(driverId: String, pageable: Pageable): Page<Ride>
    fun findFirstByDriverIdAndStatusIn(driverId: String, statuses: List<RideStatus>): Ride?
    fun existsByRiderIdAndStatusIn(riderId: String, statuses: List<RideStatus>): Boolean
    fun countByStatusAndPickupLatBetweenAndPickupLngBetween(
        status: RideStatus,
        latMin: Double, latMax: Double,
        lngMin: Double, lngMax: Double
    ): Long
}
