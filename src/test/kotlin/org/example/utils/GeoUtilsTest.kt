package org.example.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoUtilsTest {

    @Test
    fun `haversineKm is zero for the same point`() {
        assertEquals(0.0, haversineKm(40.7128, -74.0060, 40.7128, -74.0060), 0.0001)
    }

    @Test
    fun `haversineKm is symmetric`() {
        val a = haversineKm(40.7128, -74.0060, 34.0522, -118.2437)
        val b = haversineKm(34.0522, -118.2437, 40.7128, -74.0060)
        assertEquals(a, b, 0.0001)
    }

    @Test
    fun `haversineKm matches known distance between NYC and LA`() {
        val distance = haversineKm(40.7128, -74.0060, 34.0522, -118.2437)
        // Real-world great-circle distance is ~3936 km
        assertTrue(distance in 3900.0..3970.0, "expected ~3936 km, got $distance")
    }

    @Test
    fun `etaMinutes scales with distance at average city speed`() {
        assertEquals(30, etaMinutes(15.0))
        assertEquals(60, etaMinutes(30.0))
    }

    @Test
    fun `etaMinutes never returns less than one minute`() {
        assertEquals(1, etaMinutes(0.0))
        assertEquals(1, etaMinutes(0.01))
    }
}
