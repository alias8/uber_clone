package org.example.service

import org.example.dto.RideRequest
import org.example.model.Ride
import org.example.repository.IdempotencyKeyRepository
import org.example.repository.RideRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/**
 * Idempotency keys for POST /rides, so a retried ride request can't create a second ride.
 *
 * The problem: the rider taps "Request ride", the server creates the ride, and the response is
 * lost (network blip, client timeout, a 503 from something after the work). The app can't tell
 * "never happened" from "happened, didn't hear back", so a retry would book a second ride.
 *
 * The contract: the app makes one UUID per tap and sends it as `Idempotency-Key` on every retry of
 * that tap. The first request that gets through stores (rider, key) -> ride id IN THE SAME
 * TRANSACTION as the ride (RideService.requestRide). Any later request with that key gets that
 * ride back (201, `Idempotent-Replayed: true`) instead of a new one.
 *
 *  - Same key, different body -> 422: the client reused a key for a different request (a bug).
 *  - Same key while the first is still running -> no 409 "in progress" state needed: the second
 *    INSERT waits on the primary key until the first transaction ends, then sees its row.
 *    That only works because everything slow (the pricing call) happens before the transaction.
 *  - First attempt crashed -> its transaction rolled back the ride AND the key, so the retry
 *    starts clean. A key never exists without its ride.
 *  - Keys are scoped per rider, and deleted after 24 h: retries come within seconds or minutes.
 *  - No key sent -> works as before. The "one active ride per rider" check still stops most
 *    duplicates then, but it's a check-then-insert, so two requests in the same instant can both
 *    pass it; a partial unique index on rides(rider_id) WHERE status is active would close that.
 *
 * Replay returns the ride as it is NOW, not a byte-for-byte copy of the first response (Stripe
 * stores the response body). Same ride either way; the current state is what the app wants.
 */
@Component
class IdempotencyKeys(
    private val repository: IdempotencyKeyRepository,
    private val rideRepository: RideRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** The ride an earlier request with this key created, or null if the key is new. */
    fun replay(userId: String, key: String, request: RideRequest): Ride? {
        val stored = repository.find(userId, key) ?: return null
        if (stored.requestHash != requestHash(request)) {
            throw ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Idempotency-Key was already used for a different ride request"
            )
        }
        // Always there: the key and the ride were committed together.
        return rideRepository.findById(stored.rideId).orElseThrow {
            IllegalStateException("Idempotency key $key points to missing ride ${stored.rideId}")
        }
    }

    /**
     * Claim the key for [rideId]. Call inside the ride's transaction. False means a concurrent
     * request with the same key committed first: roll back this ride and replay that one.
     */
    fun claim(userId: String, key: String, request: RideRequest, rideId: String): Boolean =
        repository.insertIfAbsent(userId, key, requestHash(request), rideId) == 1

    // Every instance runs this; the DELETE is harmless to repeat.
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    fun deleteExpired() {
        val deleted = repository.deleteCreatedBefore(Instant.now().minus(KEY_TTL))
        if (deleted > 0) log.info("Deleted {} expired idempotency keys", deleted)
    }

    companion object {
        val KEY_TTL: Duration = Duration.ofHours(24)
        const val MAX_KEY_LENGTH = 255
    }
}

/** Trimmed key, or 400 if it's blank or too long for the column. */
fun validIdempotencyKey(raw: String): String {
    val key = raw.trim()
    if (key.isEmpty() || key.length > IdempotencyKeys.MAX_KEY_LENGTH) {
        throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Idempotency-Key must be 1-${IdempotencyKeys.MAX_KEY_LENGTH} characters"
        )
    }
    return key
}

/** SHA-256 of the fields that define the ride, so a reused key with a different trip is caught. */
fun requestHash(request: RideRequest): String {
    val canonical = listOf(request.pickupLat, request.pickupLng, request.dropoffLat, request.dropoffLng)
        .joinToString("|")
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
