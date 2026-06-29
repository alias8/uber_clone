package org.example.service

import org.example.model.RideStatus
import org.example.repository.RideRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

@Component
class StaleRideRetryJob(
    private val rideRepository: RideRepository,
    private val kafkaEventProducer: KafkaEventProducer
) {
    private val log = LoggerFactory.getLogger(StaleRideRetryJob::class.java)

    @Scheduled(fixedDelay = 60_000)
    fun retryStaleRides() {
        val cutoff = Instant.now().minus(2, ChronoUnit.MINUTES)
        val stale = rideRepository.findByStatusAndRequestedAtBefore(RideStatus.REQUESTED, cutoff)
        if (stale.isEmpty()) return

        log.info("Retrying {} stale ride(s)", stale.size)
        stale.forEach { ride ->
            log.info("Re-dispatching stale ride {}, requested at {}", ride.id, ride.requestedAt)
            kafkaEventProducer.publishRideRequested(ride.id)
        }
    }
}
