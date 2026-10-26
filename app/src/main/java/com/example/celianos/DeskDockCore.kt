package com.example.celianos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// ============================================================
//  Glow Dock : état, analyse audio (basses / médiums / aigus),
//  niveaux lissés et palette de couleurs tirée de la pochette.
// ============================================================

/** Réglages du dock : persistants (SharedPreferences, voir CelianPrefs.kt). */
object DeskDockState {
    // Affichage
    var glowEnabled by PrefState(true, "dock_glow")
    var glowIntensity by PrefState(1f, "dock_glow_intensity")
    var useSimulation by PrefState(false, "dock_simulation")      // false = Auto (vrai signal si possible)
    var blurBackground by PrefState(true, "dock_blur")
    var autoHideShortcuts by PrefState(true, "dock_autohide")
    var showWhenLocked by PrefState(false, "dock_locked")
    var antiBurnIn by PrefState(true, "dock_burnin")

    // Liaison avec l'ordinateur
    var autoLaunch by PrefState(true, "dock_autolaunch")
    var linkViaWindows by PrefState(true, "dock_link_windows")
    var linkViaUsb by PrefState(false, "dock_link_usb")
    var closeOnUnlink by PrefState(true, "dock_close_unlink")
    var linkViaAgent by PrefState(true, "dock_link_agent")
    // Posture déclenchant le lancement : 0 = paysage, 1 = à plat, 2 = tout sauf vertical, 3 = ignorer
    var postureMode by PrefState(2, "dock_posture_mode")

    // Agent PC (voir pc-agent/README.md)
    var pcEnabled by PrefState(true, "dock_pc_enabled")
    var pcIp by PrefState("", "dock_pc_ip")
    var pcPort by PrefState(8765, "dock_pc_port")
    var pcToken by PrefState("", "dock_pc_token")
    var pcAudio by PrefState(true, "dock_pc_audio")      // barres lumineuses d'après le son du PC
    var pcMedia by PrefState(true, "dock_pc_media")      // afficher / piloter la musique du PC

    // Icônes d'applis (lancent l'appli sur le PC) : 0 = aucune, 1 = gauche, 2 = droite
    var appsSide by PrefState(0, "dock_apps_side")
    // Disposition : 0 = lecteur + colonne d'icônes, 1 = mini lecteur + grille, 2 = grille seule (Stream Deck)
    var dockLayout by PrefState(0, "dock_layout")
    var appsCount by PrefState(4, "dock_apps_count")          // nombre de boutons (1..12)
    var appsData by PrefState("", "dock_apps_data")           // 12 lignes « id \t nom »
    var app0Id by PrefState("", "dock_app0_id"); var app0Name by PrefState("", "dock_app0_name"); var app0Icon by PrefState("apps", "dock_app0_icon")
    var app1Id by PrefState("", "dock_app1_id"); var app1Name by PrefState("", "dock_app1_name"); var app1Icon by PrefState("apps", "dock_app1_icon")
    var app2Id by PrefState("", "dock_app2_id"); var app2Name by PrefState("", "dock_app2_name"); var app2Icon by PrefState("apps", "dock_app2_icon")
    var app3Id by PrefState("", "dock_app3_id"); var app3Name by PrefState("", "dock_app3_name"); var app3Icon by PrefState("apps", "dock_app3_icon")

    /** Vrai pendant que le dock est à l'écran (la Dynamic Island se masque). Non persistant. */
    var dockOpen by mutableStateOf(false)
}

/**
 * Capte le son sortant du téléphone avec android.media.audiofx.Visualizer (session 0 = mix global)
 * et le réduit à trois niveaux 0..1 : basses (<250 Hz), médiums (250 Hz–4 kHz), aigus (4–16 kHz).
 * Nécessite la permission micro (RECORD_AUDIO). Si la capture échoue (permission refusée, appli
 * qui bloque la capture, constructeur qui ne supporte pas), `isLive` reste faux et le dock
 * bascule sur une animation simulée.
 */
object AudioReactor {
    @Volatile var rawBass = 0f
    @Volatile var rawMid = 0f
    @Volatile var rawTreble = 0f

    var isLive by mutableStateOf(false)

    private var visualizer: Visualizer? = null

    // Gain automatique : suit le pic récent de chaque bande, donc indépendant du volume
    private var peakBass = 1f
    private var peakMid = 1f
    private var peakTreble = 1f

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    fun start(context: Context): Boolean {
        if (visualizer != null) return true
        if (!hasPermission(context)) return false
        return try {
            val v = Visualizer(0)
            v.enabled = false
            v.captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
            v.setDataCaptureListener(listener, Visualizer.getMaxCaptureRate() / 2, false, true)
            v.enabled = true
            visualizer = v
            isLive = true
            true
        } catch (e: Exception) {
            visualizer = null
            isLive = false
            false
        }
    }

    fun stop() {
        runCatching {
            visualizer?.enabled = false
            visualizer?.release()
        }
        visualizer = null
        isLive = false
        rawBass = 0f; rawMid = 0f; rawTreble = 0f
    }

    private fun level(avg: Float, peak: Float, floor: Float): Float =
        (avg / maxOf(peak, floor)).coerceIn(0f, 1f).pow(1.6f)

    private val listener = object : Visualizer.OnDataCaptureListener {
        override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}

        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
            if (fft == null) return
            val n = fft.size
            // fft[0], fft[1] = continu / Nyquist ; ensuite paires (réel, imaginaire) par case
            val hzPerBin = samplingRate / 1000f / n
            var bSum = 0f; var bCnt = 0
            var mSum = 0f; var mCnt = 0
            var tSum = 0f; var tCnt = 0
            for (k in 1 until n / 2) {
                val re = fft[2 * k].toInt()
                val im = fft[2 * k + 1].toInt()
                val mag = sqrt((re * re + im * im).toFloat())
                val f = k * hzPerBin
                when {
                    f < 250f -> { bSum += mag; bCnt++ }
                    f < 4000f -> { mSum += mag; mCnt++ }
                    f < 16000f -> { tSum += mag; tCnt++ }
                }
            }
            val b = if (bCnt > 0) bSum / bCnt else 0f
            val m = if (mCnt > 0) mSum / mCnt else 0f
            val t = if (tCnt > 0) tSum / tCnt else 0f

            peakBass = maxOf(b, peakBass * 0.992f)
            peakMid = maxOf(m, peakMid * 0.992f)
            peakTreble = maxOf(t, peakTreble * 0.992f)

            rawBass = level(b, peakBass, 12f)
            rawMid = level(m, peakMid, 6f)
            rawTreble = level(t, peakTreble, 3f)
        }
    }
}

/** Niveaux affichés (lissés à chaque image). Lus uniquement pendant le dessin : pas de recomposition. */
@Stable
class GlowLevels {
    var bass by mutableFloatStateOf(0f)
    var mid by mutableFloatStateOf(0f)
    var treble by mutableFloatStateOf(0f)
    var time by mutableFloatStateOf(0f)   // secondes, sert de phase aux vagues
}

private fun approach(current: Float, target: Float, dt: Float): Float {
    // Attaque rapide, retombée plus lente : donne l'effet de « vague » fluide
    val speed = if (target > current) 28f else 7f
    return current + (target - current) * (1f - exp(-dt * speed))
}

/** Animation de secours quand on n'a pas le vrai signal audio. */
private fun simulate(t: Float): Triple<Float, Float, Float> {
    val beat = 0.5f + 0.5f * sin(t * 2f * PI.toFloat() * 1.9f)
    val bass = 0.15f + 0.85f * beat.pow(4f)
    val mid = 0.25f + 0.35f * (0.5f + 0.5f * sin(t * 2.3f)) * (0.6f + 0.4f * sin(t * 0.7f))
    val treble = 0.15f + 0.30f * (0.5f + 0.5f * sin(t * 6.1f + 1.3f)) * (0.5f + 0.5f * sin(t * 1.1f))
    return Triple(bass, mid, treble)
}

@Composable
fun rememberGlowLevels(playing: Boolean): GlowLevels {
    val levels = remember { GlowLevels() }
    val playingState = rememberUpdatedState(playing)
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1_000_000_000f).coerceAtMost(0.1f)
                last = now
                val t = now / 1_000_000_000f

                val live = AudioReactor.isLive && !DeskDockState.useSimulation
                // Le son sort du PC : on utilise les niveaux envoyés par l'agent PC
                val pcLive = PcAgent.connected && DeskDockState.pcAudio && PcAgent.levelsLive
                val target = when {
                    pcLive -> Triple(PcAgent.bass, PcAgent.mid, PcAgent.treble)
                    !playingState.value -> Triple(0f, 0f, 0f)
                    live -> Triple(AudioReactor.rawBass, AudioReactor.rawMid, AudioReactor.rawTreble)
                    else -> simulate(t)
                }
                levels.bass = approach(levels.bass, target.first, dt)
                levels.mid = approach(levels.mid, target.second, dt)
                levels.treble = approach(levels.treble, target.third, dt)
                levels.time = t
            }
        }
    }
    return levels
}

// --- Palette tirée de la pochette ---

data class GlowPalette(val bass: Color, val mid: Color, val treble: Color)

val DefaultGlowPalette = GlowPalette(
    bass = Color(0xFFFF2D6F),
    mid = Color(0xFFB388FF),
    treble = Color(0xFF00E5FF)
)

/** Prend la couleur la plus vive de la pochette (grille 8x8) et en dérive trois teintes. */
fun paletteFromGrid(grid: IntArray?): GlowPalette {
    if (grid == null) return DefaultGlowPalette
    val hsv = FloatArray(3)
    var bestScore = 0f
    var bestHue = 0f
    var bestSat = 0f
    for (px in grid) {
        android.graphics.Color.colorToHSV(px, hsv)
        val score = hsv[1] * hsv[2]
        if (score > bestScore) {
            bestScore = score
            bestHue = hsv[0]
            bestSat = hsv[1]
        }
    }
    if (bestScore < 0.2f) return DefaultGlowPalette
    return GlowPalette(
        bass = Color.hsv(bestHue, bestSat.coerceIn(0.6f, 1f), 1f),
        mid = Color.hsv((bestHue + 40f) % 360f, 0.7f, 1f),
        treble = Color.hsv((bestHue + 170f) % 360f, 0.55f, 1f)
    )
}