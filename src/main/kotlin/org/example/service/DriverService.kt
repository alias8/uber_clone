package org.example.service

import org.example.dto.DriverRegisterRequest
import org.example.dto.NearbyDriverResponse
import org.example.model.Driver
import org.example.model.RideStatus
import org.example.repository.DriverRepository
import org.example.repository.RideRepository
import org.springframework.data.geo.Circle
import org.springframework.data.geo.Distance
import org.springframework.data.geo.Metrics
import org.springframework.data.geo.Point
import org.springframework.data.redis.connection.RedisGeoCommands
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

private const val DRIVER_GEO_KEY = "drivers:locations"

private val ACTIVE_RIDE_STATUSES = listOf(RideStatus.MATCHED, RideStatus.IN_PROGRESS)

@Service
class DriverService(
    private val driverRepository: DriverRepository,
    private val redisTemplate: RedisTemplate<String, String>,
    private val rideRepository: RideRepository,
    private val emitterRegistry: EmitterRegistry
) {
    private val geo get() = redisTemplate.opsForGeo()

    fun registerDriver(userId: String, request: DriverRegisterRequest): Driver {
        if (driverRepository.existsById(userId)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Driver profile already exists")
        }
        return driverRepository.save(
            Driver(
                userId = userId,
                vehicleType = request.vehicleType,
                licensePlate = request.licensePlate,
                isAvailable = false
            )
        )
    }

    fun getProfile(userId: String): Driver =
        driverRepository.findById(userId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "No driver profile found")
        }

    fun goOnline(userId: String, lat: Double, lng: Double): Driver {
        val driver = getProfile(userId)
        geo.add(DRIVER_GEO_KEY, Point(lng, lat), userId)
        return driverRepository.save(driver.copy(isAvailable = true))
    }

    fun goOffline(userId: String): Driver {
        val driver = getProfile(userId)
        redisTemplate.opsForZSet().remove(DRIVER_GEO_KEY, userId)
        val saved = driverRepository.save(driver.copy(isAvailable = false))
        emitterRegistry.complete(userId)
        return saved
    }

    fun updateLocation(userId: String, lat: Double, lng: Double) {
        getProfile(userId)
        geo.add(DRIVER_GEO_KEY, Point(lng, lat), userId)
        rideRepository.findFirstByDriverIdAndStatusIn(userId, ACTIVE_RIDE_STATUSES)
            ?.let { emitterRegistry.emit(it.id, "driver_location", """{"lat":$lat,"lng":$lng}""") }
    }

    fun findNearby(lat: Double, lng: Double, radiusKm: Double): List<NearbyDriverResponse> {
        val args = RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
            .includeDistance()
            .sortAscending()
            .limit(20)

        val results = geo.radius(
            DRIVER_GEO_KEY,
            Circle(Point(lng, lat), Distance(radiusKm, Metrics.KILOMETERS)),
            args
        ) ?: return emptyList()

        val availableIds = driverRepository.findByIsAvailableTrue().map { it.userId }.toSet()

        return results.content
            .filter { it.content.name in availableIds }
            .map { NearbyDriverResponse(driverId = it.content.name, distanceKm = it.distance.value) }
    }

    // Returns the nearest available driver's userId, or null if none found within radius
    fun findNearestAvailableDriver(lat: Double, lng: Double, radiusKm: Double = 5.0): String? =
        findNearby(lat, lng, radiusKm).firstOrNull()?.driverId
}
