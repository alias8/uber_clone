package org.example.controller

import jakarta.servlet.http.HttpServletResponse
import org.example.dto.*
import org.example.model.Role
import org.example.repository.RideRepository
import org.example.repository.UserRepository
import org.example.security.JwtCookieService
import org.example.service.DriverService
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/driver")
class DriverController(
    private val driverService: DriverService,
    private val rideRepository: RideRepository,
    private val userRepository: UserRepository,
    private val jwtCookieService: JwtCookieService
) {
    private fun resolveUserId(username: String): String =
        userRepository.findByUsername(username)?.id
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

    @PostMapping("/register")
    fun register(
        @RequestBody request: DriverRegisterRequest,
        @AuthenticationPrincipal username: String,
        response: HttpServletResponse
    ): ResponseEntity<DriverProfileResponse> {
        val userId = resolveUserId(username)
        val result = driverService.registerDriver(userId, request)
        userRepository.findByUsername(username)?.let { userRepository.save(it.copy(role = Role.DRIVER)) }
        response.addCookie(jwtCookieService.issueTokenCookie(username, Role.DRIVER))
        return ResponseEntity.status(HttpStatus.CREATED).body(result.toResponse())
    }

    @PreAuthorize("hasRole('DRIVER')")
    @GetMapping("/profile")
    fun profile(@AuthenticationPrincipal username: String): ResponseEntity<DriverProfileResponse> {
        val userId = resolveUserId(username)
        return ResponseEntity.ok(driverService.getProfile(userId).toResponse())
    }

    @PreAuthorize("hasRole('DRIVER')")
    @PostMapping("/mode/on")
    fun goOnline(
        @RequestBody request: DriverLocationRequest,
        @AuthenticationPrincipal username: String
    ): ResponseEntity<DriverProfileResponse> {
        val userId = resolveUserId(username)
        return ResponseEntity.ok(driverService.goOnline(userId, request.lat, request.lng).toResponse())
    }

    @PreAuthorize("hasRole('DRIVER')")
    @PostMapping("/mode/off")
    fun goOffline(@AuthenticationPrincipal username: String): ResponseEntity<DriverProfileResponse> {
        val userId = resolveUserId(username)
        return ResponseEntity.ok(driverService.goOffline(userId).toResponse())
    }

    @PreAuthorize("hasRole('DRIVER')")
    @PostMapping("/location")
    fun updateLocation(
        @RequestBody request: DriverLocationRequest,
        @AuthenticationPrincipal username: String
    ): ResponseEntity<Void> {
        val userId = resolveUserId(username)
        driverService.updateLocation(userId, request.lat, request.lng)
        return ResponseEntity.noContent().build()
    }

    @PreAuthorize("hasRole('DRIVER')")
    @GetMapping("/rides")
    fun rideHistory(
        @AuthenticationPrincipal username: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<Page<RideResponse>> {
        val userId = resolveUserId(username)
        val pageable = PageRequest.of(page, size, Sort.by("requestedAt").descending())
        return ResponseEntity.ok(rideRepository.findByDriverIdOrderByRequestedAtDesc(userId, pageable).map { it.toResponse() })
    }

    @PreAuthorize("hasRole('DRIVER')")
    @GetMapping("/nearby")
    fun nearby(
        @RequestParam lat: Double,
        @RequestParam lng: Double,
        @RequestParam(defaultValue = "5.0") radiusKm: Double
    ): ResponseEntity<List<NearbyDriverResponse>> =
        ResponseEntity.ok(driverService.findNearby(lat, lng, radiusKm))
}
