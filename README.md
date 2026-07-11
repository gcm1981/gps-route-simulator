# GPS Route Simulator (Android / Kotlin)

App di test che simula un **percorso GPS** (non solo una posizione statica),
caricando un file `.gpx` e inviando posizioni mock in sequenza tramite il
`LocationManager` di Android.

## Come funziona

1. **GpxParser** legge il file GPX ed estrae i punti (`lat`, `lon`, `time` se presente).
2. **GeoMath** calcola distanza e bearing tra punti consecutivi.
3. **MockLocationService** interpola la traiettoria tra un punto e il successivo
   e invia una nuova posizione mock ogni secondo, rispettando:
   - i timestamp originali del GPX (se presenti e l'opzione è attiva), oppure
   - una velocità costante impostata dall'utente (km/h).
4. **MainActivity** gestisce la selezione del file, i permessi e l'avvio/stop del servizio.

## Come aprire il progetto

1. Apri Android Studio (Iguana o successivo).
2. `File > Open` e seleziona la cartella `GpsRouteSimulator`.
3. Lascia sincronizzare Gradle (scarica le dipendenze la prima volta).
4. Collega un device o avvia un emulatore con **API 24+**.

## Passaggi necessari sul device prima di usarla

L'uso di posizioni mock richiede che l'app sia autorizzata esplicitamente:

1. Attiva le **Opzioni sviluppatore** (Impostazioni > Info telefono > tocca 7 volte "Numero build").
2. Vai in **Opzioni sviluppatore > App di posizione fittizia** (o "Select mock location app").
3. Seleziona **GPS Route Simulator**.
4. Concedi il permesso di posizione richiesto dall'app al primo avvio.

Senza questo passaggio, `setTestProviderLocation` lancia una `SecurityException`
e il servizio si ferma automaticamente (comportamento gestito nel codice).

## Cerca destinazione per indirizzo

Nella sezione destinazione ora puoi scrivere direttamente un **indirizzo testuale**
(es. "Via Roma 1, Milano") e premere **"Cerca indirizzo"**: l'app lo converte in
coordinate tramite **Nominatim** (il servizio di geocoding gratuito di OpenStreetMap,
nessuna chiave API richiesta) e lo imposta automaticamente come destinazione — i campi
lat/lon si aggiornano di conseguenza, così sai sempre le coordinate esatte trovate.

**Anche la partenza** ha le stesse tre opzioni, intercambiabili:
- **"Usa posizione attuale come partenza"** (GPS reale del device)
- **Cerca per indirizzo** (stesso meccanismo della destinazione)
- **Coordinate lat/lon manuali**

Qualunque opzione usi per ultima (per partenza e per destinazione, indipendentemente)
è quella che verrà usata quando avvii la simulazione o generi il percorso stradale.

Note:
- Serve connessione internet.
- Se l'indirizzo non viene trovato, prova a essere più specifico (via, città, eventualmente provincia/paese).
- Nominatim ha un limite d'uso di 1 richiesta al secondo per questo tipo di utilizzo leggero — va benissimo per test, ma non è pensato per un uso intensivo o in produzione.

## Mappa integrata nell'app

In alto nella schermata principale c'è una **mappa OpenStreetMap** (gratuita, nessuna chiave API richiesta) che mostra:

- il **tracciato** del percorso corrente (linea viola), aggiornato automaticamente ogni volta che carichi un GPX, imposti partenza/destinazione, o generi un percorso stradale
- un marker per la **partenza** e uno per la **destinazione**
- un marker **"live"** che si muove in tempo reale lungo il percorso mentre la simulazione è attiva, così puoi vedere il movimento direttamente nell'app, senza dover aprire Google Maps

Note tecniche:
- Le tile della mappa vengono scaricate da OpenStreetMap: serve connessione internet la prima volta che visualizzi un'area (poi restano in cache locale sul device).
- La mappa è dentro una schermata scorrevole: puoi trascinarla per esplorarla, ma lo zoom con due dita e lo scroll verticale della schermata possono "litigare" un po' — è una semplificazione accettabile per uno strumento di test.
- Il marker live si aggiorna una volta al secondo, in sincrono con gli aggiornamenti di posizione mock inviati al sistema.

## La simulazione si ferma da sola dopo un po' (MIUI/Xiaomi)

Su device Xiaomi/Redmi/POCO, MIUI ha una gestione della batteria molto più aggressiva
dello standard Android e può terminare il servizio in background anche se è "in
primo piano" con notifica attiva. Se noti che la simulazione si interrompe da sola:

1. **Impostazioni → App → Gestisci app → GPS Route Simulator → Risparmio energetico** → **"Nessuna restrizione"**
2. **Impostazioni → App → Gestisci app → GPS Route Simulator → Autostart** → attivo
3. Nella lista delle **app recenti**, tieni premuto sull'anteprima dell'app e tocca l'icona del **lucchetto** per "bloccarla" (evita che MIUI la chiuda quando libera memoria)
4. Se presente, controlla anche l'app **Sicurezza** di MIUI → Autorizzazioni → Risparmio energetico, ed escludi l'app dalle ottimizzazioni

Il servizio include anche un **wake lock parziale** (mantiene attivo il processore
durante la simulazione, con timeout di sicurezza di 6 ore) per ridurre le probabilità
che il sistema lo sospenda in modalità Doze/standby — ma su MIUI le impostazioni sopra
restano il fattore più importante.

## Percorso che segue le strade reali

Con partenza e destinazione impostate, premi **"Genera percorso stradale"**:
l'app chiama il servizio di routing pubblico **OSRM** (Open Source Routing Machine,
`router.project-osrm.org`) che calcola il tracciato reale seguendo la rete stradale
(non una linea retta), con tutte le curve e i cambi di direzione veri.

Dettagli:

- Serve connessione internet (permesso `INTERNET` già incluso nel manifest).
- Il percorso stradale ottenuto ha **priorità massima**: se generato, viene usato
  al posto del GPX caricato o della linea diretta partenza→destinazione.
- Se cambi partenza o destinazione dopo aver generato un percorso stradale, questo
  viene invalidato: premi di nuovo "Genera percorso stradale" per ricalcolarlo.
- Il server demo OSRM è pensato per **test e sviluppo**, non per produzione:
  nessuna garanzia di uptime né rate limit documentati pubblicamente. Per un uso
  continuativo o professionale, valuta di self-hostare OSRM oppure usare un
  provider commerciale (Google Directions, Mapbox Directions, GraphHopper),
  cambiando `BASE_URL` e il parsing in `OsrmRoutingClient.kt`.
- Al momento il routing collega solo partenza e destinazione (2 punti); per
  passare per waypoint intermedi si può estendere `getRoadRoute` per accettare
  più coordinate nell'URL (OSRM lo supporta già lato server).

## Partenza e destinazione personalizzate

Oltre a caricare un GPX completo, puoi:

- premere **"Usa posizione attuale come partenza"**: legge l'ultima posizione nota
  del device (richiede permesso posizione e GPS/localizzazione attivi) e la usa
  come primo punto del percorso;
- inserire manualmente **lat/lon di destinazione** e premere "Imposta destinazione".

Comportamento:

- Se hai caricato un GPX, partenza e destinazione (se impostate) **sostituiscono**
  solo il primo e l'ultimo punto del tracciato, mantenendo i punti intermedi e i
  relativi timestamp.
- Se **non** carichi nessun GPX ma imposti sia partenza sia destinazione, l'app
  genera un percorso diretto a due punti, percorso alla velocità costante
  impostata (km/h).

## Test rapido

Nel repo trovi `sample_route.gpx`: caricalo dall'app (pulsante "Seleziona file GPX"),
lascia l'opzione "Usa i timestamp originali" attiva e premi "Avvia simulazione".
Qualsiasi altra app che legga la posizione (Google Maps, un'app di navigazione
in sviluppo, ecc.) vedrà il dispositivo muoversi lungo il tracciato.

## Note

- Funziona solo su **provider GPS** (`LocationManager.GPS_PROVIDER`); se la tua
  app di test legge da `FUSED_PROVIDER` tramite Google Play Services, potrebbe
  essere necessario un approccio diverso (es. Location Provider API di Play Services
  in modalità test, non coperta da questo skeleton).
- Pensata esclusivamente per **test e sviluppo** di app che consumano posizione
  (navigazione, tracking, geofencing): va usata sui tuoi dispositivi/app di test,
  rispettando i termini d'uso delle piattaforme coinvolte.
- Il servizio è in foreground con notifica persistente, per rispettare i requisiti
  di Android su servizi a lunga esecuzione con `location` come tipo.
