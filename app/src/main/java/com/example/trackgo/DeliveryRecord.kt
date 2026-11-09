package com.example.trackgo

// Une livraison terminée, telle qu'elle est sauvegardée.
// "data class" = une petite classe qui sert juste à ranger des informations.
data class DeliveryRecord(
    val dateMillis: Long,        // date et heure de début (en millisecondes)
    val distanceMeters: Float,   // distance totale, en mètres
    val durationMillis: Long,    // durée totale, en millisecondes
    val status: String,          // statut final (ex. : "Livraison terminée")
    val orderId: String? = null  // commande liée (ex. : "CMD-1002") ; null = livraison sans commande ou ancienne livraison
)