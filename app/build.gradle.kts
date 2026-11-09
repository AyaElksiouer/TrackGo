import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// ---------------------------------------------------------------
// Clé API Google Maps : elle est lue dans le fichier local.properties
// (ligne  MAPS_API_KEY=ta_clé ). Ce fichier est ignoré par Git :
// la clé n'est donc écrite ni dans le code, ni dans strings.xml.
// providers.fileContents est compatible avec le "configuration cache".
// Si le fichier ou la ligne n'existe pas, la valeur reste vide.
// ---------------------------------------------------------------
val mapsApiKey: String = providers
    .fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    .asText
    .map { text ->
        val properties = Properties()
        properties.load(text.reader())
        properties.getProperty("MAPS_API_KEY", "").trim()
    }
    .orElse("")
    .get()

if (mapsApiKey.isEmpty()) {
    logger.warn("ATTENTION : MAPS_API_KEY est absente de local.properties. La carte Google Maps restera grise.")
}

android {
    namespace = "com.example.trackgo"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.trackgo"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Donne la clé au manifest : elle remplace ${MAPS_API_KEY} dans AndroidManifest.xml
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // SDK Google Maps pour Android (carte de l'accueil)
    implementation("com.google.android.gms:play-services-maps:19.2.0")
    implementation("pub.devrel:easypermissions:3.0.0")
    // Génération du QR Code du livreur (module "core" seulement : pas d'écran, pas de caméra)
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.google.android.material:material:1.12.0")

}