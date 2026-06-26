package org.example.service

import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap

@Component
class EmitterRegistry {
    private val emitters = ConcurrentHashMap<String, SseEmitter>()

    fun register(key: String, emitter: SseEmitter) {
        emitters[key] = emitter
        emitter.onCompletion { emitters.remove(key) }
        emitter.onTimeout { emitters.remove(key) }
        emitter.onError { emitters.remove(key) }
    }

    fun emit(key: String, eventName: String, payload: String) {
        emitters[key]?.send(SseEmitter.event().name(eventName).data(payload).build())
    }

    fun complete(key: String) {
        emitters.remove(key)?.complete()
    }
}
