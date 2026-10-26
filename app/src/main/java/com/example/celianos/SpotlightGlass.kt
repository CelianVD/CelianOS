package com.example.celianos

import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ============================================================
//  Trois styles pour Spotlight (SpotlightState.glassStyle, même ordre que SP_STYLE_NAMES)
//   0 = Translucide : voile blanc uni + fin liseré (style d'origine)
//   1 = Matériel    : surfaces tonales Material 3 (couleurs dynamiques du fond d'écran sur Android 12+,
//                     sinon accent choisi), opaques par défaut, sans bordure
//   2 = Brutaliste  : aplats opaques, bordure épaisse, ombre dure décalée, coins peu arrondis, ni flou ni transparence
// ============================================================

const val STYLE_TRANSLUCENT = 0
const val STYLE_MATERIAL = 1
const val STYLE_BRUTAL = 2

/** Noms affichés (réglages). Même ordre que les constantes ci-dessus. */
val SP_STYLE_NAMES = listOf("Translucide", "Matériel", "Brutaliste")

val DEFAULT_GLOW = Color(0xFF7C8CFF)

/** Couleur dominante du fond d'écran (Android 8.1+, sans autorisation), éclaircie pour rester visible. */
fun wallpaperGlow(ctx: Context): Color = runCatching {
    if (Build.VERSION.SDK_INT >= 27) {
        WallpaperManager.getInstance(ctx).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            ?.primaryColor?.toArgb()?.let { lerp(Color(it), Color.White, 0.25f) }
    } else null
}.getOrNull() ?: DEFAULT_GLOW

/** Couleur dominante brute du fond d'écran (Android 8.1+), ou null. Sert à la couleur d'accent « fond d'écran ». */
fun wallpaperPrimary(ctx: Context): Color? = runCatching {
    if (Build.VERSION.SDK_INT >= 27) {
        WallpaperManager.getInstance(ctx).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            ?.primaryColor?.toArgb()?.let { Color(it) }
    } else null
}.getOrNull()

/**
 * `theme` porte le style, le thème clair/sombre et l'opacité (1.0 = d'origine). Par défaut : rendu d'origine.
 * `dark` désigne une surface « fenêtre » plus marquée (popups), pas le thème.
 * `base` (0,13 résultat, 0,14 widget, 0,16 barre) sert en Matériel à choisir le niveau de surface :
 * barre = conteneur haut, popup = conteneur le plus haut, le reste = conteneur normal.
 */
fun Modifier.spotGlassStyle(
    shape: Shape,
    base: Float = 0.14f,
    dark: Boolean = false,
    theme: SpotTheme = SpotTheme.Default
): Modifier {
    val night = theme.dark
    val k = theme.opacity
    val ink = theme.ink

    if (theme.style == STYLE_BRUTAL) {
        return spotBrutalBlock(
            shape, theme.brutalFill, theme.brutalBorder, theme.brutalShadow,
            theme.borderWidth, theme.shadowOffset
        )
    }

    val tones = theme.tones
    if (theme.style == STYLE_MATERIAL && tones != null) {
        val fill = when {
            dark -> tones.containerHighest
            base >= 0.15f -> tones.containerHigh
            else -> tones.container
        }
        val alpha = if (dark) k.coerceIn(0.55f, 1f) else k.coerceIn(0.35f, 1f)
        return clip(shape).background(fill.copy(alpha = alpha))
    }

    val fill = when {
        dark -> (if (night) Color(0xFF181A22) else Color(0xFFF7F8FB)).copy(alpha = (0.94f * k).coerceIn(0.55f, 0.98f))
        night -> Color.White.copy(alpha = (base * k).coerceIn(0f, 1f))
        else -> Color.White.copy(alpha = (base * 2.8f * k).coerceIn(0f, 0.92f)) // voile plus dense en thème clair
    }
    val edge = if (night) Color.White.copy(alpha = if (dark) 0.22f else 0.2f) else ink.copy(alpha = 0.14f)
    return clip(shape)
        .background(fill)
        .border(1.dp, edge, shape)
}

/**
 * Bloc « brutaliste » : un rectangle décalé (ombre dure, sans flou) dessiné derrière, puis l'aplat et sa bordure épaisse.
 * Compose n'a pas d'ombre dure native : l'ombre est tracée avant le clip, donc elle déborde de `offset` en bas à droite.
 */
fun Modifier.spotBrutalBlock(
    shape: Shape,
    fill: Color,
    border: Color,
    shadow: Color,
    borderWidth: Dp = 3.dp,
    offset: Dp = 6.dp
): Modifier = this
    .drawBehind {
        val o = offset.toPx()
        val path = Path()
        when (val outline = shape.createOutline(size, layoutDirection, this)) {
            is Outline.Rectangle -> path.addRect(outline.rect)
            is Outline.Rounded -> path.addRoundRect(outline.roundRect)
            is Outline.Generic -> path.addPath(outline.path)
        }
        translate(o, o) { drawPath(path, shadow) }
    }
    .clip(shape)
    .background(fill)
    .border(borderWidth, border, shape)

/** Applique le style, le thème, l'opacité et l'accent choisis dans les réglages (recompose quand ils changent). */
@Composable
fun Modifier.spotGlass(shape: Shape, base: Float = 0.14f, dark: Boolean = false): Modifier =
    spotGlassStyle(shape, base, dark, LocalSpotTheme.current)