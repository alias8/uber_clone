package org.example.service

import org.example.model.RideStatus
import org.example.repository.DriverRepository
import org.example.repository.RideRepository
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.TimeUnit

private const val SURGE_TTL_SECONDS = 30L
private const val SURGE_MAX_MULTIPLIER = 3.0
private const val SURGE_SEARCH_RADIUS_KM = 3.0

@Service
class SurgeService(
    private val rideRepository: RideRepository,
    private val driverRepository: DriverRepository,
    private val driverService: DriverService,
    private val redisTemplate: RedisTemplate<String, String>
) {
    // Grid cell key: round to 2 decimal places ≈ 1km resolution
    private fun surgeKey(lat: Double, lng: Double): String {
        val gridLat = "%.2f".format(lat)
        val gridLng = "%.2f".format(lng)
        return "surge:$gridLat:$gridLng"
    }

    fun getMultiplier(lat: Double, lng: Double): BigDecimal {
        val key = surgeKey(lat, lng)
        val cached = redisTemplate.opsForValue().get(key)
        if (cached != null) return BigDecimal(cached)

        val multiplier = compute(lat, lng)
        redisTemplate.opsForValue().set(key, multiplier.toPlainString(), SURGE_TTL_SECONDS, TimeUnit.SECONDS)
        return multiplier
    }

    private fun compute(lat: Double, lng: Double): BigDecimal {
        val availableDriverCount = driverService.findNearby(lat, lng, SURGE_SEARCH_RADIUS_KM).size

        if (availableDriverCount == 0) return BigDecimal(SURGE_MAX_MULTIPLIER)

        // Count REQUESTED rides with pickup within ~1km bounding box of this grid cell
        val delta = 0.01
        val pendingRides = rideRepository.countByStatusAndPickupLatBetweenAndPickupLngBetween(
            status = RideStatus.REQUESTED,
            latMin = lat - delta, latMax = lat + delta,
            lngMin = lng - delta, lngMax = lng + delta
        )

        val raw = (pendingRides.toDouble() / availableDriverCount).coerceIn(1.0, SURGE_MAX_MULTIPLIER)
        return BigDecimal(raw).setScale(2, RoundingMode.HALF_UP)
    }
}
