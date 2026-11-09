package com.example.trackgo

import android.content.Context
import android.location.Location
import android.util.Log
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

// "object" = une seule copie dans toute l'application (comme DeliveryManager).
// Il garde la liste des commandes même quand on change d'onglet ou de langue.
// Il est SÉPARÉ de DeliveryManager : ici on gère les commandes et leurs statuts,
// là-bas on gère uniquement le GPS, la distance, la durée et les livraisons terminées.
//
// Sauvegarde sur le téléphone (OrderStorage) :
// - les commandes ACCEPTÉES, EN COURS, LIVRÉES et ANNULÉES sont conservées ;
// - les commandes DISPONIBLES et REFUSÉES ne sont pas conservées.
object OrderManager {
    // ---------- Statuts d'une commande ----------
    // Identifiants internes (jamais affichés tels quels).
    const val STATUS_AVAILABLE = "AVAILABLE"     // visible sur la carte, pas encore de réponse
    const val STATUS_ACCEPTED = "ACCEPTED"       // acceptée : "En attente" dans Gestion de livraison
    const val STATUS_IN_PROGRESS = "IN_PROGRESS" // livraison démarrée : "En cours"
    const val STATUS_DELIVERED = "DELIVERED"     // livrée : "Livrée"
    const val STATUS_REFUSED = "REFUSED"         // refusée : disparaît de la carte
    const val STATUS_CANCELLED = "CANCELLED"     // annulée (le motif est gardé) : "Annulée"
    // Les statuts qu'on garde sur le téléphone
    private val savedStatuses = setOf(
        STATUS_ACCEPTED, STATUS_IN_PROGRESS, STATUS_DELIVERED, STATUS_CANCELLED
    )
    // ---------- Réglages de la simulation ----------
    private const val ORDER_COUNT = 3
    private const val MIN_DISTANCE_METERS = 200.0 // les commandes sont entre 200 m
    private const val MAX_DISTANCE_METERS = 800.0 // et 800 m du livreur
    private const val METERS_PER_DEGREE = 111_320.0 // 1 degré de latitude ≈ 111 320 m
    private const val ID_PREFIX = "CMD-"
    private const val TAG = "TrackGo" // pour filtrer dans Logcat

    private val customerNames = listOf(
        "Youssef Benali", "Salma El Idrissi", "Omar Alaoui", "Khadija Bennani",
        "Mehdi Tazi", "Imane Fassi", "Hamza Chraibi", "Nadia Lahlou"
    )

    // ---------- Listener : "préviens-moi quand ça change" ----------
    interface Listener {
        fun onOrdersChanged()
    }

    private val listeners = mutableListOf<Listener>()

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    // ---------- Données ----------
    private val orders = mutableListOf<Order>()
    private var nextOrderNumber = 1001

    // Contexte de l'application (pour sauvegarder). Il vit aussi longtemps que l'application :
    // pas de risque de "fuite" comme avec le contexte d'un écran.
    private var appContext: Context? = null
    private var isInitialized = false

    // Vrai quand les 3 commandes de CE lancement de l'application ont été créées.
    // Cette variable n'est PAS sauvegardée : à chaque nouveau lancement, elle repart à false.
    // Changer d'onglet ou de langue ne relance pas l'application : elle reste à true.
    private var ordersGeneratedThisLaunch = false

    // ---------- Démarrage ----------

    // À appeler au lancement (MainActivity). Recharge les commandes sauvegardées.
    // Les statuts ne sont PAS modifiés : une commande "en cours" reste "en cours".
    // Sans effet si déjà fait (MainActivity est recréée à chaque changement de langue).
    fun init(context: Context) {
        if (isInitialized) return
        val ctx = context.applicationContext
        appContext = ctx

        orders.clear()
        orders.addAll(OrderStorage.loadOrders(ctx))

        // Prochain numéro = le plus grand entre le compteur sauvegardé et
        // (numéro le plus élevé déjà utilisé + 1) : jamais de doublon.
        val highestUsed = orders
            .mapNotNull { it.id.removePrefix(ID_PREFIX).toIntOrNull() }
            .maxOrNull() ?: 0
        nextOrderNumber = max(OrderStorage.loadNextNumber(ctx), highestUsed + 1)

        isInitialized = true
        Log.d(TAG, "OrderManager prêt : ${orders.size} commande(s), prochain numéro $nextOrderNumber")
    }

    // ---------- Lecture ----------

    // Commandes visibles sur la carte (pas encore acceptées ni refusées)
    fun getAvailableOrders(): List<Order> =
        orders.filter { it.status == STATUS_AVAILABLE }

    // Commandes acceptées, pas encore démarrées ("En attente")
    fun getAcceptedOrders(): List<Order> =
        orders.filter { it.status == STATUS_ACCEPTED }

    // La commande dont la livraison est en cours (il n'y en a qu'une à la fois), ou null
    fun getInProgressOrder(): Order? =
        orders.firstOrNull { it.status == STATUS_IN_PROGRESS }

    // Toutes les commandes pour la page "Gestion de livraison" :
    // en attente, en cours, livrées et annulées (pas les disponibles ni les refusées)
    fun getManagedOrders(): List<Order> =
        orders.filter { it.status in savedStatuses }

    fun getOrderById(id: String): Order? =
        orders.firstOrNull { it.id == id }

    // ---------- Génération ----------

    // Crée 3 commandes disponibles autour de la position donnée (celle du livreur).
    // Elle ne fait rien si c'est déjà fait pour ce lancement de l'application.
    // Les anciennes commandes sauvegardées ne sont jamais supprimées.
    // Retourne true si les commandes viennent d'être créées.
    fun generateOrders(centerLat: Double, centerLng: Double): Boolean {
        val ctx = appContext
        if (!isInitialized || ctx == null) {
            Log.e(TAG, "OrderManager.init() n'a pas été appelé : aucune commande générée")
            return false
        }
        if (ordersGeneratedThisLaunch) return false

        // Noms mélangés : 3 clients différents
        val names = customerNames.shuffled().take(ORDER_COUNT)

        // Angle de départ au hasard, puis une commande tous les 120° (± un peu de hasard) :
        // les 3 commandes sont ainsi réparties autour du livreur, pas toutes du même côté.
        val startAngle = Random.nextDouble(0.0, 360.0)

        for (i in 0 until ORDER_COUNT) {
            val angle = Math.toRadians(startAngle + i * 120.0 + Random.nextDouble(-25.0, 25.0))
            val distance = Random.nextDouble(MIN_DISTANCE_METERS, MAX_DISTANCE_METERS)

            // Décalage vers le nord et vers l'est, en mètres, puis conversion en degrés
            val northMeters = distance * cos(angle)
            val eastMeters = distance * sin(angle)
            val latitude = centerLat + northMeters / METERS_PER_DEGREE
            val longitude = centerLng +
                    eastMeters / (METERS_PER_DEGREE * cos(Math.toRadians(centerLat)))

            // Distance réelle livreur → commande
            val results = FloatArray(1)
            Location.distanceBetween(centerLat, centerLng, latitude, longitude, results)

            orders.add(
                Order(
                    id = "$ID_PREFIX${nextOrderNumber++}", // numéro unique, puis on avance
                    customerName = names[i],
                    phone = "06 ${Random.nextInt(10, 100)} ${Random.nextInt(10, 100)} " +
                            "${Random.nextInt(10, 100)} ${Random.nextInt(10, 100)}",
                    amount = Random.nextInt(20, 151).toDouble(), // entre 20 et 150 MAD
                    latitude = latitude,
                    longitude = longitude,
                    distanceFromDriverMeters = results[0]
                )
            )
        }

        ordersGeneratedThisLaunch = true

        // On sauvegarde le compteur tout de suite : même si ces commandes ne sont pas
        // acceptées (donc pas conservées), leurs numéros ne seront jamais réutilisés.
        OrderStorage.saveNextNumber(ctx, nextOrderNumber)

        notifyOrdersChanged()
        return true
    }

    // ---------- Actions du livreur ----------
    // Chaque action retourne true si elle a réussi, false si elle n'est pas permise.

    // Accepter : seulement une commande encore "disponible".
    // Attention : accepter une commande ne démarre PAS le suivi GPS.
    fun acceptOrder(id: String): Boolean {
        val order = getOrderById(id) ?: return false
        if (order.status != STATUS_AVAILABLE) return false
        order.status = STATUS_ACCEPTED
        order.acceptedAtMillis = System.currentTimeMillis()
        saveOrders()
        notifyOrdersChanged()
        return true
    }

    // Refuser : seulement une commande encore "disponible". Elle disparaît de la carte.
    // Elle n'est pas sauvegardée (inutile de garder une commande refusée).
    fun refuseOrder(id: String): Boolean {
        val order = getOrderById(id) ?: return false
        if (order.status != STATUS_AVAILABLE) return false
        order.status = STATUS_REFUSED
        notifyOrdersChanged()
        return true
    }

    // Démarrer : seulement une commande acceptée, et seulement si aucune autre n'est en cours
    // (il n'y a qu'un GPS et qu'un chronomètre). Cette fonction ne touche pas au GPS :
    // c'est DeliveryManager qui le démarrera (étapes suivantes).
    fun startOrder(id: String, startTimeMillis: Long = System.currentTimeMillis()): Boolean {
        val order = getOrderById(id) ?: return false
        if (order.status != STATUS_ACCEPTED) return false
        if (getInProgressOrder() != null) return false
        order.status = STATUS_IN_PROGRESS
        order.startTimeMillis = startTimeMillis
        order.distanceMeters = 0f
        order.durationMillis = 0L
        saveOrders()
        notifyOrdersChanged()
        return true
    }

    // Garde la distance parcourue d'une commande en cours (utile si l'application se ferme).
    // Pas de notification : cette fonction sera appelée souvent pendant la livraison.
    fun updateProgress(id: String, distanceMeters: Float): Boolean {
        val order = getOrderById(id) ?: return false
        if (order.status != STATUS_IN_PROGRESS) return false
        order.distanceMeters = distanceMeters
        saveOrders()
        return true
    }

    // Livrer : seulement une commande en cours. On garde sa distance et sa durée finales.
    fun completeOrder(id: String, distanceMeters: Float, durationMillis: Long): Boolean {
        val order = getOrderById(id) ?: return false
        if (order.status != STATUS_IN_PROGRESS) return false
        order.status = STATUS_DELIVERED
        order.distanceMeters = distanceMeters
        order.durationMillis = durationMillis
        saveOrders()
        notifyOrdersChanged()
        return true
    }

    // Annuler : une commande en attente OU en cours, avec un motif obligatoire.
    // Le motif est gardé dans la commande, qui reste dans la liste (statut "Annulée").
    fun cancelOrder(id: String, reason: String): Boolean {
        val cleanReason = reason.trim()
        if (cleanReason.isEmpty()) return false
        val order = getOrderById(id) ?: return false
        if (order.status != STATUS_ACCEPTED && order.status != STATUS_IN_PROGRESS) return false
        order.status = STATUS_CANCELLED
        order.cancelReason = cleanReason
        saveOrders()
        notifyOrdersChanged()
        return true
    }

    // ---------- Sauvegarde ----------

    private fun saveOrders() {
        val ctx = appContext ?: return
        OrderStorage.saveOrders(ctx, orders.filter { it.status in savedStatuses })
    }

    private fun notifyOrdersChanged() {
        // .toList() = on parcourt une copie, au cas où une page se désinscrit pendant l'appel
        listeners.toList().forEach { it.onOrdersChanged() }
    }
}