package com.example.celianos

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt

@Composable
fun DeskDockSettingsScreen(
    notificationAccess: Boolean,
    micGranted: Boolean,
    onRequestMic: () -> Unit,
    notifGranted: Boolean,
    onRequestNotif: () -> Unit,
    overlayGranted: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    var slotDialog by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { PcAgent.ensureRunning(); PcAgent.refreshApps() }
    slotDialog?.let { DockSlotDialog(it) { slotDialog = null } }

    SettingsPage(title = "Glow Dock", onBack = onBack) {
        SectionLabel("LANCEMENT")
        LinkCard(
            title = "Lancer le dock",
            status = "Plein écran, écran toujours allumé. Pose le téléphone sur son support.",
            isOk = true
        ) {
            context.startActivity(Intent(context, DeskDockActivity::class.java))
        }
        LinkCard(
            title = "Accès aux notifications",
            status = if (notificationAccess) "Activé : titre, pochette, commandes et détection du PC disponibles"
            else "Requis pour lire la musique et détecter le PC. Touche pour l'activer.",
            isOk = notificationAccess
        ) {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        SectionLabel("AGENT PC (VOLUME, SON, APPLIS)")
        SwitchCard(
            title = "Utiliser l'agent PC",
            subtitle = "Programme à lancer sur le PC (dossier pc-agent, voir README). Même réseau Wi-Fi.",
            checked = DeskDockState.pcEnabled,
            onCheckedChange = { DeskDockState.pcEnabled = it }
        )
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp).fillMaxWidth()) {
                OutlinedTextField(
                    value = DeskDockState.pcIp, onValueChange = { DeskDockState.pcIp = it.trim() },
                    label = { Text("Adresse IP du PC") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = DeskDockState.pcPort.toString(),
                    onValueChange = { DeskDockState.pcPort = it.filter(Char::isDigit).take(5).toIntOrNull() ?: 8765 },
                    label = { Text("Port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = DeskDockState.pcToken, onValueChange = { DeskDockState.pcToken = it.trim() },
                    label = { Text("Code (token) affiché par l'agent") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        }
        LinkCard(
            title = "État de l'agent PC",
            status = if (PcAgent.connected) "Connecté à ${PcAgent.pcName.ifBlank { "l'ordinateur" }}"
            else "Non connecté" + (PcAgent.lastError?.let { " (${it})" } ?: ""),
            isOk = PcAgent.connected,
            onClick = {}
        )
        SwitchCard(
            title = "Barres lumineuses d'après le son du PC",
            subtitle = "Le son sort du PC : l'agent envoie les basses / médiums / aigus",
            checked = DeskDockState.pcAudio,
            onCheckedChange = { DeskDockState.pcAudio = it }
        )
        SwitchCard(
            title = "Musique du PC",
            subtitle = "Affiche titre, pochette et commandes de ce qui joue sur le PC",
            checked = DeskDockState.pcMedia,
            onCheckedChange = { DeskDockState.pcMedia = it }
        )

        SectionLabel("ICÔNES D'APPLIS PC")
        ChoiceCard(
            title = "Disposition du dock",
            subtitle = "Stream Deck : réduis ou masque la musique pour avoir plus de boutons",
            options = listOf("Lecteur + colonne", "Mini lecteur + grille", "Grille seule"),
            selectedIndex = DeskDockState.dockLayout,
            onSelect = { DeskDockState.dockLayout = it }
        )
        SliderCard(
            title = "Nombre de boutons",
            valueLabel = "${DeskDockState.appsCount}",
            value = DeskDockState.appsCount.toFloat(),
            onValueChange = { DeskDockState.appsCount = it.roundToInt().coerceIn(1, DOCK_APP_SLOTS) },
            valueRange = 1f..DOCK_APP_SLOTS.toFloat()
        )
        ChoiceCard(
            title = "Colonne d'icônes (disposition « Lecteur + colonne »)",
            subtitle = "Un toucher sur une icône ouvre l'appli sur le PC",
            options = listOf("Aucune", "Gauche", "Droite"),
            selectedIndex = DeskDockState.appsSide,
            onSelect = { DeskDockState.appsSide = it }
        )
        LinkCard(
            title = "Actualiser la liste des applis",
            status = "${PcAgent.apps.size} appli(s) disponible(s) sur le PC (fichier apps.json de l'agent)",
            isOk = PcAgent.apps.isNotEmpty()
        ) { PcAgent.refreshApps() }
        for (i in 0 until DeskDockState.appsCount.coerceIn(1, DOCK_APP_SLOTS)) {
            LinkCard(
                title = "Emplacement ${i + 1}",
                status = DockApps.name(i).ifBlank { "Vide : touche pour choisir" },
                isOk = DockApps.id(i).isNotBlank()
            ) { slotDialog = i }
        }

        SectionLabel("LIAISON AVEC L'ORDINATEUR")
        ChoiceCard(
            title = "Lancer quand le téléphone est…",
            subtitle = "Posture détectée avec l'accéléromètre, en plus de la liaison avec le PC",
            options = listOf("Paysage", "À plat", "Pas vertical", "Peu importe"),
            selectedIndex = DeskDockState.postureMode,
            onSelect = { DeskDockState.postureMode = it; PcLink.evaluate(context, allowLaunch = false) }
        )
        SwitchCard(
            title = "Liaison = agent PC connecté",
            subtitle = "Plus fiable que Lien avec Windows : le dock détecte l'agent sur le réseau",
            checked = DeskDockState.linkViaAgent,
            onCheckedChange = { DeskDockState.linkViaAgent = it; PcLink.evaluate(context, allowLaunch = false) }
        )
        SwitchCard(
            title = "Lancer le dock automatiquement",
            subtitle = "Quand le téléphone est lié à l'ordinateur. La Dynamic Island se masque pendant le dock.",
            checked = DeskDockState.autoLaunch,
            onCheckedChange = { DeskDockState.autoLaunch = it }
        )
        SwitchCard(
            title = "Détecter « Lien avec Windows »",
            subtitle = "Surveille le service d'arrière-plan Link to Windows / Phone Link",
            checked = DeskDockState.linkViaWindows,
            onCheckedChange = {
                DeskDockState.linkViaWindows = it
                PcLink.evaluate(context, allowLaunch = false)
            }
        )
        LinkCard(
            title = "État de Lien avec Windows",
            status = if (!notificationAccess) "Active l'accès aux notifications pour détecter"
            else if (PcLink.windowsLinked) "Détecté : service de liaison actif"
            else "Non détecté",
            isOk = PcLink.windowsLinked,
            onClick = {}
        )
        SwitchCard(
            title = "Détecter le câble USB",
            subtitle = "Lance aussi le dock quand le téléphone est branché à un ordinateur (un chargeur peut compter comme branché)",
            checked = DeskDockState.linkViaUsb,
            onCheckedChange = {
                DeskDockState.linkViaUsb = it
                PcLink.evaluate(context, allowLaunch = false)
            }
        )
        SwitchCard(
            title = "Fermer à la déconnexion",
            subtitle = "Quitte le dock lancé automatiquement quand le PC n'est plus détecté",
            checked = DeskDockState.closeOnUnlink,
            onCheckedChange = { DeskDockState.closeOnUnlink = it }
        )
        LinkCard(
            title = "Affichage par-dessus les autres applis",
            status = if (overlayGranted) "Accordé : le dock peut s'ouvrir tout seul"
            else "Recommandé : Android bloque sinon le lancement en arrière-plan. Touche pour l'accorder.",
            isOk = overlayGranted
        ) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            )
        }
        LinkCard(
            title = "Notification d'appoint",
            status = if (notifGranted) "Autorisée : si le lancement est bloqué, touche la notification"
            else "Si Android bloque le lancement, une notification te laissera ouvrir le dock. Touche pour l'autoriser.",
            isOk = notifGranted
        ) {
            if (!notifGranted) onRequestNotif()
        }

        SectionLabel("VISUALISEUR")
        SwitchCard(
            title = "Bordures lumineuses",
            subtitle = "Vagues colorées sur les basses, médiums et aigus",
            checked = DeskDockState.glowEnabled,
            onCheckedChange = { DeskDockState.glowEnabled = it }
        )
        SliderCard(
            title = "Intensité",
            valueLabel = "${(DeskDockState.glowIntensity * 100).roundToInt()} %",
            value = DeskDockState.glowIntensity,
            onValueChange = { DeskDockState.glowIntensity = it },
            valueRange = 0.3f..1.4f
        )
        ChoiceCard(
            title = "Source du signal",
            subtitle = "Auto analyse le vrai son. Simulée anime sans micro.",
            options = listOf("Auto", "Simulée"),
            selectedIndex = if (DeskDockState.useSimulation) 1 else 0,
            onSelect = { DeskDockState.useSimulation = it == 1 }
        )
        LinkCard(
            title = "Autorisation audio",
            status = if (micGranted) "Accordée : analyse du vrai signal possible"
            else "Nécessaire pour réagir au vrai son. Touche pour l'accorder.",
            isOk = micGranted
        ) {
            if (!micGranted) onRequestMic()
        }

        SectionLabel("AFFICHAGE")
        SwitchCard(
            title = "Fond flouté",
            subtitle = "Pochette en arrière-plan (flou renforcé dès Android 12)",
            checked = DeskDockState.blurBackground,
            onCheckedChange = { DeskDockState.blurBackground = it }
        )
        SwitchCard(
            title = "Raccourcis discrets",
            subtitle = "Ils s'effacent après quelques secondes, un toucher les ramène",
            checked = DeskDockState.autoHideShortcuts,
            onCheckedChange = { DeskDockState.autoHideShortcuts = it }
        )
        SwitchCard(
            title = "Anti-marquage AMOLED",
            subtitle = "Décale très lentement l'affichage",
            checked = DeskDockState.antiBurnIn,
            onCheckedChange = { DeskDockState.antiBurnIn = it }
        )
        SwitchCard(
            title = "Afficher sur l'écran verrouillé",
            subtitle = "Le dock reste visible sans déverrouiller (au prochain lancement)",
            checked = DeskDockState.showWhenLocked,
            onCheckedChange = { DeskDockState.showWhenLocked = it }
        )
    }
}