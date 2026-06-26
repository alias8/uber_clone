package org.example.service

import org.example.model.Ride
import org.example.model.RideStatus
import org.example.dto.RideRequest
import org.example.repository.DriverRepository
import org.example.repository.RideRepository
import org.springframework.http.HttpStatus
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.math.*

@Service
class RideService(
    private val rideRepository: RideRepository,
    private val driverRepository: DriverRepository,
    private val driverService: DriverService,
    private val surgeService: SurgeService,
    private val kafkaEventProducer: KafkaEventProducer
) {
    fun requestRide(riderId: String, request: RideRequest): Ride {
        val ride = Ride(
            riderId = riderId,
            pickupLat = request.pickupLat,
            pickupLng = request.pickupLng,
            dropoffLat = request.dropoffLat,
            dropoffLng = request.dropoffLng
        )
        val estimated = calculateFare(ride)
        val saved = rideRepository.save(ride.copy(estimatedFare = estimated))
        kafkaEventProducer.publishRideRequested(saved.id)
        return saved
    }

    fun getRide(rideId: String): Ride =
        rideRepository.findById(rideId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Ride not found")
        }

    @Transactional
    fun acceptRide(rideId: String, driverId: String): Ride {
        val ride = getRide(rideId)
        if (ride.status != RideStatus.REQUESTED) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Ride is not available for acceptance")
        }
        val driver = driverRepository.findById(driverId).orElseThrow {
            ResponseStatusException(HttpStatus.FORBIDDEN, "No driver profile found — register as a driver first")
        }
        if (!driver.isAvailable) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Driver is not currently available")
        }

        try {
            driverService.markUnavailable(driverId)
            val saved = rideRepository.save(ride.copy(driverId = driverId, status = RideStatus.MATCHED))
            kafkaEventProducer.publishRideAccepted(saved.id)
            return saved
        } catch (_: ObjectOptimisticLockingFailureException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Ride was already accepted by another driver")
        }
    }

    fun startRide(rideId: String, driverId: String): Ride {
        val ride = getRide(rideId)
        if (ride.status != RideStatus.MATCHED) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Ride is not in MATCHED state")
        }
        if (ride.driverId != driverId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not the assigned driver for this ride")
        }
        return rideRepository.save(ride.copy(status = RideStatus.IN_PROGRESS))
    }

    fun completeRide(rideId: String, driverId: String): Ride {
        val ride = getRide(rideId)
        if (ride.status != RideStatus.IN_PROGRESS) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Ride is not IN_PROGRESS")
        }
        if (ride.driverId != driverId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not the assigned driver for this ride")
        }

        val saved = rideRepository.save(
            ride.copy(status = RideStatus.COMPLETED, fare = ride.estimatedFare, completedAt = Instant.now())
        )

        driverService.markAvailable(driverId)
        kafkaEventProducer.publishRideCompleted(saved.id)
        return saved
    }

    fun cancelRide(rideId: String, userId: String): Ride {
        val ride = getRide(rideId)
        if (ride.status == RideStatus.IN_PROGRESS || ride.status == RideStatus.COMPLETED) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Cannot cancel a ride with status ${ride.status}")
        }
        if (ride.riderId != userId && ride.driverId != userId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not a participant in this ride")
        }

        ride.driverId?.let { driverService.markAvailable(it) }

        val saved = rideRepository.save(ride.copy(status = RideStatus.CANCELLED))
        kafkaEventProducer.publishRideCancelled(saved.id)
        return saved
    }

    internal fun calculateFare(ride: Ride): BigDecimal {
        val distanceKm = haversineKm(ride.pickupLat, ride.pickupLng, ride.dropoffLat, ride.dropoffLng)
        val base = BigDecimal("2.00")
        val perKm = BigDecimal("1.50")
        val baseFare = base + perKm * BigDecimal(distanceKm)
        val surge = surgeService.getMultiplier(ride.pickupLat, ride.pickupLng)
        return (baseFare * surge).setScale(2, RoundingMode.HALF_UP)
    }
}

fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    return r * 2 * asin(sqrt(a))
}
