package org.example.service

import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap

@Component
class RiderLocationEmitterRegistry {
    private val emitters = ConcurrentHashMap<String, SseEmitter>()

    fun register(rideId: String, emitter: SseEmitter) {
        emitters[rideId] = emitter
        emitter.onCompletion { emitters.remove(rideId) }
        emitter.onTimeout { emitters.remove(rideId) }
        emitter.onError { emitters.remove(rideId) }
    }

    fun emit(rideId: String, lat: Double, lng: Double) {
        emitters[rideId]?.send(
            SseEmitter.event().name("driver_location").data("""{"lat":$lat,"lng":$lng}""").build()
        )
    }
}
