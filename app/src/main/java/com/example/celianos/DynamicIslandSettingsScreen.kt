package com.example.celianos

import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/** Page de réglages de la Dynamic Island. */
@Composable
fun DynamicIslandSettingsScreen(
    notificationAccess: Boolean,
    accessibilityEnabled: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var offsetY by remember { mutableStateOf(DynamicIslandService.verticalOffset.toFloat()) }
    var expandedOffset by remember { mutableStateOf(DynamicIslandService.expandedOffset.toFloat()) }

    SettingsPage(title = "Dynamic Island", onBack = onBack) {

        SectionLabel("ACTIVATION")

        SwitchCard(
            title = "Dynamic Island & Audio",
            subtitle = "Affiche la pilule interactive en haut",
            checked = DynamicIslandService.enabled,
            onCheckedChange = { DynamicIslandService.sync(it, DynamicIslandService.musicEnabled) },
            trackColor = NeonRed
        )

        SwitchCard(
            title = "Lecture musicale",
            subtitle = "La pilule n'apparaît que si activé",
            checked = DynamicIslandService.musicEnabled,
            onCheckedChange = { DynamicIslandService.sync(DynamicIslandService.enabled, it) },
            trackColor = NeonCyan
        )

        SectionLabel("POSITION & TAILLE")

        SliderCard(
            title = "Hauteur de la pilule (Poinçon)",
            valueLabel = "Position actuelle : ${offsetY.toInt()} px",
            value = offsetY,
            onValueChange = {
                offsetY = it
                DynamicIslandService.updateVerticalOffset(it.toInt())
                DynamicIslandService.showPreview(PreviewMode.COMPACT)
            },
            onValueChangeFinished = { DynamicIslandService.endPreview() },
            valueRange = 0f..150f
        )

        SliderCard(
            title = "Grande version (sous la caméra)",
            valueLabel = "Décalage du contenu : ${expandedOffset.toInt()} dp",
            value = expandedOffset,
            onValueChange = {
                expandedOffset = it
                DynamicIslandService.updateExpandedOffset(it.toInt())
                DynamicIslandService.showPreview(PreviewMode.EXPANDED)
            },
            onValueChangeFinished = { DynamicIslandService.endPreview() },
            valueRange = 0f..80f
        )

        SectionLabel("VISUEL (GRANDE VERSION)")

        ChoiceCard(
            title = "Visuel à gauche du titre",
            subtitle = "Pochette en matrice de LED, cercle de points animé, ou simple icône",
            options = listOf("Pixels", "Cercle", "Icône"),
            selectedIndex = when (DynamicIslandService.artStyle) {
                ArtStyle.PIXEL_GRID -> 0
                ArtStyle.DOT_RING -> 1
                ArtStyle.ICON -> 2
            },
            onSelect = { index ->
                DynamicIslandService.updateArtStyle(
                    when (index) {
                        0 -> ArtStyle.PIXEL_GRID
                        1 -> ArtStyle.DOT_RING
                        else -> ArtStyle.ICON
                    }
                )
                // Montre le résultat tout de suite
                DynamicIslandService.showPreview(PreviewMode.EXPANDED)
                DynamicIslandService.endPreview()
            }
        )

        SectionLabel("PROFIL SONORE")

        SwitchCard(
            title = "Alertes de profil sonore",
            subtitle = "Animation lors du passage en silencieux, vibreur ou Ne pas déranger",
            checked = DynamicIslandService.soundAlertsEnabled,
            onCheckedChange = { DynamicIslandService.setSoundAlerts(it) },
            trackColor = NeonRed
        )

        ChoiceCard(
            title = "Tester l'animation",
            options = listOf("Silence", "Vibreur", "Sonnerie", "NPD"),
            selectedIndex = -1,
            onSelect = { index ->
                DynamicIslandService.postSoundEvent(
                    when (index) {
                        0 -> SoundKind.SILENT
                        1 -> SoundKind.VIBRATE
                        2 -> SoundKind.NORMAL
                        else -> SoundKind.DND_ON
                    }
                )
            }
        )

        SectionLabel("PERMISSIONS")

        LinkCard(
            title = "Titre et contrôles de la musique",
            status = if (notificationAccess) "✓ Accès accordé" else "Appuie ici pour autoriser l'accès aux notifications",
            isOk = notificationAccess,
            onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        )

        LinkCard(
            title = "Affichage devant la barre d'état",
            status = if (accessibilityEnabled) "✓ Service activé" else "Appuie ici pour activer le service d'accessibilité CélianOS",
            isOk = accessibilityEnabled,
            onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        )
    }
}