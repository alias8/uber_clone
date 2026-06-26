package org.example.service

import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.MessageListener
import org.springframework.stereotype.Component

@Component
class RideOfferListener(
    private val emitterRegistry: EmitterRegistry
) : MessageListener {
    private val log = LoggerFactory.getLogger(RideOfferListener::class.java)

    override fun onMessage(message: Message, pattern: ByteArray?) {
        val channel = String(message.channel)
        val driverId = channel.removePrefix(RIDE_OFFER_CHANNEL_PREFIX)
        val payload = String(message.body)
        try {
            emitterRegistry.emit(driverId, "ride_offer", payload)
        } catch (e: Exception) {
            log.warn("Failed to push offer to driver {}: {}", driverId, e.message)
        }
    }
}
