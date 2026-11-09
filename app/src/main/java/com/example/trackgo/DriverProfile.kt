package com.example.trackgo

// Le profil du livreur : les informations saisies dans le formulaire.
// "data class" = une petite classe qui sert juste à ranger des informations.
data class DriverProfile(
    val lastName: String,    // nom
    val firstName: String,   // prénom
    val age: Int,            // âge
    val licenseType: String, // type de permis (ex. : "B")
    val cin: String          // CIN / numéro d'identification
)