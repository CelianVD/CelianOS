package com.example.celianos

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================
//  Icônes d'applis du dock (gauche ou droite) : un toucher lance l'appli sur le PC.
//  Les icônes (et leur nom) viennent de l'application PC.
// ============================================================

const val DOCK_APP_SLOTS = 12

object DockApps {
    private fun lines(): List<String> = DeskDockState.appsData.split('\n')
    private fun field(i: Int, f: Int) = lines().getOrNull(i)?.split('\t')?.getOrNull(f) ?: ""

    fun id(i: Int) = field(i, 0)
    fun name(i: Int) = field(i, 1)

    fun set(i: Int, id: String, name: String) {
        val l = List(DOCK_APP_SLOTS) { lines().getOrNull(it) ?: "" }.toMutableList()
        l[i] = if (id.isBlank()) "" else "$id\t$name"
        DeskDockState.appsData = l.joinToString("\n")
    }

    /** Emplacements remplis parmi les `appsCount` premiers. */
    val filledSlots: List<Int> get() = (0 until DeskDockState.appsCount.coerceIn(1, DOCK_APP_SLOTS)).filter { id(it).isNotBlank() }
}

/** Icône d'une appli : celle de l'application PC, sinon une initiale. */
@Composable
fun PcAppIcon(id: String, name: String, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val bmp = PcAgent.appIcons[id]
    Box(
        modifier = modifier.size(size).clip(RoundedCornerShape(size * 0.26f)),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(
                bitmap = remember(bmp) { bmp.asImageBitmap() },
                contentDescription = name,
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Text(name.take(1).uppercase().ifBlank { "?" }, color = Color.White, fontSize = (size.value * 0.45f).sp)
            }
        }
    }
}

/** Une tuile (icône + nom). Un toucher lance l'appli sur le PC. */
@Composable
private fun AppTile(i: Int, size: Dp, showLabel: Boolean) {
    val enabled = PcAgent.connected
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled) { PcAgent.launchApp(DockApps.id(i)) }
            .padding(3.dp)
    ) {
        PcAppIcon(
            DockApps.id(i), DockApps.name(i), size,
            Modifier.border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(size * 0.26f))
                .then(if (enabled) Modifier else Modifier.background(Color.Black.copy(alpha = 0.45f)))
        )
        if (showLabel) {
            Text(
                DockApps.name(i), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp,
                color = Color.White.copy(alpha = if (enabled) 0.7f else 0.35f),
                modifier = Modifier.width(maxOf(size, 56.dp)).padding(top = 2.dp), textAlign = TextAlign.Center
            )
        }
    }
}

/** Colonne latérale : la taille des icônes s'adapte à la hauteur, 2-3 colonnes si besoin (rien n'est coupé). */
@Composable
fun DockAppRail(modifier: Modifier = Modifier) {
    val slots = DockApps.filledSlots
    if (slots.isEmpty()) return
    val cols = (slots.size + 3) / 4
    val rows = (slots.size + cols - 1) / cols
    BoxWithConstraints(modifier = modifier.width((cols * 72).dp)) {
        val labelH = 16.dp
        val tile = ((maxHeight - 12.dp * (rows - 1)) / rows - labelH - 6.dp).coerceIn(30.dp, 56.dp)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (c in 0 until cols) {
                Column(
                    Modifier.fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    slots.drop(c * rows).take(rows).forEach { AppTile(it, tile, showLabel = tile >= 40.dp) }
                }
            }
        }
    }
}

/** Grille façon Stream Deck : choisit le nombre de colonnes qui donne les plus grosses touches. */
@Composable
fun DockAppGrid(modifier: Modifier = Modifier) {
    val slots = DockApps.filledSlots
    if (slots.isEmpty()) return
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val labelH = 18.dp
        var bestCols = 1
        var bestSize = 0f
        for (c in 1..slots.size) {
            val r = (slots.size + c - 1) / c
            val size = minOf(maxWidth.value / c, maxHeight.value / r - labelH.value)
            if (size > bestSize) { bestSize = size; bestCols = c }
        }
        val tile = (bestSize * 0.78f).dp.coerceIn(36.dp, 120.dp)
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            slots.chunked(bestCols).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    row.forEach { AppTile(it, tile, showLabel = true) }
                }
            }
        }
    }
}

/** Fenêtre de choix d'une appli du PC pour un emplacement. */
@Composable
fun DockSlotDialog(slot: Int, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        dismissButton = {
            TextButton(onClick = { DockApps.set(slot, "", ""); onDismiss() }) { Text("Vider") }
        },
        title = { Text("Emplacement ${slot + 1}") },
        text = {
            if (PcAgent.apps.isEmpty()) {
                Text("Aucune appli : connecte l'application PC, puis ajoute des applis/sites dedans.")
            } else {
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    PcAgent.apps.forEach { app ->
                        Row(
                            Modifier.fillMaxWidth().clickable { DockApps.set(slot, app.id, app.name); onDismiss() }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PcAppIcon(app.id, app.name, 40.dp)
                            Spacer(Modifier.width(14.dp))
                            Text(
                                app.name,
                                color = if (DockApps.id(slot) == app.id) MaterialTheme.colorScheme.primary else Color.Unspecified
                            )
                        }
                    }
                }
            }
        }
    )
}