package org.example.service

import org.example.client.PricingClient
import org.example.model.Ride
import org.example.model.RideStatus
import org.example.dto.RideRequest
import org.example.repository.DriverRepository
import org.example.repository.RideRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.Instant

@Service
class RideService(
    private val rideRepository: RideRepository,
    private val driverRepository: DriverRepository,
    private val driverService: DriverService,
    private val pricingClient: PricingClient,
    private val kafkaEventProducer: KafkaEventProducer
) {
    // Not @Transactional on purpose: it makes a gRPC call to pricing-service, and a transaction
    // would hold a DB connection across it. Its reads are safe anyway: existsBy... is a derived
    // query, which Spring Data does NOT mark read-only, so it runs outside a read-only
    // transaction and goes to the primary.
    fun requestRide(riderId: String, request: RideRequest): Ride {
        val activeStatuses = listOf(RideStatus.REQUESTED, RideStatus.MATCHED, RideStatus.IN_PROGRESS)
        if (rideRepository.existsByRiderIdAndStatusIn(riderId, activeStatuses)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Rider already has an active ride")
        }
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

    // Used inside the write methods below (read the ride, check its status, update it). Those are
    // @Transactional, so this joins their read-write transaction and reads the primary.
    //
    // Gotcha: findById comes from SimpleJpaRepository, which Spring Data marks
    // @Transactional(readOnly = true). Called OUTSIDE a transaction, it starts its own read-only
    // one, and the router sends it to the replica. Before routing existed that flag did nothing
    // visible, so startRide/completeRide/cancelRide had no @Transactional and nobody noticed.
    // With routing on, they would check a stale status from the replica and then write.
    fun getRide(rideId: String): Ride =
        rideRepository.findById(rideId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Ride not found")
        }

    // ----- Reads that can be a little stale: replica -----

    // GET /rides/{id}: the rider's app polls this. A driver accepting shows up a few ms late,
    // which nobody notices. The rider's OWN fresh write (just requested the ride) is covered by
    // RecentWriteInterceptor, which forces the primary for 5 s after they write.
    @Transactional(readOnly = true)
    fun getRideForDisplay(rideId: String): Ride = getRide(rideId)

    // Ride history: a second stale is fine, and it's the kind of varied, paged read that a
    // replica is for (too varied to cache well).
    @Transactional(readOnly = true)
    fun getRiderHistory(riderId: String, pageable: Pageable): Page<Ride> =
        rideRepository.findByRiderIdOrderByRequestedAtDesc(riderId, pageable)

    // ----- Writes: primary -----

    @Transactional // @Transactional means "run this method inside one DB transaction."  readOnly is an option on that annotation, and its default is false
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

    @Transactional // added for routing: keeps getRide's check-then-write on the primary
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

    @Transactional // added for routing (see startRide)
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

    @Transactional // added for routing (see startRide)
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

    // Calls out to pricing-service (a separate process, over gRPC) rather than computing the fare
    // in-process — see pricing-service/README.md for why that call is synchronous on this path.
    internal fun calculateFare(ride: Ride): BigDecimal =
        pricingClient.getFareQuote(ride.pickupLat, ride.pickupLng, ride.dropoffLat, ride.dropoffLng).fare
}

