package com.example.celianos

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================
//  Composants réutilisables pour toutes les pages de réglages
// ============================================================

/** Cadre commun d'une sous-page : bouton retour, titre, contenu scrollable. */
@Composable
fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = onBack) {
                Icon(imageVector = Icons.Rounded.ArrowBack, contentDescription = "Retour", tint = Color.White)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = Color.White)
            )
        }

        content()

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** Titre de section (ex : « ACTIVATION »). */
@Composable
fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(
            color = Color.White.copy(alpha = 0.5f),
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold
        ),
        modifier = Modifier.padding(start = 4.dp, top = 12.dp)
    )
}

/** Carte avec un interrupteur. */
@Composable
fun SwitchCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    trackColor: Color = NeonRed,
    enabled: Boolean = true
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(20.dp).fillMaxWidth().alpha(if (enabled) 1f else 0.4f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium.copy(color = Color.White, fontWeight = FontWeight.Bold))
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.6f)))
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = trackColor)
            )
        }
    }
}

/** Carte avec un curseur. */
@Composable
fun SliderCard(
    title: String,
    valueLabel: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChangeFinished: () -> Unit = {},
    enabled: Boolean = true
) {
    val ac = LocalSettingsTheme.current?.accentVivid ?: NeonRed
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp).fillMaxWidth().alpha(if (enabled) 1f else 0.4f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium.copy(color = Color.White, fontWeight = FontWeight.Bold))
            Text(text = valueLabel, style = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.6f)))
            Spacer(modifier = Modifier.height(8.dp))
            Slider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = valueRange,
                enabled = enabled,
                colors = SliderDefaults.colors(thumbColor = ac, activeTrackColor = ac)
            )
        }
    }
}

/** Carte cliquable avec un statut (ex : permissions). */
@Composable
fun LinkCard(
    title: String,
    status: String,
    isOk: Boolean,
    onClick: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(modifier = Modifier.padding(20.dp).fillMaxWidth()) {
            Text(text = title, style = MaterialTheme.typography.titleMedium.copy(color = Color.White, fontWeight = FontWeight.Bold))
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = if (isOk) NeonCyan else Color.White.copy(alpha = 0.6f)
                )
            )
        }
    }
}

/** Carte d'attente pour les fonctionnalités dont les réglages ne sont pas encore codés. */
@Composable
fun ComingSoonCard(message: String) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp).fillMaxWidth()) {
            Text(text = "Bientôt disponible", style = MaterialTheme.typography.titleMedium.copy(color = Color.White, fontWeight = FontWeight.Bold))
            Text(text = message, style = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.6f)))
        }
    }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "bounce"
    )

    // Pages de réglages de Spotlight : la carte suit le style choisi (Matériel, Brutaliste). Sinon, rendu d'origine.
    val st = LocalSettingsTheme.current
    val styled = st != null && st.style != STYLE_TRANSLUCENT
    Box(
        modifier = modifier
            .scale(scale)
            .then(
                if (styled && st != null) Modifier.spotGlassStyle(st.shape(24), 0.14f, false, st)
                else Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(GlassSurface)
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(colors = listOf(GlassBorder, Color.Transparent)),
                        shape = RoundedCornerShape(24.dp)
                    )
            )
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                ) else Modifier
            )
    ) {
        content()
    }
}

/** Carte avec une rangée de choix (ex : style). `selectedIndex = -1` : aucun choix surligné (boutons d'action). */
@Composable
fun ChoiceCard(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    subtitle: String? = null
) {
    val ac = LocalSettingsTheme.current?.accentVivid ?: NeonRed
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp).fillMaxWidth()) {
            Text(text = title, style = MaterialTheme.typography.titleMedium.copy(color = Color.White, fontWeight = FontWeight.Bold))
            if (subtitle != null) {
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.6f)))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEachIndexed { index, label ->
                    val selected = index == selectedIndex
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) ac else Color.White.copy(alpha = 0.08f))
                            .clickable { onSelect(index) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            maxLines = 1,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                        )
                    }
                }
            }
        }
    }
}