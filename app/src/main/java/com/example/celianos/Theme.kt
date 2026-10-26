package com.example.celianos

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val DarkBackground = Color(0xFF0D0E12)
val GlassSurface = Color(0xFF1E2029).copy(alpha = 0.6f)
val GlassBorder = Color(0xFFFFFFFF).copy(alpha = 0.15f)
val NeonRed = Color(0xFFD71921)
val NeonCyan = Color(0xFF00E5FF)

@Composable
fun CelianOSTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = DarkBackground,
            surface = GlassSurface
        ),
        content = content
    )
}

// ============================================================
//  Thème de Spotlight : clair / sombre / automatique, accent,
//  rayon des coins, opacité du verre, style (Translucide, Matériel,
//  Brutaliste). Les valeurs d'origine (Translucide, sombre, cyan,
//  100 %, 100 %) redonnent exactement l'ancien rendu.
// ============================================================

/** Couleurs d'accent proposées (le cyan, en premier, est la valeur d'origine). */
val ACCENT_PRESETS = listOf(
    "Cyan" to NeonCyan,
    "Rouge" to NeonRed,
    "Violet" to Color(0xFFB388FF),
    "Bleu" to Color(0xFF4D8DFF),
    "Vert" to Color(0xFF30D158),
    "Orange" to Color(0xFFFF9F0A),
    "Rose" to Color(0xFFFF5FA2)
)

/** 0 = automatique, 1 = clair, 2 = sombre (même ordre que SpotlightState.themeMode). */
val THEME_MODES = listOf("Automatique", "Clair", "Sombre")

private val INK_LIGHT = Color(0xFF14161D)

/** Rayons du style Brutaliste = rayons d'origine x 0,25 (le curseur « Rayon des coins » s'applique par-dessus). */
private const val BRUTAL_CORNER_BASE = 0.25f

private fun readableOn(c: Color) = if (c.luminance() > 0.5f) Color.Black else Color.White

/**
 * Rôles de couleur du style Matériel : issus du schéma dynamique d'Android 12+ (fond d'écran),
 * ou calculés à partir de l'accent choisi quand les couleurs dynamiques n'existent pas.
 */
@Immutable
class MaterialTones(
    val primary: Color,
    val onPrimary: Color,
    val surface: Color,
    val onSurface: Color,
    val container: Color,
    val containerHigh: Color,
    val containerHighest: Color
)

/** Repli (Android 11 et moins) : neutres Material teintés par l'accent, sans dépendance supplémentaire. */
private fun seedTones(seed: Color, dark: Boolean): MaterialTones {
    fun t(neutral: Long, mix: Float) = lerp(Color(neutral), seed, mix)
    return if (dark) {
        val p = lerp(seed, Color.White, 0.30f)
        MaterialTones(
            primary = p, onPrimary = readableOn(p),
            surface = t(0xFF121316, 0.05f), onSurface = Color(0xFFE4E2E6),
            container = t(0xFF1E1F23, 0.07f), containerHigh = t(0xFF282A2E, 0.09f),
            containerHighest = t(0xFF333539, 0.11f)
        )
    } else {
        val p = lerp(seed, Color.Black, 0.25f)
        MaterialTones(
            primary = p, onPrimary = readableOn(p),
            surface = t(0xFFFAF9FD, 0.04f), onSurface = Color(0xFF1B1B1F),
            container = t(0xFFEFEDF1, 0.06f), containerHigh = t(0xFFE9E7EB, 0.08f),
            containerHighest = t(0xFFE3E1E6, 0.10f)
        )
    }
}

/** Couleurs dynamiques (API 31+) via les API officielles de Material 3 ; null si indisponibles. */
private fun dynamicTones(ctx: android.content.Context, dark: Boolean): MaterialTones? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    return runCatching {
        val cs = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        MaterialTones(
            primary = cs.primary, onPrimary = cs.onPrimary,
            surface = cs.surface, onSurface = cs.onSurface,
            container = cs.surfaceContainer, containerHigh = cs.surfaceContainerHigh,
            containerHighest = cs.surfaceContainerHighest
        )
    }.getOrNull()
}

@Immutable
class SpotTheme(
    val dark: Boolean,
    /** Accent adapté au thème (un peu plus foncé en thème clair). */
    val accent: Color,
    /** Accent « vif », pour les fonds sombres (page de réglages). */
    val accentVivid: Color,
    /** 1.0 = rayons d'origine du style. */
    val corner: Float,
    /** 1.0 = opacité d'origine (forcée à 1.0 en Brutaliste, plafonnée à 1.0 en Matériel). */
    val opacity: Float,
    /** STYLE_TRANSLUCENT, STYLE_MATERIAL ou STYLE_BRUTAL. */
    val style: Int = STYLE_TRANSLUCENT,
    /** Rôles Matériel (non nul seulement pour STYLE_MATERIAL). */
    val tones: MaterialTones? = null
) {
    private val m: MaterialTones? get() = if (style == STYLE_MATERIAL) tones else null

    /** Couleur du texte, des icônes et des voiles : blanc en thème sombre, encre en thème clair (onSurface en Matériel). */
    val ink: Color get() = m?.onSurface ?: if (dark) Color.White else INK_LIGHT
    val onAccent: Color get() = m?.onPrimary ?: readableOn(accent)
    val popupBackdrop: Color get() = Color.Black.copy(alpha = if (dark) 0.45f else 0.28f)

    // Brutaliste : aplats opaques, bordure épaisse, ombre dure décalée
    val brutalFill: Color get() = if (dark) Color(0xFF1B1B1B) else Color.White
    val brutalBorder: Color get() = if (dark) Color.White else Color(0xFF111111)
    val brutalShadow: Color get() = if (dark) accentVivid else Color(0xFF111111)
    val borderWidth: Dp get() = if (style == STYLE_BRUTAL) 3.dp else 1.dp

    /** Décalage de l'ombre dure : sert aussi de marge aux listes pour qu'elle ne soit pas coupée. */
    val shadowOffset: Dp get() = if (style == STYLE_BRUTAL) 6.dp else 0.dp

    fun shape(dp: Int) = RoundedCornerShape((dp * corner).dp)

    /** Voile posé sur le fond (flouté par la fenêtre si blurOn, sinon plus opaque). Opaque en Brutaliste. */
    fun scrim(blurOn: Boolean): Color {
        if (style == STYLE_BRUTAL) return if (dark) Color(0xFF0F0F0F) else Color(0xFFF5F0E6)
        m?.let { t ->
            return when {
                dark && blurOn -> Color.Black.copy(alpha = 0.32f)
                dark -> t.surface.copy(alpha = 0.92f)
                blurOn -> t.surface.copy(alpha = 0.40f)
                else -> t.surface.copy(alpha = 0.92f)
            }
        }
        return when {
            dark && blurOn -> Color.Black.copy(alpha = 0.30f)
            dark -> Color(0xFF0D0E12).copy(alpha = 0.88f)
            blurOn -> Color.White.copy(alpha = 0.38f)
            else -> Color(0xFFF2F3F7).copy(alpha = 0.88f)
        }
    }

    companion object {
        val Default = SpotTheme(true, NeonCyan, NeonCyan, 1f, 1f)
    }
}

val LocalSpotTheme = compositionLocalOf { SpotTheme.Default }

/**
 * Thème des pages de réglages (toujours sombres) : non nul quand un style autre que Translucide est choisi.
 * GlassCard, SliderCard, ChoiceCard… le lisent pour suivre le style.
 */
val LocalSettingsTheme = compositionLocalOf<SpotTheme?> { null }

/** Couleur du texte et des voiles du thème courant (remplace Color.White dans Spotlight). */
val SpotInk: Color
    @Composable get() = LocalSpotTheme.current.ink

/** Coin arrondi de `dp` dp, multiplié par le réglage « Rayon des coins ». */
@Composable
fun spotShape(dp: Int): RoundedCornerShape = LocalSpotTheme.current.shape(dp)

/**
 * Lit les réglages en direct : toute modification recompose les écrans qui l'utilisent.
 * `style` permet de calculer le thème d'un autre style (aperçus) ; `forceDark` impose le thème sombre (pages de réglages).
 */
@Composable
fun rememberSpotTheme(
    style: Int = SpotlightState.glassStyle.coerceIn(0, STYLE_BRUTAL),
    forceDark: Boolean? = null
): SpotTheme {
    val ctx = LocalContext.current
    val system = isSystemInDarkTheme()
    val s = SpotlightState
    val dark = forceDark ?: when (s.themeMode) { 1 -> false; 2 -> true; else -> system }
    val fromWall = s.accentFromWallpaper
    val idx = s.accentIndex.coerceIn(0, ACCENT_PRESETS.lastIndex)
    val wallpaper = remember(ctx, fromWall) { if (fromWall) wallpaperPrimary(ctx) else null }
    val vivid = if (wallpaper != null) lerp(wallpaper, Color.White, 0.25f) else ACCENT_PRESETS[idx].second
    val accent = if (dark) vivid else lerp(vivid, Color.Black, 0.18f)
    val cornerBase = if (style == STYLE_BRUTAL) BRUTAL_CORNER_BASE else 1f
    val corner = s.cornerPct.coerceIn(0f, 150f) / 100f * cornerBase
    val k = s.glassOpacityPct.coerceIn(20f, 200f) / 100f
    val opacity = when (style) { STYLE_BRUTAL -> 1f; STYLE_MATERIAL -> k.coerceAtMost(1f); else -> k }

    if (style == STYLE_MATERIAL) {
        // dynamique si possible (accent et fond d'écran réglables ignorés), sinon accent de l'utilisateur
        val tones = remember(ctx, dark, vivid) { dynamicTones(ctx, dark) ?: seedTones(vivid, dark) }
        val vividTones = remember(ctx, vivid) { dynamicTones(ctx, true) ?: seedTones(vivid, true) }
        return SpotTheme(dark, tones.primary, vividTones.primary, corner, opacity, style, tones)
    }
    return SpotTheme(dark, accent, vivid, corner, opacity, style)
}

/** Thème de l'activité Spotlight : schéma Material clair ou sombre + SpotTheme pour les composants maison. */
@Composable
fun SpotlightTheme(content: @Composable () -> Unit) {
    val t = rememberSpotTheme()
    MaterialTheme(
        colorScheme = if (t.dark) darkColorScheme(
            background = DarkBackground,
            surface = GlassSurface,
            primary = t.accent
        ) else lightColorScheme(
            background = Color(0xFFF2F3F7),
            surface = Color.White.copy(alpha = 0.6f),
            primary = t.accent
        )
    ) {
        CompositionLocalProvider(LocalSpotTheme provides t, content = content)
    }
}