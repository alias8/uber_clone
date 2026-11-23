package org.example.service

import org.example.utils.afterCommit
import org.slf4j.LoggerFactory
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

    private val log = LoggerFactory.getLogger(javaClass)

    // The DB change has already committed by the time this runs, so a Kafka failure must not turn
    // the request into a 500 (the client would retry something that already happened). Log it
    // instead: a lost event, the known no-outbox gap. Two ways it fails (application.properties):
    //  - send() itself throws after max.block.ms (2 s) when it can't get topic metadata;
    //  - the returned future fails after delivery.timeout.ms (10 s) of retries in the background.
    private fun send(topic: String, rideId: String) = afterCommit {
        try {
            kafkaTemplate.send(topic, rideId).whenComplete { _, ex ->
                if (ex != null) log.error("Lost event {} for ride {}: delivery failed", topic, rideId, ex)
            }
        } catch (ex: Exception) {
            log.error("Lost event {} for ride {}: send() timed out", topic, rideId, ex)
        }
    }
}
