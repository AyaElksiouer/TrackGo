package com.example.trackgo

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

// Tout ce qui concerne la notification de fin de livraison est regroupé ici.
// Comme DeliveryManager, c'est un "object" : une seule copie dans l'application.
//
// LANGUE : les textes sont lus avec le "context" reçu en paramètre. HomeFragment lui donne
// le contexte de l'écran, qui suit la langue choisie (FR, EN ou AR).
object NotificationHelper {
    private const val CHANNEL_ID = "delivery_channel"
    private const val NOTIFICATION_ID = 1001
    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        )
        channel.description = context.getString(R.string.notif_channel_description)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
    @SuppressLint("MissingPermission") // la permission est vérifiée au début de la fonction
    fun showDeliveryFinished(context: Context, record: DeliveryRecord) {
        if (!hasNotificationPermission(context)) return
        val appContext = context.applicationContext
        createChannel(context)
        val manager = NotificationManagerCompat.from(appContext)
        if (!manager.areNotificationsEnabled()) return
        val title = DeliveryManager.getStatusLabel(context, record.status)
        val distance = DeliveryManager.formatDistance(context, record.distanceMeters)
        val duration = DeliveryManager.formatDuration(record.durationMillis)
        val intent = Intent(appContext, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        val pendingIntent = PendingIntent.getActivity(appContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_delivery)        // icône camion
            .setContentTitle(title)                      // "Livraison terminée" / "Delivery completed" / ...
            .setContentText(context.getString(R.string.notif_text, distance, duration))
            .setStyle(
                NotificationCompat.BigTextStyle()        // texte complet si on déplie
                    .bigText(context.getString(R.string.notif_big_text, distance, duration)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)                         // disparaît après un appui
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}