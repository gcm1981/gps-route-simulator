package com.example.gpsroutesim

/**
 * Un singolo punto del percorso.
 * timestampMs è opzionale: se presente nel GPX, viene usato per rispettare
 * i tempi reali della traccia originale.
 */
data class RoutePoint(
    val lat: Double,
    val lon: Double,
    val timestampMs: Long? = null,
    val elevation: Double? = null
)
