package org.example.service

import org.example.model.Ride
import org.example.model.RideStatus
import org.example.repository.RideRepository
import org.example.utils.etaMinutes
import org.example.utils.haversineKm
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Service

@Service
class KafkaConsumer(
    private val rideRepository: RideRepository,
    private val dispatchService: DispatchService,
    private val emitterRegistry: EmitterRegistry,
    private val driverService: DriverService,
    private val redisTemplate: RedisTemplate<String, String>
) {
    private val log = LoggerFactory.getLogger(KafkaConsumer::class.java)

    // Must read the primary, and does by default: it doesn't opt in through ReplicaReads.
    // The event can arrive within milliseconds of the save, often before a replica has the row;
    // there findById would return empty, the "?: return" below would drop the event, and the ride
    // wouldn't be dispatched until StaleRideRetryJob 2 minutes later, with no error anywhere.
    // Rule: a consumer reading the row its event is about never uses the replica.
    private fun loadRide(rideId: String): Ride? = rideRepository.findById(rideId).orElse(null)

    @KafkaListener(topics = ["ride-requested"], groupId = "feed-fanout-group")
    fun onRideRequested(rideId: String) {
        val ride = loadRide(rideId) ?: return
        if (ride.status != RideStatus.REQUESTED) return
        log.info("Ride requested: id={} rider={} status={}", ride.id, ride.riderId, ride.status)
        dispatchService.fanoutToNearbyDrivers(ride)
    }

    @KafkaListener(topics = ["ride-accepted"], groupId = "feed-fanout-group")
    fun onRideAccepted(rideId: String) {
        val ride = loadRide(rideId) ?: return
        log.info("Ride accepted: id={} driver={} rider={}", ride.id, ride.driverId, ride.riderId)

        ride.driverId?.let { driverId ->
            driverService.getDriverLocation(driverId)?.let { pos ->
                // pos.x = lng, pos.y = lat (Spring Data Geo convention)
                val eta = etaMinutes(haversineKm(pos.y, pos.x, ride.pickupLat, ride.pickupLng))
                emitterRegistry.emit(ride.id, "driver_eta_to_pickup", """{"etaMinutes":$eta}""")
            }
        }

        val dispatchedKey = "$DISPATCHED_KEY_PREFIX$rideId"
        val payload = """{"rideId":"$rideId"}"""
        redisTemplate.opsForSet().members(dispatchedKey)
            ?.filter { it != ride.driverId }
            ?.forEach { driverId -> emitterRegistry.emit(driverId, "offer_cancelled", payload) }
        redisTemplate.delete(dispatchedKey)
    }

    // When a ride completes: close rider's location stream, trigger payment, analytics
    @KafkaListener(topics = ["ride-completed"], groupId = "feed-fanout-group")
    fun onRideCompleted(rideId: String) {
        val ride = loadRide(rideId) ?: return
        if (ride.status != RideStatus.COMPLETED) return
        log.info("Ride completed: id={} fare={} driver={} rider={}", ride.id, ride.fare, ride.driverId, ride.riderId)
        emitterRegistry.complete(rideId)
        // TODO: trigger payment processing, send receipt to rider
    }

    @KafkaListener(topics = ["ride-cancelled"], groupId = "feed-fanout-group")
    fun onRideCancelled(rideId: String) {
        val ride = loadRide(rideId) ?: return
        log.info("Ride cancelled: id={} rider={}", ride.id, ride.riderId)
        emitterRegistry.complete(rideId)
    }
}
