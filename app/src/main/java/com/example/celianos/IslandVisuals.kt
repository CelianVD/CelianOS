package com.example.celianos

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.rounded.DoNotDisturbOff
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ============================================================
//  Visuels de la Dynamic Island : pochette en LED, cercle de points,
//  et animations de changement de profil sonore.
// ============================================================

/** Pastille rouge avec une note de musique (visuel par défaut / de secours). */
@Composable
fun MusicBadge(boxSize: Dp) {
    Box(
        modifier = Modifier
            .size(boxSize)
            .clip(CircleShape)
            .background(NeonRed),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(boxSize * 0.55f)
        )
    }
}

/** Choisit le visuel affiché à gauche du titre selon le style réglé. */
@Composable
fun ArtView(
    style: ArtStyle,
    grid: IntArray?,
    active: Boolean,
    isPreview: Boolean,
    boxSize: Dp
) {
    val demo = remember { demoGrid() }
    when (style) {
        ArtStyle.DOT_RING -> DotRing(active = active, boxSize = boxSize)
        ArtStyle.PIXEL_GRID -> {
            // Sans pochette : motif de démonstration en aperçu, sinon icône de secours
            val shown = grid ?: if (isPreview) demo else null
            if (shown != null) {
                PixelGridArt(colors = shown, n = ART_GRID_SIZE, boxSize = boxSize)
            } else {
                MusicBadge(boxSize)
            }
        }
        ArtStyle.ICON -> MusicBadge(boxSize)
    }
}

/** Pochette rééchantillonnée en matrice de LED (style Matrix). */
@Composable
fun PixelGridArt(colors: IntArray, n: Int, boxSize: Dp) {
    Canvas(modifier = Modifier.size(boxSize)) {
        val cell = this.size.minDimension / n
        val radius = cell * 0.42f
        for (y in 0 until n) {
            for (x in 0 until n) {
                val center = Offset((x + 0.5f) * cell, (y + 0.5f) * cell)
                // LED éteinte en fond, puis LED allumée à la couleur du pixel
                drawCircle(Color.White.copy(alpha = 0.06f), radius, center)
                val argb = colors[y * n + x] or 0xFF000000.toInt()
                drawCircle(Color(argb), radius, center)
            }
        }
    }
}

/** Visualiseur circulaire à points (animation simulée, pas une vraie analyse audio). */
@Composable
fun DotRing(active: Boolean, boxSize: Dp) {
    val infinite = rememberInfiniteTransition(label = "dot_ring")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "phase"
    )

    Canvas(modifier = Modifier.size(boxSize)) {
        val dots = 16
        val side = this.size.minDimension
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val baseRadius = side * 0.34f

        for (i in 0 until dots) {
            val angle = i * (2 * PI.toFloat()) / dots - PI.toFloat() / 2f
            // Amplitude 0..1, mélange de deux sinusoïdes pour un rendu organique
            val amp = if (active) {
                0.5f + 0.5f * (sin(phase * 2f + i * 0.9f) * 0.6f + sin(phase * 3f - i * 1.7f) * 0.4f)
            } else {
                0.15f
            }
            val ringRadius = baseRadius + side * 0.06f * amp
            val dotRadius = side * (0.035f + 0.045f * amp)
            val color = lerp(NeonCyan, NeonRed, i / (dots - 1f))
            drawCircle(
                color = color,
                radius = dotRadius,
                center = Offset(
                    center.x + cos(angle) * ringRadius,
                    center.y + sin(angle) * ringRadius
                )
            )
        }
        drawCircle(NeonRed.copy(alpha = 0.9f), side * 0.07f, center)
    }
}

/** Animation affichée lors d'un changement de profil sonore. */
@Composable
fun SoundEventAnimation(kind: SoundKind, eventId: Int, boxSize: Dp) {
    val pop = remember(eventId) { Animatable(0f) }
    val swing = remember(eventId) { Animatable(0f) }
    val strike = remember(eventId) { Animatable(0f) }

    LaunchedEffect(eventId) {
        launch { pop.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)) }
        launch { swing.animateTo(1f, tween(900, easing = LinearEasing)) }
        if (kind == SoundKind.SILENT) {
            // La barre apparaît après que la cloche a oscillé
            delay(380)
            strike.animateTo(1f, tween(260, easing = FastOutSlowInEasing))
        }
    }

    val infinite = rememberInfiniteTransition(label = "sound_fx")
    val wave by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "wave"
    )
    val buzz by infinite.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(60, easing = LinearEasing), RepeatMode.Reverse),
        label = "buzz"
    )

    Box(modifier = Modifier.size(boxSize), contentAlignment = Alignment.Center) {
        when (kind) {
            SoundKind.VIBRATE -> {
                // Vaguelettes qui s'étendent depuis le centre
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val c = Offset(this.size.width / 2f, this.size.height / 2f)
                    for (i in 0 until 3) {
                        val p = (wave + i / 3f) % 1f
                        drawCircle(
                            color = NeonCyan.copy(alpha = (1f - p) * 0.7f),
                            radius = this.size.minDimension * (0.25f + 0.25f * p),
                            center = c,
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Rounded.Vibration,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(boxSize * 0.6f)
                        .graphicsLayer {
                            val s = 0.3f + 0.7f * pop.value
                            translationX = buzz * 2.dp.toPx()
                            scaleX = s
                            scaleY = s
                        }
                )
            }

            SoundKind.SILENT, SoundKind.NORMAL -> {
                val oscillations = if (kind == SoundKind.NORMAL) 8f else 5f
                Icon(
                    imageVector = Icons.Rounded.Notifications,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(boxSize * 0.7f)
                        .graphicsLayer {
                            val p = swing.value
                            val s = 0.3f + 0.7f * pop.value
                            rotationZ = sin(p * PI.toFloat() * oscillations) * 18f * (1f - p)
                            transformOrigin = TransformOrigin(0.5f, 0.1f)
                            scaleX = s
                            scaleY = s
                        }
                )
                if (kind == SoundKind.SILENT) {
                    // Barre diagonale qui se trace sur la cloche
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val start = Offset(this.size.width * 0.18f, this.size.height * 0.18f)
                        val finish = Offset(this.size.width * 0.82f, this.size.height * 0.82f)
                        val t = strike.value
                        val end = Offset(
                            start.x + (finish.x - start.x) * t,
                            start.y + (finish.y - start.y) * t
                        )
                        if (t > 0f) {
                            drawLine(Color.Black, start, end, strokeWidth = 7.dp.toPx(), cap = StrokeCap.Round)
                            drawLine(NeonRed, start, end, strokeWidth = 3.5.dp.toPx(), cap = StrokeCap.Round)
                        }
                    }
                }
            }

            SoundKind.DND_ON, SoundKind.DND_OFF -> {
                val on = kind == SoundKind.DND_ON
                Icon(
                    imageVector = if (on) Icons.Rounded.DoNotDisturbOn else Icons.Rounded.DoNotDisturbOff,
                    contentDescription = null,
                    tint = if (on) NeonRed else NeonCyan,
                    modifier = Modifier
                        .size(boxSize * 0.85f)
                        .graphicsLayer {
                            val s = 0.3f + 0.7f * pop.value
                            scaleX = s
                            scaleY = s
                        }
                )
            }
        }
    }
}

/** Motif coloré affiché en aperçu quand aucune pochette n'est disponible. */
private fun demoGrid(n: Int = ART_GRID_SIZE): IntArray = IntArray(n * n) { i ->
    val x = i % n
    val y = i / n
    val hue = (x * 360f / n + y * 20f) % 360f
    android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.8f, 1f))
}