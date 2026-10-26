plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" // 👈 Version du plugin Compose (adaptée à Kotlin 2.0+)
}

android {
    namespace = "com.example.celianos"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.celianos"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
    }
    // ⚠️ Plus besoin de bloc "composeOptions" avec Kotlin 2.0+ !
}

dependencies {
    // Versions stables compatibles API 36
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")

    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Dépendances Jetpack Compose (BOM)
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.0") // (ou ta version actuelle de lifecycle)
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")

}

// Règle de résolution globale pour forcer Gradle à ignorer toute version supérieure à 1.15.0
configurations.all {
    resolutionStrategy {
        force("androidx.core:core:1.15.0")
        force("androidx.core:core-ktx:1.15.0")
    }
}