package org.example.service

import org.example.model.RideStatus
import org.example.repository.RideRepository
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Service

@Service
class KafkaConsumer(
    private val rideRepository: RideRepository,
    private val dispatchService: DispatchService,
    private val emitterRegistry: EmitterRegistry,
    private val redisTemplate: RedisTemplate<String, String>
) {
    private val log = LoggerFactory.getLogger(KafkaConsumer::class.java)

    @KafkaListener(topics = ["ride-requested"], groupId = "feed-fanout-group")
    fun onRideRequested(rideId: String) {
        val ride = rideRepository.findById(rideId).orElse(null) ?: return
        log.info("Ride requested: id={} rider={} status={}", ride.id, ride.riderId, ride.status)
        dispatchService.fanoutToNearbyDrivers(ride)
    }

    @KafkaListener(topics = ["ride-accepted"], groupId = "feed-fanout-group")
    fun onRideAccepted(rideId: String) {
        val ride = rideRepository.findById(rideId).orElse(null) ?: return
        log.info("Ride accepted: id={} driver={} rider={}", ride.id, ride.driverId, ride.riderId)
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
        val ride = rideRepository.findById(rideId).orElse(null) ?: return
        if (ride.status != RideStatus.COMPLETED) return
        log.info("Ride completed: id={} fare={} driver={} rider={}", ride.id, ride.fare, ride.driverId, ride.riderId)
        emitterRegistry.complete(rideId)
        // TODO: trigger payment processing, send receipt to rider
    }

    @KafkaListener(topics = ["ride-cancelled"], groupId = "feed-fanout-group")
    fun onRideCancelled(rideId: String) {
        val ride = rideRepository.findById(rideId).orElse(null) ?: return
        log.info("Ride cancelled: id={} rider={}", ride.id, ride.riderId)
        emitterRegistry.complete(rideId)
    }
}
