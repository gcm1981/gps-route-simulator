package com.example.gpsroutesim

/**
 * Contenitore in-memory per il percorso caricato.
 * Semplice singleton: evita di dover serializzare liste di punti negli Intent
 * (che su percorsi lunghi supererebbero facilmente il limite della Binder transaction).
 */
object RouteHolder {
    var points: List<RoutePoint> = emptyList()
    var speedKmh: Float = 30f          // usata se il GPX non ha timestamp o l'utente forza una velocità
    var useGpxTiming: Boolean = true   // se true e i punti hanno timestamp, rispetta i tempi originali
    var loop: Boolean = false
}
