package org.example.service

import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service

@Service
class KafkaEventProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>
) {
    fun publishRideRequested(rideId: String) {
        kafkaTemplate.send("ride-requested", rideId)
    }

    fun publishRideAccepted(rideId: String) {
        kafkaTemplate.send("ride-accepted", rideId)
    }

    fun publishRideCompleted(rideId: String) {
        kafkaTemplate.send("ride-completed", rideId)
    }

    fun publishRideCancelled(rideId: String) {
        kafkaTemplate.send("ride-cancelled", rideId)
    }
}