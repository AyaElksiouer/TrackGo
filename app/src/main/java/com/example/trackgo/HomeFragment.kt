package com.example.trackgo

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButtonToggleGroup
import pub.devrel.easypermissions.EasyPermissions

class HomeFragment : Fragment(R.layout.fragment_home),
    EasyPermissions.PermissionCallbacks {
    // Codes qui permettent de savoir quelle permission vient d'être acceptée ou refusée
    companion object {
        private const val REQUEST_LOCATION = 100
        // NOUVEAU (étape 3B) : pour Logcat, et petit espace (en dp) entre le panneau et le logo Google
        private const val TAG = "TrackGo"
        private const val MAP_PADDING_GAP_DP = 8
        // Zoom de la carte à la première position :
        // 16 = très proche ; 14 = plus large, pour voir aussi les commandes (jusqu'à 800 m)
        private const val ZOOM_DRIVER_ONLY = 16f
        private const val ZOOM_WITH_ORDERS = 14f
    }
    // ---------- Variables ----------
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var txtCurrentOrder: TextView? = null
    private var txtDistance: TextView? = null
    private var txtDuration: TextView? = null
    private var txtDeliveryStatus: TextView? = null
    private var txtNoActiveDelivery: TextView? = null
    // ----- Google Maps -----
    private var googleMap: GoogleMap? = null
    private var locationMarker: Marker? = null
    private var pendingPosition: LatLng? = null
    // Markers des commandes actuellement affichés sur la carte
    private val orderMarkers = mutableListOf<Marker>()
    private var currentLocationCancel: CancellationTokenSource? = null
    private var orderDialog: BottomSheetDialog? = null
    private var mapPaddingTop = 0
    private var mapPaddingBottom = 0

    // Cet écran s'inscrit auprès de DeliveryManager pour être prévenu des changements.
    // Le GPS, le chronomètre et la distance sont dans DeliveryManager (inchangé).
    private val deliveryListener = object : DeliveryManager.Listener {
        override fun onDeliveryChanged() {
            refreshUi()
        }
        override fun onLocationChanged(location: Location) {
            updateMarker(LatLng(location.latitude, location.longitude))
        }
    }

    // Cet écran s'inscrit aussi auprès de OrderManager pour redessiner
    // les markers quand les commandes changent (création, acceptation, refus...)
    private val orderListener = object : OrderManager.Listener {
        override fun onOrdersChanged() {
            refreshOrderMarkers()
        }
    }

    // ---------- Cycle de vie du fragment ----------
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        txtCurrentOrder = view.findViewById(R.id.txtCurrentOrder)
        txtDistance = view.findViewById(R.id.txtDistance)
        txtDuration = view.findViewById(R.id.txtDuration)
        txtDeliveryStatus = view.findViewById(R.id.txtDeliveryStatus)
        txtNoActiveDelivery = view.findViewById(R.id.txtNoActiveDelivery)

        setupLanguageSelector(view)
        setupMap(view)
        setupMapPadding(view) // NOUVEAU (étape 3B)
        verifierPermission()

        // 1. On s'inscrit pour être prévenu des changements
        DeliveryManager.addListener(deliveryListener)
        OrderManager.addListener(orderListener)

        // 2. On affiche tout de suite l'état actuel (utile quand on revient sur cet onglet)
        refreshUi()
        // 3. Si une position est déjà connue, on la montrera dès que la carte sera prête
        DeliveryManager.lastLocation?.let {
            updateMarker(LatLng(it.latitude, it.longitude))
        }
    }

    // Quand l'écran est détruit (changement d'onglet ou de langue), on se désinscrit.
    // Le GPS et le chronomètre continuent dans DeliveryManager.
    override fun onDestroyView() {
        super.onDestroyView()
        DeliveryManager.removeListener(deliveryListener)
        OrderManager.removeListener(orderListener)
        currentLocationCancel?.cancel()
        currentLocationCancel = null
        //  on ferme la fenêtre de commande si elle est ouverte
        orderDialog?.dismiss()
        orderDialog = null
        txtCurrentOrder = null
        txtDistance = null
        txtDuration = null
        txtDeliveryStatus = null
        txtNoActiveDelivery = null
        googleMap = null
        locationMarker = null
        pendingPosition = null
        orderMarkers.clear()
        //  l'écran sera recalculé à la prochaine ouverture
        mapPaddingTop = 0
        mapPaddingBottom = 0
    }

    // ---------- Sélecteur de langue (FR | EN | AR) ----------
    private fun setupLanguageSelector(view: View) {
        val toggleLanguage = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleLanguage)

        // 1. On coche le bouton de la langue actuellement affichée (c'est ce qui la met en évidence)
        val currentButtonId = when (LanguageHelper.getCurrentLanguage(requireContext())) {
            LanguageHelper.ENGLISH -> R.id.btnLangEn
            LanguageHelper.ARABIC -> R.id.btnLangAr
            else -> R.id.btnLangFr
        }
        toggleLanguage.check(currentButtonId)
        // 2. Quand l'utilisateur appuie sur un autre bouton, on change la langue.
        // (L'écran est recréé automatiquement et le sélecteur réapparaît dans la nouvelle langue.)
        // Le listener est ajouté APRÈS check() pour ne pas se déclencher à l'ouverture.
        toggleLanguage.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener

            val selectedLanguage = when (checkedId) {
                R.id.btnLangEn -> LanguageHelper.ENGLISH
                R.id.btnLangAr -> LanguageHelper.ARABIC
                else -> LanguageHelper.FRENCH
            }

            if (selectedLanguage != LanguageHelper.getCurrentLanguage(requireContext())) {
                LanguageHelper.setLanguage(selectedLanguage)
            }
        }
    }

    // ---------- Carte Google Maps ----------
    private fun setupMap(view: View) {
        // 1. On récupère le fragment de carte placé dans fragment_home.xml (id "map")
        val mapFragment = childFragmentManager.findFragmentById(R.id.map) as? SupportMapFragment
        // 2. On demande la carte. Quand elle est prête, Android appelle le bloc ci-dessous.
        mapFragment?.getMapAsync { map ->
            googleMap = map
            //  style de la carte (clair ou sombre) et marges
            applyMapStyle(map)
            applyMapPadding()
            // Vue de départ (centre du Maroc) tant qu'on n'a pas la position du livreur
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(31.7917, -7.0926),
                5f))
            //  clic sur un marker.
            // - Marker de commande (il a un "tag" = l'identifiant) : on ouvre notre fenêtre
            //   et on renvoie true = "c'est géré", Google Maps n'affiche pas son texte par défaut.
            // - Marker du livreur (pas de tag) : on renvoie false = comportement habituel.
            map.setOnMarkerClickListener { marker ->
                val orderId = marker.tag as? String
                if (orderId == null) {
                    false
                } else {
                    showOrderDialog(orderId)
                    true
                }
            }

            // Si une position est arrivée avant la carte, on l'affiche maintenant
            pendingPosition?.let { position ->
                updateMarker(position)
                pendingPosition = null
            }
            // Si des commandes existent déjà (ex. : retour sur l'onglet Accueil),
            // on les affiche maintenant que la carte est prête
            refreshOrderMarkers()
        }
        // 3. La carte est dans un ScrollView : sans cette ligne, faire glisser le doigt sur
        // la carte ferait défiler la page au lieu de déplacer la carte.
        setupMapTouchHandling(view)
    }
    // La vue transparente "mapTouchOverlay" est posée par-dessus la carte.
    // Quand le doigt touche la carte, elle dit au ScrollView de ne pas intercepter le geste.
    // Elle renvoie false pour que la carte reçoive quand même le toucher.
    @SuppressLint("ClickableViewAccessibility")
    private fun setupMapTouchHandling(view: View) {
        view.findViewById<View>(R.id.mapTouchOverlay).setOnTouchListener { overlay, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                overlay.parent?.requestDisallowInterceptTouchEvent(true)
            }
            false
        }
    }

    //  style et marges de la carte ----------
    // Applique le style JSON de la carte : sombre si le téléphone est en mode sombre, clair sinon.
    // Les fichiers sont dans res/raw (map_style_light.json et map_style_dark.json).
    // Si le style est invalide ou introuvable, l'application ne plante pas :
    // la carte garde simplement le style standard de Google (et Logcat l'indique).
    private fun applyMapStyle(map: GoogleMap) {
        if (!isAdded) return // l'écran a été fermé avant que la carte soit prête
        val context = requireContext()
        val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val styleResource = if (nightMode == Configuration.UI_MODE_NIGHT_YES) {
            R.raw.map_style_dark
        } else {
            R.raw.map_style_light
        }

        try {
            val applied = map.setMapStyle(MapStyleOptions.loadRawResourceStyle(context, styleResource))
            if (!applied) {
                Log.w(TAG, "Style de carte invalide : le style standard est conservé")
            }
        } catch (e: Resources.NotFoundException) {
            Log.w(TAG, "Fichier de style de carte introuvable : le style standard est conservé", e)
        }
    }

    // La carte occupe tout l'écran, mais le header (haut) et le panneau flottant (bas) sont posés
    // par-dessus. Avec des "marges" (padding), Google Maps place son logo, son texte de copyright
    // et le centre de la caméra dans la zone réellement visible, entre les deux.
    // On mesure le header et le panneau, car leur taille varie : le panneau est court sans
    // livraison et plus haut avec une livraison en cours, et le header dépend de la barre d'état.
    private fun setupMapPadding(view: View) {
        val header = view.findViewById<View>(R.id.homeHeader)
        val panel = view.findViewById<View>(R.id.homePanel)

        // Chaque fois que le header ou le panneau est redessiné, on recalcule les marges
        header.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateMapPadding(view, header, panel)
        }
        panel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateMapPadding(view, header, panel)
        }
    }
    // Calcule les marges (en pixels) et ne les applique que si elles ont changé
    private fun updateMapPadding(root: View, header: View, panel: View) {
        if (!isAdded) return
        // L'écran n'est pas encore mesuré : on attend le prochain calcul
        if (root.height == 0 || panel.height == 0) return

        val gap = (MAP_PADDING_GAP_DP * resources.displayMetrics.density).toInt()
        val top = header.bottom
        // Distance entre le haut du panneau et le bas de l'écran (marge du panneau comprise)
        val bottom = (root.height - panel.top + gap).coerceAtLeast(0)

        if (top == mapPaddingTop && bottom == mapPaddingBottom) return

        mapPaddingTop = top
        mapPaddingBottom = bottom
        applyMapPadding()
    }

    // Applique les marges à la carte. Si la carte n'est pas encore prête (googleMap = null),
    // rien ne se passe ici : elle rappellera cette fonction dès qu'elle sera prête.
    private fun applyMapPadding() {
        googleMap?.setPadding(0, mapPaddingTop, 0, mapPaddingBottom)
    }

    // Centre la caméra sur la position et déplace (ou crée) le marqueur
    private fun updateMarker(position: LatLng) {
        val map = googleMap
        if (map == null) {
            // La carte n'est pas encore prête : on garde la position pour plus tard
            pendingPosition = position
            return
        }

        val marker = locationMarker
        if (marker == null) {
            // Première position : on crée le marqueur et on zoome sur le livreur
            locationMarker = map.addMarker(
                MarkerOptions().position(position).title(getString(R.string.marker_my_position))
            )
            // Si des commandes existent, on prend un zoom plus large pour les voir aussi
            val zoom = if (OrderManager.getAvailableOrders().isNotEmpty()) {
                ZOOM_WITH_ORDERS
            } else {
                ZOOM_DRIVER_ONLY
            }
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(position, zoom))
        } else {
            // Positions suivantes : on déplace le marqueur et on recentre la caméra
            marker.position = position
            map.animateCamera(CameraUpdateFactory.newLatLng(position))
        }
    }

    // Redessine les markers des commandes disponibles.
    // On efface les anciens puis on recrée un marker par commande disponible :
    // une commande refusée (ou acceptée) disparaît donc automatiquement de la carte.
    private fun refreshOrderMarkers() {
        val map = googleMap ?: return // carte pas encore prête : elle rappellera cette fonction

        orderMarkers.forEach { it.remove() }
        orderMarkers.clear()

        for (order in OrderManager.getAvailableOrders()) {
            val marker = map.addMarker(
                MarkerOptions()
                    .position(LatLng(order.latitude, order.longitude))
                    .title(getString(R.string.order_marker_title, order.id))
                    .snippet(order.customerName)
                    // Orange : différent du marker rouge du livreur
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
            )
            if (marker != null) {
                // On range l'identifiant dans le marker : c'est lui qui permet de retrouver
                // la commande quand le livreur clique dessus (voir setOnMarkerClickListener)
                marker.tag = order.id
                orderMarkers.add(marker)
            }
        }
    }

    // fenêtre de détails d'une commande
    // Affiche les détails de la commande dans une fenêtre qui monte du bas,
    // avec les boutons Accepter et Refuser.
    private fun showOrderDialog(orderId: String) {
        // 1. On retrouve la commande. Si elle n'existe plus ou n'est plus disponible, on s'arrête.
        val order = OrderManager.getOrderById(orderId) ?: return
        if (order.status != OrderManager.STATUS_AVAILABLE) return
        // 2. On ferme une éventuelle fenêtre déjà ouverte (jamais deux en même temps)
        orderDialog?.dismiss()
        // 3. On crée la fenêtre à partir de dialog_order_details.xml
        val content = layoutInflater.inflate(R.layout.dialog_order_details, null)
        content.findViewById<TextView>(R.id.txtOrderTitle).text =
            getString(R.string.order_marker_title, order.id)
        content.findViewById<TextView>(R.id.txtOrderCustomer).text =
            getString(R.string.order_label_customer, order.customerName)
        content.findViewById<TextView>(R.id.txtOrderPhone).text =
            getString(R.string.order_label_phone, order.phone)
        content.findViewById<TextView>(R.id.txtOrderAmount).text = getString(
            R.string.order_label_amount,
            getString(R.string.order_amount_value, order.amount)
        )
        content.findViewById<TextView>(R.id.txtOrderDistance).text = getString(
            R.string.label_distance,
            DeliveryManager.formatDistance(requireContext(), order.distanceFromDriverMeters)
        )
        val dialog = BottomSheetDialog(requireContext())
        dialog.setContentView(content)
        // Ouverte en grand dès le début : les deux boutons sont toujours visibles
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        // 4. Bouton Accepter : statut ACCEPTED.
        // Attention : cela ne démarre PAS le suivi GPS (DeliveryManager n'est pas appelé).
        content.findViewById<Button>(R.id.btnAcceptOrder).setOnClickListener {
            if (OrderManager.acceptOrder(order.id)) {
                Toast.makeText(requireContext(), getString(R.string.toast_order_accepted,
                    order.id),
                    Toast.LENGTH_SHORT
                ).show()
            }
            dialog.dismiss()
        }
        // 5. Bouton Refuser : statut REFUSED. Le marker disparaît, les autres restent.
        content.findViewById<Button>(R.id.btnRefuseOrder).setOnClickListener {
            if (OrderManager.refuseOrder(order.id)) {
                Toast.makeText(requireContext(),
                    getString(R.string.toast_order_refused, order.id),
                    Toast.LENGTH_SHORT
                ).show()
            }
            dialog.dismiss()
        }

        // 6. Quand la fenêtre se ferme (bouton ou glissement vers le bas), on oublie la référence
        dialog.setOnDismissListener {
            if (orderDialog === dialog) orderDialog = null
        }

        orderDialog = dialog
        dialog.show()
    }

    // ---------- Affichage ----------

    // affiche la livraison suivie, à partir de DeliveryManager.
    // - Une commande est suivie par le GPS : son numéro, son statut "En cours",
    //   la distance parcourue et la durée, mis à jour en direct (chaque seconde).
    // - Sinon : seulement le message "Aucune livraison en cours" (pas de 0 km ni de 00:00:00).
    // Le démarrage et la fin d'une livraison se font dans "Gestion des commandes".
    // Tous les textes viennent de strings.xml : ils suivent donc la langue choisie.
    private fun refreshUi() {
        val context = requireContext()
        val orderId = DeliveryManager.currentOrderId

        if (DeliveryManager.isTracking && orderId != null) {
            txtCurrentOrder?.text = getString(R.string.home_current_order, orderId)
            txtDeliveryStatus?.text = getString(
                R.string.label_status,
                getString(R.string.order_status_in_progress)
            )
            txtDistance?.text = getString(
                R.string.label_distance_travelled,
                DeliveryManager.formatDistance(context, DeliveryManager.totalDistance)
            )
            txtDuration?.text = getString(
                R.string.label_duration,
                DeliveryManager.formatDuration(DeliveryManager.getDurationMillis())
            )
            showTrackingInfo(true)
        } else {
            showTrackingInfo(false)
        }
    }

    // Montre les informations de suivi (true) ou le message "Aucune livraison en cours" (false)
    private fun showTrackingInfo(active: Boolean) {
        val infoVisibility = if (active) View.VISIBLE else View.GONE
        txtCurrentOrder?.visibility = infoVisibility
        txtDeliveryStatus?.visibility = infoVisibility
        txtDistance?.visibility = infoVisibility
        txtDuration?.visibility = infoVisibility
        txtNoActiveDelivery?.visibility = if (active) View.GONE else View.VISIBLE
    }

    // ---------- Permissions ----------
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        EasyPermissions.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults,
            this
        )
    }
    override fun onPermissionsGranted(requestCode: Int, perms: List<String>) {
        if (requestCode == REQUEST_LOCATION) {
            getLastLocation()
        }
    }
    override fun onPermissionsDenied(requestCode: Int, perms: List<String>) {
        Toast.makeText(
            requireContext(),
            getString(R.string.permission_location_denied), Toast.LENGTH_LONG
        ).show()
    }
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
    private fun verifierPermission() {
        if (hasLocationPermission()) {
            getLastLocation()
        } else {
            EasyPermissions.requestPermissions(
                this,
                getString(R.string.permission_location_rationale),
                REQUEST_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }

    // ---------- Position du livreur et génération des commandes ----------
    // 1) On essaie d'abord la dernière position connue (rapide).
    // 2) Si elle n'existe pas (null) ou si la demande échoue, on demande la position actuelle.
    // Ici, on ne démarre PAS le suivi GPS : c'est seulement une lecture de position.
    @SuppressLint("MissingPermission") // la permission est vérifiée juste au-dessous
    private fun getLastLocation() {
        if (!hasLocationPermission()) return

        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    onPositionFound(location)
                } else {
                    requestCurrentLocation()
                }
            }
            .addOnFailureListener {
                requestCurrentLocation()
            }
    }
    // Demande UNE position actuelle (pas de suivi continu).
    // Utile quand lastLocation est null, par exemple sur un émulateur neuf.
    @SuppressLint("MissingPermission") // la permission est vérifiée juste au-dessous
    private fun requestCurrentLocation() {
        // L'écran a peut-être été fermé entre-temps : on s'arrête sans rien faire
        if (!isAdded || !hasLocationPermission()) return
        currentLocationCancel?.cancel() // jamais deux demandes en même temps
        val cancelSource = CancellationTokenSource()
        currentLocationCancel = cancelSource
        fusedLocationClient
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancelSource.token)
            .addOnSuccessListener { location ->
                if (!isAdded) return@addOnSuccessListener
                if (location != null) {
                    onPositionFound(location)
                } else {
                    // Aucune position disponible (ex. : localisation désactivée)
                    showPositionUnavailable()
                }
            }
            .addOnFailureListener {
                if (isAdded) showPositionUnavailable()
            }
    }
    // Appelée dès qu'on a une position du livreur.
    private fun onPositionFound(location: Location) {
        // 1. On génère les 3 commandes autour de cette position.
        // OrderManager ne les crée qu'une seule fois : si elles existent déjà, il ne fait rien.
        OrderManager.generateOrders(location.latitude, location.longitude)
        // 2. On affiche (ou déplace) le marker du livreur
        updateMarker(LatLng(location.latitude, location.longitude))
    }
    // Message quand aucune position n'a pu être obtenue.
    // Pas de commandes dans ce cas. En revenant sur l'onglet Accueil, on réessaie.
    private fun showPositionUnavailable() {
        Toast.makeText(requireContext(), getString(R.string.toast_position_unavailable),
            Toast.LENGTH_LONG
        ).show()
    }
}