package com.example.celianos

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

// ============================================================
//  Réglages de l'assistant, en blocs : la page Spotlight les range dans ses sections repliables.
//   - AssistantQuickCommandsCard : commandes rapides (sans IA)        -> « Recherche et modules »
//   - AssistantModulesSettings   : mes modules (préfixes)             -> « Recherche et modules »
//   - AssistantBrainSettings     : assistant IA, fournisseurs, connecteurs -> « Assistant IA » (Bêta)
//   - AssistantDataSettings      : import / export JSON des modules, journal -> « Journal et données »
// ============================================================

@Composable
fun AssistantQuickCommandsCard() {
    val s = SpotlightState
    SwitchCard("Commandes rapides", "« minuteur 10 min », « réveil 7h30 », « note : … », préfixes de modules (yt, w…)",
        s.cmdEnabled, { s.cmdEnabled = it }, NeonCyan)
}

@Composable
fun AssistantBrainSettings() {
    val s = SpotlightState
    SwitchCard("Assistant IA (Gemini)",
        "Comprend les phrases (« dis à Maman… », « rdv dentiste jeudi 14h »). N'est appelé que si rien d'autre ne correspond, ou si la phrase ressemble à une commande. Au moins une clé IA est requise (fournisseurs ci-dessous).",
        s.brainEnabled, { s.brainEnabled = it }, NeonCyan)

    AiProvidersSettingsSection()

    SectionLabel("CONNECTEURS")
    val on = ConnectorRegistry.enabledIds()
    ConnectorRegistry.builtin.forEach { c ->
        SwitchCard(c.name, c.description, c.id in on, { ConnectorRegistry.setEnabled(c.id, it) }, NeonCyan)
    }
    GlassNote("Seules les actions des connecteurs activés sont proposées à l'assistant. Tout ce qui est visible par d'autres (message, e-mail, webhook) passe par un brouillon ou une confirmation : rien n'est envoyé sans toi.")
}

@Composable
fun AssistantModulesSettings() {
    SectionLabel("MES MODULES")
    // la liste se relit quand SpotlightState.modules change (état Compose), plus besoin de compteur
    val raw = SpotlightState.modules
    val mods = remember(raw) { UserModules.load() }

    mods.forEach { m ->
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.name + if (m.prefix.isNotEmpty()) "   ·   ${m.prefix} …" else "",
                        style = MaterialTheme.typography.titleSmall.copy(color = Color.White, fontWeight = FontWeight.Bold))
                    Text(if (m.kind == "webhook") "Webhook (confirmation demandée)" else m.url,
                        style = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.55f)), maxLines = 1)
                }
                Icon(Icons.Rounded.Close, "Supprimer", tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(40.dp).clickable { UserModules.remove(m.id) }.padding(9.dp))
            }
        }
    }

    val available = UserModules.PRESETS.filter { p -> mods.none { it.id == p.id } }
    if (available.isNotEmpty()) {
        DropdownCard("Ajouter depuis le catalogue", "Raccourcis vers des applis et sites (s'ouvrent dans l'appli si installée)",
            "Choisir…", available.map { "${it.name}  (${it.prefix})" }) { i -> UserModules.add(available[i]) }
    }

    var name by remember { mutableStateOf("") }
    var prefix by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var kind by remember { mutableIntStateOf(0) }
    TextCard("Créer mon module : nom", "Ex. Mon site", name) { name = it }
    TextCard("Préfixe", "Ex. ms — tape « ms chats » pour l'utiliser", prefix) { prefix = it }
    TextCard("Adresse avec {q}", "Ex. https://monsite.fr/recherche?q={q}", url) { url = it }
    ChoiceCard("Type", listOf("Lien", "Webhook"), kind, { kind = it })
    LinkCard("Ajouter ce module", if (name.isBlank() || !url.contains("{q}")) "Il faut un nom et une adresse contenant {q}" else "Prêt", name.isNotBlank() && url.contains("{q}")) {
        val m = UserModules.fromJson(
            JSONObject().put("name", name).put("prefix", prefix).put("url", url).put("kind", if (kind == 1) "webhook" else "url")
        )
        if (m != null) { UserModules.add(m); name = ""; prefix = ""; url = "" }
    }
}

@Composable
fun AssistantDataSettings() {
    val ctx = LocalContext.current
    val rawMods = SpotlightState.modules
    val mods = remember(rawMods) { UserModules.load() }

    SectionLabel("MODULES : IMPORT ET EXPORT")
    var import by remember { mutableStateOf("") }
    TextCard("Importer un module (JSON)", "Colle un module partagé par quelqu'un d'autre", import) { import = it }
    LinkCard("Importer", "Ajoute le ou les modules du texte ci-dessus", import.isNotBlank()) {
        runCatching {
            val t = import.trim()
            val arr = if (t.startsWith("[")) JSONArray(t) else JSONArray().put(JSONObject(t))
            for (i in 0 until arr.length()) UserModules.fromJson(arr.getJSONObject(i))?.let { UserModules.add(it) }
            import = ""
        }
    }
    LinkCard("Copier mes modules (JSON)", "Pour les partager ou les sauvegarder", mods.isNotEmpty()) {
        ctx.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("modules", UserModules.toJson(mods).toString(2)))
    }

    // le journal se relit tout seul : ActionLog lit SpotlightState.actionLog (état Compose)
    val logRaw = SpotlightState.actionLog
    val log = remember(logRaw) { ActionLog.lines() }
    SectionLabel("JOURNAL")
    if (log.isEmpty()) {
        GlassNote("Le journal est vide. Il garde les 50 dernières actions et les appels IA (rôle, fournisseur, modèle, durée, résultat), jamais le texte des messages ni les clés.")
    } else {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp).fillMaxWidth()) {
                log.take(12).forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.7f)),
                        modifier = Modifier.padding(bottom = 4.dp))
                }
            }
        }
        LinkCard("Effacer le journal", "${log.size} entrée(s)", true) { ActionLog.clear() }
    }
}