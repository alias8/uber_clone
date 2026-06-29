package org.example.service

import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.distributed.proxy.ProxyManager
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class RateLimiterService(private val proxyManager: ProxyManager<String>) {

    private val rideRequestConfig = BucketConfiguration.builder()
        .addLimit(Bandwidth.builder()
            .capacity(5)
            .refillIntervally(5, Duration.ofMinutes(1))
            .build())
        .build()

    // 10 attempts per 15 minutes per IP — covers both login and register
    private val authAttemptConfig = BucketConfiguration.builder()
        .addLimit(Bandwidth.builder()
            .capacity(10)
            .refillIntervally(10, Duration.ofMinutes(15))
            .build())
        .build()

    fun allowRideRequest(userId: String): Boolean =
        proxyManager.builder()
            .build("rate_limit:ride_request:$userId") { rideRequestConfig }
            .tryConsume(1)

    fun allowAuthAttempt(ip: String): Boolean =
        proxyManager.builder()
            .build("rate_limit:auth:$ip") { authAttemptConfig }
            .tryConsume(1)
}
