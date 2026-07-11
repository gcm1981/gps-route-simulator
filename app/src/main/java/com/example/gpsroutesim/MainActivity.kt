package com.example.gpsroutesim

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var tvFileName: TextView
    private lateinit var tvStatus: TextView
    private lateinit var etSpeed: EditText
    private lateinit var cbUseGpxTiming: CheckBox
    private lateinit var cbLoop: CheckBox
    private lateinit var tvStartInfo: TextView
    private lateinit var tvDestInfo: TextView
    private lateinit var etDestLat: EditText
    private lateinit var etDestLon: EditText
    private lateinit var etDestAddress: EditText

    private var gpxPoints: List<RoutePoint> = emptyList()
    private var startOverride: RoutePoint? = null
    private var destinationOverride: RoutePoint? = null
    private var roadRoutePoints: List<RoutePoint> = emptyList()

    private lateinit var mapView: MapView
    private var routeOverlay: Polyline? = null
    private var startMarker: Marker? = null
    private var endMarker: Marker? = null
    private var liveMarker: Marker? = null

    private val pickGpxLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { loadGpx(it) }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Permesso posizione negato", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configura osmdroid per usare la cache interna dell'app (evita permessi storage extra)
        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().osmdroidBasePath = File(cacheDir, "osmdroid")
        Configuration.getInstance().osmdroidTileCache = File(cacheDir, "osmdroid/tiles")

        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(15.0)
        mapView.controller.setCenter(GeoPoint(41.9028, 12.4964)) // centro provvisorio (Italia), si aggiorna col percorso

        tvFileName = findViewById(R.id.tvFileName)
        tvStatus = findViewById(R.id.tvStatus)
        etSpeed = findViewById(R.id.etSpeed)
        cbUseGpxTiming = findViewById(R.id.cbUseGpxTiming)
        cbLoop = findViewById(R.id.cbLoop)
        tvStartInfo = findViewById(R.id.tvStartInfo)
        tvDestInfo = findViewById(R.id.tvDestInfo)
        etDestLat = findViewById(R.id.etDestLat)
        etDestLon = findViewById(R.id.etDestLon)
        etDestAddress = findViewById(R.id.etDestAddress)

        findViewById<Button>(R.id.btnPickFile).setOnClickListener {
            pickGpxLauncher.launch("*/*")
        }

        findViewById<Button>(R.id.btnUseCurrentLocation).setOnClickListener {
            useCurrentLocationAsStart()
        }

        findViewById<Button>(R.id.btnSetDestination).setOnClickListener {
            setDestinationFromFields()
        }

        findViewById<Button>(R.id.btnSearchAddress).setOnClickListener {
            searchDestinationAddress()
        }

        findViewById<Button>(R.id.btnGenerateRoadRoute).setOnClickListener {
            generateRoadRoute()
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener { startSimulation() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopSimulation() }

        ensureLocationPermission()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        SimulationTracker.listener = { lat, lon, bearing ->
            runOnUiThread { moveLiveMarker(lat, lon, bearing) }
        }
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        SimulationTracker.listener = null
    }

    /** Disegna (o ridisegna) il percorso corrente sulla mappa: linea + marker partenza/destinazione. */
    private fun refreshMapRoute() {
        val points = buildFinalRoute()

        routeOverlay?.let { mapView.overlays.remove(it) }
        startMarker?.let { mapView.overlays.remove(it) }
        endMarker?.let { mapView.overlays.remove(it) }

        if (points.size < 2) {
            mapView.invalidate()
            return
        }

        val geoPoints = points.map { GeoPoint(it.lat, it.lon) }

        val polyline = Polyline().apply {
            setPoints(geoPoints)
            outlinePaint.color = 0xFF6200EE.toInt()
            outlinePaint.strokeWidth = 8f
        }
        mapView.overlays.add(polyline)
        routeOverlay = polyline

        val start = Marker(mapView).apply {
            position = geoPoints.first()
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Partenza"
        }
        mapView.overlays.add(start)
        startMarker = start

        val end = Marker(mapView).apply {
            position = geoPoints.last()
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Destinazione"
        }
        mapView.overlays.add(end)
        endMarker = end

        mapView.controller.setCenter(geoPoints.first())
        mapView.zoomToBoundingBox(
            org.osmdroid.util.BoundingBox.fromGeoPoints(geoPoints).increaseByScale(1.4f),
            false
        )
        mapView.invalidate()
    }

    /** Aggiorna la posizione del marker "live" mentre la simulazione è in corso. */
    private fun moveLiveMarker(lat: Double, lon: Double, bearing: Float) {
        val point = GeoPoint(lat, lon)
        val marker = liveMarker
        if (marker == null) {
            val newMarker = Marker(mapView).apply {
                position = point
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = "Posizione simulata"
                rotation = bearing
            }
            mapView.overlays.add(newMarker)
            liveMarker = newMarker
        } else {
            marker.position = point
            marker.rotation = bearing
        }
        mapView.controller.animateTo(point)
        mapView.invalidate()
    }

    private fun ensureLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun loadGpx(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val points = GpxParser.parse(input)
                if (points.size < 2) {
                    Toast.makeText(this, "Il file non contiene un percorso valido", Toast.LENGTH_LONG).show()
                    return
                }
                gpxPoints = points
                roadRoutePoints = emptyList()
                tvFileName.text = "Percorso caricato: ${points.size} punti"
                refreshMapRoute()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Errore lettura GPX: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun useCurrentLocationAsStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ensureLocationPermission()
            Toast.makeText(this, "Concedi il permesso posizione e riprova", Toast.LENGTH_SHORT).show()
            return
        }

        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        for (provider in lm.getProviders(true)) {
            val loc = try {
                lm.getLastKnownLocation(provider)
            } catch (e: SecurityException) {
                null
            } ?: continue
            if (best == null || loc.accuracy < best!!.accuracy) best = loc
        }

        if (best == null) {
            Toast.makeText(
                this,
                "Posizione non disponibile: attiva il GPS/la posizione del device e riprova",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        startOverride = RoutePoint(best.latitude, best.longitude)
        roadRoutePoints = emptyList()
        tvStartInfo.text = "Partenza: %.6f, %.6f".format(best.latitude, best.longitude)
        refreshMapRoute()
    }

    private fun setDestinationFromFields() {
        val lat = etDestLat.text.toString().toDoubleOrNull()
        val lon = etDestLon.text.toString().toDoubleOrNull()
        if (lat == null || lon == null) {
            Toast.makeText(this, "Inserisci coordinate di destinazione valide", Toast.LENGTH_SHORT).show()
            return
        }
        destinationOverride = RoutePoint(lat, lon)
        roadRoutePoints = emptyList()
        tvDestInfo.text = "Destinazione: %.6f, %.6f".format(lat, lon)
        refreshMapRoute()
    }

    /**
     * Cerca l'indirizzo scritto in etDestAddress tramite Nominatim (OpenStreetMap)
     * e, se trovato, lo imposta come destinazione (equivalente a inserire lat/lon manualmente).
     */
    private fun searchDestinationAddress() {
        val address = etDestAddress.text.toString().trim()
        if (address.isEmpty()) {
            Toast.makeText(this, "Scrivi un indirizzo da cercare", Toast.LENGTH_SHORT).show()
            return
        }

        tvDestInfo.text = "Ricerca indirizzo in corso..."
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                NominatimClient.geocode(address)
            }
            if (result == null) {
                tvDestInfo.text = "Indirizzo non trovato, prova a essere più specifico"
            } else {
                destinationOverride = result
                roadRoutePoints = emptyList()
                etDestLat.setText("%.6f".format(result.lat))
                etDestLon.setText("%.6f".format(result.lon))
                tvDestInfo.text = "Destinazione: %.6f, %.6f (da \"$address\")".format(result.lat, result.lon)
                refreshMapRoute()
            }
        }
    }

    /**
     * Chiama il servizio di routing OSRM per calcolare un percorso reale su strada
     * tra partenza e destinazione, e lo salva in roadRoutePoints (priorità massima
     * in buildFinalRoute rispetto a GPX caricato o linea retta).
     */
    private fun generateRoadRoute() {
        val start = startOverride
        val end = destinationOverride
        if (start == null || end == null) {
            Toast.makeText(this, "Imposta prima partenza e destinazione", Toast.LENGTH_SHORT).show()
            return
        }

        tvStatus.text = "Calcolo percorso stradale in corso..."
        lifecycleScope.launch {
            val route = withContext(Dispatchers.IO) {
                OsrmRoutingClient.getRoadRoute(listOf(start, end))
            }
            if (route == null) {
                roadRoutePoints = emptyList()
                tvStatus.text = "Impossibile calcolare il percorso stradale (verifica la connessione)"
            } else {
                roadRoutePoints = route
                tvStatus.text = "Percorso stradale generato: ${route.size} punti"
                refreshMapRoute()
            }
        }
    }

    /**
     * Combina GPX caricato (se presente) con partenza/destinazione forzate:
     * - se c'è un GPX, sostituisce solo primo e ultimo punto con gli override impostati
     * - se non c'è un GPX ma sono impostate partenza e destinazione, crea un percorso diretto a 2 punti
     */
    private fun buildFinalRoute(): List<RoutePoint> {
        if (roadRoutePoints.size >= 2) {
            return roadRoutePoints
        }
        if (gpxPoints.size >= 2) {
            val points = gpxPoints.toMutableList()
            startOverride?.let { points[0] = it.copy(timestampMs = points[0].timestampMs) }
            destinationOverride?.let { points[points.size - 1] = it.copy(timestampMs = points.last().timestampMs) }
            return points
        }
        val s = startOverride
        val e = destinationOverride
        return if (s != null && e != null) listOf(s, e) else emptyList()
    }

    private fun startSimulation() {
        val finalRoute = buildFinalRoute()
        if (finalRoute.size < 2) {
            Toast.makeText(
                this,
                "Carica un GPX oppure imposta sia partenza sia destinazione",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        RouteHolder.points = finalRoute
        RouteHolder.speedKmh = etSpeed.text.toString().toFloatOrNull() ?: 30f
        RouteHolder.useGpxTiming = cbUseGpxTiming.isChecked
        RouteHolder.loop = cbLoop.isChecked

        liveMarker?.let { mapView.overlays.remove(it) }
        liveMarker = null
        mapView.invalidate()

        if (!isMockLocationAppSelected()) {
            Toast.makeText(
                this,
                "Imposta questa app come 'app di posizione fittizia' nelle Opzioni sviluppatore",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            return
        }

        val intent = Intent(this, MockLocationService::class.java)
        ContextCompat.startForegroundService(this, intent)
        tvStatus.text = "Simulazione avviata"
    }

    private fun stopSimulation() {
        stopService(Intent(this, MockLocationService::class.java))
        tvStatus.text = "Simulazione fermata"
    }

    /**
     * Non esiste un modo diretto per verificare via codice se l'app è impostata come
     * mock location app: si tenta e si intercetta la SecurityException nel service.
     * Qui restituiamo sempre true e lasciamo il controllo reale al service stesso,
     * mostrando l'errore solo se il setup del test provider fallisce.
     */
    private fun isMockLocationAppSelected(): Boolean = true
}
