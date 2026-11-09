package com.example.trackgo

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// Sauvegarde et lecture des livraisons terminées.
// On utilise SharedPreferences (déjà fourni par Android) : un petit fichier
// qui reste sur le téléphone, même après la fermeture de l'application.
// La liste est écrite sous forme de texte JSON (org.json est déjà inclus dans Android).
object DeliveryStorage {

    private const val PREFS_NAME = "trackgo_prefs"
    private const val KEY_DELIVERIES = "deliveries"
    private const val TAG = "TrackGo" // pour filtrer dans Logcat

    // Ajoute une livraison à la liste sauvegardée
    fun saveDelivery(context: Context, record: DeliveryRecord) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 1. Lire la liste existante (ou une liste vide au tout début)
        val array = try {
            JSONArray(prefs.getString(KEY_DELIVERIES, "[]") ?: "[]")
        } catch (e: JSONException) {
            JSONArray()
        }

        // 2. Transformer la livraison en objet JSON
        val obj = JSONObject()
        obj.put("date", record.dateMillis)
        obj.put("distance", record.distanceMeters.toDouble())
        obj.put("duration", record.durationMillis)
        obj.put("status", record.status)
        // orderId n'est écrit que s'il existe : les livraisons sans commande gardent exactement le même format qu'avant
        record.orderId?.let { obj.put("orderId", it) }

        // 3. L'ajouter à la liste puis tout réécrire
        array.put(obj)
        prefs.edit().putString(KEY_DELIVERIES, array.toString()).apply()

        Log.d(TAG, "Livraison sauvegardée (${array.length()} au total) : $obj")
    }

    // Retourne toutes les livraisons sauvegardées, la plus récente en premier
    fun getAllDeliveries(context: Context): List<DeliveryRecord> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val list = mutableListOf<DeliveryRecord>()

        try {
            val array = JSONArray(prefs.getString(KEY_DELIVERIES, "[]") ?: "[]")
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    DeliveryRecord(
                        dateMillis = obj.getLong("date"),
                        distanceMeters = obj.getDouble("distance").toFloat(),
                        durationMillis = obj.getLong("duration"),
                        status = obj.getString("status"),
                        // Lecture tolérante : les anciennes livraisons n'ont pas ce champ -> null
                        orderId = if (obj.isNull("orderId")) null
                        else obj.optString("orderId", "").ifEmpty { null }
                    )
                )
            }
        } catch (e: JSONException) {
            Log.e(TAG, "Lecture des livraisons impossible", e)
        }

        return list.reversed()
    }
}