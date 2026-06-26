package org.example.config

import org.example.service.RideOfferListener
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.listener.PatternTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer

@Configuration
class RedisConfig(private val rideOfferListener: RideOfferListener) {

    @Bean
    fun redisListenerContainer(connectionFactory: RedisConnectionFactory): RedisMessageListenerContainer =
        RedisMessageListenerContainer().apply {
            setConnectionFactory(connectionFactory)
            addMessageListener(rideOfferListener, PatternTopic("ride_offers:*"))
        }
}
