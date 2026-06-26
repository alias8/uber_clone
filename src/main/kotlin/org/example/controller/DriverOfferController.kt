package org.example.controller

import org.example.repository.UserRepository
import org.example.service.EmitterRegistry
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@RequestMapping("/driver")
class DriverOfferController(
    private val emitterRegistry: EmitterRegistry,
    private val userRepository: UserRepository
) {
    // Driver app connects here and holds the connection open; ride offers are pushed as SSE events.
    @GetMapping("/offers", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamOffers(@AuthenticationPrincipal username: String): SseEmitter {
        val userId = userRepository.findByUsername(username)?.id
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val emitter = SseEmitter(Long.MAX_VALUE)
        emitterRegistry.register(userId, emitter)
        return emitter
    }
}
