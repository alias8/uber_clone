package org.example.service

import org.example.model.RideStatus
import org.example.repository.RideRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Service

@Service
class KafkaConsumer(
    private val rideRepository: RideRepository
) {
    private val log = LoggerFactory.getLogger(KafkaConsumer::class.java)

    // When a ride is requested: log for audit, could notify nearby drivers here
    @KafkaListener(topics = ["ride-requested"], groupId = "feed-fanout-group")
    fun onRideRequested(rideId: String) {
        val ride = rideRepository.findById(rideId).orElse(null) ?: return
        log.info("Ride requested: id={} rider={} status={}", ride.id, ride.riderId, ride.status)
        // TODO: push notification to nearby driver apps
    }

    // When a driver accepts: notify the rider their driver is on the way
    @KafkaListener(topics = ["ride-accepted"], groupId = "feed-fanout-group")
    fun onRideAccepted(rideId: String) {
        val ride = rideRepository.findById(rideId).orElse(null) ?: return
        log.info("Ride accepted: id={} driver={} rider={}", ride.id, ride.driverId, ride.riderId)
        // TODO: push notification to rider — "your driver is on the way"
    }

    // When a ride completes: record analytics, trigger any billing integration
    @KafkaListener(topics = ["ride-completed"], groupId = "feed-fanout-group")
    fun onRideCompleted(rideId: String) {
        val ride = rideRepository.findById(rideId).orElse(null) ?: return
        if (ride.status != RideStatus.COMPLETED) return
        log.info("Ride completed: id={} fare={} driver={} rider={}", ride.id, ride.fare, ride.driverId, ride.riderId)
        // TODO: trigger payment processing, send receipt to rider
    }
}
