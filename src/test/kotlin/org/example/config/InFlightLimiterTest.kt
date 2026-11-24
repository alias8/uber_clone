package org.example.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InFlightLimiterTest {

    @Test
    fun `normal requests stop when only the reserved permits are left`() {
        val limiter = InFlightLimiter(max = 5, reservedForCritical = 2)
        repeat(3) { assertTrue(limiter.tryEnter(critical = false)) }
        assertFalse(limiter.tryEnter(critical = false)) // 2 left, both reserved
        assertEquals(3, limiter.inFlight())
    }

    @Test
    fun `critical requests can use the reserve, then they are shed too`() {
        val limiter = InFlightLimiter(max = 5, reservedForCritical = 2)
        repeat(3) { limiter.tryEnter(critical = false) }
        assertTrue(limiter.tryEnter(critical = true))
        assertTrue(limiter.tryEnter(critical = true))
        assertFalse(limiter.tryEnter(critical = true)) // completely full
        assertEquals(5, limiter.inFlight())
    }

    @Test
    fun `finishing a request frees its permit`() {
        val limiter = InFlightLimiter(max = 1, reservedForCritical = 0)
        assertTrue(limiter.tryEnter(critical = false))
        assertFalse(limiter.tryEnter(critical = false))
        limiter.exit()
        assertTrue(limiter.tryEnter(critical = false))
    }
}
