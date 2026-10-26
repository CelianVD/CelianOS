package com.example.celianos

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Message
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.hypot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ============================================================
//  Introduction de l'APPLI (5 écrans) : Bienvenue → Spotlight → Autres fonctionnalités → Confidentialité → Prêt ?
//
//  Première ouverture, ou « Revoir l'introduction » dans les réglages généraux.
//  Cet écran ne demande AUCUNE autorisation et ne modifie AUCUN réglage.
//
//  La configuration de Spotlight est un onboarding à part, dans SpotlightOnboardingScreen.kt :
//  « Configurer Spotlight » (dernier écran) l'ouvre, via onStartSpotlight.
//
//  Le drapeau « onboarding vu » est écrit par MainActivity (AppState.onboardingSeen).
// ============================================================

const val ONBOARDING_OFF = 0
const val ONBOARDING_FULL = 1   // introduction de l'appli (ce fichier)
// ONBOARDING_SPOTLIGHT = 2     // configuration de Spotlight (SpotlightOnboardingScreen.kt)

private enum class OnbPage { WELCOME, SPOTLIGHT, MORE, PRIVACY, READY }

private val FULL_PAGES = OnbPage.values().toList()

internal val ORANGE = Color(0xFFFFB74D)

/**
 * @param onClose          « Passer », « Terminer », « Plus tard (accueil) » : retour à l'accueil.
 * @param onStartSpotlight « Configurer Spotlight » : lance l'onboarding de Spotlight.
 */
@Composable
fun OnboardingScreen(onClose: () -> Unit, onStartSpotlight: () -> Unit) {
    var index by rememberSaveable { mutableStateOf(0) }
    val last = FULL_PAGES.lastIndex
    val safeIndex = index.coerceIn(0, last)
    val cur = FULL_PAGES[safeIndex]

    // transition « iris » entre la bienvenue et l'écran Spotlight
    val scope = rememberCoroutineScope()
    val wipe = remember { Animatable(0f) }
    var busy by remember { mutableStateOf(false) }
    fun startFromWelcome() {
        if (busy) return
        busy = true
        scope.launch {
            wipe.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
            index = 1
            wipe.animateTo(0f, tween(560, easing = FastOutSlowInEasing))
            busy = false
        }
    }

    BackHandler { if (busy) return@BackHandler; if (safeIndex > 0) index = safeIndex - 1 else onClose() }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)
        ) {
            // repère (jamais sur la bienvenue) + « Passer » (sur chaque écran)
            OnbTopBar(
                label = if (cur == OnbPage.WELCOME) "" else "INTRODUCTION  ·  ${safeIndex + 1} / ${FULL_PAGES.size}",
                onSkip = { if (!busy) onClose() }
            )

            Crossfade(targetState = cur, modifier = Modifier.weight(1f).fillMaxWidth(), label = "onboarding") { page ->
                if (page == OnbPage.WELCOME) {
                    WelcomePage(onStart = ::startFromWelcome)
                } else {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        when (page) {
                            OnbPage.SPOTLIGHT -> SpotlightPage()
                            OnbPage.MORE -> MorePage()
                            OnbPage.PRIVACY -> PrivacyPage()
                            OnbPage.READY -> ReadyPage(onStartSpotlight = onStartSpotlight, onLater = onClose)
                            OnbPage.WELCOME -> {}
                        }
                    }
                }
            }

            // précédent, points, suivant (absente sur la bienvenue ; l'écran final a ses propres boutons)
            if (cur != OnbPage.WELCOME) {
                OnbBottomBar(
                    count = FULL_PAGES.size, current = safeIndex,
                    showNext = cur != OnbPage.READY,
                    nextLabel = if (safeIndex < last) "Suivant" else "Terminer",
                    onPrev = if (safeIndex > 0) ({ if (!busy) index = safeIndex - 1 }) else null,
                    onNext = { if (safeIndex < last) index = safeIndex + 1 else onClose() }
                )
            }
        }

        // voile d'« iris » : un cercle qui grandit depuis le bouton, puis se rétracte sur l'écran suivant
        Box(
            Modifier.fillMaxSize().drawBehind {
                val p = wipe.value
                if (p > 0f) {
                    val maxR = hypot(size.width, size.height)
                    val c = Offset(size.width / 2f, size.height * 0.82f)
                    val r = (maxR * p).coerceAtLeast(1f)
                    drawCircle(
                        brush = Brush.radialGradient(listOf(NeonCyan, Color(0xFFB388FF), NeonRed), center = c, radius = r),
                        radius = r, center = c
                    )
                    drawCircle(Color.White.copy(alpha = 0.55f), radius = r, center = c, style = Stroke(width = 3.dp.toPx()))
                }
            }
        )
    }
}

// ---------------------------------------------------------------- composants communs (aussi utilisés par SpotlightOnboardingScreen.kt)

/** Barre du haut : repère à gauche, « Passer » à droite. */
@Composable
internal fun OnbTopBar(label: String, onSkip: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(
                color = Color.White.copy(alpha = 0.5f), letterSpacing = 2.sp, fontWeight = FontWeight.Bold
            ),
            modifier = Modifier.weight(1f).padding(start = 4.dp)
        )
        Box(
            Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onSkip)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text("Passer", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Barre du bas : « Précédent » (si onPrev), points de progression, « Suivant » (si showNext). */
@Composable
internal fun OnbBottomBar(
    count: Int, current: Int, showNext: Boolean, nextLabel: String,
    onPrev: (() -> Unit)?, onNext: () -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (onPrev != null) {
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onPrev)
                        .padding(horizontal = 4.dp, vertical = 10.dp)
                ) {
                    Text("Précédent", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(count) { i ->
                Box(
                    Modifier.height(6.dp).width(if (i == current) 18.dp else 6.dp).clip(CircleShape)
                        .background(if (i == current) NeonRed else Color.White.copy(alpha = 0.25f))
                )
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (showNext) {
                Box(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(NeonRed)
                        .clickable(onClick = onNext)
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Text(nextLabel, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
internal fun PageTitle(icon: ImageVector, title: String, subtitle: String, strong: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(NeonCyan.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = NeonCyan, modifier = Modifier.size(28.dp)) }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall.copy(color = Color.White, fontWeight = FontWeight.ExtraBold)
        )
        Text(
            subtitle,
            style = if (strong) MaterialTheme.typography.titleSmall.copy(color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.Medium)
            else MaterialTheme.typography.bodyMedium.copy(color = Color.White.copy(alpha = 0.6f))
        )
    }
}

@Composable
internal fun BulletCard(title: String, tint: Color, lines: List<String>) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(color = tint, fontWeight = FontWeight.Bold))
            lines.forEach {
                Text("•  $it", style = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.75f), lineHeight = 18.sp))
            }
        }
    }
}

/** Bouton plein (rouge). `enabled = false` : grisé et sans effet. */
@Composable
internal fun PrimaryButton(label: String, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(if (enabled) NeonRed else Color.White.copy(alpha = 0.12f))
            .clickable(enabled = enabled, onClick = onClick).padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Color.White.copy(alpha = if (enabled) 1f else 0.5f), modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(label, color = Color.White.copy(alpha = if (enabled) 1f else 0.5f), fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

/** Bouton à contour (action secondaire, mais bien visible). */
@Composable
internal fun SecondaryButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp, fontWeight = FontWeight.Medium) }
}

// ---------------------------------------------------------------- 1. bienvenue

@Composable
private fun WelcomePage(onStart: () -> Unit) {
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        1f, 1.18f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "scale"
    )
    val glow by pulse.animateFloat(
        0.10f, 0.30f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow"
    )

    Column(
        Modifier.fillMaxSize().graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 48f },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(Modifier.size(160.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(150.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(CircleShape).background(NeonRed.copy(alpha = glow))
            )
            Box(
                Modifier.size(96.dp).clip(CircleShape)
                    .background(Brush.verticalGradient(listOf(Color(0xFF1A1C29), Color(0xFF0D0E12))))
                    .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.FlashOn, null, tint = NeonRed, modifier = Modifier.size(48.dp)) }
        }
        Spacer(Modifier.height(28.dp))
        Text(
            "Bienvenue dans",
            style = MaterialTheme.typography.titleMedium.copy(color = Color.White.copy(alpha = 0.6f), fontWeight = FontWeight.Medium)
        )
        Text(
            "CélianOS",
            style = MaterialTheme.typography.displaySmall.copy(
                color = Color.White, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp
            )
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Ton téléphone, en plus rapide. Quelques écrans pour tout découvrir.",
            style = MaterialTheme.typography.bodyMedium.copy(color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.Center),
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Spacer(Modifier.height(40.dp))
        Box(Modifier.padding(horizontal = 12.dp)) { PrimaryButton("Commencer", onClick = onStart) }
    }
}

// ---------------------------------------------------------------- 2. Spotlight : des exemples qui se tapent

private class Demo(val query: String, val icon: ImageVector, val title: String, val sub: String)

private val DEMOS = listOf(
    Demo("minuteur 10 min", Icons.Rounded.Timer, "Minuteur · 10 min", "Lancé avec l'appli Horloge"),
    Demo("dis à Maman que j'arrive", Icons.Rounded.Message, "Message à Maman", "Brouillon rédigé par l'IA* · tu valides"),
    Demo("12*(3+4)", Icons.Rounded.Calculate, "= 84", "12*(3+4)  ·  toucher pour copier"),
    Demo("wifi", Icons.Rounded.Wifi, "Wi-Fi", "Réglages Android")
)

@Composable
private fun SpotlightPage() {
    PageTitle(
        Icons.Rounded.Search, "Spotlight",
        "Tape ce que tu veux faire, ton téléphone s'en occupe.", strong = true
    )
    TypingDemo()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MiniChip(Icons.Rounded.Search, "Recherche", NeonCyan, Modifier.weight(1f))
        MiniChip(Icons.Rounded.AutoAwesome, "Assistant", Color(0xFFB388FF), Modifier.weight(1f))
        MiniChip(Icons.Rounded.Widgets, "Widgets", Color(0xFFFF6FB5), Modifier.weight(1f))
    }
    GlassNote("* Rédaction de messages avec l'IA : facultatif. Tu ouvriras Spotlight d'un balayage depuis le bord de l'écran : on le règle ensemble à l'étape suivante.")
}

@Composable
private fun MiniChip(icon: ImageVector, label: String, tint: Color, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.08f)).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** Fausse barre de recherche : les exemples se tapent seuls, puis le résultat apparaît dessous. */
@Composable
private fun TypingDemo() {
    var typed by remember { mutableStateOf("") }
    var current by remember { mutableStateOf(DEMOS[0]) }
    var showResult by remember { mutableStateOf(false) }
    val resultAlpha by animateFloatAsState(if (showResult) 1f else 0f, tween(250), label = "result")
    val blink = rememberInfiniteTransition(label = "cursor")
    val cursor by blink.animateFloat(
        0f, 1f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "cursorAlpha"
    )

    LaunchedEffect(Unit) {
        var i = 0
        while (true) {
            val d = DEMOS[i % DEMOS.size]
            current = d
            typed = ""
            delay(400)
            for (k in 1..d.query.length) { typed = d.query.substring(0, k); delay(65) }
            delay(300)
            showResult = true
            delay(2300)
            showResult = false
            delay(250)
            while (typed.isNotEmpty()) { typed = typed.dropLast(1); delay(18) }
            delay(250)
            i++
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val bar = RoundedCornerShape(28.dp)
        Row(
            Modifier.fillMaxWidth().height(56.dp).clip(bar)
                .background(Color.White.copy(alpha = 0.14f))
                .border(1.dp, Color.White.copy(alpha = 0.2f), bar)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Search, null, tint = Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.width(10.dp))
            if (typed.isEmpty()) {
                Text("Rechercher", color = Color.White.copy(alpha = 0.45f), fontSize = 17.sp)
            } else {
                Text(typed, color = Color.White, fontSize = 17.sp, maxLines = 1)
            }
            Box(Modifier.padding(start = 2.dp).width(2.dp).height(22.dp).background(Color.White.copy(alpha = cursor)))
        }
        // résultat : place réservée pour que la page ne saute pas
        Row(
            Modifier.fillMaxWidth().height(68.dp).graphicsLayer { alpha = resultAlpha }
                .clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = 0.13f))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(NeonCyan.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) { Icon(current.icon, null, tint = NeonCyan) }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(current.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(current.sub, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

// ---------------------------------------------------------------- 3. autres fonctionnalités

@Composable
private fun MorePage() {
    PageTitle(
        Icons.Rounded.Widgets, "Et ce n'est pas tout",
        "Deux modules facultatifs, à retrouver sur l'accueil."
    )
    ShowcaseCard(
        accent = Color(0xFFB388FF), icon = Icons.Rounded.MusicNote, title = "Dynamic Island",
        text = "Une pilule flottante au-dessus des autres applis, avec ce qui joue en ce moment.",
        need = "Demande l'accessibilité et l'accès aux notifications, dans son module."
    ) {
        Row(
            Modifier.clip(CircleShape).background(Color.Black)
                .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.MusicNote, null, tint = Color(0xFFB388FF), modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("En lecture", color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp)
        }
    }
    ShowcaseCard(
        accent = Color(0xFFFF6FB5), icon = Icons.Rounded.Headphones, title = "Glow Dock",
        text = "Pose ton téléphone sur son support : un lecteur plein écran dont la lumière danse avec les basses, les médiums et les aigus.",
        need = "Demande le micro, pour analyser le son qui sort."
    ) { EqualizerBars(Color(0xFFFF6FB5)) }
}

@Composable
private fun ShowcaseCard(
    accent: Color, icon: ImageVector, title: String, text: String, need: String,
    visual: @Composable () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium.copy(color = Color.White, fontWeight = FontWeight.Bold))
                visual()
            }
            Text(text, style = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.75f), lineHeight = 18.sp))
            Text(need, style = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp))
        }
    }
}

@Composable
private fun EqualizerBars(color: Color) {
    val t = rememberInfiniteTransition(label = "eq")
    Row(
        Modifier.height(30.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        listOf(500, 720, 410, 650, 560).forEachIndexed { i, ms ->
            val h by t.animateFloat(
                0.25f, 1f, infiniteRepeatable(tween(ms, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bar$i"
            )
            Box(Modifier.width(5.dp).fillMaxHeight(h).clip(RoundedCornerShape(3.dp)).background(color))
        }
    }
}

// ---------------------------------------------------------------- 4. confidentialité

@Composable
private fun PrivacyPage() {
    var more by rememberSaveable { mutableStateOf(false) }
    PageTitle(
        Icons.Rounded.Lock, "Confidentialité",
        "Par défaut, tes données restent sur ton téléphone. L'IA est facultative et désactivée tant que tu n'ajoutes pas de clé.",
        strong = true
    )
    BulletCard("Reste sur ton téléphone", NeonCyan, PRIVACY_LOCAL_SHORT)
    BulletCard("Part vers Internet", ORANGE, PRIVACY_ONLINE_SHORT)

    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { more = !more }.padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("En savoir plus", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Icon(if (more) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null, tint = Color.White.copy(alpha = 0.7f))
    }
    AnimatedVisibility(more) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BulletCard("Sur ton téléphone · détail", NeonCyan, PRIVACY_LOCAL_DETAIL)
            BulletCard("Vers Internet · détail", ORANGE, PRIVACY_ONLINE_DETAIL)
            GlassNote("Tout se désactive dans Réglages Spotlight (sections Recherche et IA).")
        }
    }
}

// Textes de confidentialité regroupés ici pour être faciles à relire et à corriger.
private val PRIVACY_LOCAL_SHORT = listOf(
    "Recherche d'applis, de contacts, de fichiers et de réglages, calculatrice.",
    "Widgets, notes et réglages.",
    "Tes clés API, chiffrées."
)

private val PRIVACY_ONLINE_SHORT = listOf(
    "Wikipédia et dictionnaire (activés par défaut) : ce que tu tapes est envoyé à Wikimedia.",
    "Météo : la ville de ton widget est envoyée à un service météo.",
    "IA (désactivée par défaut) : ta question, seulement si tu ajoutes une clé."
)

private val PRIVACY_LOCAL_DETAIL = listOf(
    "La recherche d'applis, de contacts, de fichiers (par titre) et de réglages, ainsi que la calculatrice, se font sur le téléphone.",
    "Les widgets batterie, habitudes et agenda, tes notes et tous tes réglages ne quittent pas le téléphone.",
    "Tes clés API sont chiffrées avec l'Android Keystore et stockées uniquement sur le téléphone."
)

private val PRIVACY_ONLINE_DETAIL = listOf(
    "Wikipédia : dès que tu tapes, le texte est envoyé à Wikimedia pour proposer une page. Dictionnaire (Wiktionnaire) : seulement pour un mot seul. Les deux se coupent dans Réglages Spotlight.",
    "Météo : la ville du widget est envoyée à un service météo pour obtenir les prévisions.",
    "Réponse rapide et Assistant IA (désactivés par défaut) : ta question est envoyée au fournisseur que tu as choisi (Gemini, Mistral, OpenAI, Claude…), avec ta propre clé API. Pour l'assistant, les informations utiles à ta demande peuvent l'accompagner.",
    "« Demander à une IA » : ta question est transmise à l'appli IA que tu touches.",
    "Recherche web : ton navigateur s'ouvre sur le moteur choisi quand tu valides.",
    "Agent PC (inactif tant qu'il n'est pas configuré) et pochettes d'albums HD : utilisent le réseau."
)

// ---------------------------------------------------------------- 5. fin de l'introduction

@Composable
private fun ReadyPage(onStartSpotlight: () -> Unit, onLater: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "ready")
    val lift by pulse.animateFloat(
        0f, -10f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "lift"
    )
    Column(
        Modifier.fillMaxWidth().padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(110.dp).clip(CircleShape).background(NeonRed.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.RocketLaunch, null, tint = NeonRed,
                modifier = Modifier.size(56.dp).graphicsLayer { translationY = lift.dp.toPx() }
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Prêt à découvrir ?",
            style = MaterialTheme.typography.headlineMedium.copy(color = Color.White, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
        )
        Text(
            "Dernière étape : configurer Spotlight. Le geste d'ouverture, ce que tu veux chercher, l'assistant, les widgets et l'apparence, en quelques écrans. " +
                    "Tu peux aussi la faire plus tard dans les réglages généraux.",
            style = MaterialTheme.typography.bodyMedium.copy(color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.Center),
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Tester & ouvrir Spotlight", Icons.Rounded.RocketLaunch, onClick = onStartSpotlight)
        SecondaryButton("Plus tard (accueil)", onClick = onLater)
    }
}