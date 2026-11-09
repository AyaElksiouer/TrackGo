package com.example.trackgo

import android.Manifest
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import pub.devrel.easypermissions.EasyPermissions

// Page "Gestion des commandes" :
// - la liste des COMMANDES (lues dans OrderManager) : en attente, en cours, livrées, annulées
// (la livraison actuelle se suit depuis l'Accueil, les livraisons terminées sont dans l'Historique)
// NOUVEAU (étape 7A) : c'est ici qu'on DÉMARRE et qu'on TERMINE la livraison d'une commande.
// - Démarrer : vérifie qu'aucune autre livraison n'est suivie, vérifie (et demande si besoin)
//   la permission de localisation, puis OrderManager (statut) + DeliveryManager (GPS).
// - Terminer : DeliveryManager arrête le GPS et enregistre dans l'historique,
//   OrderManager passe la commande à "Livrée", puis notification et résumé.
// - Annuler : voir plus bas (arrête aussi le GPS si c'est la commande suivie).
// OrderManager gère les statuts, DeliveryManager gère le GPS : chacun son travail.
// Tous les textes viennent de strings.xml : ils suivent la langue choisie.
class DeliveryManagementFragment : Fragment(R.layout.fragment_delivery_management),
    EasyPermissions.PermissionCallbacks {
    companion object {
        private const val TAG = "TrackGo" // pour filtrer dans Logcat

        // Codes qui permettent de savoir quelle permission vient d'être acceptée ou refusée
        private const val REQUEST_LOCATION = 200
        private const val REQUEST_NOTIFICATIONS = 201
    }

    // Références vers les éléments de l'écran (remises à null quand l'écran est détruit)
    // La section "Commandes"
    private var txtOrdersEmpty: TextView? = null
    private var ordersContainer: LinearLayout? = null

    // Commande à démarrer dès que la permission de localisation sera accordée
    private var pendingStartOrderId: String? = null

    // Format d'affichage de la date : "02/11/2026 20:52".
    // Locale.US : toujours les chiffres 0 1 2 3..., dans les trois langues.
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US)

    // Cette page s'inscrit auprès d'OrderManager.
    // Dès qu'une commande change (annulée, par exemple), la liste est reconstruite.
    private val orderListener = object : OrderManager.Listener {
        override fun onOrdersChanged() {
            loadOrders()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        txtOrdersEmpty = view.findViewById(R.id.txtOrdersEmpty)
        ordersContainer = view.findViewById(R.id.ordersContainer)

        OrderManager.addListener(orderListener)

        loadOrders()
    }

    // On se désinscrit quand l'écran disparaît
    override fun onDestroyView() {
        super.onDestroyView()
        OrderManager.removeListener(orderListener)
        pendingStartOrderId = null
        txtOrdersEmpty = null
        ordersContainer = null
    }

    // ---------- NOUVEAU (étape 6) : liste des commandes ----------

    // Reconstruit la liste des commandes à partir d'OrderManager.
    // getManagedOrders() = commandes en attente, en cours, livrées et annulées
    // (les commandes "disponibles" et "refusées" n'ont rien à faire dans cette page).
    private fun loadOrders() {
        val container = ordersContainer ?: return
        val context = requireContext()

        container.removeAllViews()
        val orders = sortOrders(OrderManager.getManagedOrders())

        // Aucune commande : on affiche un message
        if (orders.isEmpty()) {
            txtOrdersEmpty?.visibility = View.VISIBLE
            return
        }
        txtOrdersEmpty?.visibility = View.GONE

        // Pour chaque commande, on crée une carte à partir de item_order.xml
        val inflater = LayoutInflater.from(context)
        for (order in orders) {
            container.addView(createOrderItem(inflater, container, order))
        }
    }

    // Ordre d'affichage : d'abord la commande en cours, puis les commandes en attente,
    // puis les commandes livrées et annulées. À l'intérieur d'un groupe : la plus
    // récemment acceptée en premier.
    private fun sortOrders(orders: List<Order>): List<Order> {
        return orders.sortedWith(
            compareBy<Order> { statusRank(it.status) }
                .thenByDescending { it.acceptedAtMillis }
        )
    }

    private fun statusRank(status: String): Int {
        return when (status) {
            OrderManager.STATUS_IN_PROGRESS -> 0
            OrderManager.STATUS_ACCEPTED -> 1
            else -> 2 // livrées et annulées
        }
    }

    // Texte d'un statut de commande dans la langue choisie
    // (les statuts internes d'OrderManager ne s'affichent jamais tels quels)
    private fun getOrderStatusLabel(status: String): String {
        return when (status) {
            OrderManager.STATUS_ACCEPTED -> getString(R.string.order_status_waiting)
            OrderManager.STATUS_IN_PROGRESS -> getString(R.string.order_status_in_progress)
            OrderManager.STATUS_DELIVERED -> getString(R.string.order_status_delivered)
            OrderManager.STATUS_CANCELLED -> getString(R.string.order_status_cancelled)
            else -> status // statut inconnu : on l'affiche tel quel
        }
    }

    // NOUVEAU (étape 4) : colore la pastille du statut (fond + texte).
    // Affichage seulement : aucune règle de commande n'est touchée.
    // - en attente : orange   - en cours : bleu   - livrée : vert   - annulée : rouge
    // Les couleurs viennent du thème : elles changent toutes seules en mode clair / sombre.
    private fun applyStatusColors(txtStatus: TextView, status: String) {
        val colors: Pair<Int, Int>? = when (status) {
            OrderManager.STATUS_ACCEPTED ->
                Pair(R.attr.tgPendingContainer, R.attr.tgOnPendingContainer)
            OrderManager.STATUS_IN_PROGRESS -> Pair(
                com.google.android.material.R.attr.colorPrimaryContainer,
                com.google.android.material.R.attr.colorOnPrimaryContainer
            )
            OrderManager.STATUS_DELIVERED -> Pair(
                com.google.android.material.R.attr.colorTertiaryContainer,
                com.google.android.material.R.attr.colorOnTertiaryContainer
            )
            OrderManager.STATUS_CANCELLED -> Pair(
                com.google.android.material.R.attr.colorErrorContainer,
                com.google.android.material.R.attr.colorOnErrorContainer
            )
            else -> null // statut inconnu : couleurs par défaut du layout
        }
        if (colors != null) {
            txtStatus.backgroundTintList =
                ColorStateList.valueOf(MaterialColors.getColor(txtStatus, colors.first))
            txtStatus.setTextColor(MaterialColors.getColor(txtStatus, colors.second))
        }
    }

    // Crée la carte d'une commande. Les champs inutiles pour son statut restent masqués.
    private fun createOrderItem(
        inflater: LayoutInflater,
        container: ViewGroup,
        order: Order
    ): View {
        val context = requireContext()
        val item = inflater.inflate(R.layout.item_order, container, false)
        val status = order.status

        // Titre : "Commande CMD-1002" (même texte que sur la carte de l'Accueil)
        item.findViewById<TextView>(R.id.txtOrderItemTitle).text =
            getString(R.string.order_marker_title, order.id)

        // Statut bien visible : pastille colorée selon le statut (étape 4)
        val txtStatus = item.findViewById<TextView>(R.id.txtOrderItemStatus)
        txtStatus.text = getString(R.string.label_status, getOrderStatusLabel(status))
        applyStatusColors(txtStatus, status)

        // Informations du client (toujours affichées)
        item.findViewById<TextView>(R.id.txtOrderItemCustomer).text =
            getString(R.string.order_label_customer, order.customerName)
        item.findViewById<TextView>(R.id.txtOrderItemPhone).text =
            getString(R.string.order_label_phone, order.phone)
        item.findViewById<TextView>(R.id.txtOrderItemAmount).text = getString(
            R.string.order_label_amount,
            getString(R.string.order_amount_value, order.amount)
        )

        // Distance : en cours ou livrée = distance parcourue ;
        // en attente ou annulée = distance entre le livreur et la commande (à la création)
        val txtDistance = item.findViewById<TextView>(R.id.txtOrderItemDistance)
        txtDistance.text = if (status == OrderManager.STATUS_IN_PROGRESS ||
            status == OrderManager.STATUS_DELIVERED
        ) {
            getString(
                R.string.label_distance_travelled,
                DeliveryManager.formatDistance(context, order.distanceMeters)
            )
        } else {
            getString(
                R.string.label_distance,
                DeliveryManager.formatDistance(context, order.distanceFromDriverMeters)
            )
        }

        // Heure de début : seulement si la livraison a démarré (en cours ou livrée)
        val txtStartTime = item.findViewById<TextView>(R.id.txtOrderItemStartTime)
        if ((status == OrderManager.STATUS_IN_PROGRESS || status == OrderManager.STATUS_DELIVERED) &&
            order.startTimeMillis > 0L
        ) {
            txtStartTime.text = getString(
                R.string.label_start_time,
                dateFormat.format(Date(order.startTimeMillis))
            )
            txtStartTime.visibility = View.VISIBLE
        }

        // Durée finale : seulement pour une commande livrée
        val txtDuration = item.findViewById<TextView>(R.id.txtOrderItemDuration)
        if (status == OrderManager.STATUS_DELIVERED) {
            txtDuration.text = getString(
                R.string.label_duration,
                DeliveryManager.formatDuration(order.durationMillis)
            )
            txtDuration.visibility = View.VISIBLE
        }

        // Motif d'annulation : toujours visible pour une commande annulée
        val txtCancelReason = item.findViewById<TextView>(R.id.txtOrderItemCancelReason)
        if (status == OrderManager.STATUS_CANCELLED) {
            txtCancelReason.text =
                getString(R.string.order_label_cancel_reason, order.cancelReason.orEmpty())
            txtCancelReason.visibility = View.VISIBLE
        }

        // Boutons d'action : seulement pour une commande en attente ou en cours.
        // - en attente : Démarrer + Annuler
        // - en cours   : Terminer + Annuler
        if (status == OrderManager.STATUS_ACCEPTED || status == OrderManager.STATUS_IN_PROGRESS) {
            item.findViewById<View>(R.id.layoutOrderActions).visibility = View.VISIBLE
            val orderId = order.id // on garde l'identifiant, pas l'objet

            if (status == OrderManager.STATUS_ACCEPTED) {
                val btnStart = item.findViewById<View>(R.id.btnStartOrder)
                btnStart.visibility = View.VISIBLE
                // Une seule livraison à la fois : désactivé si une autre commande est en cours
                btnStart.isEnabled = OrderManager.getInProgressOrder() == null
                btnStart.setOnClickListener { startOrderDelivery(orderId) }
            } else {
                val btnFinish = item.findViewById<View>(R.id.btnFinishOrder)
                btnFinish.visibility = View.VISIBLE
                btnFinish.setOnClickListener { finishOrderDelivery(orderId) }
            }

            item.findViewById<View>(R.id.btnCancelOrder).setOnClickListener {
                showCancelOrderDialog(orderId)
            }
        }

        return item
    }

    // ---------- NOUVEAU (étape 7A) : démarrer la livraison d'une commande ----------

    private fun startOrderDelivery(orderId: String) {
        val order = OrderManager.getOrderById(orderId) ?: return
        if (order.status != OrderManager.STATUS_ACCEPTED) return
        if (isAnotherDeliveryRunning()) {
            showToast(getString(R.string.toast_delivery_already_running))
            return
        }
        if (!hasLocationPermission()) {
            pendingStartOrderId = orderId
            EasyPermissions.requestPermissions(
                this,
                getString(R.string.permission_location_rationale),
                REQUEST_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            return
        }
        beginOrderDelivery(orderId)
    }

    // Démarre vraiment : le GPS (DeliveryManager) d'abord, puis le statut de la commande
    // (OrderManager). Ainsi la commande ne passe "En cours" que si le GPS tourne vraiment,
    // et elle ne reste jamais bloquée "En cours" sans suivi.
    // En cas d'échec à n'importe quelle étape : le suivi est annulé avec
    // DeliveryManager.cancelDelivery() (rien n'est ajouté à l'historique) et la commande
    // reste "En attente".
    // Appelée quand la permission est accordée (directement ou après la demande).
    private fun beginOrderDelivery(orderId: String) {
        // Revérification : l'état a pu changer pendant la demande de permission
        if (isAnotherDeliveryRunning()) {
            showToast(getString(R.string.toast_delivery_already_running))
            return
        }

        // 1. Le GPS et le chronomètre démarrent, liés à CETTE commande.
        // try/catch : si le démarrage lève une exception (permission retirée à la dernière
        // seconde, par exemple), on nettoie le suivi et la commande n'a pas changé de statut.
        try {
            DeliveryManager.startDelivery(requireContext(), orderId)
        } catch (e: Exception) {
            Log.e(TAG, "Démarrage du GPS impossible pour $orderId", e)
            DeliveryManager.cancelDelivery()
            showToast(getString(R.string.toast_order_start_impossible))
            return
        }

        // 2. Sécurité : le suivi doit bien être actif et lié à cette commande
        if (!DeliveryManager.isTracking || DeliveryManager.currentOrderId != orderId) {
            Log.e(TAG, "Le suivi GPS n'a pas démarré pour $orderId")
            DeliveryManager.cancelDelivery()
            showToast(getString(R.string.toast_order_start_impossible))
            return
        }

        // 3. Le GPS tourne : la commande passe à "En cours", avec la MÊME heure de début
        // que la livraison. Si OrderManager refuse, on arrête le GPS qu'on vient de lancer.
        if (!OrderManager.startOrder(orderId, DeliveryManager.startTime)) {
            DeliveryManager.cancelDelivery()
            showToast(getString(R.string.toast_order_start_impossible))
            return
        }

        showToast(getString(R.string.toast_order_started, orderId))

        // 4. On règle maintenant la permission des notifications,
        // pour qu'elle soit déjà réglée quand la livraison se terminera.
        askNotificationPermission()
        // La liste se met à jour toute seule : OrderManager prévient orderListener.
    }

    // Vrai si un suivi GPS tourne déjà, ou si une commande est déjà "en cours"
    private fun isAnotherDeliveryRunning(): Boolean {
        return DeliveryManager.isTracking || OrderManager.getInProgressOrder() != null
    }

    // ---------- NOUVEAU (étape 7A) : terminer la livraison d'une commande ----------

    private fun finishOrderDelivery(orderId: String) {
        val context = requireContext()
        val order = OrderManager.getOrderById(orderId) ?: return
        if (order.status != OrderManager.STATUS_IN_PROGRESS) return
        if (!DeliveryManager.isTracking || DeliveryManager.currentOrderId != orderId) {
            Toast.makeText(
                context, getString(R.string.toast_order_tracking_inactive), Toast.LENGTH_LONG
            ).show()
            return
        }
        val record = DeliveryManager.finishDelivery(context) ?: return
        if (!OrderManager.completeOrder(orderId, record.distanceMeters, record.durationMillis)) {
            Log.e(TAG, "completeOrder a échoué pour $orderId (la livraison est déjà enregistrée)")
        }
        NotificationHelper.showDeliveryFinished(context, record)
        val finalDistance = DeliveryManager.formatDistance(context, record.distanceMeters)
        val finalDuration = DeliveryManager.formatDuration(record.durationMillis)
        MaterialAlertDialogBuilder(context)
            .setTitle(getString(R.string.finish_order_dialog_title, orderId))
            .setMessage(getString(R.string.finish_dialog_message, finalDistance, finalDuration))
            .setPositiveButton(getString(R.string.btn_ok), null)
            .show()
    }

    // ---------- NOUVEAU (étape 7A) : permissions ----------

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this)
    }

    override fun onPermissionsGranted(requestCode: Int, perms: List<String>) {
        if (!isAdded) return
        // Localisation accordée : on démarre la commande qui attendait.
        // (Pour les notifications, rien de spécial : elles seront envoyées à la fin.)
        if (requestCode == REQUEST_LOCATION && hasLocationPermission()) {
            val orderId = pendingStartOrderId
            pendingStartOrderId = null
            if (orderId != null) beginOrderDelivery(orderId)
        }
    }

    override fun onPermissionsDenied(requestCode: Int, perms: List<String>) {
        if (!isAdded) return
        if (requestCode == REQUEST_NOTIFICATIONS) {
            showToast(getString(R.string.permission_notification_denied), Toast.LENGTH_LONG)
        } else if (requestCode == REQUEST_LOCATION && !hasLocationPermission()) {
            // Refusée : la commande reste "En attente", rien n'a démarré
            pendingStartOrderId = null
            showToast(getString(R.string.permission_location_denied), Toast.LENGTH_LONG)
        }
    }

    // Vrai si au moins une permission de localisation est accordée
    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    // Demande la permission d'envoyer des notifications (Android 13 et plus seulement).
    // Sur Android 12 et moins, hasNotificationPermission() répond "oui" et on ne demande rien.
    private fun askNotificationPermission() {
        if (NotificationHelper.hasNotificationPermission(requireContext())) return

        EasyPermissions.requestPermissions(
            this,
            getString(R.string.permission_notification_rationale),
            REQUEST_NOTIFICATIONS,
            Manifest.permission.POST_NOTIFICATIONS
        )
    }

    private fun showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(requireContext(), message, duration).show()
    }

    // ---------- NOUVEAU (étape 6) : annuler une commande ----------

    // Fenêtre qui demande le motif d'annulation (obligatoire).
    // Même principe que le formulaire du profil : le bouton de confirmation reçoit son action
    // APRÈS show(), ainsi la fenêtre reste ouverte tant que le motif est vide.
    private fun showCancelOrderDialog(orderId: String) {
        // Seule une commande en attente ou en cours peut être annulée
        val order = OrderManager.getOrderById(orderId) ?: return
        if (order.status != OrderManager.STATUS_ACCEPTED &&
            order.status != OrderManager.STATUS_IN_PROGRESS
        ) return

        val dialogView = layoutInflater.inflate(R.layout.dialog_cancel_order, null)
        val layoutReason = dialogView.findViewById<TextInputLayout>(R.id.layoutCancelReason)
        val editReason = dialogView.findViewById<TextInputEditText>(R.id.editCancelReason)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.cancel_order_dialog_title, order.id))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.btn_confirm_cancel_order), null)
            .setNegativeButton(getString(R.string.btn_back), null)
            .show()

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            layoutReason.error = null

            // trim() enlève les espaces autour : un motif fait d'espaces est considéré vide
            val reason = editReason.text.toString().trim()
            if (reason.isEmpty()) {
                layoutReason.error = getString(R.string.cancel_order_error_reason)
                return@setOnClickListener // la fenêtre reste ouverte
            }

            cancelOrder(orderId, reason)
            dialog.dismiss()
        }
    }

    // Annule la commande avec son motif.
    // Si c'est la commande dont le GPS est en train de tourner, on arrête aussi le suivi :
    // OrderManager change le statut, DeliveryManager arrête le GPS (chacun son travail).
    private fun cancelOrder(orderId: String, reason: String) {
        val isTrackedOrder = DeliveryManager.isTracking &&
                DeliveryManager.currentOrderId == orderId

        if (OrderManager.cancelOrder(orderId, reason)) {
            if (isTrackedOrder) {
                DeliveryManager.cancelDelivery()
            }
            Toast.makeText(
                requireContext(),
                getString(R.string.toast_order_cancelled, orderId),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(
                requireContext(),
                getString(R.string.toast_order_cancel_impossible),
                Toast.LENGTH_SHORT
            ).show()
        }
        // La liste se met à jour toute seule : OrderManager prévient orderListener.
    }
}