package com.example.celianos

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay

// ============================================================
//  Assistant de configuration de SPOTLIGHT (6 étapes, onboarding distinct de celui de l'appli)
//
//  1. Ouverture de Spotlight  : autorisation « par-dessus les autres applis » (indispensable),
//                               côté de la zone, « Essayer » → écran d'essai plein format
//                               (la vraie zone + un point qui montre le geste), puis Spotlight
//                               s'ouvre en mode démo et se referme tout seul.
//  2. Que veux-tu chercher ?  : une carte par module, avec son autorisation si besoin
//  3. Assistant IA            : facultatif, désactivé par défaut, « Plus tard » toujours visible
//  4. Widgets                 : lesquels s'affichent, ville, Bluetooth
//  5. Apparence               : style, thème, accent, aperçu en direct, réglages fins repliés
//  6. Résumé                  : ce qui est activé / désactivé / modifiable, « Ouvrir Spotlight »
//
//  Lancé par « Configurer Spotlight » (fin de l'introduction) ou « Relancer l'assistant de configuration ».
//  Rien n'est jamais réinitialisé : chaque écran affiche les valeurs ACTUELLES et n'enregistre que
//  ce que tu modifies. « Passer » ne change rien de plus.
//  Aucune autorisation n'est demandée toute seule : toujours après un toucher sur un bouton.
// ============================================================

const val ONBOARDING_SPOTLIGHT = 2

/**
 * Passerelle avec SpotlightActivity pendant l'essai du geste.
 * `active` : l'écran d'essai est affiché → Spotlight s'ouvre en mode démo (bulle d'aide, rien n'est lancé,
 *            fermeture automatique après une frappe).
 * `done`   : Spotlight vient de se refermer en mode démo → l'assistant passe à l'étape suivante.
 */
object SpotlightOnbDemo {
    var active by mutableStateOf(false)
    var done by mutableStateOf(false)
}

/** Styles de verre proposés. Même ordre que SpotlightState.glassStyle (0, 1…) : ajoute un nom ici quand un style est ajouté. */
private val SP_GLASS_STYLES = listOf("Translucide", "Matériel", "Brutaliste")

private enum class SpPage { OPEN, TRY, SEARCH, AI, WIDGETS, LOOK, SUMMARY }

private const val SP_STEPS = 6

private fun SpPage.step(): Int = when (this) {
    SpPage.OPEN, SpPage.TRY -> 1
    SpPage.SEARCH -> 2
    SpPage.AI -> 3
    SpPage.WIDGETS -> 4
    SpPage.LOOK -> 5
    SpPage.SUMMARY -> 6
}

@Composable
fun SpotlightOnboardingScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val pages = remember { SpPage.values().toList() }
    var index by rememberSaveable { mutableStateOf(0) }
    val cur = pages[index.coerceIn(0, pages.lastIndex)]

    fun go(p: SpPage) { index = p.ordinal }
    // l'essai du geste est facultatif : « Suivant » et « Précédent » l'enjambent
    fun next() = go(
        when (cur) {
            SpPage.OPEN, SpPage.TRY -> SpPage.SEARCH
            else -> pages[(cur.ordinal + 1).coerceAtMost(pages.lastIndex)]
        }
    )
    fun prev() {
        when (cur) {
            SpPage.OPEN -> onClose()
            SpPage.TRY, SpPage.SEARCH -> go(SpPage.OPEN)
            else -> go(pages[cur.ordinal - 1])
        }
    }
    BackHandler { prev() }

    // Statuts des autorisations : recalculés à chaque retour dans l'app
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) { tick++; SpotlightEdge.refresh(ctx) }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val contactsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val mediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val calendarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val bluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val askContacts = { contactsLauncher.launch(Manifest.permission.READ_CONTACTS) }
    val askMedia = { mediaLauncher.launch(SpotlightSearch.mediaPermissions()) }
    val askCalendar = { calendarLauncher.launch(Manifest.permission.READ_CALENDAR) }
    val askBluetooth = { if (Build.VERSION.SDK_INT >= 31) bluetoothLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) }

    // Spotlight vient de se refermer en mode démo : on passe à la suite
    val demoDone = SpotlightOnbDemo.done
    LaunchedEffect(demoDone) {
        if (demoDone && cur == SpPage.TRY) { SpotlightOnbDemo.done = false; go(SpPage.SEARCH) }
    }
    // écran d'essai affiché : Spotlight s'ouvre en mode démo, et la zone reste cliquable même hors de l'accueil
    if (cur == SpPage.TRY) {
        DisposableEffect(Unit) {
            SpotlightOnbDemo.done = false
            SpotlightOnbDemo.active = true
            SpotlightEdge.setDemo(true)
            onDispose { SpotlightOnbDemo.active = false; SpotlightEdge.setDemo(false) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // essai du geste : la zone et le point sont dessinés à la taille réelle, sous le texte
        if (cur == SpPage.TRY) SpGestureLayer(ctx)

        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)
        ) {
            OnbTopBar(label = "SPOTLIGHT  ·  ${cur.step()} / $SP_STEPS", onSkip = onClose)

            Crossfade(targetState = cur, modifier = Modifier.weight(1f).fillMaxWidth(), label = "spotlightOnboarding") { page ->
                when (page) {
                    SpPage.TRY -> SpTryPage(ctx, tick, onOpen = { launchSpotlight(ctx) })
                    SpPage.LOOK -> SpLookPage()
                    else -> Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        when (page) {
                            SpPage.OPEN -> SpOpenPage(ctx, tick, onTry = { go(SpPage.TRY) })
                            SpPage.SEARCH -> SpSearchPage(ctx, tick, askContacts, askMedia)
                            SpPage.AI -> SpAiPage(ctx, tick, onLater = { next() }, onCalendar = askCalendar)
                            SpPage.WIDGETS -> SpWidgetsPage(ctx, tick, askBluetooth, askCalendar)
                            SpPage.SUMMARY -> SpSummaryPage(ctx, tick, onOpen = { onClose(); launchSpotlight(ctx) }, onDone = onClose)
                            else -> {}
                        }
                    }
                }
            }

            OnbBottomBar(
                count = SP_STEPS, current = cur.step() - 1,
                showNext = cur != SpPage.SUMMARY, nextLabel = "Suivant",
                onPrev = if (cur != SpPage.OPEN) ({ prev() }) else null,
                onNext = { next() }
            )
        }
    }
}

// ---------------------------------------------------------------- petits composants

@Composable
private fun SpBadge(text: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.18f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

/** Ligne d'autorisation : bouton « Autoriser… » tant que ce n'est pas fait, puis « ✓ … autorisé ». */
@Composable
private fun SpPermButton(title: String, ok: Boolean, onClick: () -> Unit) {
    if (ok) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Check, null, tint = NeonCyan, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("$title · autorisé", color = NeonCyan, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    } else {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(NeonRed)
                .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Lock, null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("Autoriser : $title", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SpNote(text: String, color: Color = Color.White.copy(alpha = 0.55f)) {
    Text(text, style = MaterialTheme.typography.bodySmall.copy(color = color, lineHeight = 17.sp))
}

/** Carte d'un module : icône, titre, interrupteur, et un contenu qui n'apparaît que quand le module est actif. */
@Composable
private fun SpModuleCard(
    icon: ImageVector, title: String, subtitle: String,
    checked: Boolean, onChecked: (Boolean) -> Unit,
    badge: String? = null,
    extra: (@Composable ColumnScope.() -> Unit)? = null
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(NeonCyan.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) { Icon(icon, null, tint = NeonCyan, modifier = Modifier.size(22.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium.copy(color = Color.White, fontWeight = FontWeight.Bold))
                    Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.6f)))
                }
                Switch(
                    checked = checked, onCheckedChange = onChecked,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = NeonCyan)
                )
            }
            if (badge != null) SpBadge(badge, NeonCyan)
            if (checked && extra != null) extra()
        }
    }
}

/** Interrupteur secondaire, à l'intérieur d'une carte. */
@Composable
private fun SpSubSwitch(title: String, sub: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = NeonCyan)
        )
    }
}

/** Choix parmi quelques options, sur plusieurs lignes si besoin. */
@Composable
private fun SpChips(options: List<String>, selected: Int, perRow: Int = 3, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.withIndex().chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (i, label) ->
                    val sel = i == selected
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                            .background(if (sel) NeonRed else Color.White.copy(alpha = 0.08f))
                            .clickable { onSelect(i) }.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label, color = Color.White, fontSize = 12.sp, maxLines = 1,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SpField(value: String, hint: String, password: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { Text(hint, color = Color.White.copy(alpha = 0.35f)) },
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
            cursorColor = Color.White, focusedBorderColor = NeonCyan,
            unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
        )
    )
}

/** Rangée repliable (« Réglages avancés », « Réglages fins »…). */
@Composable
private fun SpFoldRow(title: String, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Icon(
            if (open) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
            null, tint = Color.White.copy(alpha = 0.7f)
        )
    }
}

private fun openOverlaySettings(ctx: Context) {
    ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
}

// ---------------------------------------------------------------- 1. ouverture de Spotlight

@Composable
private fun SpOpenPage(ctx: Context, tick: Int, onTry: () -> Unit) {
    val s = SpotlightState
    LaunchedEffect(s.edgeEnabled, s.edgeSide, s.edgeDir) { SpotlightEdge.refresh(ctx) }
    val overlayOk = remember(tick) { Settings.canDrawOverlays(ctx) }
    val usageOk = remember(tick) { SpotlightEdge.hasUsageAccess(ctx) }
    var advanced by rememberSaveable { mutableStateOf(false) }

    PageTitle(
        Icons.Rounded.TouchApp, "Ouvrir Spotlight",
        "Un balayage le long du bord de l'écran, et la recherche s'ouvre par-dessus n'importe quelle appli."
    )

    // 1) l'autorisation indispensable
    GlassCard(modifier = Modifier.fillMaxWidth(), onClick = { if (!overlayOk) openOverlaySettings(ctx) }) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Afficher par-dessus les autres applis", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium.copy(color = Color.White, fontWeight = FontWeight.Bold)
                )
                if (overlayOk) SpBadge("AUTORISÉ", NeonCyan) else SpBadge("INDISPENSABLE", NeonRed)
            }
            SpNote(
                if (overlayOk) "C'est bon : la zone de balayage peut se poser sur le bord de l'écran."
                else "Sans cette autorisation, le geste ne marche pas. Toucher ici pour l'accorder, puis reviens dans l'appli."
            )
        }
    }

    // 2) côté de la zone, avec le téléphone qui suit les choix en direct
    ZonePreview(s.edgeSide.coerceIn(0, 1), s.edgeHeightPct, s.edgePos, s.edgeDir, s.edgeWidthDp)
    ChoiceCard(
        "Côté de la zone", listOf("Gauche", "Droite"), s.edgeSide.coerceIn(0, 1), { s.edgeSide = it },
        subtitle = "La bande bleue du téléphone montre où poser le doigt"
    )
    PrimaryButton(if (overlayOk) "Essayer" else "Autoriser, puis essayer", Icons.Rounded.TouchApp) {
        if (!overlayOk) openOverlaySettings(ctx)
        else { s.edgeEnabled = true; SpotlightEdge.refresh(ctx); onTry() }
    }
    if (!s.edgeEnabled) SpNote("Le balayage est désactivé pour l'instant : « Essayer » l'active. Tu peux aussi passer cette étape et utiliser la tuile « Spotlight » des réglages rapides.")

    // 3) accès à l'utilisation : expliqué et facultatif
    GlassCard(modifier = Modifier.fillMaxWidth(), onClick = {
        if (!usageOk) ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Laisser la zone libre hors de l'accueil", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium.copy(color = Color.White, fontWeight = FontWeight.Bold)
                )
                if (usageOk) SpBadge("AUTORISÉ", NeonCyan) else SpBadge("FACULTATIF", Color.White.copy(alpha = 0.7f))
            }
            SpNote(
                "Sans rien de plus, la zone est active dans toutes les applis. Avec « Accès à l'utilisation », CélianOS sait quand tu es sur l'écran d'accueil " +
                        "et rend la zone transparente aux touches partout ailleurs : elle ne gêne plus aucune appli."
            )
            SpNote("Android ne lui donne que le nom de l'appli au premier plan. C'est lu sur ton téléphone ; rien n'est enregistré ni envoyé.")
            SpNote(
                when {
                    !usageOk -> "Tu peux t'en passer : le geste marche pareil. Toucher ici pour l'accorder."
                    s.edgeScope == 1 -> "Accordé : la zone n'est active que sur l'écran d'accueil."
                    else -> "Accordé, mais tu as choisi une zone active partout (Réglages Spotlight)."
                },
                color = if (usageOk) NeonCyan else Color.White.copy(alpha = 0.55f)
            )
        }
    }

    // 4) réglages avancés, repliés
    SpFoldRow("Réglages avancés", advanced) { advanced = !advanced }
    AnimatedVisibility(advanced) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SliderCard(
                "Largeur de la zone", "${s.edgeWidthDp.toInt()} dp, plus fin = moins gênant",
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
                "Sens du balayage", listOf("Vers le bas ↓", "Vers le haut ↑", "Les deux ↕"), s.edgeDir.coerceIn(0, 2), { s.edgeDir = it },
                subtitle = "Le balayage vers l'intérieur fonctionne toujours"
            )
        }
    }
}

// ---------------------------------------------------------------- 1 (suite). essai du geste, taille réelle

/** Zone et point à la taille et à la place RÉELLES (même calcul que SpotlightEdge.refresh), sous le texte. */
@Composable
private fun SpGestureLayer(ctx: Context) {
    val s = SpotlightState
    val dm = ctx.resources.displayMetrics
    var topPx by remember { mutableFloatStateOf(0f) }

    val progress = remember { Animatable(0f) }
    val dotAlpha = remember { Animatable(0f) }
    var alongEdge by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            // 1) vers l'intérieur de l'écran
            alongEdge = false
            progress.snapTo(0f)
            dotAlpha.animateTo(1f, tween(250))
            progress.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
            dotAlpha.animateTo(0f, tween(250))
            delay(450)
            // 2) le long du bord
            alongEdge = true
            progress.snapTo(0f)
            dotAlpha.animateTo(1f, tween(250))
            progress.animateTo(1f, tween(1000, easing = FastOutSlowInEasing))
            dotAlpha.animateTo(0f, tween(250))
            delay(800)
        }
    }
    val pulse = rememberInfiniteTransition(label = "zonePulse")
    val glow by pulse.animateFloat(
        0.35f, 0.9f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "zoneGlow"
    )

    Canvas(Modifier.fillMaxSize().onGloballyPositioned { topPx = it.positionInWindow().y }) {
        val d = dm.density
        val left = s.edgeSide == 0
        val screenH = dm.heightPixels.toFloat()
        val zh = screenH * s.edgeHeightPct / 100f
        val zy = ((screenH - zh) * s.edgePos / 100f).coerceAtLeast(0f) - topPx
        val zw = (s.edgeWidthDp * d).coerceAtLeast(1f)
        val zx = if (left) 0f else size.width - zw
        val corner = CornerRadius(zw / 2f, zw / 2f)

        // halo + zone réelle
        val halo = 10.dp.toPx()
        drawRoundRect(
            NeonCyan.copy(alpha = 0.10f * glow),
            topLeft = Offset(if (left) 0f else zx - halo, zy), size = Size(zw + halo, zh),
            cornerRadius = CornerRadius(halo, halo)
        )
        drawRoundRect(NeonCyan.copy(alpha = 0.35f * glow), topLeft = Offset(zx, zy), size = Size(zw, zh), cornerRadius = corner)
        drawRoundRect(NeonCyan.copy(alpha = 0.9f), topLeft = Offset(zx, zy), size = Size(zw, zh), cornerRadius = corner, style = Stroke(1.5.dp.toPx()))

        // le point : où poser le doigt et comment glisser
        val cx = zx + zw / 2f
        val cy = zy + zh / 2f
        val inward = 130f * d
        val travel = minOf(zh * 0.6f, 180f * d)
        val down = s.edgeDir != 1
        fun at(t: Float): Offset =
            if (!alongEdge) Offset(cx + (if (left) 1f else -1f) * inward * t, cy)
            else Offset(cx, cy + (if (down) 1f else -1f) * travel * (t - 0.5f))

        val a = dotAlpha.value
        for (k in 5 downTo 1) {
            val t = progress.value - k * 0.05f
            if (t >= 0f) drawCircle(Color.White.copy(alpha = a * 0.30f * (1f - k / 6f)), radius = (13 - k).dp.toPx(), center = at(t))
        }
        val p = at(progress.value)
        drawCircle(Color.White.copy(alpha = 0.25f * a), radius = 26.dp.toPx(), center = p)
        drawCircle(Color.White.copy(alpha = a), radius = 12.dp.toPx(), center = p)
        drawCircle(NeonCyan.copy(alpha = a), radius = 12.dp.toPx(), center = p, style = Stroke(2.dp.toPx()))
    }
}

@Composable
private fun SpTryPage(ctx: Context, tick: Int, onOpen: () -> Unit) {
    val s = SpotlightState
    val overlayOk = remember(tick) { Settings.canDrawOverlays(ctx) }
    LaunchedEffect(overlayOk) {
        if (overlayOk) { if (!s.edgeEnabled) s.edgeEnabled = true; SpotlightEdge.refresh(ctx) }
    }
    val dirText = when (s.edgeDir) { 1 -> "vers le haut"; 2 -> "vers le haut ou vers le bas"; else -> "vers le bas" }

    Column(Modifier.fillMaxSize().padding(vertical = 16.dp)) {
        PageTitle(
            Icons.Rounded.TouchApp, "Essaie le geste",
            "Pose le doigt sur la zone bleue, puis glisse vers l'intérieur de l'écran, ou $dirText le long du bord."
        )
        Spacer(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!overlayOk) {
                SpPermButton("Afficher par-dessus les autres applis", false) { openOverlaySettings(ctx) }
            } else {
                SpNote("Spotlight s'ouvre : tape un mot pour voir, et il se referme tout seul. Rien ne se passe ? Reviens en arrière pour changer de côté ou agrandir la zone.")
            }
            SecondaryButton("Ouvrir Spotlight sans le geste", onOpen)
        }
    }
}

// ---------------------------------------------------------------- 2. que veux-tu chercher ?

@Composable
private fun SpSearchPage(ctx: Context, tick: Int, askContacts: () -> Unit, askMedia: () -> Unit) {
    val s = SpotlightState
    val contactsOk = remember(tick) { SpotlightSearch.hasContactsPermission(ctx) }
    val mediaOk = remember(tick) { SpotlightSearch.hasMediaPermission(ctx) }

    PageTitle(
        Icons.Rounded.Search, "Que veux-tu chercher ?",
        "Un module par carte. Les autorisations ne sont demandées que pour ceux que tu gardes."
    )
    val none = "AUCUNE AUTORISATION"

    SpModuleCard(Icons.Rounded.Apps, "Applications", "Chercher parmi les applis installées", s.searchApps, { s.searchApps = it }, badge = none)

    SpModuleCard(Icons.Rounded.Contacts, "Contacts", "Chercher dans ton répertoire", s.searchContacts, { s.searchContacts = it }) {
        SpPermButton("Contacts", contactsOk, askContacts)
    }

    SpModuleCard(
        Icons.Rounded.PhotoLibrary, "Photos, vidéos et documents", "Recherche par titre de fichier",
        s.searchFiles, { s.searchFiles = it }
    ) {
        SpPermButton("Photos et vidéos", mediaOk, askMedia)
        SpNote("Seul le titre est lu, jamais le contenu. Pour retrouver aussi les PDF et documents Word, Android demande en plus « Accès à tous les fichiers » : c'est dans Réglages Spotlight.")
    }

    SpModuleCard(
        Icons.Rounded.Settings, "Réglages Android", "« wifi », « bluetooth », « luminosité »… ouvrent la bonne page",
        s.searchSettings, { s.searchSettings = it }, badge = none
    )
    SpModuleCard(
        Icons.Rounded.Calculate, "Calculatrice", "12*(3+4), 15 %, sqrt(81)",
        s.searchCalc, { s.searchCalc = it }, badge = none
    )

    // dictionnaire + Wikipédia : une carte, deux interrupteurs, un avertissement
    SpModuleCard(
        Icons.Rounded.MenuBook, "Dictionnaire et Wikipédia", "Définitions d'un mot et page Wikipédia du mot tapé",
        s.searchDict || s.searchWiki, { on -> s.searchDict = on; s.searchWiki = on }
    ) {
        SpSubSwitch("Dictionnaire (Wiktionnaire)", "Pour un mot seul", s.searchDict) { s.searchDict = it }
        SpSubSwitch("Wikipédia", "Ouvre la page dans l'appli Wikipédia", s.searchWiki) { s.searchWiki = it }
        SpNote("Envoie ce que tu tapes à Wikimedia : tout ce que tu tapes pour Wikipédia, un mot seul pour le dictionnaire.", ORANGE)
    }

    // recherche web + choix du moteur
    SpModuleCard(
        Icons.Rounded.Language, "Recherche web", "Lance le moteur ou ouvre une adresse",
        s.searchWeb, { s.searchWeb = it }
    ) {
        Text("Moteur de recherche", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        SpChips(WEB_ENGINES.map { it.first }, s.engine.coerceIn(0, WEB_ENGINES.lastIndex)) { s.engine = it }
        SpNote("Ton navigateur s'ouvre sur le moteur choisi quand tu valides. Rien n'est envoyé avant.")
    }
}

// ---------------------------------------------------------------- 3. assistant IA (facultatif)

@Composable
private fun SpAiPage(ctx: Context, tick: Int, onLater: () -> Unit, onCalendar: () -> Unit) {
    val s = SpotlightState
    val provs = AiProviders.all
    var provId by rememberSaveable { mutableStateOf(provs.firstOrNull { it.id == s.aiMain }?.id ?: provs.first().id) }
    val prov = provs.firstOrNull { it.id == provId } ?: provs.first()
    var key by remember(provId) { mutableStateOf(SpotlightAiApi.key(provId)) }
    val hasKey = key.isNotBlank()
    val calOk = remember(tick) { SpotlightAgenda.hasPermission(ctx) }

    PageTitle(
        Icons.Rounded.AutoAwesome, "Assistant IA",
        "Facultatif, et désactivé tant que tu ne l'actives pas."
    )
    BetaBadge(NeonCyan, Color.White)
    SpNote(BETA_NOTE)
    BulletCard(
        "Tu écris une phrase, il s'en occupe", NeonCyan,
        listOf(
            "Minuteur : « minuteur 10 min »",
            "Notes : « note : acheter du pain »",
            "Messages : « dis à Maman que j'arrive » (un brouillon, tu valides avant l'envoi)",
            "Agenda : « rdv dentiste jeudi 14h »"
        )
    )
    if (s.cmdEnabled) SpNote("Sans IA, les commandes rapides comme « minuteur 10 min » ou « note : … » marchent déjà.")

    // fournisseur + clé
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Fournisseur", style = MaterialTheme.typography.titleMedium.copy(color = Color.White, fontWeight = FontWeight.Bold))
            SpChips(provs.map { it.label }, provs.indexOfFirst { it.id == provId }.coerceAtLeast(0)) { i ->
                provId = provs[i].id
                if (s.brainEnabled) {
                    s.aiMain = provs[i].id
                    if (s.aiBackup == provs[i].id) s.aiBackup = ""
                }
            }
            Text("Clé API ${prov.label}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            SpField(key, "Colle ta clé ici", password = true) { key = it; SpotlightAiApi.setKey(provId, it) }
            SpNote("Chiffrée avec l'Android Keystore, gardée uniquement sur ce téléphone.")
        }
    }

    // ce qui part vers Internet
    BulletCard(
        "Ce qui part vers Internet", ORANGE,
        listOf(
            "La phrase que tu tapes, et le nom du contact quand il y en a un, envoyés à ${prov.label} avec ta clé.",
            "Jamais le numéro de téléphone, ni ton répertoire."
        )
    )

    if (!s.brainEnabled) {
        PrimaryButton("Activer l'assistant", Icons.Rounded.AutoAwesome, enabled = hasKey) {
            s.aiMain = provId
            if (s.aiBackup == provId) s.aiBackup = ""
            s.brainEnabled = true
        }
        if (!hasKey) SpNote("Il faut d'abord une clé API pour pouvoir l'activer.")
        SecondaryButton("Plus tard", onLater)
    } else {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = NeonCyan, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Assistant activé", color = NeonCyan, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                SpNote("Fournisseur : ${prov.label}" + if (hasKey) "" else ". Il manque la clé, il ne répondra pas.")
            }
        }
        SecondaryButton("Désactiver l'assistant") { s.brainEnabled = false }

        // agenda : seulement si l'assistant est actif ET que l'utilisateur veut l'agenda dedans
        SpModuleCard(
            Icons.Rounded.CalendarMonth, "Agenda dans l'assistant",
            "Lire tes prochains événements (« j'ai quoi demain ? »). Préparer un rendez-vous ne demande aucune autorisation.",
            "calendar" in ConnectorRegistry.enabledIds(), { ConnectorRegistry.setEnabled("calendar", it) }
        ) {
            SpPermButton("Agenda", calOk, onCalendar)
        }
    }
}

// ---------------------------------------------------------------- 4. widgets

private fun spWidgetIcon(id: String): ImageVector = when (id) {
    "weather" -> Icons.Rounded.WbSunny
    "battery" -> Icons.Rounded.BatteryFull
    "habits" -> Icons.Rounded.CheckCircle
    "quote" -> Icons.Rounded.FormatQuote
    "agenda" -> Icons.Rounded.CalendarMonth
    else -> Icons.Rounded.Edit
}

private fun spWidgetDesc(id: String): String = when (id) {
    "weather" -> "Température et prévisions de ta ville"
    "battery" -> "Batterie du téléphone et des écouteurs"
    "habits" -> "Tes habitudes du jour, à cocher"
    "quote" -> "Une citation par jour"
    "agenda" -> "Tes prochains événements"
    else -> "Tes notes rapides"
}

@Composable
private fun SpWidgetsPage(ctx: Context, tick: Int, askBluetooth: () -> Unit, askCalendar: () -> Unit) {
    val s = SpotlightState
    val ids = s.widgetList.split(',').map { it.trim() }.filter { id -> WIDGET_DEFS.any { it.first == id } }.distinct()
    val btOk = remember(tick) {
        Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }
    val calOk = remember(tick) { SpotlightAgenda.hasPermission(ctx) }
    // un widget qu'on coche se place à la fin ; l'ordre se règle ensuite dans Réglages Spotlight
    fun setWidget(id: String, on: Boolean) { s.widgetList = (if (on) (ids + id).distinct() else ids - id).joinToString(",") }

    PageTitle(
        Icons.Rounded.Widgets, "Widgets",
        "Coche ceux qui s'affichent dans Spotlight quand la barre de recherche est vide."
    )
    WIDGET_DEFS.forEach { (id, name) ->
        SpModuleCard(spWidgetIcon(id), name, spWidgetDesc(id), id in ids, { setWidget(id, it) }) {
            if (id == "weather") {
                Text("Ville", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                SpField(s.city, "Ta ville") { s.city = it }
                SpNote("La ville est envoyée à un service météo pour obtenir les prévisions.", ORANGE)
            }
            if (id == "battery" && Build.VERSION.SDK_INT >= 31) {
                SpNote("Pour afficher aussi la batterie de tes écouteurs, Android demande l'autorisation Bluetooth. Facultatif : sans elle, seul le téléphone s'affiche.")
                SpPermButton("Bluetooth", btOk, askBluetooth)
            }
            if (id == "agenda") SpPermButton("Agenda", calOk, askCalendar)
        }
    }
    if (ids.isEmpty()) SpNote("Aucun widget : Spotlight n'affichera que la barre de recherche.")
}

// ---------------------------------------------------------------- 5. apparence

@Composable
private fun SpLookPage() {
    val s = SpotlightState
    val accent = rememberSpotTheme().accentVivid
    var fine by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Apparence",
            style = MaterialTheme.typography.headlineSmall.copy(color = Color.White, fontWeight = FontWeight.ExtraBold)
        )
        // l'aperçu reste en place : chaque choix en dessous s'y voit tout de suite
        BlurPreview(s.blurRadius)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ChoiceCard(
                "Style du verre", SP_GLASS_STYLES, s.glassStyle.coerceIn(0, SP_GLASS_STYLES.lastIndex), { s.glassStyle = it }
            )
            ChoiceCard(
                "Thème", THEME_MODES, s.themeMode.coerceIn(0, 2), { s.themeMode = it },
                subtitle = "Pour Spotlight. Automatique suit le thème du téléphone"
            )
            SwitchCard(
                "Couleurs du fond d'écran", "L'accent reprend la couleur dominante de ton fond d'écran (Android 8.1+)",
                s.accentFromWallpaper, { s.accentFromWallpaper = it }, accent
            )
            if (!s.accentFromWallpaper) {
                AccentPaletteCard(s.accentIndex.coerceIn(0, ACCENT_PRESETS.lastIndex)) { s.accentIndex = it }
            }
            SpFoldRow("Réglages fins", fine) { fine = !fine }
            AnimatedVisibility(fine) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SliderCard(
                        "Rayon des coins", "${s.cornerPct.toInt()} % (100 % = d'origine, 0 % = coins droits)",
                        s.cornerPct, { s.cornerPct = it }, 0f..150f
                    )
                    SliderCard(
                        "Opacité du verre", "${s.glassOpacityPct.toInt()} % (100 % = d'origine)",
                        s.glassOpacityPct, { s.glassOpacityPct = it }, 20f..200f
                    )
                    SliderCard(
                        "Intensité du flou", "${s.blurRadius.toInt()} (Android 12+ avec flou activé)",
                        s.blurRadius, { s.blurRadius = it }, 10f..100f
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ---------------------------------------------------------------- 6. résumé

@Composable
private fun SpSummaryPage(ctx: Context, tick: Int, onOpen: () -> Unit, onDone: () -> Unit) {
    val s = SpotlightState
    val overlayOk = remember(tick) { Settings.canDrawOverlays(ctx) }
    val contactsOk = remember(tick) { SpotlightSearch.hasContactsPermission(ctx) }
    val mediaOk = remember(tick) { SpotlightSearch.hasMediaPermission(ctx) }
    val calOk = remember(tick) { SpotlightAgenda.hasPermission(ctx) }

    val on = ArrayList<String>()
    val off = ArrayList<String>()
    val todo = ArrayList<String>()

    // ouverture
    val side = if (s.edgeSide == 0) "gauche" else "droite"
    when {
        s.edgeEnabled && overlayOk -> on += "Ouverture par balayage, côté $side"
        s.edgeEnabled -> { off += "Ouverture par balayage (autorisation manquante)"; todo += "« Afficher par-dessus les autres applis », sinon le geste ne marche pas" }
        else -> off += "Ouverture par balayage (la tuile « Spotlight » des réglages rapides reste possible)"
    }
    // recherche
    fun mod(enabled: Boolean, label: String) { if (enabled) { on += label } else { off += label } }
    mod(s.searchApps, "Recherche : applications")
    mod(s.searchContacts, "Recherche : contacts")
    mod(s.searchFiles, "Recherche : photos, vidéos et documents")
    mod(s.searchSettings, "Recherche : réglages Android")
    mod(s.searchCalc, "Recherche : calculatrice")
    mod(s.searchDict, "Recherche : dictionnaire")
    mod(s.searchWiki, "Recherche : Wikipédia")
    mod(s.searchWeb, "Recherche web (${WEB_ENGINES[s.engine.coerceIn(0, WEB_ENGINES.lastIndex)].first})")
    if (s.searchContacts && !contactsOk) todo += "Autorisation Contacts, pour chercher dans le répertoire"
    if (s.searchFiles && !mediaOk) todo += "Autorisation Photos et vidéos, pour chercher les fichiers"
    // assistant
    val hasKey = AiProviders.all.any { SpotlightAiApi.key(it.id).isNotBlank() }
    when {
        s.brainEnabled && hasKey -> on += "Assistant IA (${AiProviders.all.firstOrNull { it.id == s.aiMain }?.label ?: s.aiMain})"
        s.brainEnabled -> { off += "Assistant IA (il manque la clé)"; todo += "Une clé API pour l'assistant IA" }
        else -> off += "Assistant IA"
    }
    // widgets
    val ids = s.widgetList.split(',').map { it.trim() }.filter { id -> WIDGET_DEFS.any { it.first == id } }.distinct()
    WIDGET_DEFS.forEach { (id, name) ->
        if (id in ids) { on += "Widget : $name" } else { off += "Widget : $name" }
    }
    val agendaWanted = "agenda" in ids || (s.brainEnabled && "calendar" in ConnectorRegistry.enabledIds())
    if (agendaWanted && !calOk) todo += "Autorisation Agenda, pour lire tes événements"

    val styleName = SP_GLASS_STYLES[s.glassStyle.coerceIn(0, SP_GLASS_STYLES.lastIndex)]
    val themeName = THEME_MODES[s.themeMode.coerceIn(0, 2)]
    on += "Apparence : $styleName, thème $themeName, " + if (s.accentFromWallpaper) "accent du fond d'écran" else "accent ${ACCENT_PRESETS[s.accentIndex.coerceIn(0, ACCENT_PRESETS.lastIndex)].first}"

    PageTitle(
        Icons.Rounded.RocketLaunch, "Tout est prêt",
        "Voilà ce que fait Spotlight maintenant.", strong = true
    )
    BulletCard("Activé", NeonCyan, on.ifEmpty { listOf("Rien pour l'instant") })
    if (off.isNotEmpty()) BulletCard("Désactivé", Color.White.copy(alpha = 0.7f), off)
    if (todo.isNotEmpty()) BulletCard("Il reste à autoriser", ORANGE, todo)
    BulletCard(
        "Modifiable plus tard", Color(0xFFB388FF),
        listOf(
            "Tout se change dans Réglages Spotlight, depuis la carte Spotlight de l'accueil.",
            "Les réglages avancés (zone, ordre des widgets, modèles IA, modules perso) y sont aussi."
        )
    )

    Spacer(Modifier.height(4.dp))
    PrimaryButton("Ouvrir Spotlight", Icons.Rounded.RocketLaunch, onClick = onOpen)
    SecondaryButton("Terminer", onDone)
    SpNote("Tu peux refaire cet assistant quand tu veux : « Relancer l'assistant de configuration », dans les réglages généraux (la roue dentée de l'accueil). Il garde tes réglages actuels.")
}