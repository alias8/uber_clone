package org.example.client

import io.grpc.Deadline
import io.grpc.ManagedChannelBuilder
import io.grpc.Status
import io.grpc.StatusRuntimeException
import org.example.pricing.grpc.FareQuoteRequest
import org.example.pricing.grpc.PricingServiceGrpc
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.concurrent.TimeUnit.MILLISECONDS

data class FareQuote(val fare: BigDecimal, val surgeMultiplier: BigDecimal)

/** Pricing couldn't answer within its budget. PricingUnavailableHandler turns it into a 503. */
class PricingUnavailableException(cause: Throwable) : RuntimeException("Pricing service unavailable", cause)

@Component
class PricingClient(
    @Value("\${pricing-service.host:localhost}") host: String,
    @Value("\${pricing-service.port:6565}") port: Int,
    // Total time for the fare quote, retry included. A gRPC call with no deadline waits forever,
    // so before this a hung pricing-service hung POST /rides with it.
    @Value("\${pricing-service.budget-ms:800}") private val budgetMs: Long
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
        // One deadline shared by both attempts: the retry only gets what's left of the budget.
        val deadline = Deadline.after(budgetMs, MILLISECONDS)
        val response = callWithOneRetry(deadline) { d -> stub.withDeadline(d).getFareQuote(request) }
        return FareQuote(BigDecimal(response.fare), BigDecimal(response.surgeMultiplier))
    }
}

private const val MIN_RETRY_BUDGET_MS = 300L
private const val RETRY_PAUSE_MS = 50L

/**
 * Run [call] with [deadline]; retry once if it's worth it, otherwise throw PricingUnavailableException.
 *
 * Retries only UNAVAILABLE (pricing restarting, connection refused: the call probably never ran),
 * and only if at least MIN_RETRY_BUDGET_MS of the deadline is left. Never retries
 * DEADLINE_EXCEEDED: pricing is already slow, and a retry just adds load to it.
 *
 * Only this layer retries. The rider's app gets a 503 + Retry-After and tries again itself, so a
 * pricing outage costs at most 2 calls per tap, not 3 x 3 x 3 across layers.
 * Safe to retry because a fare quote is a read.
 */
internal fun <T> callWithOneRetry(deadline: Deadline, call: (Deadline) -> T): T {
    try {
        return call(deadline)
    } catch (first: StatusRuntimeException) {
        val retryable = first.status.code == Status.Code.UNAVAILABLE &&
            deadline.timeRemaining(MILLISECONDS) >= MIN_RETRY_BUDGET_MS
        if (!retryable) throw PricingUnavailableException(first)
        Thread.sleep(RETRY_PAUSE_MS)
        try {
            return call(deadline)
        } catch (second: StatusRuntimeException) {
            throw PricingUnavailableException(second)
        }
    }
}
