package com.example.trackgo

import android.content.Context
import android.util.Log

// Sauvegarde et lecture du profil du livreur.
// Même principe que DeliveryStorage : SharedPreferences, un petit fichier qui reste
// sur le téléphone même après la fermeture de l'application.
object ProfileStorage {

    private const val PREFS_NAME = "trackgo_prefs" // même fichier que les livraisons
    private const val KEY_LAST_NAME = "profile_last_name"
    private const val KEY_FIRST_NAME = "profile_first_name"
    private const val KEY_AGE = "profile_age"
    private const val KEY_LICENSE = "profile_license"
    private const val KEY_CIN = "profile_cin"
    private const val TAG = "TrackGo" // pour filtrer dans Logcat

    // Enregistre le profil (remplace l'ancien s'il existe)
    fun saveProfile(context: Context, profile: DriverProfile) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_LAST_NAME, profile.lastName)
            .putString(KEY_FIRST_NAME, profile.firstName)
            .putInt(KEY_AGE, profile.age)
            .putString(KEY_LICENSE, profile.licenseType)
            .putString(KEY_CIN, profile.cin)
            .apply()
        Log.d(TAG, "Profil du livreur sauvegardé")
    }
    fun getProfile(context: Context): DriverProfile? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastName = prefs.getString(KEY_LAST_NAME, null) ?: return null
        return DriverProfile(
            lastName = lastName,
            firstName = prefs.getString(KEY_FIRST_NAME, "") ?: "",
            age = prefs.getInt(KEY_AGE, 0),
            licenseType = prefs.getString(KEY_LICENSE, "") ?: "",
            cin = prefs.getString(KEY_CIN, "") ?: ""
        )
    }
}