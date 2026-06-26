package org.example.service

import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap

@Component
class EmitterRegistry {
    private val emitters = ConcurrentHashMap<String, SseEmitter>()

    fun register(driverId: String, emitter: SseEmitter) {
        emitters[driverId] = emitter
        emitter.onCompletion { emitters.remove(driverId) }
        emitter.onTimeout { emitters.remove(driverId) }
        emitter.onError { emitters.remove(driverId) }
    }

    fun emit(driverId: String, payload: String) {
        emitters[driverId]?.send(SseEmitter.event().name("ride_offer").data(payload).build())
    }

    fun complete(driverId: String) {
        emitters.remove(driverId)?.complete()
    }
}
