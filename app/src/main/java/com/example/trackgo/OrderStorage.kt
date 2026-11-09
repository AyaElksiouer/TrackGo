package com.example.trackgo

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// Sauvegarde et lecture des commandes sur le téléphone.
// Même principe que DeliveryStorage (SharedPreferences + texte JSON), mais dans un
// fichier À PART : les anciennes livraisons de DeliveryStorage ne sont pas touchées.
object OrderStorage {

    private const val PREFS_NAME = "trackgo_orders_prefs"
    private const val KEY_ORDERS = "orders"
    private const val KEY_NEXT_NUMBER = "next_order_number"
    private const val FIRST_NUMBER = 1001 // la toute première commande sera CMD-1001
    private const val TAG = "TrackGo" // pour filtrer dans Logcat

    // Réécrit toute la liste des commandes à conserver
    fun saveOrders(context: Context, orders: List<Order>) {
        val array = JSONArray()
        for (order in orders) {
            val obj = JSONObject()
            obj.put("id", order.id)
            obj.put("customerName", order.customerName)
            obj.put("phone", order.phone)
            obj.put("amount", order.amount)
            obj.put("latitude", order.latitude)
            obj.put("longitude", order.longitude)
            obj.put("distanceFromDriver", order.distanceFromDriverMeters.toDouble())
            obj.put("status", order.status)
            obj.put("cancelReason", order.cancelReason ?: "") // vide = pas de motif
            obj.put("createdAt", order.createdAtMillis)
            obj.put("acceptedAt", order.acceptedAtMillis)
            obj.put("startTime", order.startTimeMillis)
            obj.put("distance", order.distanceMeters.toDouble())
            obj.put("duration", order.durationMillis)
            array.put(obj)
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ORDERS, array.toString()).apply()
        Log.d(TAG, "Commandes sauvegardées : ${array.length()}")
    }

    // Relit toutes les commandes sauvegardées
    fun loadOrders(context: Context): List<Order> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val list = mutableListOf<Order>()

        try {
            val array = JSONArray(prefs.getString(KEY_ORDERS, "[]") ?: "[]")
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val reason = obj.optString("cancelReason", "")
                list.add(
                    Order(
                        id = obj.getString("id"),
                        customerName = obj.getString("customerName"),
                        phone = obj.getString("phone"),
                        amount = obj.getDouble("amount"),
                        latitude = obj.getDouble("latitude"),
                        longitude = obj.getDouble("longitude"),
                        distanceFromDriverMeters = obj.getDouble("distanceFromDriver").toFloat(),
                        status = obj.getString("status"),
                        cancelReason = if (reason.isEmpty()) null else reason,
                        createdAtMillis = obj.optLong("createdAt", 0L),
                        acceptedAtMillis = obj.optLong("acceptedAt", 0L),
                        startTimeMillis = obj.optLong("startTime", 0L),
                        distanceMeters = obj.optDouble("distance", 0.0).toFloat(),
                        durationMillis = obj.optLong("duration", 0L)
                    )
                )
            }
        } catch (e: JSONException) {
            Log.e(TAG, "Lecture des commandes impossible", e)
        }

        Log.d(TAG, "Commandes chargées : ${list.size}")
        return list
    }

    // Prochain numéro de commande à utiliser (le compteur ne revient jamais en arrière)
    fun loadNextNumber(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_NEXT_NUMBER, FIRST_NUMBER)
    }

    fun saveNextNumber(context: Context, number: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_NEXT_NUMBER, number).apply()
    }
}