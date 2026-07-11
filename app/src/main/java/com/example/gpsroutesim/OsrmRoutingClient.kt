package com.example.gpsroutesim

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client per il servizio pubblico di routing OSRM (Open Source Routing Machine).
 *
 * NOTA: il server demo (router.project-osrm.org) è pensato per test/sviluppo,
 * non per uso in produzione (nessuna garanzia di uptime, rate limit non documentati
 * pubblicamente). Per un uso continuativo conviene self-hostare OSRM oppure usare
 * un provider commerciale (Google Directions, Mapbox Directions, GraphHopper, ecc.),
 * cambiando solo BASE_URL e il parsing della risposta se necessario.
 */
object OsrmRoutingClient {

    private const val BASE_URL = "https://router.project-osrm.org/route/v1/driving/"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Calcola il percorso stradale che passa per tutti i punti forniti (nell'ordine).
     * Ritorna null in caso di errore di rete o risposta non valida.
     */
    fun getRoadRoute(points: List<RoutePoint>): List<RoutePoint>? {
        if (points.size < 2) return null

        val coords = points.joinToString(";") { "${it.lon},${it.lat}" }
        val url = "$BASE_URL$coords?overview=full&geometries=geojson"

        val request = Request.Builder().url(url).build()

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

    private fun parseResponse(body: String): List<RoutePoint>? {
        val json = JSONObject(body)
        if (json.optString("code") != "Ok") return null

        val routes = json.optJSONArray("routes") ?: return null
        if (routes.length() == 0) return null

        val geometry = routes.getJSONObject(0).getJSONObject("geometry")
        val coordinates = geometry.getJSONArray("coordinates")

        val result = mutableListOf<RoutePoint>()
        for (i in 0 until coordinates.length()) {
            val pair = coordinates.getJSONArray(i)
            val lon = pair.getDouble(0)
            val lat = pair.getDouble(1)
            result.add(RoutePoint(lat, lon))
        }
        return if (result.size >= 2) result else null
    }
}
