package com.example.celianos

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Toutes les pages de l'app. Ajoute une valeur ici pour créer une nouvelle page. */
enum class Screen {
    HOME,
    DYNAMIC_ISLAND,
    SPOTLIGHT,
    DESK_DOCK,
    SETTINGS
}

class MainActivity : ComponentActivity() {

    // Incrémenté à chaque retour dans l'app (après avoir accordé une permission)
    private var resumeCount by mutableIntStateOf(0)

    // Autorisation audio pour le visualiseur du Glow Dock
    private val micLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { resumeCount++ }

    // Notification d'appoint si Android bloque le lancement automatique du dock
    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { resumeCount++ }

    override fun onResume() {
        super.onResume()
        resumeCount++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            CelianOSTheme {
                var currentScreen by rememberSaveable { mutableStateOf(Screen.HOME) }

                // Introduction de l'appli (première ouverture, rouvrable depuis les réglages généraux)
                // puis, à la suite ou plus tard, configuration de Spotlight : deux onboardings distincts
                var onboarding by rememberSaveable {
                    mutableStateOf(if (AppState.onboardingSeen) ONBOARDING_OFF else ONBOARDING_FULL)
                }

                val notificationAccess = remember(resumeCount) {
                    NotificationManagerCompat.getEnabledListenerPackages(this@MainActivity)
                        .contains(packageName)
                }

                // Service d'accessibilité activé ? (nécessaire pour passer devant la barre d'état)
                val accessibilityEnabled = remember(resumeCount) {
                    Settings.Secure.getString(
                        contentResolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                    )?.contains("$packageName/") == true
                }

                val micGranted = remember(resumeCount) {
                    ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                }

                val notifGranted = remember(resumeCount) {
                    Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                }

                // « Afficher par-dessus les autres applis » : aide à lancer le dock depuis l'arrière-plan
                val overlayGranted = remember(resumeCount) { Settings.canDrawOverlays(this@MainActivity) }

                // Le bouton retour du système ramène à l'accueil depuis une sous-page
                BackHandler(enabled = currentScreen != Screen.HOME && onboarding == ONBOARDING_OFF) {
                    currentScreen = Screen.HOME
                }

                val goHome = { currentScreen = Screen.HOME }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF1A1C29),
                                    DarkBackground
                                ),
                                radius = 1200f
                            )
                        )
                ) {
                    if (onboarding == ONBOARDING_FULL) {
                        OnboardingScreen(
                            onClose = { AppState.onboardingSeen = true; onboarding = ONBOARDING_OFF },
                            // « Configurer Spotlight » : enchaîne sur l'onboarding de Spotlight
                            onStartSpotlight = { AppState.onboardingSeen = true; onboarding = ONBOARDING_SPOTLIGHT }
                        )
                    } else if (onboarding == ONBOARDING_SPOTLIGHT) {
                        SpotlightOnboardingScreen(
                            onClose = { AppState.onboardingSeen = true; onboarding = ONBOARDING_OFF }
                        )
                    } else when (currentScreen) {
                        Screen.HOME -> HomeScreen(onNavigate = { currentScreen = it })

                        Screen.DYNAMIC_ISLAND -> DynamicIslandSettingsScreen(
                            notificationAccess = notificationAccess,
                            accessibilityEnabled = accessibilityEnabled,
                            onBack = goHome
                        )

                        Screen.SPOTLIGHT -> SpotlightSettingsScreen(onBack = goHome)

                        Screen.DESK_DOCK -> DeskDockSettingsScreen(
                            notificationAccess = notificationAccess,
                            micGranted = micGranted,
                            onRequestMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                            notifGranted = notifGranted,
                            onRequestNotif = {
                                if (Build.VERSION.SDK_INT >= 33) {
                                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                            overlayGranted = overlayGranted,
                            onBack = goHome
                        )

                        Screen.SETTINGS -> GeneralSettingsScreen(
                            onBack = goHome,
                            onReplayIntro = { onboarding = ONBOARDING_FULL },
                            onReplaySetup = { onboarding = ONBOARDING_SPOTLIGHT }
                        )
                    }
                }
            }
        }
    }
}