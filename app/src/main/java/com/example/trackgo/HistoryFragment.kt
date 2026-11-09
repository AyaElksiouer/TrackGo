package com.example.trackgo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Page "Historique" : retrouver une ancienne livraison.
// - une barre de recherche
// - des filtres par période (Toutes, Aujourd'hui, Cette semaine, Ce mois)
// - une liste de cartes : un clic sur une carte ouvre une fenêtre (du bas) avec les détails
// Les données viennent de DeliveryStorage (livraisons réellement enregistrées).
// Tous les textes viennent de strings.xml : ils suivent la langue choisie.
class HistoryFragment : Fragment(R.layout.fragment_history) {

    // Références vers les éléments de l'écran (remises à null quand l'écran est détruit)
    private var editSearch: TextInputEditText? = null
    private var chipGroupPeriod: ChipGroup? = null
    private var txtResultCount: TextView? = null
    private var txtHistoryMessage: TextView? = null
    private var historyContainer: LinearLayout? = null

    // Fenêtre de détails ouverte (jamais deux en même temps ; fermée quand l'écran disparaît)
    private var detailsDialog: BottomSheetDialog? = null

    // Toutes les livraisons sauvegardées (la plus récente en premier)
    private var allDeliveries: List<DeliveryRecord> = emptyList()

    // Locale.US : toujours les chiffres 0 1 2 3..., dans les trois langues
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        editSearch = view.findViewById(R.id.editSearch)
        chipGroupPeriod = view.findViewById(R.id.chipGroupPeriod)
        txtResultCount = view.findViewById(R.id.txtResultCount)
        txtHistoryMessage = view.findViewById(R.id.txtHistoryMessage)
        historyContainer = view.findViewById(R.id.historyContainer)

        // 1. On lit les livraisons sauvegardées
        allDeliveries = DeliveryStorage.getAllDeliveries(requireContext())

        // 2. À chaque lettre tapée, on refait la recherche
        editSearch?.doAfterTextChanged {
            applyFilters()
        }

        // 3. À chaque changement de période, on refait la recherche
        chipGroupPeriod?.setOnCheckedStateChangeListener { _, _ ->
            applyFilters()
        }

        // 4. Premier affichage
        applyFilters()
    }

    // Après une rotation de l'écran, Android remet le texte et le filtre choisis :
    // on réapplique alors les filtres pour que la liste corresponde.
    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        applyFilters()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        detailsDialog?.dismiss()
        detailsDialog = null
        editSearch = null
        chipGroupPeriod = null
        txtResultCount = null
        txtHistoryMessage = null
        historyContainer = null
    }

    // ---------- Recherche et filtres ----------

    private fun applyFilters() {
        val container = historyContainer ?: return
        container.removeAllViews()

        // Cas 1 : aucune livraison n'a jamais été enregistrée
        if (allDeliveries.isEmpty()) {
            txtResultCount?.visibility = View.GONE
            showMessage(getString(R.string.history_no_deliveries))
            return
        }

        // Texte tapé dans la barre de recherche (en minuscules, sans espaces autour)
        val query = editSearch?.text?.toString()?.trim()?.lowercase(Locale.getDefault()) ?: ""

        // Début de la période choisie (0 = pas de limite)
        val periodStart = getPeriodStart()

        // On garde les livraisons qui respectent la période ET la recherche
        val results = allDeliveries.filter { delivery ->
            delivery.dateMillis >= periodStart &&
                    (query.isEmpty() || buildSearchText(delivery).contains(query))
        }

        // Cas 2 : des livraisons existent, mais aucune ne correspond
        if (results.isEmpty()) {
            txtResultCount?.visibility = View.GONE
            showMessage(getString(R.string.history_no_results))
            return
        }

        // Cas 3 : on affiche les résultats
        txtHistoryMessage?.visibility = View.GONE
        txtResultCount?.visibility = View.VISIBLE
        // getQuantityString choisit le bon texte selon le nombre ("1 livraison" / "3 livraisons"...),
        // en respectant les règles de chaque langue (l'arabe a 6 formes).
        txtResultCount?.text = resources.getQuantityString(
            R.plurals.history_results,
            results.size,
            results.size
        )

        val inflater = LayoutInflater.from(requireContext())
        for (delivery in results) {
            val item = inflater.inflate(R.layout.item_history, container, false)

            // Date et heure : "02/11/2026 à 22:27"
            val dateTime = getString(
                R.string.history_row_title,
                formatDate(delivery),
                formatTime(delivery)
            )
            val txtTitle = item.findViewById<TextView>(R.id.txtHistoryTitle)
            val txtDate = item.findViewById<TextView>(R.id.txtHistoryDate)
            val orderId = delivery.orderId
            if (!orderId.isNullOrBlank()) {
                // Livraison liée à une commande : "Commande CMD-1002", la date en dessous
                txtTitle.text = getString(R.string.order_marker_title, orderId)
                txtDate.text = dateTime
                txtDate.visibility = View.VISIBLE
            } else {
                // Ancienne livraison sans commande : la date et l'heure servent de titre
                txtTitle.text = dateTime
                txtDate.visibility = View.GONE
            }

            // Distance et durée, chacune dans son bloc
            item.findViewById<TextView>(R.id.txtHistoryDistance).text = formatDistance(delivery)
            item.findViewById<TextView>(R.id.txtHistoryDuration).text = formatDuration(delivery)

            // Un clic sur la ligne ouvre les détails
            item.findViewById<View>(R.id.rowHistory).setOnClickListener {
                showDetails(delivery)
            }

            container.addView(item)
        }
    }

    private fun showMessage(message: String) {
        txtHistoryMessage?.text = message
        txtHistoryMessage?.visibility = View.VISIBLE
    }

    // Calcule le moment où commence la période choisie (en millisecondes)
    private fun getPeriodStart(): Long {
        // On part de "aujourd'hui à 00:00:00"
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        when (chipGroupPeriod?.checkedChipId) {
            R.id.chipToday -> {
                // Aujourd'hui : on garde le calendrier tel quel
            }
            R.id.chipWeek -> {
                // Cette semaine : on recule jusqu'au premier jour de la semaine
                // (lundi ou dimanche selon la langue du téléphone)
                val daysSinceStart =
                    (calendar.get(Calendar.DAY_OF_WEEK) - calendar.firstDayOfWeek + 7) % 7
                calendar.add(Calendar.DAY_OF_YEAR, -daysSinceStart)
            }
            R.id.chipMonth -> {
                // Ce mois : on recule jusqu'au 1er du mois
                calendar.set(Calendar.DAY_OF_MONTH, 1)
            }
            else -> return 0L // "Toutes" : aucune limite
        }

        return calendar.timeInMillis
    }

    // Tout le texte d'une livraison dans lequel la recherche peut trouver quelque chose.
    // C'est le même texte que celui affiché (dans la langue choisie), donc on peut chercher
    // "02/11", "22:27", "0,00", le nom du statut, le numéro de commande ("CMD-1002"), etc.
    private fun buildSearchText(delivery: DeliveryRecord): String {
        return (formatDate(delivery) + " " + formatTime(delivery) + " " +
                formatDistance(delivery) + " " + formatDuration(delivery) + " " +
                formatStatus(delivery) + " " + (delivery.orderId ?: ""))
            .lowercase(Locale.getDefault())
    }

    // ---------- Détails d'une livraison ----------

    // Fenêtre qui monte du bas (même principe que la fenêtre d'une commande sur l'Accueil).
    // Mêmes informations qu'avant : date, heure de début, distance, durée, statut
    // (+ le numéro de commande quand il existe).
    private fun showDetails(delivery: DeliveryRecord) {
        // On ferme une éventuelle fenêtre déjà ouverte (jamais deux en même temps)
        detailsDialog?.dismiss()

        val content = layoutInflater.inflate(R.layout.dialog_history_details, null)

        content.findViewById<TextView>(R.id.txtHistoryDetailsTitle).text =
            getString(R.string.history_details_title)

        val txtOrder = content.findViewById<TextView>(R.id.txtHistoryDetailsOrder)
        val orderId = delivery.orderId
        if (!orderId.isNullOrBlank()) {
            txtOrder.text = getString(R.string.order_marker_title, orderId)
            txtOrder.visibility = View.VISIBLE
        }

        content.findViewById<TextView>(R.id.txtHistoryDetailsDate).text =
            getString(R.string.label_date, formatDate(delivery))
        content.findViewById<TextView>(R.id.txtHistoryDetailsStart).text =
            getString(R.string.label_start_time, formatTime(delivery))
        content.findViewById<TextView>(R.id.txtHistoryDetailsDistance).text =
            getString(R.string.label_distance, formatDistance(delivery))
        content.findViewById<TextView>(R.id.txtHistoryDetailsDuration).text =
            getString(R.string.label_duration, formatDuration(delivery))
        content.findViewById<TextView>(R.id.txtHistoryDetailsStatus).text =
            getString(R.string.label_status, formatStatus(delivery))

        val dialog = BottomSheetDialog(requireContext())
        dialog.setContentView(content)
        // Ouverte en grand dès le début : le bouton Fermer est toujours visible
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED

        content.findViewById<View>(R.id.btnHistoryDetailsClose).setOnClickListener {
            dialog.dismiss()
        }

        // Quand la fenêtre se ferme (bouton ou glissement vers le bas), on oublie la référence
        dialog.setOnDismissListener {
            if (detailsDialog === dialog) detailsDialog = null
        }

        detailsDialog = dialog
        dialog.show()
    }

    // ---------- Petites fonctions d'affichage ----------

    private fun formatDate(delivery: DeliveryRecord): String =
        dateFormat.format(Date(delivery.dateMillis))

    private fun formatTime(delivery: DeliveryRecord): String =
        timeFormat.format(Date(delivery.dateMillis))

    private fun formatDistance(delivery: DeliveryRecord): String =
        DeliveryManager.formatDistance(requireContext(), delivery.distanceMeters)

    private fun formatDuration(delivery: DeliveryRecord): String =
        DeliveryManager.formatDuration(delivery.durationMillis)

    // Le statut sauvegardé, écrit dans la langue choisie
    private fun formatStatus(delivery: DeliveryRecord): String =
        DeliveryManager.getStatusLabel(requireContext(), delivery.status)
}