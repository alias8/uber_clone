package org.example.service

import org.example.dto.DriverRegisterRequest
import org.example.dto.NearbyDriverResponse
import org.example.model.Driver
import org.example.model.RideStatus
import org.example.repository.DriverRepository
import org.example.repository.RideRepository
import org.example.utils.afterCommit
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
import org.springframework.transaction.annotation.Transactional
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

    // @Transactional added on the read-then-write methods below for replica routing: otherwise
    // findById/existsById (read-only in Spring Data) would run on the replica and the write
    // would be based on stale data. See RideService.getRide.
    @Transactional
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

    @Transactional
    fun goOnline(userId: String, lat: Double, lng: Double): Driver {
        val driver = getProfile(userId)
        geo.add(DRIVER_GEO_KEY, Point(lng, lat), userId)
        return markAvailable(driver)
    }

    @Transactional
    fun goOffline(userId: String): Driver {
        val driver = getProfile(userId)
        redisTemplate.opsForZSet().remove(DRIVER_GEO_KEY, userId)
        val saved = markUnavailable(driver)
        emitterRegistry.complete(userId)
        return saved
    }

    @Transactional
    fun markAvailable(userId: String) {
        driverRepository.findById(userId).ifPresent { markAvailable(it) }
    }

    @Transactional
    fun markUnavailable(userId: String) {
        driverRepository.findById(userId).ifPresent { markUnavailable(it) }
    }

    // The DB row is the source of truth; the Redis set is a copy for fast dispatch lookups.
    // Redis isn't part of the DB transaction, so it's updated only after commit. Before, a
    // rolled-back acceptRide (lost the race) left the driver removed from the Redis set while
    // the DB still said available, and no new offers reached them.
    // If the process dies between commit and the Redis call, Redis is briefly wrong; acceptRide
    // checks driver.isAvailable in the DB, so the worst case is a wasted offer, not a double booking.
    private fun markAvailable(driver: Driver): Driver {
        val saved = driverRepository.save(driver.copy(isAvailable = true))
        afterCommit { redisTemplate.opsForSet().add(DRIVER_AVAILABLE_SET, driver.userId) }
        return saved
    }

    private fun markUnavailable(driver: Driver): Driver {
        val saved = driverRepository.save(driver.copy(isAvailable = false))
        afterCommit { redisTemplate.opsForSet().remove(DRIVER_AVAILABLE_SET, driver.userId) }
        return saved
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

        val content = results.content
        if (content.isEmpty()) return emptyList()
        
        val driverIds = content.map { it.content.name }.toTypedArray()
        // Batched via SMISMEMBER (one round trip) instead of one isMember() call per driver.
        // availability looks like 
//        {
//            "driver-1" to true,
//            "driver-2" to false,
//            "driver-3" to true
//        }
        val availability = redisTemplate.opsForSet().isMember(DRIVER_AVAILABLE_SET, *driverIds)

        return content
            .filter { availability[it.content.name] == true }
            .map { NearbyDriverResponse(driverId = it.content.name, distanceKm = it.distance.value) }
    }
}
