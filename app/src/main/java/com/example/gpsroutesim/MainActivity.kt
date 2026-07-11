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

    private var gpxPoints: List<RoutePoint> = emptyList()
    private var startOverride: RoutePoint? = null
    private var destinationOverride: RoutePoint? = null
    private var roadRoutePoints: List<RoutePoint> = emptyList()

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
        setContentView(R.layout.activity_main)

        tvFileName = findViewById(R.id.tvFileName)
        tvStatus = findViewById(R.id.tvStatus)
        etSpeed = findViewById(R.id.etSpeed)
        cbUseGpxTiming = findViewById(R.id.cbUseGpxTiming)
        cbLoop = findViewById(R.id.cbLoop)
        tvStartInfo = findViewById(R.id.tvStartInfo)
        tvDestInfo = findViewById(R.id.tvDestInfo)
        etDestLat = findViewById(R.id.etDestLat)
        etDestLon = findViewById(R.id.etDestLon)

        findViewById<Button>(R.id.btnPickFile).setOnClickListener {
            pickGpxLauncher.launch("*/*")
        }

        findViewById<Button>(R.id.btnUseCurrentLocation).setOnClickListener {
            useCurrentLocationAsStart()
        }

        findViewById<Button>(R.id.btnSetDestination).setOnClickListener {
            setDestinationFromFields()
        }

        findViewById<Button>(R.id.btnGenerateRoadRoute).setOnClickListener {
            generateRoadRoute()
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener { startSimulation() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopSimulation() }

        ensureLocationPermission()
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
