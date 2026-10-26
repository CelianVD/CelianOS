package com.example.celianos

import androidx.compose.runtime.Composable

// ============================================================
//  Réglages généraux (roue dentée de l'accueil)
// ============================================================

/**
 * Les deux entrées rouvrent l'introduction de l'appli (OnboardingScreen.kt) ou l'assistant de configuration
 * de Spotlight (SpotlightOnboardingScreen.kt).
 * Elles n'effacent et ne réinitialisent rien.
 */
@Composable
fun GeneralSettingsScreen(
    onBack: () -> Unit,
    onReplayIntro: () -> Unit,
    onReplaySetup: () -> Unit
) {
    SettingsPage(title = "Réglages généraux", onBack = onBack) {
        SectionLabel("AIDE")
        LinkCard(
            "Revoir l'introduction",
            "Ce que fait CélianOS, ses modules et la confidentialité",
            false, onReplayIntro
        )
        LinkCard(
            "Relancer l'assistant de configuration",
            "Spotlight : geste d'ouverture, recherche, assistant IA, widgets et apparence, avec tes réglages actuels",
            false, onReplaySetup
        )
        GlassNote(
            "Ces écrans n'effacent ni ne réinitialisent aucun réglage ni aucune donnée. " +
                    "L'assistant affiche tes valeurs actuelles ; seuls les choix que tu modifies sont enregistrés."
        )
    }
}