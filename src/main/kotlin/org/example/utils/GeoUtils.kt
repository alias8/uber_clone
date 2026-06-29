package org.example.utils

import kotlin.math.*

const val AVERAGE_CITY_SPEED_KMH = 30.0

fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    return r * 2 * asin(sqrt(a))
}

fun etaMinutes(distanceKm: Double): Int =
    (distanceKm / AVERAGE_CITY_SPEED_KMH * 60).toInt().coerceAtLeast(1)
