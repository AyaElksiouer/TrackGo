package com.example.trackgo

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

// Tout ce qui concerne le changement de langue est regroupé ici.
//
// On utilise la fonction officielle d'AppCompat : AppCompatDelegate.setApplicationLocales().
// - Elle change la langue de TOUTE l'application et recrée l'écran automatiquement.
// - Le choix est mémorisé par Android / AppCompat : la langue est restaurée au prochain lancement
//   (voir le <service> "AppLocalesMetadataHolderService" dans AndroidManifest.xml).
// - Elle gère aussi le sens de lecture : l'arabe passe l'application en droite-à-gauche (RTL).
object LanguageHelper {

    const val FRENCH = "fr"
    const val ENGLISH = "en"
    const val ARABIC = "ar"

    private val SUPPORTED = listOf(FRENCH, ENGLISH, ARABIC)

    // Retourne la langue réellement affichée : "fr", "en" ou "ar".
    // Si la langue du téléphone n'est pas gérée, l'application s'affiche en français (par défaut).
    fun getCurrentLanguage(context: Context): String {
        val language = context.resources.configuration.locales[0].language
        return if (language in SUPPORTED) language else FRENCH
    }

    // Change la langue de l'application. L'écran est recréé automatiquement.
    fun setLanguage(languageCode: String) {
        // Pour l'arabe, on utilise la variante du Maroc ("ar-MA") : les chiffres restent 0 1 2 3...
        val tag = if (languageCode == ARABIC) "ar-MA" else languageCode
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }
}