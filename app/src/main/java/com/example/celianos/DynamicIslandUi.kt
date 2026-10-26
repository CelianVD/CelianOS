package com.example.celianos

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private enum class IslandMode { COMPACT, ANNOUNCE, CONTROLS, SOUND }

// Durée d'affichage de la grande version (sans boutons) à l'activation / nouvelle musique
private const val ANNOUNCE_MS = 1000L

// Durée avant fermeture automatique de la grande version avec boutons
private const val CONTROLS_MS = 5000L

// Taille du visuel (pochette / cercle / icône) en grande version
private val ART_SIZE = 40.dp

@Composable
fun DynamicIsland(modifier: Modifier = Modifier) {
    var mode by remember { mutableStateOf(IslandMode.COMPACT) }
    // Incrémenté à chaque interaction pour relancer le minuteur de fermeture automatique
    var keepOpen by remember { mutableIntStateOf(0) }

    val title = MediaState.title
    val artist = MediaState.artist
    val playing = MediaState.isPlaying
    val artGrid = MediaState.artGrid
    val preview = DynamicIslandService.previewMode
    val topOffset = DynamicIslandService.expandedOffset
    val artStyle = DynamicIslandService.artStyle
    val soundEvent = DynamicIslandService.soundEvent
    val hiding = DynamicIslandService.hiding

    // Un clic en dehors de la pilule la ramène en version petite
    val outsideTick = DynamicIslandService.outsideTouchTick
    val initialOutsideTick = remember { outsideTick }
    LaunchedEffect(outsideTick) {
        if (outsideTick != initialOutsideTick) mode = IslandMode.COMPACT
    }

    // À l'activation et à chaque nouvelle musique : grande version SANS boutons, puis retour en petit
    // (si les boutons sont déjà ouverts, on les garde)
    LaunchedEffect(title) {
        if (mode != IslandMode.CONTROLS) mode = IslandMode.ANNOUNCE
        keepOpen++
    }

    LaunchedEffect(mode, keepOpen) {
        when (mode) {
            IslandMode.ANNOUNCE -> {
                delay(ANNOUNCE_MS)
                mode = IslandMode.COMPACT
            }
            IslandMode.CONTROLS -> {
                delay(CONTROLS_MS)
                mode = IslandMode.COMPACT
            }
            else -> Unit
        }
    }

    // Priorité : aperçu des réglages > animation de profil sonore > mode normal
    val shownMode = when {
        preview == PreviewMode.COMPACT -> IslandMode.COMPACT
        preview == PreviewMode.EXPANDED -> IslandMode.CONTROLS
        soundEvent != null -> IslandMode.SOUND
        else -> mode
    }
    val isPreview = preview != PreviewMode.NONE

    val sizeSpec = spring<Dp>(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)
    val width by animateDpAsState(
        targetValue = when (shownMode) {
            IslandMode.COMPACT -> 120.dp
            IslandMode.SOUND -> 250.dp
            else -> 300.dp
        },
        animationSpec = sizeSpec,
        label = "width"
    )
    val height by animateDpAsState(
        targetValue = when (shownMode) {
            IslandMode.COMPACT -> 32.dp
            IslandMode.ANNOUNCE, IslandMode.SOUND -> (topOffset + 56).dp
            IslandMode.CONTROLS -> (topOffset + 110).dp
        },
        animationSpec = sizeSpec,
        label = "height"
    )
    val topPad by animateDpAsState(
        targetValue = topOffset.dp,
        animationSpec = sizeSpec,
        label = "topPad"
    )
    val shape = RoundedCornerShape(if (shownMode == IslandMode.COMPACT) 20.dp else 30.dp)

    // Fondu de sortie avant le retrait de la fenêtre
    val exitAlpha by animateFloatAsState(
        targetValue = if (hiding) 0f else 1f,
        animationSpec = tween(HIDE_ANIM_MS.toInt()),
        label = "exit"
    )

    val shownTitle = title ?: if (isPreview) "Titre du morceau" else "Lecture en cours"
    val shownArtist = artist ?: if (isPreview) "Artiste" else "Titre indisponible"
    val barsMoving = playing || isPreview

    Box(
        modifier = modifier
            .graphicsLayer {
                alpha = exitAlpha
                scaleX = 0.92f + 0.08f * exitAlpha
                scaleY = 0.92f + 0.08f * exitAlpha
                transformOrigin = TransformOrigin(0.5f, 0f)
            }
            .wrapContentSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            modifier = Modifier
                .size(width = width, height = height)
                .clip(shape)
                .clickable {
                    if (soundEvent == null) {
                        mode = if (mode == IslandMode.CONTROLS) IslandMode.COMPACT else IslandMode.CONTROLS
                    }
                },
            shape = shape,
            color = Color.Black,
            shadowElevation = 8.dp
        ) {
            when {
                shownMode == IslandMode.COMPACT -> {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MusicBadge(boxSize = 18.dp)
                        Equalizer(playing = barsMoving)
                    }
                }

                shownMode == IslandMode.SOUND && soundEvent != null -> {
                    // Animation de profil sonore (cloche barrée, vibrations…), sous la caméra
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 18.dp, end = 18.dp, top = topPad, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SoundEventAnimation(
                            kind = soundEvent.kind,
                            eventId = soundEvent.id,
                            boxSize = ART_SIZE
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = soundEvent.kind.label(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                ),
                                maxLines = 1
                            )
                            Text(
                                text = soundEvent.kind.detail(),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 11.sp
                                ),
                                maxLines = 1
                            )
                        }
                    }
                }

                else -> {
                    // Grande version : le contenu démarre sous la caméra (décalage réglable)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 18.dp, end = 18.dp, top = topPad, bottom = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ArtView(
                                style = artStyle,
                                grid = artGrid,
                                active = barsMoving,
                                isPreview = isPreview,
                                boxSize = ART_SIZE
                            )

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp),
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = shownTitle,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = shownArtist,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = Color.White.copy(alpha = 0.6f),
                                        fontSize = 11.sp
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Equalizer(playing = barsMoving)
                        }

                        if (shownMode == IslandMode.CONTROLS) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ControlButton(Icons.Default.SkipPrevious, "Précédent") {
                                    keepOpen++
                                    MediaState.skipPrevious()
                                }
                                ControlButton(
                                    icon = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    description = if (playing) "Pause" else "Lecture",
                                    size = 46.dp,
                                    highlighted = true
                                ) {
                                    keepOpen++
                                    MediaState.togglePlayPause()
                                }
                                ControlButton(Icons.Default.SkipNext, "Suivant") {
                                    keepOpen++
                                    MediaState.skipNext()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun SoundKind.label(): String = when (this) {
    SoundKind.SILENT -> "Silencieux"
    SoundKind.VIBRATE -> "Vibreur"
    SoundKind.NORMAL -> "Sonnerie"
    SoundKind.DND_ON, SoundKind.DND_OFF -> "Ne pas déranger"
}

private fun SoundKind.detail(): String = when (this) {
    SoundKind.SILENT -> "Sonnerie coupée"
    SoundKind.VIBRATE -> "Vibrations uniquement"
    SoundKind.NORMAL -> "Son activé"
    SoundKind.DND_ON -> "Activé"
    SoundKind.DND_OFF -> "Désactivé"
}

@Composable
fun ControlButton(
    icon: ImageVector,
    description: String,
    size: Dp = 38.dp,
    highlighted: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (highlighted) Color.White else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (highlighted) Color.Black else Color.White,
            modifier = Modifier.size(size * 0.55f)
        )
    }
}

@Composable
fun Equalizer(playing: Boolean) {
    val infinite = rememberInfiniteTransition(label = "audio_wave")
    val w1 by infinite.animateFloat(
        0.3f, 1f,
        infiniteRepeatable(tween(400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "w1"
    )
    val w2 by infinite.animateFloat(
        0.8f, 0.2f,
        infiniteRepeatable(tween(300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "w2"
    )
    val w3 by infinite.animateFloat(
        0.5f, 0.9f,
        infiniteRepeatable(tween(500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "w3"
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(12.dp)
    ) {
        // En pause, les barres restent figées
        AudioBar(if (playing) w1 else 0.3f, NeonCyan)
        AudioBar(if (playing) w2 else 0.3f, NeonRed)
        AudioBar(if (playing) w3 else 0.3f, Color.White)
    }
}

@Composable
fun AudioBar(scale: Float, color: Color) {
    Box(
        modifier = Modifier
            .width(2.dp)
            .fillMaxHeight(scale)
            .clip(RoundedCornerShape(50))
            .background(color)
    )
}