package org.example.client

import io.grpc.Deadline
import io.grpc.Status
import java.util.concurrent.TimeUnit.MILLISECONDS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PricingClientRetryTest {

    private fun failing(code: Status.Code) = code.toStatus().asRuntimeException()

    @Test
    fun `success on first try makes one call`() {
        var calls = 0
        val result = callWithOneRetry(Deadline.after(800, MILLISECONDS)) { calls++; "quote" }
        assertEquals("quote", result)
        assertEquals(1, calls)
    }

    @Test
    fun `UNAVAILABLE then success retries once`() {
        var calls = 0
        val result = callWithOneRetry(Deadline.after(800, MILLISECONDS)) {
            calls++
            if (calls == 1) throw failing(Status.Code.UNAVAILABLE) else "quote"
        }
        assertEquals("quote", result)
        assertEquals(2, calls)
    }

    @Test
    fun `UNAVAILABLE twice gives up after two calls`() {
        var calls = 0
        assertFailsWith<PricingUnavailableException> {
            callWithOneRetry(Deadline.after(800, MILLISECONDS)) { calls++; throw failing(Status.Code.UNAVAILABLE) }
        }
        assertEquals(2, calls)
    }

    @Test
    fun `DEADLINE_EXCEEDED is never retried`() {
        var calls = 0
        assertFailsWith<PricingUnavailableException> {
            callWithOneRetry(Deadline.after(800, MILLISECONDS)) { calls++; throw failing(Status.Code.DEADLINE_EXCEEDED) }
        }
        assertEquals(1, calls)
    }

    @Test
    fun `no retry when too little of the budget is left`() {
        var calls = 0
        assertFailsWith<PricingUnavailableException> {
            callWithOneRetry(Deadline.after(100, MILLISECONDS)) { calls++; throw failing(Status.Code.UNAVAILABLE) }
        }
        assertEquals(1, calls)
    }

    @Test
    fun `both attempts share the same deadline`() {
        val deadline = Deadline.after(800, MILLISECONDS)
        val seen = mutableListOf<Deadline>()
        callWithOneRetry(deadline) {
            seen += it
            if (seen.size == 1) throw failing(Status.Code.UNAVAILABLE) else "quote"
        }
        assertEquals(listOf(deadline, deadline), seen)
    }
}
