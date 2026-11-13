package org.example.service

import org.example.utils.afterCommit
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service

// Every event waits for the caller's transaction to commit (see afterCommit). Before this, the
// event went out while the ride change was still uncommitted, so a fast consumer could read the
// old row: e.g. ride-accepted seen with driverId = null, cancelling the offer for every driver
// including the winner. And a driver who lost the accept race still sent ride-accepted.
@Service
class KafkaEventProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>
) {
    fun publishRideRequested(rideId: String) = send("ride-requested", rideId)

    fun publishRideAccepted(rideId: String) = send("ride-accepted", rideId)

    fun publishRideCompleted(rideId: String) = send("ride-completed", rideId)

    fun publishRideCancelled(rideId: String) = send("ride-cancelled", rideId)

    private fun send(topic: String, rideId: String) = afterCommit { kafkaTemplate.send(topic, rideId) }
}
