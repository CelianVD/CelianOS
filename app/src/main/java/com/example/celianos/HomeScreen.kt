package com.example.celianos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Un module = une carte sur l'accueil + une page de réglages.
 * Pour ajouter une fonctionnalité : ajoute une entrée ici, une valeur dans `Screen`
 * (MainActivity.kt) et la page correspondante.
 */
data class Module(
    val screen: Screen,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val accent: Color
)

private val modules = listOf(
    Module(Screen.DYNAMIC_ISLAND, "Dynamic Island", "Pilule & Audio", Icons.Rounded.MusicNote, Color(0xFFB388FF)),
    Module(Screen.SPOTLIGHT, "Spotlight", "Recherche & Widgets", Icons.Rounded.Search, NeonCyan),
    Module(Screen.DESK_DOCK, "Glow Dock", "Lecteur de bureau", Icons.Rounded.Headphones, Color(0xFFFF6FB5))
)

// --- ÉCRAN PRINCIPAL D'ACCUEIL ---
@Composable
fun HomeScreen(onNavigate: (Screen) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .statusBarsPadding()
    ) {
        Spacer(modifier = Modifier.height(36.dp))

        HeaderSection(onSettings = { onNavigate(Screen.SETTINGS) })

        Spacer(modifier = Modifier.height(28.dp))

        MainStatusCard()

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = "MODULES SYSTÈME",
            style = MaterialTheme.typography.labelMedium.copy(
                color = Color.White.copy(alpha = 0.5f),
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold
            ),
            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
        )

        ModulesGrid(onNavigate = onNavigate)
    }
}

// --- COMPOSANT : HEADER ---
@Composable
fun HeaderSection(onSettings: () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "CélianOS",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    letterSpacing = (-1).sp
                )
            )
            Text(
                text = "Nothing Phone (4a) • v1.0",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = Color.White.copy(alpha = 0.5f)
                )
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onSettings, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = "Réglages généraux",
                    tint = Color.White.copy(alpha = 0.7f)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(NeonRed)
            )
        }
    }
}

// --- COMPOSANTS DE L'ACCUEIL ---
@Composable
fun MainStatusCard() {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.FlashOn,
                contentDescription = null,
                tint = NeonRed,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = "Bienvenue !",
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = "Prêt à répondre à vos besoins !",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = Color.White.copy(alpha = 0.6f)
                    )
                )
            }
        }
    }
}

@Composable
fun ModulesGrid(onNavigate: (Screen) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(modules) { module ->
            ModuleCard(
                title = module.title,
                subtitle = module.subtitle,
                icon = module.icon,
                accentColor = module.accent,
                onClick = { onNavigate(module.screen) }
            )
        }
    }
}

@Composable
fun ModuleCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    onClick: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth().height(140.dp), onClick = onClick) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(28.dp))
            Column {
                Text(text = title, style = MaterialTheme.typography.titleSmall.copy(color = Color.White, fontWeight = FontWeight.Bold))
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp))
            }
        }
    }
}