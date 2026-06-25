package org.example.dto

import org.example.model.Driver

data class DriverRegisterRequest(
    val vehicleType: String,
    val licensePlate: String
)

data class DriverProfileResponse(
    val userId: String,
    val vehicleType: String,
    val licensePlate: String,
    val isAvailable: Boolean,
    val avgRating: Double?
)

data class DriverLocationRequest(
    val lat: Double,
    val lng: Double
)

data class NearbyDriverResponse(
    val driverId: String,
    val distanceKm: Double
)

fun Driver.toResponse() = DriverProfileResponse(
    userId = userId,
    vehicleType = vehicleType,
    licensePlate = licensePlate,
    isAvailable = isAvailable,
    avgRating = avgRating
)
