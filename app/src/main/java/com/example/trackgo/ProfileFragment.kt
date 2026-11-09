package com.example.trackgo

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

// Page "Profil" du livreur.
// - Sans profil : un message et un bouton "Renseigner mon profil" (aucun QR Code).
// - Avec profil : carte d'identité (avatar, nom, bouton "Modifier le profil"), tuiles âge et permis,
//   informations personnelles et QR Code.
// La saisie se fait dans une fenêtre qui monte du bas (BottomSheetDialog, dialog_edit_profile.xml).
// Le QR Code est recréé à chaque affichage du profil, donc aussi après chaque modification
// et après chaque changement de langue.
// Tous les textes viennent de strings.xml : ils suivent la langue choisie.
class ProfileFragment : Fragment(R.layout.fragment_profile) {
    companion object {
        private val LICENSE_TYPES = listOf("A1", "A", "B", "C", "D", "EB", "EC", "ED")
        private const val MIN_AGE = 16
        private const val MAX_AGE = 99
        private const val QR_SIZE_DP = 200
    }
    private var layoutNoProfile: LinearLayout? = null
    private var layoutProfile: LinearLayout? = null
    private var txtProfileInitials: TextView? = null
    private var txtProfileFullName: TextView? = null
    private var txtProfileLastName: TextView? = null
    private var txtProfileFirstName: TextView? = null
    private var txtProfileAge: TextView? = null
    private var txtProfileLicense: TextView? = null
    private var txtProfileCin: TextView? = null
    private var cardQrCode: View? = null
    private var imgQrCode: ImageView? = null
    private var editDialog: BottomSheetDialog? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        layoutNoProfile = view.findViewById(R.id.layoutNoProfile)
        layoutProfile = view.findViewById(R.id.layoutProfile)
        txtProfileInitials = view.findViewById(R.id.txtProfileInitials)
        txtProfileFullName = view.findViewById(R.id.txtProfileFullName)
        txtProfileLastName = view.findViewById(R.id.txtProfileLastName)
        txtProfileFirstName = view.findViewById(R.id.txtProfileFirstName)
        txtProfileAge = view.findViewById(R.id.txtProfileAge)
        txtProfileLicense = view.findViewById(R.id.txtProfileLicense)
        txtProfileCin = view.findViewById(R.id.txtProfileCin)
        cardQrCode = view.findViewById(R.id.cardQrCode)
        imgQrCode = view.findViewById(R.id.imgQrCode)

        // Les deux boutons ouvrent le même formulaire
        view.findViewById<Button>(R.id.btnFillProfile).setOnClickListener {
            showEditProfileDialog()
        }
        view.findViewById<Button>(R.id.btnEditProfile).setOnClickListener {
            showEditProfileDialog()
        }

        refreshProfile()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        editDialog?.dismiss()
        editDialog = null
        layoutNoProfile = null
        layoutProfile = null
        txtProfileInitials = null
        txtProfileFullName = null
        txtProfileLastName = null
        txtProfileFirstName = null
        txtProfileAge = null
        txtProfileLicense = null
        txtProfileCin = null
        cardQrCode = null
        imgQrCode = null
    }

    // ---------- Affichage du profil ----------

    // Lit le profil sauvegardé et affiche le bon état de la page
    private fun refreshProfile() {
        val profile = ProfileStorage.getProfile(requireContext())

        if (profile == null) {
            layoutNoProfile?.visibility = View.VISIBLE
            layoutProfile?.visibility = View.GONE
        } else {
            layoutNoProfile?.visibility = View.GONE
            layoutProfile?.visibility = View.VISIBLE
            txtProfileInitials?.text = buildInitials(profile)
            txtProfileFullName?.text = "${profile.firstName} ${profile.lastName}"
            txtProfileLastName?.text = profile.lastName
            txtProfileFirstName?.text = profile.firstName
            txtProfileAge?.text = getString(R.string.profile_age_value, profile.age)
            txtProfileLicense?.text = profile.licenseType
            txtProfileCin?.text = profile.cin

            // ... et son QR Code
            showQrCode(profile)
        }
    }
    private fun buildInitials(profile: DriverProfile): String {
        val first = profile.firstName.trim().firstOrNull()
        val last = profile.lastName.trim().firstOrNull()
        return listOfNotNull(first, last).joinToString("").uppercase()
    }

    // ---------- QR Code ----------
    // Crée le QR Code à partir du profil enregistré et l'affiche
    private fun showQrCode(profile: DriverProfile) {
        val sizePx = (QR_SIZE_DP * resources.displayMetrics.density).toInt()
        val bitmap = QrCodeGenerator.generate(buildQrText(profile), sizePx)
        if (bitmap == null) {
            cardQrCode?.visibility = View.GONE
            return
        }
        imgQrCode?.setImageBitmap(bitmap)
        cardQrCode?.visibility = View.VISIBLE
    }
    private fun buildQrText(profile: DriverProfile): String {
        return getString(
            R.string.qr_payload,
            profile.lastName,
            profile.firstName,
            profile.age,
            profile.licenseType,
            profile.cin
        )
    }

    // ---------- Formulaire de saisie ----------

    private fun showEditProfileDialog() {
        // 1. On crée le formulaire à partir de dialog_edit_profile.xml
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_profile, null)

        val layoutLastName = dialogView.findViewById<TextInputLayout>(R.id.layoutLastName)
        val layoutFirstName = dialogView.findViewById<TextInputLayout>(R.id.layoutFirstName)
        val layoutAge = dialogView.findViewById<TextInputLayout>(R.id.layoutAge)
        val layoutLicense = dialogView.findViewById<TextInputLayout>(R.id.layoutLicense)
        val layoutCin = dialogView.findViewById<TextInputLayout>(R.id.layoutCin)

        val editLastName = dialogView.findViewById<TextInputEditText>(R.id.editLastName)
        val editFirstName = dialogView.findViewById<TextInputEditText>(R.id.editFirstName)
        val editAge = dialogView.findViewById<TextInputEditText>(R.id.editAge)
        val dropdownLicense = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.dropdownLicense)
        val editCin = dialogView.findViewById<TextInputEditText>(R.id.editCin)

        // 2. La liste de choix du type de permis
        dropdownLicense.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, LICENSE_TYPES)
        )

        // 3. Si un profil existe déjà, on remplit le formulaire avec ses valeurs
        val current = ProfileStorage.getProfile(requireContext())
        if (current != null) {
            editLastName.setText(current.lastName)
            editFirstName.setText(current.firstName)
            editAge.setText(current.age.toString())
            dropdownLicense.setText(current.licenseType, false) // false = ne pas filtrer la liste
            editCin.setText(current.cin)
        }

        // 4. Le titre : "Renseigner mon profil" (première fois) ou "Modifier le profil"
        dialogView.findViewById<TextView>(R.id.txtEditProfileTitle).text = getString(
            if (current == null) R.string.profile_fill_button else R.string.profile_edit_button
        )

        // 5. On affiche la fenêtre (elle monte du bas, comme les détails de l'Historique).
        val dialog = BottomSheetDialog(requireContext())
        dialog.setContentView(dialogView)
        // Ouverte en grand dès le début, sans étape "à moitié ouverte" : les champs et les boutons
        // sont tout de suite visibles.
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        // Quand le clavier s'ouvre, la fenêtre se redimensionne pour qu'il ne cache rien
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        // Sur les Android récents, la fenêtre n'est pas toujours redimensionnée par le clavier :
        // dans ce cas, on ajoute nous-mêmes une marge en bas égale à la hauteur du clavier,
        // pour que les boutons restent visibles. Si la fenêtre est déjà redimensionnée,
        // aucune hauteur de clavier n'arrive ici et cette marge reste à 0 (rien ne change).
        ViewCompat.setOnApplyWindowInsetsListener(dialogView) { view, insets ->
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            view.updatePadding(bottom = (keyboard - systemBars).coerceAtLeast(0))
            insets
        }

        // Quand la fenêtre se ferme (bouton Annuler, glissement vers le bas...), on oublie la référence
        dialog.setOnDismissListener {
            if (editDialog === dialog) editDialog = null
        }

        // Bouton Annuler : on ferme simplement la fenêtre
        dialogView.findViewById<View>(R.id.btnCancelProfile).setOnClickListener {
            dialog.dismiss()
        }

        // Le bouton "Enregistrer" fait la vérification ci-dessous : la fenêtre reste ouverte
        // si le formulaire contient des erreurs.
        dialogView.findViewById<View>(R.id.btnSaveProfile).setOnClickListener {
            // a) On efface les anciens messages d'erreur
            layoutLastName.error = null
            layoutFirstName.error = null
            layoutAge.error = null
            layoutLicense.error = null
            layoutCin.error = null

            // b) On lit ce que la personne a écrit (trim() enlève les espaces autour)
            val lastName = editLastName.text.toString().trim()
            val firstName = editFirstName.text.toString().trim()
            val age = editAge.text.toString().trim().toIntOrNull() // null si ce n'est pas un nombre
            val license = dropdownLicense.text.toString().trim()
            val cin = editCin.text.toString().trim()

            // c) On vérifie chaque champ (les messages d'erreur viennent de strings.xml)
            var isValid = true

            if (lastName.isEmpty()) {
                layoutLastName.error = getString(R.string.error_last_name)
                isValid = false
            }
            if (firstName.isEmpty()) {
                layoutFirstName.error = getString(R.string.error_first_name)
                isValid = false
            }
            if (age == null || age < MIN_AGE || age > MAX_AGE) {
                layoutAge.error = getString(R.string.error_age, MIN_AGE, MAX_AGE)
                isValid = false
            }
            if (license.isEmpty()) {
                layoutLicense.error = getString(R.string.error_license)
                isValid = false
            }
            if (cin.isEmpty()) {
                layoutCin.error = getString(R.string.error_cin)
                isValid = false
            }

            // d) S'il y a une erreur, on laisse la fenêtre ouverte
            if (!isValid || age == null) {
                return@setOnClickListener
            }

            // e) Tout est correct : on enregistre, on ferme la fenêtre et on réaffiche le profil.
            // refreshProfile() recrée aussi le QR Code avec les nouvelles informations.
            ProfileStorage.saveProfile(
                requireContext(),
                DriverProfile(lastName, firstName, age, license, cin)
            )
            dialog.dismiss()
            refreshProfile()
            Toast.makeText(
                requireContext(),
                getString(R.string.toast_profile_saved),
                Toast.LENGTH_SHORT
            ).show()
        }

        editDialog = dialog
        dialog.show()
    }
}