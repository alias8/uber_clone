package org.example.service

import org.example.client.PricingClient
import org.example.model.Ride
import org.example.model.RideStatus
import org.example.dto.RideRequest
import org.example.repository.DriverRepository
import org.example.repository.RideRepository
import org.springframework.http.HttpStatus
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.Instant

/** The ride POST /rides returns, and whether it came from an earlier request with the same idempotency key. */
data class RideCreation(val ride: Ride, val replayed: Boolean)

@Service
class RideService(
    private val rideRepository: RideRepository,
    private val driverRepository: DriverRepository,
    private val driverService: DriverService,
    private val pricingClient: PricingClient,
    private val kafkaEventProducer: KafkaEventProducer,
    private val idempotencyKeys: IdempotencyKeys,
    transactionManager: PlatformTransactionManager
) {
    private val writeTx = TransactionTemplate(transactionManager)

    // Not @Transactional on purpose: it makes a gRPC call to pricing-service, and a transaction
    // would hold a DB connection across it. Only the inserts at the end run in a transaction
    // (writeTx). Its reads go to the primary like every read that doesn't opt in to the replica.
    //
    // With an idempotency key, the ride and the key are inserted in that one transaction, so they
    // commit or roll back together (see IdempotencyKeys). The controller has already checked for
    // a stored key; the two re-checks here cover a copy of this request running at the same time.
    fun requestRide(riderId: String, request: RideRequest, idempotencyKey: String? = null): RideCreation {
        val activeStatuses = listOf(RideStatus.REQUESTED, RideStatus.MATCHED, RideStatus.IN_PROGRESS)
        if (rideRepository.existsByRiderIdAndStatusIn(riderId, activeStatuses)) {
            // The active ride may be the one a concurrent copy of this request just committed.
            idempotencyKey?.let { idempotencyKeys.replay(riderId, it, request) }?.let { return RideCreation(it, replayed = true) }
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
        val saved = writeTx.execute { status ->
            val saved = rideRepository.saveAndFlush(ride.copy(estimatedFare = estimated))
            if (idempotencyKey != null && !idempotencyKeys.claim(riderId, idempotencyKey, request, saved.id)) {
                // Lost the race: a concurrent request with this key committed first. Undo this ride.
                status.setRollbackOnly()
                return@execute null
            }
            kafkaEventProducer.publishRideRequested(saved.id) // sent after commit, see KafkaEventProducer
            saved
        }
        if (saved == null) {
            val winner = idempotencyKeys.replay(riderId, idempotencyKey!!, request)
                ?: error("Idempotency key $idempotencyKey lost a race but has no stored ride")
            return RideCreation(winner, replayed = true)
        }
        return RideCreation(saved, replayed = false)
    }

    // Used inside the write methods below (read the ride, check its status, update it), so it
    // must be fresh: primary. Reads only go to the replica when code opts in through ReplicaReads
    // (see RideQueries); this doesn't. Spring Data marks findById readOnly = true, but under
    // opt-in routing that flag no longer picks the database.
    //
    // (Under the first version of this branch, where readOnly = true meant "replica", this
    // findById outside a transaction went to the replica, and startRide/completeRide/cancelRide
    // would have checked a stale status before writing. That surprise is why routing is now opt-in.)
    fun getRide(rideId: String): Ride =
        rideRepository.findById(rideId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Ride not found")
        }

    // Reads that may be stale (GET /rides/{id}, history) live in RideQueries.

    @Transactional(readOnly = false) // @Transactional means "run this method inside one DB transaction."  readOnly is an option on that annotation, and its default is false
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
            // saveAndFlush, not save: save() only queues the UPDATE in Hibernate's memory and it
            // would be sent at commit, after this method has returned and left the try. Flushing
            // now runs "UPDATE rides ... WHERE id = ? AND version = ?" here, so if another driver
            // accepted first (0 rows updated) the exception is caught below and becomes a 409.
            val saved = rideRepository.saveAndFlush(ride.copy(driverId = driverId, status = RideStatus.MATCHED))
            kafkaEventProducer.publishRideAccepted(saved.id) // sent after commit, see KafkaEventProducer
            return saved
        } catch (_: ObjectOptimisticLockingFailureException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Ride was already accepted by another driver")
        }
    }

    @Transactional(readOnly = false) // check-then-write as one unit: the status check and the update commit or roll back together
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

    @Transactional(readOnly = false) // see startRide
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

    @Transactional(readOnly = false) // see startRide
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

