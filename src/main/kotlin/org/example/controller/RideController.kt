package org.example.controller

import org.example.dto.RatingRequest
import org.example.dto.RideRequest
import org.example.dto.RideResponse
import org.example.dto.toResponse
import org.example.repository.RideRepository
import org.example.repository.UserRepository
import org.example.service.RateLimiterService
import org.example.service.RatingService
import org.example.service.RideService
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
@RequestMapping("/rides")
class RideController(
    private val rideService: RideService,
    private val ratingService: RatingService,
    private val rateLimiterService: RateLimiterService,
    private val rideRepository: RideRepository,
    private val userRepository: UserRepository
) {
    private fun resolveUserId(username: String): String =
        userRepository.findByUsername(username)?.id
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

    @PreAuthorize("hasRole('RIDER')")
    @PostMapping
    fun requestRide(
        @RequestBody request: RideRequest,
        @AuthenticationPrincipal username: String
    ): ResponseEntity<RideResponse> {
        val userId = resolveUserId(username)
        // Prevents rider from requesting, then cancelling rapidly 
        if (!rateLimiterService.allowRideRequest(userId)) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many ride requests — try again shortly")
        }
        val ride = rideService.requestRide(userId, request)
        return ResponseEntity.status(HttpStatus.CREATED).body(ride.toResponse())
    }

    @GetMapping("/{id}")
    fun getRide(@PathVariable id: String): ResponseEntity<RideResponse> =
        ResponseEntity.ok(rideService.getRide(id).toResponse())

    @PreAuthorize("hasRole('DRIVER')")
    @PostMapping("/{id}/accept")
    fun acceptRide(
        @PathVariable id: String,
        @AuthenticationPrincipal username: String
    ): ResponseEntity<RideResponse> {
        val userId = resolveUserId(username)
        return ResponseEntity.ok(rideService.acceptRide(id, userId).toResponse())
    }

    @PreAuthorize("hasRole('DRIVER')")
    @PostMapping("/{id}/start")
    fun startRide(
        @PathVariable id: String,
        @AuthenticationPrincipal username: String
    ): ResponseEntity<RideResponse> {
        val userId = resolveUserId(username)
        return ResponseEntity.ok(rideService.startRide(id, userId).toResponse())
    }

    @PreAuthorize("hasRole('DRIVER')")
    @PostMapping("/{id}/complete")
    fun completeRide(
        @PathVariable id: String,
        @AuthenticationPrincipal username: String
    ): ResponseEntity<RideResponse> {
        val userId = resolveUserId(username)
        return ResponseEntity.ok(rideService.completeRide(id, userId).toResponse())
    }

    @PostMapping("/{id}/cancel")
    fun cancelRide(
        @PathVariable id: String,
        @AuthenticationPrincipal username: String
    ): ResponseEntity<RideResponse> {
        val userId = resolveUserId(username)
        return ResponseEntity.ok(rideService.cancelRide(id, userId).toResponse())
    }

    @GetMapping("/history")
    fun history(
        @AuthenticationPrincipal username: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<Page<RideResponse>> {
        val userId = resolveUserId(username)
        val pageable = PageRequest.of(page, size, Sort.by("requestedAt").descending())
        return ResponseEntity.ok(rideRepository.findByRiderIdOrderByRequestedAtDesc(userId, pageable).map { it.toResponse() })
    }

    @PostMapping("/{id}/rate")
    fun rate(
        @PathVariable id: String,
        @RequestBody request: RatingRequest,
        @AuthenticationPrincipal username: String
    ): ResponseEntity<Void> {
        val userId = resolveUserId(username)
        ratingService.rate(id, userId, request.score, request.comment)
        return ResponseEntity.noContent().build()
    }
}
