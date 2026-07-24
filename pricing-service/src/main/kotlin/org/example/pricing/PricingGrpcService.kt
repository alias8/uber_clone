package org.example.pricing

import io.grpc.stub.StreamObserver
import org.example.pricing.grpc.FareQuoteRequest
import org.example.pricing.grpc.FareQuoteResponse
import org.example.pricing.grpc.PricingServiceGrpc
import org.example.pricing.model.RideStatus
import org.example.pricing.repository.RideRepository
import org.springframework.data.geo.Circle
import org.springframework.data.geo.Distance
import org.springframework.data.geo.Metrics
import org.springframework.data.geo.Point
import org.springframework.data.redis.connection.RedisGeoCommands
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.TimeUnit

private const val SURGE_TTL_SECONDS = 30L
private const val SURGE_MAX_MULTIPLIER = 3.0
private const val SURGE_SEARCH_RADIUS_KM = 3.0
private const val DRIVER_GEO_KEY = "drivers:locations"
private const val DRIVER_AVAILABLE_SET = "drivers:available"

// Ported from uber_clone's SurgeService + RideService.calculateFare — this service now owns
// fare/surge as a synchronous call on the ride-request critical path. Reads uber_clone's Redis
// geo-index and Postgres ride table directly rather than replicating that state via events;
// see README for why that's an acceptable simplification here but not in production.
@Service
class PricingGrpcService(
    private val rideRepository: RideRepository,
    private val redisTemplate: RedisTemplate<String, String>
) : PricingServiceGrpc.PricingServiceImplBase() {

    override fun getFareQuote(request: FareQuoteRequest, responseObserver: StreamObserver<FareQuoteResponse>) {
        val distanceKm = haversineKm(request.pickupLat, request.pickupLng, request.dropoffLat, request.dropoffLng)
        val base = BigDecimal("2.00")
        val perKm = BigDecimal("1.50")
        val baseFare = base + perKm * BigDecimal(distanceKm)
        val surge = getSurgeMultiplier(request.pickupLat, request.pickupLng)
        val fare = (baseFare * surge).setScale(2, RoundingMode.HALF_UP)

        val response = FareQuoteResponse.newBuilder()
            .setFare(fare.toPlainString())
            .setSurgeMultiplier(surge.toPlainString())
            .build()

        responseObserver.onNext(response)
        responseObserver.onCompleted()
    }

    private fun getSurgeMultiplier(lat: Double, lng: Double): BigDecimal {
        val key = surgeKey(lat, lng)
        val cached = redisTemplate.opsForValue().get(key)
        if (cached != null) return BigDecimal(cached)

        val multiplier = computeSurge(lat, lng)
        redisTemplate.opsForValue().set(key, multiplier.toPlainString(), SURGE_TTL_SECONDS, TimeUnit.SECONDS)
        return multiplier
    }

    // Grid cell key: round to 2 decimal places ≈ 1km resolution
    private fun surgeKey(lat: Double, lng: Double): String {
        val gridLat = "%.2f".format(lat)
        val gridLng = "%.2f".format(lng)
        return "surge:$gridLat:$gridLng"
    }

    private fun computeSurge(lat: Double, lng: Double): BigDecimal {
        val availableDriverCount = nearbyAvailableDriverCount(lat, lng, SURGE_SEARCH_RADIUS_KM)
        if (availableDriverCount == 0) return BigDecimal(SURGE_MAX_MULTIPLIER)

        val delta = 0.01
        val pendingRides = rideRepository.countByStatusAndPickupLatBetweenAndPickupLngBetween(
            status = RideStatus.REQUESTED,
            latMin = lat - delta, latMax = lat + delta,
            lngMin = lng - delta, lngMax = lng + delta
        )

        val raw = (pendingRides.toDouble() / availableDriverCount).coerceIn(1.0, SURGE_MAX_MULTIPLIER)
        return BigDecimal(raw).setScale(2, RoundingMode.HALF_UP)
    }

    private fun nearbyAvailableDriverCount(lat: Double, lng: Double, radiusKm: Double): Int {
        val args = RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().limit(50)
        val results = redisTemplate.opsForGeo().radius(
            DRIVER_GEO_KEY,
            Circle(Point(lng, lat), Distance(radiusKm, Metrics.KILOMETERS)),
            args
        ) ?: return 0

        return results.content.count {
            redisTemplate.opsForSet().isMember(DRIVER_AVAILABLE_SET, it.content.name) == true
        }
    }
}
