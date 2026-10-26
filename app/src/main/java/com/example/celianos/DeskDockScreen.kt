package com.example.celianos

import android.graphics.Bitmap
import android.media.AudioManager
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ============================================================
//  Glow Dock : lecteur plein écran pour téléphone posé sur un support.
//  Fond = pochette floutée, panneau de verre, pochette qui pulse sur les
//  basses, bordures lumineuses, raccourcis qui s'effacent après quelques secondes.
// ============================================================

private val DimLevels = floatArrayOf(-1f, 0.35f, 0.08f)   // -1f = luminosité du système

@Composable
fun DeskDockScreen(
    onExit: () -> Unit,
    onOpenApp: () -> Unit,
    onDim: (Float) -> Unit
) {
    val playing = DockMedia.isPlaying
    val hasMedia = DockMedia.title != null
    val art = DockMedia.artBitmap

    val palette = remember(DockMedia.artGrid) { paletteFromGrid(DockMedia.artGrid) }
    val bassC by animateColorAsState(palette.bass, tween(900), label = "bass")
    val midC by animateColorAsState(palette.mid, tween(900), label = "mid")
    val trebleC by animateColorAsState(palette.treble, tween(900), label = "treble")

    val levels = rememberGlowLevels(playing)

    // Raccourcis discrets : visibles après un toucher, puis ils s'effacent
    var lastTouch by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    val shortcutsVisible = !DeskDockState.autoHideShortcuts || now - lastTouch < 6000L

    var dimIndex by remember { mutableIntStateOf(0) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        lastTouch = SystemClock.elapsedRealtime()
                    }
                }
            }
    ) {
        DockBackground(art = art, levels = levels, tint = bassC)

        if (DeskDockState.glowEnabled) {
            GlowBorder(
                levels = levels,
                bass = bassC,
                mid = midC,
                treble = trebleC,
                intensity = DeskDockState.glowIntensity
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 28.dp, vertical = 24.dp)
        ) {
            val landscape = maxWidth > maxHeight
            val hasApps = DockApps.filledSlots.isNotEmpty()
            val mode = if (hasApps) DeskDockState.dockLayout else 0
            val railSide = DeskDockState.appsSide
            val showRail = mode == 0 && railSide != 0 && hasApps

            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // Dérive très lente pour ménager l'écran AMOLED : décale tout l'ensemble (rails compris)
                        if (DeskDockState.antiBurnIn) {
                            translationX = sin(levels.time / 37f) * 10.dp.toPx()
                            translationY = cos(levels.time / 53f) * 10.dp.toPx()
                        }
                    }
            ) {
                when (mode) {
                    2 -> DockAppGrid(Modifier.fillMaxSize().padding(top = 56.dp))   // Stream Deck : que des touches
                    1 -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DockAppGrid(Modifier.weight(1f).fillMaxWidth().padding(top = 56.dp))
                        MiniPlayer(art, bassC, Modifier.fillMaxWidth().height(92.dp))
                    }
                    else -> Row(
                        Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (showRail && railSide == 1) DockAppRail(Modifier.fillMaxHeight())
                        DockGlassPanel(
                            accent = bassC,
                            modifier = Modifier
                                .weight(1f)
                                .then(
                                    if (landscape) Modifier.fillMaxHeight(0.8f)
                                    else Modifier.fillMaxHeight(0.86f)
                                )
                        ) {
                            if (landscape) {
                                Row(
                                    modifier = Modifier.fillMaxSize().padding(28.dp),
                                    horizontalArrangement = Arrangement.spacedBy(36.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Artwork(art, levels, bassC, Modifier.fillMaxHeight().aspectRatio(1f))
                                    PlayerColumn(
                                        accent = bassC,
                                        centered = false,
                                        modifier = Modifier.weight(1f).fillMaxHeight()
                                    )
                                }
                            } else {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(28.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(24.dp)
                                ) {
                                    Artwork(art, levels, bassC, Modifier.weight(1f, fill = false).aspectRatio(1f))
                                    PlayerColumn(accent = bassC, centered = true, modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                        if (showRail && railSide == 2) DockAppRail(Modifier.fillMaxHeight())
                    }
                }
            }
        }

        // Raccourcis en haut à droite
        AnimatedVisibility(
            visible = shortcutsVisible,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(600)),
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Row(
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ShortcutButton(Icons.Rounded.GraphicEq, DeskDockState.glowEnabled, "Bordures lumineuses") {
                    DeskDockState.glowEnabled = !DeskDockState.glowEnabled
                }
                ShortcutButton(
                    if (dimIndex == 0) Icons.Rounded.BrightnessMedium else Icons.Rounded.BrightnessLow,
                    dimIndex != 0,
                    "Luminosité"
                ) {
                    dimIndex = (dimIndex + 1) % DimLevels.size
                    onDim(DimLevels[dimIndex])
                }
                if (hasMedia) {
                    ShortcutButton(Icons.Rounded.OpenInNew, false, "Ouvrir l'appli de musique", onOpenApp)
                }
                ShortcutButton(Icons.Rounded.Close, false, "Quitter le dock", onExit)
            }
        }
    }
}

// ------------------------------------------------------------
//  Mini lecteur (disposition Stream Deck)
// ------------------------------------------------------------
@Composable
private fun MiniPlayer(art: Bitmap?, accent: Color, modifier: Modifier) {
    DockGlassPanel(accent = accent, modifier = modifier, corner = 28.dp) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(Modifier.fillMaxHeight().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.12f))) {
                if (art != null) {
                    Image(
                        bitmap = remember(art) { art.asImageBitmap() }, contentDescription = null,
                        contentScale = ContentScale.Crop, filterQuality = FilterQuality.High,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.Center).size(30.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    DockMedia.title ?: "Aucune musique", maxLines = 1, modifier = Modifier.basicMarquee(),
                    style = MaterialTheme.typography.titleMedium.copy(color = Color.White, fontWeight = FontWeight.Bold)
                )
                if (!DockMedia.artist.isNullOrBlank()) {
                    Text(DockMedia.artist ?: "", maxLines = 1, style = MaterialTheme.typography.bodyMedium.copy(color = Color.White.copy(alpha = 0.65f)))
                }
            }
            if (DockMedia.title != null) {
                PressIcon(Icons.Rounded.SkipPrevious, "Précédent", 30.dp) { DockMedia.skipPrevious() }
                PressIcon(if (DockMedia.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Lecture / pause", 38.dp) { DockMedia.togglePlayPause() }
                PressIcon(Icons.Rounded.SkipNext, "Suivant", 30.dp) { DockMedia.skipNext() }
            }
        }
    }
}

// ------------------------------------------------------------
//  Fond : pochette floutée qui respire avec les basses
// ------------------------------------------------------------
@Composable
private fun DockBackground(art: Bitmap?, levels: GlowLevels, tint: Color) {
    Box(Modifier.fillMaxSize()) {
        Crossfade(targetState = art, animationSpec = tween(900), label = "bg") { bitmap ->
            if (bitmap != null) {
                // Version minuscule agrandie : déjà douce sur tous les Android, floutée en plus dès Android 12
                val small = remember(bitmap) {
                    Bitmap.createScaledBitmap(bitmap, 64, 64, true).asImageBitmap()
                }
                Image(
                    bitmap = small,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Medium,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val s = 1.25f + 0.03f * levels.bass
                            scaleX = s
                            scaleY = s
                        }
                        .then(
                            if (DeskDockState.blurBackground) Modifier.blur(40.dp, BlurredEdgeTreatment.Unbounded)
                            else Modifier
                        )
                )
            } else {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.radialGradient(listOf(tint.copy(alpha = 0.35f), Color.Black))
                    )
                )
            }
        }
        // Voile sombre pour garder le texte lisible
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.65f)))
            )
        )
    }
}

// ------------------------------------------------------------
//  Panneau de verre
// ------------------------------------------------------------
@Composable
private fun DockGlassPanel(
    accent: Color,
    modifier: Modifier = Modifier,
    corner: Dp = 44.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(corner)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.10f))
                )
            )
            .border(
                1.2.dp,
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.65f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.30f))
                ),
                shape
            )
    ) {
        // Reflet spéculaire en haut du verre
        Box(
            Modifier.fillMaxWidth().height(100.dp).background(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f), Color.Transparent))
            )
        )
        content()
    }
}

// ------------------------------------------------------------
//  Pochette : pulse sur les basses, s'incline au doigt
// ------------------------------------------------------------
@Composable
private fun Artwork(art: Bitmap?, levels: GlowLevels, accent: Color, modifier: Modifier) {
    val shape = RoundedCornerShape(30.dp)
    val rotX = remember { Animatable(0f) }
    val rotY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .graphicsLayer {
                rotationX = rotX.value
                rotationY = rotY.value
                cameraDistance = 16f * density
                val s = 1f + 0.04f * levels.bass
                scaleX = s
                scaleY = s
            }
            .clip(shape)
            .border(
                1.dp,
                Brush.linearGradient(listOf(Color.White.copy(alpha = 0.6f), Color.White.copy(alpha = 0.05f))),
                shape
            )
            .pointerInput(Unit) {
                val release: () -> Unit = {
                    scope.launch { rotX.animateTo(0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)) }
                    scope.launch { rotY.animateTo(0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)) }
                }
                detectDragGestures(
                    onDragEnd = release,
                    onDragCancel = release,
                    onDrag = { change, drag ->
                        change.consume()
                        scope.launch {
                            rotY.snapTo((rotY.value + drag.x * 0.12f).coerceIn(-16f, 16f))
                            rotX.snapTo((rotX.value - drag.y * 0.12f).coerceIn(-16f, 16f))
                        }
                    }
                )
            }
    ) {
        Crossfade(targetState = art, animationSpec = tween(600), label = "art") { bitmap ->
            if (bitmap != null) {
                val image = remember(bitmap) { bitmap.asImageBitmap() }
                Image(
                    bitmap = image,
                    contentDescription = "Pochette",
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.linearGradient(listOf(accent.copy(alpha = 0.8f), Color(0xFF1E2029)))
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.fillMaxSize(0.35f))
                }
            }
        }
        // Reflet de verre sur la pochette
        Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.22f), Color.Transparent, Color.Transparent),
                    start = Offset.Zero,
                    end = Offset(600f, 600f)
                )
            )
        )
    }
}

// ------------------------------------------------------------
//  Colonne lecteur : titre, progression, commandes, volume
// ------------------------------------------------------------
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerColumn(accent: Color, centered: Boolean, modifier: Modifier) {
    val title = DockMedia.title
    val artist = DockMedia.artist
    val playing = DockMedia.isPlaying
    val align = if (centered) Alignment.CenterHorizontally else Alignment.Start
    val textAlign = if (centered) TextAlign.Center else TextAlign.Start

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = align
    ) {
        if (title != null) {
            Text(
                text = title,
                maxLines = 1,
                textAlign = textAlign,
                modifier = Modifier.fillMaxWidth().basicMarquee(),
                style = MaterialTheme.typography.headlineMedium.copy(
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 30.sp
                )
            )
            if (!artist.isNullOrBlank()) {
                Text(
                    text = artist,
                    maxLines = 1,
                    textAlign = textAlign,
                    modifier = Modifier.fillMaxWidth().basicMarquee(),
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = Color.White.copy(alpha = 0.65f), fontSize = 18.sp
                    )
                )
            }
            Spacer(Modifier.height(24.dp))
            SeekBar(accent)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                PressIcon(Icons.Rounded.SkipPrevious, "Précédent", 40.dp) { DockMedia.skipPrevious() }
                Spacer(Modifier.width(20.dp))
                PlayButton(playing)
                Spacer(Modifier.width(20.dp))
                PressIcon(Icons.Rounded.SkipNext, "Suivant", 40.dp) { DockMedia.skipNext() }
            }
            Spacer(Modifier.height(18.dp))
            VolumeRow()
        } else {
            DockClock(textAlign)
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Aucune musique en cours",
                textAlign = textAlign,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyLarge.copy(color = Color.White.copy(alpha = 0.55f))
            )
        }
    }
}

@Composable
private fun DockClock(textAlign: TextAlign) {
    var time by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            time = LocalDateTime.now()
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }
    val hour = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val date = remember { DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()) }
    Text(
        text = time.format(hour),
        textAlign = textAlign,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.displayLarge.copy(
            color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Thin
        )
    )
    Text(
        text = time.format(date).replaceFirstChar { it.uppercase() },
        textAlign = textAlign,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.titleMedium.copy(color = Color.White.copy(alpha = 0.7f))
    )
}

// ------------------------------------------------------------
//  Barre de progression (toucher ou glisser pour avancer)
// ------------------------------------------------------------
@Composable
private fun SeekBar(accent: Color) {
    val duration = DockMedia.durationMs
    var tick by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(DockMedia.isPlaying, DockMedia.positionTimeMs) {
        tick = SystemClock.elapsedRealtime()
        while (DockMedia.isPlaying) {
            delay(250)
            tick = SystemClock.elapsedRealtime()
        }
    }

    val speed = if (DockMedia.speed > 0f) DockMedia.speed else 1f
    val rawPosition = if (DockMedia.isPlaying) {
        DockMedia.positionMs + ((tick - DockMedia.positionTimeMs) * speed).toLong()
    } else {
        DockMedia.positionMs
    }
    val position = rawPosition.coerceIn(0L, maxOf(duration, 0L))

    // Flux en direct ou durée inconnue : pas de barre
    if (duration <= 0L) return

    val fraction = dragFraction ?: (position.toFloat() / duration)
    val thumbRadius by animateFloatAsState(if (dragFraction != null) 9f else 0f, label = "thumb")

    Column(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .pointerInput(duration) {
                    detectTapGestures { offset ->
                        DockMedia.seekTo(((offset.x / size.width).coerceIn(0f, 1f) * duration).toLong())
                    }
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> dragFraction = (offset.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            dragFraction?.let { DockMedia.seekTo((it * duration).toLong()) }
                            dragFraction = null
                        },
                        onDragCancel = { dragFraction = null },
                        onHorizontalDrag = { change, _ ->
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        }
                    )
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val h = 5.dp.toPx()
                val cy = size.height / 2f
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.2f),
                    topLeft = Offset(0f, cy - h / 2),
                    size = androidx.compose.ui.geometry.Size(size.width, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2)
                )
                drawRoundRect(
                    brush = Brush.horizontalGradient(listOf(accent, Color.White)),
                    topLeft = Offset(0f, cy - h / 2),
                    size = androidx.compose.ui.geometry.Size(size.width * fraction.coerceIn(0f, 1f), h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2)
                )
                if (thumbRadius > 0.5f) {
                    drawCircle(Color.White, thumbRadius.dp.toPx(), Offset(size.width * fraction.coerceIn(0f, 1f), cy))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val shown = dragFraction?.let { (it * duration).toLong() } ?: position
            Text(formatTime(shown), style = MaterialTheme.typography.labelMedium.copy(color = Color.White.copy(alpha = 0.6f)))
            Text(formatTime(duration), style = MaterialTheme.typography.labelMedium.copy(color = Color.White.copy(alpha = 0.6f)))
        }
    }
}

private fun formatTime(ms: Long): String {
    val s = (ms / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(s / 60, s % 60)
}

// ------------------------------------------------------------
//  Boutons
// ------------------------------------------------------------
@Composable
private fun PressIcon(icon: ImageVector, description: String, size: Dp, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.84f else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy),
        label = "press"
    )
    Box(
        modifier = Modifier
            .size(size + 20.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(size))
    }
}

@Composable
private fun PlayButton(playing: Boolean) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy),
        label = "playPress"
    )
    Box(
        modifier = Modifier
            .size(78.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(Color.White)
            .clickable(interactionSource = source, indication = null) { DockMedia.togglePlayPause() },
        contentAlignment = Alignment.Center
    ) {
        Crossfade(targetState = playing, animationSpec = tween(180), label = "playIcon") { p ->
            Icon(
                imageVector = if (p) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (p) "Pause" else "Lecture",
                tint = Color.Black.copy(alpha = 0.9f),
                modifier = Modifier.size(42.dp)
            )
        }
    }
}

@Composable
private fun ShortcutButton(icon: ImageVector, active: Boolean, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (active) 0.28f else 0.12f))
            .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, description, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun VolumeRow() {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val max = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }

    // Agent PC connecté : le curseur règle le VRAI volume de l'ordinateur (0..100)
    val pc = PcAgent.connected
    val shown = if (pc) PcAgent.pcVolume else volume

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (pc && PcAgent.pcMuted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeDown,
            if (pc) "Couper le son du PC" else null,
            tint = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp).clickable(enabled = pc) { PcAgent.toggleMute() }
        )
        Slider(
            value = shown,
            onValueChange = {
                if (pc) PcAgent.setVolume(it)
                else {
                    volume = it
                    runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, it.roundToInt(), 0) }
                }
            },
            valueRange = 0f..(if (pc) 100f else max.toFloat()),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White.copy(alpha = 0.85f),
                inactiveTrackColor = Color.White.copy(alpha = 0.2f)
            )
        )
        Icon(Icons.Rounded.VolumeUp, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
    }
}