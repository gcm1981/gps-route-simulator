package com.example.gpsroutesim

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.asin
import kotlin.math.PI

object GeoMath {

    private const val EARTH_RADIUS_M = 6371000.0

    fun toRad(deg: Double) = deg * PI / 180.0
    fun toDeg(rad: Double) = rad * 180.0 / PI

    /** Distanza in metri tra due coordinate (formula haversine). */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = toRad(lat2 - lat1)
        val dLon = toRad(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
                cos(toRad(lat1)) * cos(toRad(lat2)) * sin(dLon / 2).let { it * it }
        val c = 2 * asin(sqrt(a))
        return EARTH_RADIUS_M * c
    }

    /** Bearing iniziale in gradi (0-360) da punto 1 a punto 2. */
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val phi1 = toRad(lat1)
        val phi2 = toRad(lat2)
        val dLon = toRad(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        val theta = atan2(y, x)
        return ((toDeg(theta) + 360) % 360).toFloat()
    }

    /**
     * Interpola linearmente tra due punti in base a una frazione [0,1].
     * Sufficiente per tratti brevi (qualche decina/centinaia di metri) tra vertici GPX.
     */
    fun interpolate(lat1: Double, lon1: Double, lat2: Double, lon2: Double, fraction: Double): Pair<Double, Double> {
        val f = fraction.coerceIn(0.0, 1.0)
        val lat = lat1 + (lat2 - lat1) * f
        val lon = lon1 + (lon2 - lon1) * f
        return Pair(lat, lon)
    }
}
