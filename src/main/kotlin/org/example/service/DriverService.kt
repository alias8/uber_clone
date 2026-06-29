package org.example.service

import org.example.dto.DriverRegisterRequest
import org.example.dto.NearbyDriverResponse
import org.example.model.Driver
import org.example.model.RideStatus
import org.example.repository.DriverRepository
import org.example.repository.RideRepository
import org.example.utils.etaMinutes
import org.example.utils.haversineKm
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
private const val DRIVER_AVAILABLE_SET = "drivers:available"

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
        return markAvailable(driver)
    }

    fun goOffline(userId: String): Driver {
        val driver = getProfile(userId)
        redisTemplate.opsForZSet().remove(DRIVER_GEO_KEY, userId)
        val saved = markUnavailable(driver)
        emitterRegistry.complete(userId)
        return saved
    }

    fun markAvailable(userId: String) {
        driverRepository.findById(userId).ifPresent { markAvailable(it) }
    }

    fun markUnavailable(userId: String) {
        driverRepository.findById(userId).ifPresent { markUnavailable(it) }
    }

    private fun markAvailable(driver: Driver): Driver {
        redisTemplate.opsForSet().add(DRIVER_AVAILABLE_SET, driver.userId)
        return driverRepository.save(driver.copy(isAvailable = true))
    }

    private fun markUnavailable(driver: Driver): Driver {
        redisTemplate.opsForSet().remove(DRIVER_AVAILABLE_SET, driver.userId)
        return driverRepository.save(driver.copy(isAvailable = false))
    }

    fun updateLocation(userId: String, lat: Double, lng: Double) {
        getProfile(userId)
        geo.add(DRIVER_GEO_KEY, Point(lng, lat), userId)
        rideRepository.findFirstByDriverIdAndStatusIn(userId, ACTIVE_RIDE_STATUSES)
            ?.let { ride ->
                val eta = etaMinutes(haversineKm(lat, lng, ride.dropoffLat, ride.dropoffLng))
                emitterRegistry.emit(ride.id, "driver_location", """{"lat":$lat,"lng":$lng,"etaMinutes":$eta}""")
            }
    }

    fun getDriverLocation(driverId: String): org.springframework.data.geo.Point? =
        geo.position(DRIVER_GEO_KEY, driverId)?.firstOrNull()

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

        return results.content
            .filter { redisTemplate.opsForSet().isMember(DRIVER_AVAILABLE_SET, it.content.name) == true }
            .map { NearbyDriverResponse(driverId = it.content.name, distanceKm = it.distance.value) }
    }
}
