package com.example.trackgo

// Une commande simulée.
// "data class" = une petite classe qui sert juste à ranger des informations
// (comme DeliveryRecord pour les livraisons terminées).
data class Order(
    val id: String,                    // identifiant affiché (ex. : "CMD-1001")
    val customerName: String,          // nom du client
    val phone: String,                 // téléphone du client
    val amount: Double,                // montant à encaisser, en dirhams
    val latitude: Double,              // position de la commande sur la carte
    val longitude: Double,
    val distanceFromDriverMeters: Float, // distance entre le livreur et la commande, à la création
    // "var" : ces valeurs changent pendant la vie de la commande
    var status: String = OrderManager.STATUS_AVAILABLE,
    var cancelReason: String? = null,  // motif d'annulation (null tant que la commande n'est pas annulée)
    val createdAtMillis: Long = System.currentTimeMillis(), // création de la commande
    var acceptedAtMillis: Long = 0L,   // moment de l'acceptation (0 = pas encore accepté)
    var startTimeMillis: Long = 0L,    // moment où la livraison de cette commande a démarré
    var distanceMeters: Float = 0f,    // distance PARCOURUE pour livrer cette commande
    var durationMillis: Long = 0L      // durée finale de la livraison (remplie quand elle est livrée)
)