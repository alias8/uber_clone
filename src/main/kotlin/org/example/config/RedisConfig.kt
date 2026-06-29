package org.example.config

import io.github.bucket4j.distributed.proxy.ProxyManager
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager
import io.lettuce.core.RedisClient
import io.lettuce.core.codec.ByteArrayCodec
import io.lettuce.core.codec.RedisCodec
import io.lettuce.core.codec.StringCodec
import org.example.service.RideOfferListener
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
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

    @Bean
    fun bucketProxyManager(connectionFactory: RedisConnectionFactory): ProxyManager<String> {
        val client = (connectionFactory as LettuceConnectionFactory).nativeClient as RedisClient
        val connection = client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE))
        return LettuceBasedProxyManager.builderFor(connection).build()
    }
}
