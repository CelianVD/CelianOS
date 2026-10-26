package com.example.celianos

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.sin

// ============================================================
//  Aura lumineuse façon Siri / Gemini :
//  un dégradé multicolore qui tourne doucement autour de l'écran, en couches floues
//  superposées. Il respire tout le temps, et gonfle / s'intensifie avec le son
//  (basses = bas, médiums = côtés, aigus = haut).
// ============================================================

private val GeminiBlue = Color(0xFF4285F4)
private val GeminiPurple = Color(0xFF9B72CB)
private val GeminiPink = Color(0xFFD96570)

/** Liste de couleurs décalée de `phase` : première et dernière identiques, donc boucle sans raccord. */
private fun shifted(palette: List<Color>, phase: Float): List<Color> {
    val n = palette.size
    val base = floor(phase).toInt()
    val frac = phase - base
    return List(n + 1) { k ->
        lerp(palette[(base + k) % n], palette[(base + k + 1) % n], frac)
    }
}

@Composable
fun GlowBorder(
    levels: GlowLevels,
    bass: Color,
    mid: Color,
    treble: Color,
    intensity: Float,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 44.dp
) {
    val palette = listOf(bass, GeminiBlue, mid, GeminiPurple, treble, GeminiPink)

    Canvas(modifier.fillMaxSize()) {
        val t = levels.time
        val b = levels.bass
        val m = levels.mid
        val tr = levels.treble
        val energy = b * 0.5f + m * 0.3f + tr * 0.2f

        // Respiration lente en permanence + gonflement avec la musique
        val amp = ((0.45f + 0.12f * sin(t * 0.9f) + 1.0f * energy) * intensity).coerceIn(0.1f, 1.7f)
        val grow = 0.8f + 0.6f * b

        val center = Offset(size.width / 2f, size.height / 2f)
        val brush = Brush.sweepGradient(shifted(palette, (t * 0.35f) % palette.size), center)

        // Lavis de couleur sur les bords selon la bande de fréquences
        fun wash(c: Color, level: Float) = c.copy(alpha = ((0.08f + 0.40f * level) * intensity).coerceIn(0f, 0.7f))
        val bottomH = (70.dp.toPx() + 200.dp.toPx() * b)
        val topH = (50.dp.toPx() + 130.dp.toPx() * tr)
        val sideW = (40.dp.toPx() + 110.dp.toPx() * m)
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, wash(bass, b)), startY = size.height - bottomH, endY = size.height),
            topLeft = Offset(0f, size.height - bottomH), size = Size(size.width, bottomH), blendMode = BlendMode.Plus
        )
        drawRect(
            Brush.verticalGradient(listOf(wash(treble, tr), Color.Transparent), startY = 0f, endY = topH),
            size = Size(size.width, topH), blendMode = BlendMode.Plus
        )
        drawRect(
            Brush.horizontalGradient(listOf(wash(mid, m), Color.Transparent), startX = 0f, endX = sideW),
            size = Size(sideW, size.height), blendMode = BlendMode.Plus
        )
        drawRect(
            Brush.horizontalGradient(listOf(Color.Transparent, wash(mid, m)), startX = size.width - sideW, endX = size.width),
            topLeft = Offset(size.width - sideW, 0f), size = Size(sideW, size.height), blendMode = BlendMode.Plus
        )

        // Couches de contour : du trait net au halo très diffus
        val widths = floatArrayOf(3f, 8f, 16f, 28f, 44f, 64f, 90f)
        val alphas = floatArrayOf(0.95f, 0.6f, 0.42f, 0.28f, 0.18f, 0.11f, 0.06f)
        val r = cornerRadius.toPx()
        for (i in widths.indices) {
            drawRoundRect(
                brush = brush,
                topLeft = Offset.Zero,
                size = size,
                cornerRadius = CornerRadius(r),
                style = Stroke(width = (widths[i] * grow).dp.toPx()),
                alpha = (alphas[i] * amp).coerceIn(0f, 1f),
                blendMode = BlendMode.Plus
            )
        }
    }
}