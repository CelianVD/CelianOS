package com.example.celianos

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.zIndex
import androidx.compose.animation.core.animateFloatAsState
import kotlin.math.roundToInt
import androidx.compose.foundation.border
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.sp
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@Composable
fun SpotlightSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val s = SpotlightState
    val accent = rememberSpotTheme().accentVivid
    var tick by remember { mutableIntStateOf(0) }

    // Recalcule les statuts de permission et réapplique la zone tactile au retour dans l'app
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) { tick++; SpotlightEdge.refresh(ctx) }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(s.edgeEnabled, s.edgeSide, s.edgeHandle, s.edgeDir, s.edgeScope, s.pillEnabled) { SpotlightEdge.refresh(ctx) }

    val contactsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val mediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val calendarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    val overlayOk = remember(tick) { Settings.canDrawOverlays(ctx) }
    val contactsOk = remember(tick) { SpotlightSearch.hasContactsPermission(ctx) }
    val mediaOk = remember(tick) { SpotlightSearch.hasMediaPermission(ctx) }
    val allFilesOk = remember(tick) { SpotlightSearch.hasAllFilesAccess() }
    val calOk = remember(tick) { SpotlightAgenda.hasPermission(ctx) }
    val usageOk = remember(tick) { SpotlightEdge.hasUsageAccess(ctx) }
    val btOk = remember(tick) {
        Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.BLUETOOTH_CONNECT
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    // pages de réglages (toujours sombres) : les cartes suivent le style choisi ; Translucide garde le rendu d'origine
    val styleNow = s.glassStyle.coerceIn(0, STYLE_BRUTAL)
    // résumés courts affichés dans les en-têtes repliables
    val sumTrigger = listOfNotNull(
        if (s.edgeEnabled) "Bord ${if (s.edgeSide == 0) "gauche" else "droit"}" else null,
        if (s.pillEnabled) "barre du bas" else null
    ).let { if (it.isEmpty()) "Désactivé" else it.joinToString(" + ") + " · activé" }
    val nSources = listOf(s.searchApps, s.searchContacts, s.searchFiles, s.searchSettings, s.searchDict,
        s.searchCalc, s.askAi, s.searchWeb, s.searchWiki).count { it }
    val nModules = remember(s.modules) { UserModules.load().size }
    val sumSearch = "$nSources sources" +
            if (s.cmdEnabled) " · $nModules module" + (if (nModules > 1) "s" else "") else " · commandes rapides désactivées"
    val sumAssistant = if (s.brainEnabled)
        "${AiProviders.byId(s.aiMain)?.label ?: AiProviders.all.first().label} · activé" else "Désactivé"
    val nWidgets = s.widgetList.split(',').map { it.trim() }.filter { id -> WIDGET_DEFS.any { it.first == id } }.distinct().size
    val sumWidgets = when (nWidgets) { 0 -> "Aucun widget"; 1 -> "1 widget"; else -> "$nWidgets widgets" }
    val sumAppearance = "${SP_STYLE_NAMES[styleNow]} · ${THEME_MODES[s.themeMode.coerceIn(0, 2)]}"
    val nLog = ActionLog.lines().size
    val sumData = if (nLog == 0) "Journal vide" else "$nLog entrée(s) au journal"

    val settingsTheme = rememberSpotTheme(forceDark = true)
    val reduceMotion = rememberReduceMotion()
    CompositionLocalProvider(
        LocalSettingsTheme provides (if (styleNow != STYLE_TRANSLUCENT) settingsTheme else null),
        LocalReduceMotion provides reduceMotion
    ) {
        SettingsPage(title = "Spotlight", onBack = onBack) {
            LinkCard("Ouvrir Spotlight", "Tester maintenant", true) { launchSpotlight(ctx) }

            SettingsAccordion("trigger", "Ouverture et geste", sumTrigger, accent, defaultOpen = true) {
                SwitchCard(
                    "Balayage depuis le bord",
                    "Glisse vers l'intérieur ou vers le bas le long du bord de l'écran",
                    s.edgeEnabled, { s.edgeEnabled = it }, accent
                )
                LinkCard(
                    "Afficher par-dessus les autres applis",
                    if (overlayOk) "Autorisé" else "Requis pour la zone tactile — toucher pour autoriser",
                    overlayOk
                ) {
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                    )
                }
                ChoiceCard("Côté de la zone", listOf("Gauche", "Droite"), s.edgeSide, { s.edgeSide = it })
                ZonePreview(s.edgeSide, s.edgeHeightPct, s.edgePos, s.edgeDir, s.edgeWidthDp)
                DropdownCard(
                    "Zone active", "Hors de l'accueil, la zone ne bloque plus aucun toucher",
                    if (s.edgeScope == 1) "Écran d'accueil uniquement" else "Partout",
                    listOf("Partout", "Écran d'accueil uniquement")
                ) { s.edgeScope = it }
                if (s.edgeScope == 1 && !usageOk) {
                    LinkCard("Accès à l'utilisation", "Requis pour savoir quand tu es sur l'accueil — toucher pour autoriser", false) {
                        ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                SliderCard(
                    "Largeur de la zone", "${s.edgeWidthDp.toInt()} dp — plus fin = moins gênant",
                    s.edgeWidthDp, { s.edgeWidthDp = it }, 4f..30f,
                    onValueChangeFinished = { SpotlightEdge.refresh(ctx) }
                )
                SliderCard(
                    "Hauteur de la zone", "${s.edgeHeightPct.toInt()} % de l'écran",
                    s.edgeHeightPct, { s.edgeHeightPct = it }, 15f..70f,
                    onValueChangeFinished = { SpotlightEdge.refresh(ctx) }
                )
                SliderCard(
                    "Position verticale",
                    when {
                        s.edgePos < 20f -> "Tout en haut"
                        s.edgePos > 80f -> "Tout en bas"
                        else -> "${s.edgePos.toInt()} % (0 = haut, 100 = bas)"
                    },
                    s.edgePos, { s.edgePos = it }, 0f..100f,
                    onValueChangeFinished = { SpotlightEdge.refresh(ctx) }
                )
                ChoiceCard(
                    "Sens du balayage", listOf("Vers le bas ↓", "Vers le haut ↑", "Les deux ↕"), s.edgeDir, { s.edgeDir = it },
                    subtitle = "Le balayage vers l'intérieur fonctionne toujours"
                )
                SwitchCard(
                    "Double toucher la barre du bas",
                    if (SpotlightEdge.isGestureNav(ctx)) "Utilise le service d'accessibilité de la Dynamic Island (doit être activé)"
                    else "Disponible seulement avec la navigation par gestes",
                    s.pillEnabled, { s.pillEnabled = it }, accent
                )
                SwitchCard("Afficher le petit trait", "Repère visible sur le bord", s.edgeHandle, { s.edgeHandle = it }, accent)
                GlassNote("Astuce : ajoute aussi la tuile « Spotlight » dans tes réglages rapides (menu ✏️ de la barre de notifications). Si la zone gêne une appli, change de côté ou baisse la hauteur.")
            }

            SettingsAccordion("search", "Recherche et modules", sumSearch, accent) {
                SwitchCard("Applications", "Chercher parmi les applis installées", s.searchApps, { s.searchApps = it }, accent)
                SwitchCard("Contacts", "Chercher dans le répertoire", s.searchContacts, { s.searchContacts = it }, accent)
                if (s.searchContacts && !contactsOk) {
                    LinkCard("Autorisation Contacts", "Requise — toucher pour autoriser", false) {
                        contactsLauncher.launch(Manifest.permission.READ_CONTACTS)
                    }
                }
                SwitchCard("Photos, vidéos et documents", "Recherche par titre de fichier", s.searchFiles, { s.searchFiles = it }, accent)
                if (s.searchFiles) {
                    LinkCard("Accès aux photos et vidéos", if (mediaOk) "Autorisé" else "Requis — toucher pour autoriser", mediaOk) {
                        mediaLauncher.launch(SpotlightSearch.mediaPermissions())
                    }
                    LinkCard(
                        "Accès à tous les fichiers (documents)",
                        if (allFilesOk) "Autorisé" else "Requis pour trouver les PDF, Word… — toucher pour autoriser",
                        allFilesOk
                    ) {
                        if (Build.VERSION.SDK_INT >= 30) ctx.startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                        )
                    }
                }
                SwitchCard("Raccourcis de réglages", "« wifi », « bluetooth », « luminosité »… ouvrent la bonne page", s.searchSettings, { s.searchSettings = it }, accent)
                SwitchCard("Dictionnaire (Wiktionnaire)", "Pour un mot : définitions dans une fenêtre", s.searchDict, { s.searchDict = it }, accent)
                SwitchCard("Calculatrice", "Ex : 12*(3+4), 15% , sqrt(81)", s.searchCalc, { s.searchCalc = it }, accent)
                SwitchCard("Demander à une IA", "Choisis 1 ou 2 assistants ci-dessous", s.askAi, { s.askAi = it }, accent)
                if (s.askAi) {
                    val ais = remember(tick) { SpotlightAi.installed(ctx) }
                    val on = SpotlightAi.enabledIds()
                    if (ais.isEmpty()) {
                        GlassNote("Aucun assistant IA détecté sur ce téléphone (Gemini, ChatGPT, Perplexity, Le Chat, Claude, DeepSeek, Lumo, Euria, Copilot, Grok).")
                    } else {
                        val order = on.filter { id -> ais.any { it.id == id } }
                        val first = order.getOrNull(0)
                        val second = order.getOrNull(1)
                        fun save(p: String?, sec: String?) {
                            SpotlightState.aiEnabled = listOfNotNull(p, sec).distinct().joinToString(",")
                        }
                        fun name(id: String?) = ais.firstOrNull { it.id == id }?.name ?: "Aucune"

                        val optsFirst = listOf<AiAssistant?>(null) + ais.filter { it.id != second }
                        DropdownCard(
                            "IA principale", "Première ligne « Demander à … »",
                            name(first), optsFirst.map { it?.name ?: "Aucune" }
                        ) { i -> save(optsFirst[i]?.id, second) }

                        val optsSecond = listOf<AiAssistant?>(null) + ais.filter { it.id != first }
                        DropdownCard(
                            "IA secondaire", "Optionnelle — une deuxième ligne",
                            name(second), optsSecond.map { it?.name ?: "Aucune" }
                        ) { i -> save(first, optsSecond[i]?.id) }
                        val direct = ais.filter { it.id in on && it.urlTemplate == null }
                        if (direct.isNotEmpty()) {
                            GlassNote(direct.joinToString(", ") { it.name } + " : la question est partagée, à valider dans l'appli.")
                        }
                    }
                }
                SwitchCard("Recherche web", "Lance le moteur ou ouvre une adresse", s.searchWeb, { s.searchWeb = it }, accent)
                DropdownCard(
                    "Moteur de recherche", "Ouvert dans ton navigateur par défaut",
                    WEB_ENGINES[s.engine.coerceIn(0, WEB_ENGINES.lastIndex)].first,
                    WEB_ENGINES.map { it.first }
                ) { s.engine = it }
                SwitchCard("Wikipédia", "Propose la page du mot tapé, ouverte dans l'appli Wikipédia", s.searchWiki, { s.searchWiki = it }, accent)
                if (s.searchWiki) {
                    ChoiceCard("Langue de Wikipédia", listOf("Français", "English"), if (s.wikiLang == "en") 1 else 0,
                        { s.wikiLang = if (it == 1) "en" else "fr" })
                }
                AssistantQuickCommandsCard()

                SectionLabel("IA DANS SPOTLIGHT")
                SwitchCard(
                    "Réponse rapide dans Spotlight",
                    "Envoie ta question à l'API du fournisseur et affiche la réponse ici (clé API requise)",
                    s.aiInline, { s.aiInline = it }, accent
                )
                if (s.aiInline) {
                    val provs = SpotlightAiApi.PROVIDERS
                    val cur = provs[s.aiProvider.coerceIn(0, provs.lastIndex)]
                    DropdownCard("Fournisseur", "Tu as besoin d'une clé API de ce fournisseur", cur.name, provs.map { it.name }) {
                        s.aiProvider = it
                    }
                    var key by remember(cur.id) { mutableStateOf(SpotlightAiApi.key(cur.id)) }
                    TextCard(
                        "Clé API ${cur.name}",
                        "Stockée sur ce téléphone. Ta question est envoyée à ${cur.name}.",
                        key, password = true
                    ) { key = it; SpotlightAiApi.setKey(cur.id, it) }
                    var model by remember(cur.id) { mutableStateOf(SpotlightAiApi.model(cur)) }
                    TextCard("Modèle", "Par défaut : ${cur.defaultModel} (change-le si le fournisseur le retire)", model) {
                        model = it; SpotlightAiApi.setModel(cur.id, it)
                    }
                }

                AssistantModulesSettings()
            }

            SettingsAccordion("assistant", "Assistant IA", sumAssistant, accent, beta = true) {
                AssistantBrainSettings()
            }

            SettingsAccordion("widgets", "Widgets", sumWidgets, accent) {
                val ids = s.widgetList.split(',').map { it.trim() }.filter { id -> WIDGET_DEFS.any { it.first == id } }.distinct()
                val remaining = WIDGET_DEFS.filter { it.first !in ids }
                WidgetOrderCard(ids) { s.widgetList = it.joinToString(",") }
                if (remaining.isNotEmpty()) {
                    DropdownCard("Ajouter un widget", "Il se place à la fin, glisse-le ensuite où tu veux", "Choisir…",
                        remaining.map { it.second }) { i -> s.widgetList = (ids + remaining[i].first).joinToString(",") }
                }
                if ("weather" in ids) TextCard("Ville", "Pour le widget météo", s.city) { s.city = it }
                if ("battery" in ids && !btOk) {
                    LinkCard("Autorisation Bluetooth", "Pour lire la batterie des écouteurs — toucher pour autoriser", false) {
                        if (Build.VERSION.SDK_INT >= 31) btLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                }
                if ("habits" in ids) TextCard("Mes habitudes", "5 maximum, séparées par des virgules", s.habits) { s.habits = it }
                if ("agenda" in ids && !calOk) {
                    LinkCard("Autorisation Agenda", "Pour afficher tes prochains événements — toucher pour autoriser", false) {
                        calendarLauncher.launch(Manifest.permission.READ_CALENDAR)
                    }
                }
            }

            SettingsAccordion("appearance", "Apparence et confort", sumAppearance, accent) {
                ChoiceCard(
                    "Thème", THEME_MODES, s.themeMode.coerceIn(0, 2), { s.themeMode = it },
                    subtitle = "Pour Spotlight. Automatique suit le thème du téléphone"
                )
                // Matériel sur Android 12+ : les couleurs viennent déjà du fond d'écran (couleurs dynamiques du système)
                val dynamicMaterial = styleNow == STYLE_MATERIAL && Build.VERSION.SDK_INT >= 31
                SwitchCard(
                    "Couleurs du fond d'écran",
                    if (dynamicMaterial) "Géré par Android : le style Matériel reprend déjà les couleurs de ton fond d'écran"
                    else "L'accent reprend la couleur dominante de ton fond d'écran (Android 8.1+)",
                    s.accentFromWallpaper, { s.accentFromWallpaper = it }, accent, enabled = !dynamicMaterial
                )
                if (!s.accentFromWallpaper && !dynamicMaterial) {
                    AccentPaletteCard(s.accentIndex.coerceIn(0, ACCENT_PRESETS.lastIndex)) { s.accentIndex = it }
                }
                GlassStylePreview(styleNow)
                DropdownCard(
                    "Style",
                    "Translucide (voile uni), Matériel (couleurs dynamiques, surfaces tonales) ou Brutaliste (aplats, bordures épaisses, ombres dures)",
                    SP_STYLE_NAMES[styleNow],
                    SP_STYLE_NAMES
                ) { s.glassStyle = it }
                SliderCard(
                    "Rayon des coins", "${s.cornerPct.toInt()} % (100 % = défaut du style, 0 % = coins droits)",
                    s.cornerPct, { s.cornerPct = it }, 0f..150f
                )
                SliderCard(
                    when (styleNow) {
                        STYLE_MATERIAL -> "Opacité des surfaces"
                        STYLE_BRUTAL -> "Opacité"
                        else -> "Opacité du verre"
                    },
                    when (styleNow) {
                        STYLE_BRUTAL -> "Sans effet : le Brutaliste est toujours opaque"
                        STYLE_MATERIAL -> "${s.glassOpacityPct.toInt()} % (100 % = opaque ; au-delà, sans effet)"
                        else -> "${s.glassOpacityPct.toInt()} % (100 % = d'origine)"
                    },
                    s.glassOpacityPct, { s.glassOpacityPct = it }, 20f..200f,
                    enabled = styleNow != STYLE_BRUTAL
                )
                BlurPreview(s.blurRadius)
                SliderCard(
                    "Intensité du flou",
                    if (styleNow == STYLE_BRUTAL) "Sans effet : pas de flou en Brutaliste"
                    else "${s.blurRadius.toInt()} (Android 12+ avec flou activé)",
                    s.blurRadius, { s.blurRadius = it }, 10f..100f,
                    enabled = styleNow != STYLE_BRUTAL
                )
                SwitchCard("Clavier automatique", "Ouvre le clavier dès l'ouverture", s.autoKeyboard, { s.autoKeyboard = it }, accent)
            }

            SettingsAccordion("data", "Journal et données", sumData, accent) {
                AssistantDataSettings()
            }
        }
    }
}

@Composable
internal fun GlassNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.55f)),
        modifier = Modifier.padding(horizontal = 8.dp))
}

@Composable
internal fun TextCard(title: String, subtitle: String, value: String, password: Boolean = false, onChange: (String) -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp).fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(color = Color.White, fontWeight = FontWeight.Bold))
            Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.6f)))
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = value, onValueChange = onChange, singleLine = true,
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    cursorColor = Color.White, focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                )
            )
        }
    }
}


/** Maquette de téléphone : zone tactile (cyan) selon côté, hauteur, position et sens du balayage, en direct. */
@Composable
internal fun ZonePreview(side: Int, heightPct: Float, posPct: Float, dir: Int, widthDp: Float) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        val shape = RoundedCornerShape(22.dp)
        val total = 200f
        val h = total * heightPct / 100f
        val y = (total - h) * posPct / 100f
        Box(
            Modifier.width(110.dp).height(total.dp).clip(shape)
                .background(Brush.verticalGradient(listOf(Color(0xFF1A1C29), Color(0xFF0D0E12))))
                .border(2.dp, Color.White.copy(alpha = 0.3f), shape)
        ) {
            Box(
                Modifier.align(if (side == 0) Alignment.TopStart else Alignment.TopEnd)
                    .offset(y = y.dp).height(h.dp).width((3 + widthDp / 30f * 9f).dp)
                    .background(NeonCyan.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
            )
            Text(
                when (dir) { 0 -> "↓"; 1 -> "↑"; else -> "↕" },
                color = NeonCyan, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

/**
 * Aperçu en direct : fond coloré flouté + voile du thème + un résultat et la barre de recherche en verre.
 * Reflète le thème, l'accent, le style, l'opacité, le rayon des coins et le flou (Android 12+).
 */
@Composable
internal fun BlurPreview(radius: Float) {
    val th = rememberSpotTheme()
    // le rayon réel est en pixels sur un écran entier, l'aperçu est ~3x plus petit ; pas de flou en Brutaliste
    val previewDp = if (th.style == STYLE_BRUTAL) 0.dp
    else (radius / LocalContext.current.resources.displayMetrics.density * 0.3f).dp
    val frame = RoundedCornerShape(24.dp)
    Box(Modifier.fillMaxWidth().height(200.dp).clip(frame).border(1.dp, Color.White.copy(alpha = 0.2f), frame)) {
        Box(
            Modifier.fillMaxSize().blur(previewDp, BlurredEdgeTreatment.Rectangle)
                .background(Brush.linearGradient(listOf(Color(0xFF3A1C71), Color(0xFFD76D77), Color(0xFFFFAF7B))))
        ) {
            Box(Modifier.size(70.dp).align(Alignment.TopStart).padding(12.dp).background(Color(0xFF00E5FF), CircleShape))
            Box(Modifier.size(90.dp).align(Alignment.CenterEnd).padding(14.dp).background(Color(0xFF30D158), CircleShape))
            Box(Modifier.size(60.dp).align(Alignment.CenterStart).padding(10.dp).background(Color.White, RoundedCornerShape(14.dp)))
            Text("Fond d'écran", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp))
        }
        Box(Modifier.fillMaxSize().background(th.scrim(true)))
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().height(44.dp).spotGlassStyle(th.shape(16), 0.13f, false, th),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(26.dp).clip(th.shape(8)).background(th.accent), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(th.onAccent))
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Application", color = th.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text("Résultat d'exemple", color = th.ink.copy(alpha = 0.55f), fontSize = 10.sp)
                }
            }
            Row(
                Modifier.fillMaxWidth().height(36.dp).spotGlassStyle(th.shape(18), 0.16f, false, th)
                    .padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🔍  Rechercher", color = th.ink.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.weight(1f))
                Box(Modifier.size(24.dp).clip(CircleShape).background(th.accent))
            }
        }
    }
}

/** Palette des couleurs d'accent (masquée quand « Couleurs du fond d'écran » est activé). */
@Composable
internal fun AccentPaletteCard(selected: Int, onSelect: (Int) -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp).fillMaxWidth()) {
            Text("Couleur d'accent", style = MaterialTheme.typography.titleMedium.copy(color = Color.White, fontWeight = FontWeight.Bold))
            Text(
                ACCENT_PRESETS[selected].first,
                style = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.6f))
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ACCENT_PRESETS.forEachIndexed { i, (_, color) ->
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(color)
                            .then(if (i == selected) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
                            .clickable { onSelect(i) }
                    )
                }
            }
        }
    }
}

/** Menu déroulant intégré à la carte (même style verre que le reste de l'app). */
@Composable
internal fun DropdownCard(
    title: String,
    subtitle: String,
    value: String,
    options: List<String>,
    onSelect: (Int) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val ac = LocalSettingsTheme.current?.accentVivid ?: NeonRed
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp).fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(color = Color.White, fontWeight = FontWeight.Bold))
            Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.6f)))
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .clickable { open = !open }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(value, Modifier.weight(1f), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Icon(
                    if (open) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    null, tint = Color.White.copy(alpha = 0.7f)
                )
            }
            AnimatedVisibility(open) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.forEachIndexed { i, label ->
                        val sel = label == value
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(if (sel) ac else Color.White.copy(alpha = 0.05f))
                                .clickable { onSelect(i); open = false }
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Text(
                                label, color = Color.White, fontSize = 13.sp,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}


/** Liste des widgets actifs : glisse la poignée ≡ pour changer l'ordre, ✕ pour retirer. */
@Composable
private fun WidgetOrderCard(ids: List<String>, onChange: (List<String>) -> Unit) {
    val rowH = 52.dp
    val gap = 8.dp
    val step = with(LocalDensity.current) { (rowH + gap).toPx() }
    val curIds by rememberUpdatedState(ids)
    val curChange by rememberUpdatedState(onChange)
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp).fillMaxWidth()) {
            Text("Mes widgets", style = MaterialTheme.typography.titleMedium.copy(color = Color.White, fontWeight = FontWeight.Bold))
            Text("Glisse ≡ pour changer l'ordre, ✕ pour retirer", style = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.6f)))
            Spacer(Modifier.height(12.dp))
            if (ids.isEmpty()) {
                Text("Aucun widget : Spotlight n'affichera que la barre de recherche.",
                    style = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.6f)))
            } else {
                Box(Modifier.fillMaxWidth().height(rowH * ids.size + gap * (ids.size - 1))) {
                    val from = ids.indexOf(dragId)
                    val target = if (from >= 0) (from + (dragOffset / step).roundToInt()).coerceIn(0, ids.lastIndex) else -1
                    ids.forEachIndexed { i, id ->
                        key(id) {
                            val isDrag = id == dragId
                            val shift = when {
                                from < 0 || isDrag -> 0f
                                from < target && i in (from + 1)..target -> -step
                                from > target && i in target until from -> step
                                else -> 0f
                            }
                            val animShift by animateFloatAsState(shift, label = "shift")
                            val y = i * step + (if (isDrag) dragOffset else animShift)
                            Row(
                                Modifier.fillMaxWidth().height(rowH)
                                    .zIndex(if (isDrag) 1f else 0f)
                                    .offset { IntOffset(0, y.roundToInt()) }
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color.White.copy(alpha = if (isDrag) 0.22f else 0.10f)),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier.width(52.dp).fillMaxHeight().pointerInput(id) {
                                        detectDragGestures(
                                            onDragStart = { dragId = id; dragOffset = 0f },
                                            onDragEnd = {
                                                val cur = curIds
                                                val f = cur.indexOf(id)
                                                val t2 = (f + (dragOffset / step).roundToInt()).coerceIn(0, cur.lastIndex)
                                                if (f >= 0 && t2 != f) {
                                                    curChange(cur.toMutableList().also { it.removeAt(f); it.add(t2, id) })
                                                }
                                                dragId = null; dragOffset = 0f
                                            },
                                            onDragCancel = { dragId = null; dragOffset = 0f },
                                            onDrag = { change, d -> change.consume(); dragOffset += d.y }
                                        )
                                    },
                                    contentAlignment = Alignment.Center
                                ) { Icon(Icons.Rounded.DragHandle, "Déplacer", tint = Color.White.copy(alpha = 0.7f)) }
                                Text(
                                    WIDGET_DEFS.firstOrNull { it.first == id }?.second ?: id,
                                    Modifier.weight(1f), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium
                                )
                                Box(
                                    Modifier.width(48.dp).fillMaxHeight().clickable { curChange(curIds - id) },
                                    contentAlignment = Alignment.Center
                                ) { Icon(Icons.Rounded.Close, "Retirer", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(20.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }
}


/** Les trois styles côte à côte sur un fond coloré, avec le thème, l'accent, l'opacité et le rayon des coins en direct. */
@Composable
private fun GlassStylePreview(selected: Int) {
    val shape = RoundedCornerShape(24.dp)
    // voile commun (celui de Translucide) : sinon le Brutaliste, opaque, cacherait le fond coloré
    val veil = rememberSpotTheme(STYLE_TRANSLUCENT).scrim(true)
    Box(
        Modifier.fillMaxWidth().height(120.dp).clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF3A1C71), Color(0xFFD76D77), Color(0xFFFFAF7B))))
    ) {
        Box(Modifier.size(60.dp).align(Alignment.TopStart).padding(10.dp).background(Color(0xFF00E5FF), CircleShape))
        Box(Modifier.size(70.dp).align(Alignment.BottomEnd).padding(10.dp).background(Color(0xFF30D158), CircleShape))
        Box(Modifier.fillMaxSize().background(veil))
        Row(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SP_STYLE_NAMES.forEachIndexed { i, label ->
                val ti = rememberSpotTheme(style = i)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.fillMaxWidth().height(48.dp).spotGlassStyle(ti.shape(22), 0.16f, false, ti),
                        contentAlignment = Alignment.Center
                    ) { Text(label, color = ti.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier.width(24.dp).height(3.dp).clip(RoundedCornerShape(2.dp))
                            .background(if (i == selected) ti.accent else Color.Transparent)
                    )
                }
            }
        }
    }
}