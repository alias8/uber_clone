package org.example.controller

import org.example.dto.RatingRequest
import org.example.dto.RideRequest
import org.example.dto.RideResponse
import org.example.dto.toResponse
import org.example.repository.UserRepository
import org.example.service.RateLimiterService
import org.example.service.TxRetry
import org.example.service.IdempotencyKeys
import org.example.service.RideCreation
import org.example.service.validIdempotencyKey
import org.example.service.RatingService
import org.example.service.RideService
import org.example.service.RideQueries
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
    private val rideQueries: RideQueries,
    private val ratingService: RatingService,
    private val rateLimiterService: RateLimiterService,
    private val userRepository: UserRepository,
    private val txRetry: TxRetry, // retries transient DB failures from outside the transaction
    private val idempotencyKeys: IdempotencyKeys
) {
    private fun resolveUserId(username: String): String =
        userRepository.findByUsername(username)?.id
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

    @PreAuthorize("hasRole('RIDER')")
    @PostMapping
    fun requestRide(
        @RequestBody request: RideRequest,
        @AuthenticationPrincipal username: String,
        // Optional: one UUID per "Request ride" tap, reused on every retry of it. See IdempotencyKeys.
        @RequestHeader("Idempotency-Key", required = false) rawIdempotencyKey: String?
    ): ResponseEntity<RideResponse> {
        val userId = resolveUserId(username)
        val idempotencyKey = rawIdempotencyKey?.let { validIdempotencyKey(it) }
        // Before the rate limiter: a retry isn't a new ride, so it shouldn't use up the allowance.
        idempotencyKey?.let { idempotencyKeys.replay(userId, it, request) }?.let { return created(RideCreation(it, replayed = true)) }
        // Prevents rider from requesting, then cancelling rapidly
        if (!rateLimiterService.allowRideRequest(userId)) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many ride requests — try again shortly")
        }
        return created(rideService.requestRide(userId, request, idempotencyKey))
    }

    // A replay gets the same 201 as the first response, flagged so clients and logs can tell.
    private fun created(result: RideCreation): ResponseEntity<RideResponse> {
        val response = ResponseEntity.status(HttpStatus.CREATED)
        if (result.replayed) response.header("Idempotent-Replayed", "true")
        return response.body(result.ride.toResponse())
    }

    @GetMapping("/{id}")
    fun getRide(@PathVariable id: String): ResponseEntity<RideResponse> =
        ResponseEntity.ok(rideQueries.rideForDisplay(id).toResponse())

    @PreAuthorize("hasRole('DRIVER')")
    @PostMapping("/{id}/accept")
    fun acceptRide(
        @PathVariable id: String,
        @AuthenticationPrincipal username: String
    ): ResponseEntity<RideResponse> {
        val userId = resolveUserId(username)
        return ResponseEntity.ok(txRetry.run { rideService.acceptRide(id, userId) }.toResponse())
    }

    @PreAuthorize("hasRole('DRIVER')")
    @PostMapping("/{id}/start")
    fun startRide(
        @PathVariable id: String,
        @AuthenticationPrincipal username: String
    ): ResponseEntity<RideResponse> {
        val userId = resolveUserId(username)
        return ResponseEntity.ok(txRetry.run { rideService.startRide(id, userId) }.toResponse())
    }

    @PreAuthorize("hasRole('DRIVER')")
    @PostMapping("/{id}/complete")
    fun completeRide(
        @PathVariable id: String,
        @AuthenticationPrincipal username: String
    ): ResponseEntity<RideResponse> {
        val userId = resolveUserId(username)
        return ResponseEntity.ok(txRetry.run { rideService.completeRide(id, userId) }.toResponse())
    }

    @PostMapping("/{id}/cancel")
    fun cancelRide(
        @PathVariable id: String,
        @AuthenticationPrincipal username: String
    ): ResponseEntity<RideResponse> {
        val userId = resolveUserId(username)
        return ResponseEntity.ok(txRetry.run { rideService.cancelRide(id, userId) }.toResponse())
    }

    @GetMapping("/history")
    fun history(
        @AuthenticationPrincipal username: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<Page<RideResponse>> {
        val userId = resolveUserId(username)
        val pageable = PageRequest.of(page, size, Sort.by("requestedAt").descending())
        return ResponseEntity.ok(rideQueries.riderHistory(userId, pageable).map { it.toResponse() })
    }

    @PostMapping("/{id}/rate")
    fun rate(
        @PathVariable id: String,
        @RequestBody request: RatingRequest,
        @AuthenticationPrincipal username: String
    ): ResponseEntity<Void> {
        val userId = resolveUserId(username)
        txRetry.run { ratingService.rate(id, userId, request.score, request.comment) }
        return ResponseEntity.noContent().build()
    }
}
