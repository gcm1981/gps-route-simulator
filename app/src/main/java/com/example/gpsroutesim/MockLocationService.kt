package com.example.gpsroutesim

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MockLocationService : Service() {

    companion object {
        const val CHANNEL_ID = "mock_gps_channel"
        const val NOTIFICATION_ID = 1
        const val UPDATE_INTERVAL_MS = 1000L // frequenza di aggiornamento posizione
        const val PROVIDER = LocationManager.GPS_PROVIDER
    }

    private var job: Job? = null
    private lateinit var locationManager: LocationManager
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Simulazione GPS in corso..."))
        acquireWakeLock()

        try {
            setupTestProvider()
        } catch (e: SecurityException) {
            // L'app non è impostata come "app di posizione fittizia" nelle Opzioni sviluppatore
            stopSelf()
            return START_NOT_STICKY
        }

        job?.cancel()
        job = CoroutineScope(Dispatchers.Default).launch {
            playRoute()
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GpsRouteSimulator:SimulationWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L) // timeout di sicurezza: max 6 ore, poi si rilascia da solo
        }
    }

    private fun setupTestProvider() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            locationManager.addTestProvider(
                PROVIDER,
                false, false, false, false,
                true, true, true,
                android.location.provider.ProviderProperties.POWER_USAGE_LOW,
                android.location.provider.ProviderProperties.ACCURACY_FINE
            )
        } else {
            @Suppress("DEPRECATION")
            locationManager.addTestProvider(
                PROVIDER,
                false, false, false, false,
                true, true, true,
                android.location.Criteria.POWER_LOW,
                android.location.Criteria.ACCURACY_FINE
            )
        }
        locationManager.setTestProviderEnabled(PROVIDER, true)
    }

    private suspend fun playRoute() {
        val points = RouteHolder.points
        if (points.size < 2) return

        do {
            for (i in 0 until points.size - 1) {
                val a = points[i]
                val b = points[i + 1]

                val distance = GeoMath.distanceMeters(a.lat, a.lon, b.lat, b.lon)
                val bearing = GeoMath.bearingDegrees(a.lat, a.lon, b.lat, b.lon)

                val segmentDurationMs = computeSegmentDuration(a, b, distance)
                val steps = (segmentDurationMs / UPDATE_INTERVAL_MS).coerceAtLeast(1)
                val speedMps = if (segmentDurationMs > 0) (distance / (segmentDurationMs / 1000.0)).toFloat() else 0f

                for (step in 0..steps) {
                    val fraction = step.toDouble() / steps
                    val (lat, lon) = GeoMath.interpolate(a.lat, a.lon, b.lat, b.lon, fraction)
                    pushMockLocation(lat, lon, bearing, speedMps)
                    delay(UPDATE_INTERVAL_MS)
                }
            }
        } while (RouteHolder.loop)
    }

    private fun computeSegmentDuration(a: RoutePoint, b: RoutePoint, distanceMeters: Double): Long {
        if (RouteHolder.useGpxTiming && a.timestampMs != null && b.timestampMs != null) {
            val diff = b.timestampMs - a.timestampMs
            if (diff > 0) return diff
        }
        val speedMps = RouteHolder.speedKmh / 3.6
        if (speedMps <= 0) return UPDATE_INTERVAL_MS
        return ((distanceMeters / speedMps) * 1000).toLong().coerceAtLeast(UPDATE_INTERVAL_MS)
    }

    private fun pushMockLocation(lat: Double, lon: Double, bearing: Float, speedMps: Float) {
        val location = Location(PROVIDER).apply {
            latitude = lat
            longitude = lon
            altitude = 0.0
            accuracy = 3f
            this.bearing = bearing
            speed = speedMps
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        try {
            locationManager.setTestProviderLocation(PROVIDER, location)
            SimulationTracker.listener?.invoke(lat, lon, bearing)
        } catch (e: SecurityException) {
            // permesso mock revocato durante l'esecuzione: ferma il servizio
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Simulazione GPS", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS Route Simulator")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        job?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        try {
            locationManager.removeTestProvider(PROVIDER)
        } catch (e: Exception) {
            // provider già rimosso o mai impostato
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
