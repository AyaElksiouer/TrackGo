package com.example.trackgo

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.Locale
// "object" = une seule copie dans toute l'application.
// Contrairement à un Fragment (détruit quand on change d'onglet),
// DeliveryManager reste en vie : c'est lui qui garde la livraison en cours.
// Le GPS, le chronomètre et la distance sont ici ; les pages ne font que les afficher.
//
// NOUVEAU (étape 5) : le suivi peut être lié à UNE commande (currentOrderId).
// DeliveryManager ne change jamais le statut d'une commande : c'est le travail d'OrderManager.
// L'écran qui démarre ou termine une livraison appelle les deux, dans le bon ordre.
object DeliveryManager {
    const val STATUS_WAITING = "En attente"
    const val STATUS_IN_PROGRESS = "Livraison en cours"
    const val STATUS_FINISHED = "Livraison terminée"
    private const val TAG = "TrackGo"
    interface Listener {
        fun onDeliveryChanged()                    // distance, durée ou statut ont changé
        fun onLocationChanged(location: Location)  // nouvelle position GPS reçue
    }
    private val listeners = mutableListOf<Listener>()
    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }
    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }
    var isTracking = false
        private set
    var status = STATUS_WAITING
        private set
    var totalDistance = 0f // en mètres
        private set
    var lastLocation: Location? = null // dernière position reçue (pour le marqueur)
        private set
    var startTime = 0L
        private set
    var currentOrderId: String? = null
        private set
    private var finalDuration = 0L
    private var startLocation: Location? = null // point de référence pour la distance

    private var fusedLocationClient: FusedLocationProviderClient? = null

    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY,
        5000
    ).build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val location = locationResult.lastLocation ?: return
            handleNewLocation(location)
        }
    }

    // Chronomètre : chaque seconde, on prévient les pages pour qu'elles réaffichent la durée
    private val handler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            notifyDeliveryChanged()
            handler.postDelayed(this, 1000)
        }
    }

    // ---------- Actions ----------

    // Démarre une nouvelle livraison, avec ou sans commande.
    // À appeler seulement quand la permission de localisation est accordée
    // (c'est l'écran qui la vérifie avant).
    // Sans "orderId" : fonctionne exactement comme avant (HomeFragment actuel).
    fun startDelivery(context: Context, orderId: String? = null) {
        if (isTracking) return // déjà en cours
        beginTracking(context, orderId, System.currentTimeMillis(), 0f)
    }
    fun resumeDelivery(context: Context, orderId: String, startTimeMillis: Long, distanceMeters: Float) {
        if (isTracking) return // déjà en cours
        beginTracking(context, orderId, startTimeMillis, distanceMeters)
    }
    @SuppressLint("MissingPermission")
    private fun beginTracking(context: Context, orderId: String?, beginTimeMillis: Long, initialDistance: Float) {
        val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)
        fusedLocationClient = client
        isTracking = true
        status = STATUS_IN_PROGRESS
        currentOrderId = orderId
        startTime = beginTimeMillis
        finalDuration = 0L
        totalDistance = initialDistance
        startLocation = null
        lastLocation = null
        client.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        handler.removeCallbacks(timerRunnable) // sécurité : jamais deux chronomètres
        handler.post(timerRunnable)
        Log.d(TAG, "Suivi GPS démarré (commande : ${orderId ?: "aucune"}, distance de départ : ${initialDistance.toInt()} m)")
        notifyDeliveryChanged()
    }

    // Termine la livraison, la sauvegarde et la retourne.
    // Retourne null s'il n'y avait aucune livraison en cours.
    // Le DeliveryRecord retourné contient l'identifiant de la commande (record.orderId),
    // sa distance et sa durée : ce sont les données dont OrderManager.completeOrder() a besoin.
    fun finishDelivery(context: Context): DeliveryRecord? {
        if (!isTracking) return null
        fusedLocationClient?.removeLocationUpdates(locationCallback)
        handler.removeCallbacks(timerRunnable)
        isTracking = false
        finalDuration = System.currentTimeMillis() - startTime
        status = STATUS_FINISHED
        val record = DeliveryRecord(
            dateMillis = startTime,
            distanceMeters = totalDistance,
            durationMillis = finalDuration,
            status = status,
            orderId = currentOrderId
        )
        DeliveryStorage.saveDelivery(context, record)
        currentOrderId = null
        notifyDeliveryChanged()
        return record
    }

    // NOUVEAU (étape 5) : arrête le GPS et le chronomètre SANS enregistrer la livraison
    // comme terminée (rien n'est ajouté à l'historique). Sert à annuler une commande en cours.
    // Le statut de la commande, lui, est changé par OrderManager.cancelOrder().
    fun cancelDelivery() {
        if (!isTracking) return

        fusedLocationClient?.removeLocationUpdates(locationCallback)
        handler.removeCallbacks(timerRunnable)
        isTracking = false

        Log.d(TAG, "Suivi GPS annulé (commande : ${currentOrderId ?: "aucune"})")

        // On remet tout à zéro : aucune livraison n'est conservée
        status = STATUS_WAITING
        totalDistance = 0f
        finalDuration = 0L
        startTime = 0L
        startLocation = null
        currentOrderId = null

        notifyDeliveryChanged()
    }

    // Durée écoulée : en direct pendant la livraison, figée après la fin
    fun getDurationMillis(): Long {
        return if (isTracking) System.currentTimeMillis() - startTime else finalDuration
    }

    // Transforme des millisecondes en texte "HH:mm:ss" (ex. : 01:05:09)
    fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }
    fun formatDistance(context: Context, meters: Float): String {
        return context.getString(R.string.distance_km_value, meters / 1000)
    }
    fun getStatusLabel(context: Context, status: String): String {
        return when (status) {
            STATUS_WAITING -> context.getString(R.string.status_waiting)
            STATUS_IN_PROGRESS -> context.getString(R.string.status_in_progress)
            STATUS_FINISHED -> context.getString(R.string.status_finished)
            else -> status // statut inconnu : on l'affiche tel quel
        }
    }

    // ---------- Calcul de la distance (même logique qu'avant) ----------
    private fun handleNewLocation(location: Location) {
        val previous = startLocation
        if (previous == null) {
            startLocation = location
        } else {
            val distance = previous.distanceTo(location)
            if (distance > 10) { // on ignore les petits mouvements (bruit du GPS)
                totalDistance += distance
                startLocation = location
                currentOrderId?.let { orderId ->
                    OrderManager.updateProgress(orderId, totalDistance)
                }
            }
        }
        lastLocation = location
        notifyDeliveryChanged()
        // .toList() = on parcourt une copie, au cas où une page se désinscrit pendant l'appel
        listeners.toList().forEach { it.onLocationChanged(location) }
    }

    private fun notifyDeliveryChanged() {
        listeners.toList().forEach { it.onDeliveryChanged() }
    }
}