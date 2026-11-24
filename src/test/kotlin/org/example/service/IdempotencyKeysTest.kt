package org.example.service

import org.example.dto.RideRequest
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class IdempotencyKeysTest {

    private val trip = RideRequest(pickupLat = 37.77, pickupLng = -122.42, dropoffLat = 37.80, dropoffLng = -122.27)

    @Test
    fun `same request hashes the same`() {
        assertEquals(requestHash(trip), requestHash(trip.copy()))
        assertEquals(64, requestHash(trip).length)
    }

    @Test
    fun `different trip hashes differently`() {
        assertNotEquals(requestHash(trip), requestHash(trip.copy(dropoffLat = 37.81)))
    }

    @Test
    fun `swapping pickup and dropoff is a different request`() {
        val reversed = RideRequest(trip.dropoffLat, trip.dropoffLng, trip.pickupLat, trip.pickupLng)
        assertNotEquals(requestHash(trip), requestHash(reversed))
    }

    @Test
    fun `key is trimmed`() {
        assertEquals("abc-123", validIdempotencyKey("  abc-123 "))
    }

    @Test
    fun `blank or too long key is a 400`() {
        listOf("", "   ", "x".repeat(256)).forEach { bad ->
            val ex = assertFailsWith<ResponseStatusException> { validIdempotencyKey(bad) }
            assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
        }
        assertEquals(255, validIdempotencyKey("x".repeat(255)).length)
    }
}
