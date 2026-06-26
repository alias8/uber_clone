package org.example.controller

import org.example.model.RideStatus
import org.example.repository.UserRepository
import org.example.service.RideService
import org.example.service.EmitterRegistry
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@RequestMapping("/rides")
class RideLocationController(
    private val rideService: RideService,
    private val emitterRegistry: EmitterRegistry,
    private val userRepository: UserRepository
) {
    // Rider holds this connection open after MATCHED to track driver position in real time.
    @GetMapping("/{id}/location", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamDriverLocation(
        @PathVariable id: String,
        @AuthenticationPrincipal username: String
    ): SseEmitter {
        val userId = userRepository.findByUsername(username)?.id
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val ride = rideService.getRide(id)
        if (ride.riderId != userId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN)
        }
        if (ride.status != RideStatus.MATCHED && ride.status != RideStatus.IN_PROGRESS) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "No active driver to track for this ride")
        }
        val emitter = SseEmitter(Long.MAX_VALUE)
        emitterRegistry.register(id, emitter)
        return emitter
    }
}
