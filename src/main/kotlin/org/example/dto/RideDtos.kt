package org.example.dto

import org.example.model.Ride
import org.example.model.RideStatus
import org.example.utils.etaMinutes
import org.example.utils.haversineKm
import java.math.BigDecimal
import java.time.Instant

data class RideRequest(
    val pickupLat: Double,
    val pickupLng: Double,
    val dropoffLat: Double,
    val dropoffLng: Double
)

data class RideResponse(
    val id: String,
    val riderId: String,
    val driverId: String?,
    val pickupLat: Double,
    val pickupLng: Double,
    val dropoffLat: Double,
    val dropoffLng: Double,
    val status: RideStatus,
    val estimatedFare: BigDecimal?,
    val fare: BigDecimal?,
    val requestedAt: Instant,
    val completedAt: Instant?,
    val estimatedJourneyMinutes: Int
)

fun Ride.toResponse() = RideResponse(
    id = id,
    riderId = riderId,
    driverId = driverId,
    pickupLat = pickupLat,
    pickupLng = pickupLng,
    dropoffLat = dropoffLat,
    dropoffLng = dropoffLng,
    status = status,
    estimatedFare = estimatedFare,
    fare = fare,
    requestedAt = requestedAt,
    completedAt = completedAt,
    estimatedJourneyMinutes = etaMinutes(haversineKm(pickupLat, pickupLng, dropoffLat, dropoffLng))
)
