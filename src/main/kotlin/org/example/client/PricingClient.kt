package org.example.client

import io.grpc.ManagedChannelBuilder
import org.example.pricing.grpc.FareQuoteRequest
import org.example.pricing.grpc.PricingServiceGrpc
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal

data class FareQuote(val fare: BigDecimal, val surgeMultiplier: BigDecimal)

@Component
class PricingClient(
    @Value("\${pricing-service.host:localhost}") host: String,
    @Value("\${pricing-service.port:6565}") port: Int
) {
    private val channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build()
    private val stub = PricingServiceGrpc.newBlockingStub(channel)

    fun getFareQuote(pickupLat: Double, pickupLng: Double, dropoffLat: Double, dropoffLng: Double): FareQuote {
        val request = FareQuoteRequest.newBuilder()
            .setPickupLat(pickupLat)
            .setPickupLng(pickupLng)
            .setDropoffLat(dropoffLat)
            .setDropoffLng(dropoffLng)
            .build()
        val response = stub.getFareQuote(request)
        return FareQuote(BigDecimal(response.fare), BigDecimal(response.surgeMultiplier))
    }
}
