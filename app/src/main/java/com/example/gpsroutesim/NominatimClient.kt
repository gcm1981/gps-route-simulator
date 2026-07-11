package com.example.gpsroutesim

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Client per il servizio di geocoding pubblico Nominatim (OpenStreetMap).
 *
 * NOTA: Nominatim ha una policy d'uso che richiede un User-Agent identificativo
 * e un limite di 1 richiesta al secondo per uso "leggero" come questo. Per un uso
 * intensivo o in produzione conviene self-hostare Nominatim o usare un provider
 * commerciale (Google Geocoding, Mapbox Geocoding, ecc.).
 */
object NominatimClient {

    private const val BASE_URL = "https://nominatim.openstreetmap.org/search"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Cerca un indirizzo testuale e ritorna il primo risultato come RoutePoint.
     * Ritorna null se non trova nulla o in caso di errore di rete.
     */
    fun geocode(address: String): RoutePoint? {
        if (address.isBlank()) return null

        val encoded = URLEncoder.encode(address, "UTF-8")
        val url = "$BASE_URL?q=$encoded&format=json&limit=1"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "GpsRouteSimulator-TestApp/1.0")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                parseResponse(body)
            }
        } catch (e: IOException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun parseResponse(body: String): RoutePoint? {
        val results = JSONArray(body)
        if (results.length() == 0) return null

        val first = results.getJSONObject(0)
        val lat = first.getString("lat").toDoubleOrNull() ?: return null
        val lon = first.getString("lon").toDoubleOrNull() ?: return null
        return RoutePoint(lat, lon)
    }
}

private fun String.toDoubleOrNull(): Double? = try {
    this.toDouble()
} catch (e: Exception) {
    null
}
