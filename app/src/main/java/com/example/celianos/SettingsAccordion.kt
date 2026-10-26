package com.example.celianos

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================
//  Sections repliables (accordéon) des réglages Spotlight, badge « Bêta »,
//  et respect du réglage Android « Supprimer les animations ».
//  Aucune valeur enregistrée n'est touchée : l'état ouvert/fermé vit en mémoire,
//  le temps de la session (processus), et n'est écrit nulle part.
// ============================================================

/** Phrase affichée sous le badge Bêta (réglages, assistant de configuration, panneau Spotlight). */
const val BETA_NOTE = "Fonction en cours de test : les résultats peuvent varier."

/** État ouvert/fermé de chaque section, mémorisé tant que l'appli tourne (pas de SharedPreferences). */
object SettingsAccordionState {
    val open = mutableStateMapOf<String, Boolean>()
}

/** Vrai quand l'utilisateur a désactivé les animations d'Android (échelle de durée des animations = 0). */
val LocalReduceMotion = compositionLocalOf { false }

private fun animationsOff(ctx: android.content.Context): Boolean =
    runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
        .getOrDefault(false)

/** Lit le réglage Android et suit ses changements pendant que l'écran est affiché. */
@Composable
fun rememberReduceMotion(): Boolean {
    val ctx = LocalContext.current
    var off by remember { mutableStateOf(animationsOff(ctx)) }
    DisposableEffect(ctx) {
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { off = animationsOff(ctx) }
        }
        runCatching {
            ctx.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, obs
            )
        }
        onDispose { runCatching { ctx.contentResolver.unregisterContentObserver(obs) } }
    }
    return off
}

/** Petite étiquette « Bêta ». `accent` = couleur d'accent courante, `ink` = couleur du texte du thème. */
@Composable
fun BetaBadge(accent: Color, ink: Color, shape: Shape = RoundedCornerShape(8.dp)) {
    Text(
        "Bêta", color = ink, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(shape)
            .background(accent.copy(alpha = 0.28f))
            .border(1.dp, accent, shape)
            .padding(horizontal = 7.dp, vertical = 2.dp)
    )
}

/**
 * Section repliable : un en-tête (titre, résumé de l'état, flèche) puis le contenu.
 * L'en-tête est une GlassCard : il suit donc le style (Translucide, Matériel, Brutaliste),
 * l'accent et le rayon des coins comme les autres cartes de la page.
 * TalkBack lit « titre, résumé, Ouvert/Fermé, bouton ». Fermée, la section n'est pas dans l'arbre
 * d'accessibilité (son contenu n'est pas composé).
 */
@Composable
fun SettingsAccordion(
    id: String,
    title: String,
    summary: String,
    accent: Color,
    beta: Boolean = false,
    defaultOpen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val expanded = SettingsAccordionState.open[id] ?: defaultOpen
    val reduce = LocalReduceMotion.current
    val st = LocalSettingsTheme.current
    val angle by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (reduce) snap() else tween(200),
        label = "accordionArrow"
    )

    Column(Modifier.fillMaxWidth()) {
        GlassCard(
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                role = Role.Button
                stateDescription = if (expanded) "Ouvert" else "Fermé"
            },
            onClick = { SettingsAccordionState.open[id] = !expanded }
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium.copy(color = Color.White, fontWeight = FontWeight.Bold)
                        )
                        if (beta) {
                            Spacer(Modifier.width(8.dp))
                            BetaBadge(accent, Color.White, st?.shape(8) ?: RoundedCornerShape(8.dp))
                        }
                    }
                    if (beta) {
                        Text(BETA_NOTE, style = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.6f)))
                    }
                    Text(summary, style = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.6f)))
                }
                Spacer(Modifier.width(12.dp))
                Icon(Icons.Rounded.KeyboardArrowDown, null, tint = accent, modifier = Modifier.rotate(angle))
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = if (reduce) EnterTransition.None else expandVertically(animationSpec = tween(220)) + fadeIn(tween(220)),
            exit = if (reduce) ExitTransition.None else shrinkVertically(animationSpec = tween(180)) + fadeOut(tween(180))
        ) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}