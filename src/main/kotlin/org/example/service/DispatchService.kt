package org.example.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.example.model.Ride
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

internal const val RIDE_OFFER_CHANNEL_PREFIX = "ride_offers:"
internal const val DISPATCHED_KEY_PREFIX = "dispatched:"
private const val DISPATCHED_TTL_MINUTES = 5L

@Service
class DispatchService(
    private val driverService: DriverService,
    private val redisTemplate: RedisTemplate<String, String>,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(DispatchService::class.java)

    fun fanoutToNearbyDrivers(ride: Ride) {
        val nearby = driverService.findNearby(ride.pickupLat, ride.pickupLng, radiusKm = 5.0)
        if (nearby.isEmpty()) {
            log.warn("No nearby drivers for ride {}", ride.id)
            return
        }
        val payload = objectMapper.writeValueAsString(
            mapOf(
                "rideId" to ride.id,
                "pickupLat" to ride.pickupLat,
                "pickupLng" to ride.pickupLng,
                "dropoffLat" to ride.dropoffLat,
                "dropoffLng" to ride.dropoffLng,
                "estimatedFare" to ride.estimatedFare
            )
        )
        val driverIds = nearby.map { it.driverId }.toTypedArray()
        redisTemplate.opsForSet().add("$DISPATCHED_KEY_PREFIX${ride.id}", *driverIds)
        redisTemplate.expire("$DISPATCHED_KEY_PREFIX${ride.id}", DISPATCHED_TTL_MINUTES, TimeUnit.MINUTES)

        nearby.forEach { driver ->
            redisTemplate.convertAndSend("$RIDE_OFFER_CHANNEL_PREFIX${driver.driverId}", payload)
            log.info("Dispatched offer for ride {} to driver {} ({} km away)", ride.id, driver.driverId, driver.distanceKm)
        }
    }
}
