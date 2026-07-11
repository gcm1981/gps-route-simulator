package com.example.gpsroutesim

/**
 * Ponte in-memoria tra il servizio di simulazione (MockLocationService) e l'Activity,
 * usato per aggiornare il marker "live" sulla mappa mentre la simulazione è in corso.
 *
 * Funziona perché servizio e activity girano nello stesso processo dell'app
 * (non serve IPC/broadcast): il servizio invoca semplicemente il listener registrato.
 */
object SimulationTracker {
    var listener: ((lat: Double, lon: Double, bearing: Float) -> Unit)? = null
}
