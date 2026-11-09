package com.example.trackgo

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // NOUVEAU (étape 4) : recharge les commandes sauvegardées sur le téléphone.
        // Doit être fait AVANT l'affichage des écrans. Sans effet s'il a déjà été fait.
        OrderManager.init(applicationContext)

        setContentView(R.layout.activity_main)
        val bottomNavigation = findViewById<BottomNavigationView>(
            R.id.bottom_navigation
        )
        bottomNavigation.setOnItemSelectedListener { item ->
            val fragment = when (item.itemId) {
                R.id.nav_home -> HomeFragment()
                R.id.nav_history -> HistoryFragment()
                R.id.nav_delivery_management -> DeliveryManagementFragment()
                R.id.nav_profile -> ProfileFragment()
                else -> null
            }
            if (fragment != null) {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, fragment)
                    .commit()
                true
            } else {
                false
            }
        }
        if (savedInstanceState == null) {
            bottomNavigation.selectedItemId = R.id.nav_home
        }
    }
}