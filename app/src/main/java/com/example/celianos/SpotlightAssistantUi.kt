package com.example.celianos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ============================================================
//  5. Interface : chaque ActionResult a son composant
// ============================================================

@Composable
fun AssistantPanel(s: AssistantSession, onClose: () -> Unit, requestPerms: (List<String>) -> Unit) {
    s.done.forEach {
        Text(it, color = SpotInk.copy(alpha = 0.88f), fontSize = 15.sp, lineHeight = 21.sp,
            modifier = Modifier.padding(bottom = 8.dp))
    }
    val ld = s.loading
    val r = s.result
    if (ld != null) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
            CircularProgressIndicator(Modifier.size(18.dp), color = SpotInk, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(if (ld == "…") "En cours…" else ld, color = SpotInk.copy(alpha = 0.7f), fontSize = 14.sp)
        }
        Spacer(Modifier.height(10.dp))
        PopupButton("Annuler") { s.cancel(); onClose() }
    } else when (r) {
        is ActionResult.Error -> {
            Text(r.message, color = Color(0xFFFF6B6B), fontSize = 15.sp)
            Spacer(Modifier.height(12.dp))
            PopupButton("Fermer", onClose)
        }
        is ActionResult.NeedPermission -> {
            Text("Une autorisation Android est nécessaire pour cette action.", color = SpotInk, fontSize = 15.sp)
            Spacer(Modifier.height(12.dp))
            LaunchedEffect(r) { requestPerms(r.permissions) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PopupButton("Autoriser") { requestPerms(r.permissions) }
                PopupButton("Annuler", onClose)
            }
        }
        is ActionResult.Choice -> {
            Text(r.prompt, color = SpotInk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            r.options.forEach { o ->
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.fillMaxWidth().clip(spotShape(14)).background(SpotInk.copy(alpha = 0.10f))
                        .clickable { s.interact(o.next) }.padding(horizontal = 14.dp, vertical = 12.dp)
                ) { Text(o.label, color = SpotInk, fontSize = 14.sp) }
            }
        }
        is ActionResult.Confirm -> {
            Text(r.summary, color = SpotInk, fontSize = 16.sp, lineHeight = 22.sp)
            if (r.risk == Risk.VISIBLE) {
                Text("Visible par d'autres personnes", color = Color(0xFFFFB74D), fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PopupButton("Annuler", onClose)
                PopupButton("Confirmer") { s.interact { r.run() } }
            }
        }
        is ActionResult.Draft -> DraftView(s, r, onClose)
        else -> {}
    }
    LaunchedEffect(s.finished) {
        if (s.finished && !s.keepOpen && s.result == null) { delay(900); onClose() }
    }
}

@Composable
private fun DraftView(s: AssistantSession, d: ActionResult.Draft, onClose: () -> Unit) {
    var body by remember(d) { mutableStateOf(d.body) }
    var subject by remember(d) { mutableStateOf(d.subject ?: "") }
    var channel by remember(d) { mutableStateOf(d.channel) }
    var edited by remember(d) { mutableStateOf(false) }          // l'utilisateur a modifié le texte
    var ai by remember(d) { mutableStateOf(d.written) }           // null = rédaction en cours (si writer) ou sans IA

    // Le texte brut est déjà affiché ; la version IA le remplace à son arrivée, sauf si l'utilisateur a déjà tapé.
    // Si le brouillon quitte l'écran (annulation, envoi), cet effet est annulé et la requête réseau est fermée.
    LaunchedEffect(d) {
        val w = d.writer ?: return@LaunchedEffect
        val r = w()
        ai = r
        if (r is DraftWrite.Ok && !edited) body = r.text
    }

    Text(d.title, color = SpotInk, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Text(d.to, color = SpotInk.copy(alpha = 0.55f), fontSize = 13.sp)
    if (d.channels.size > 1) {
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            d.channels.forEach { c ->
                Box(
                    Modifier.clip(spotShape(12))
                        .background(if (c == channel) LocalSpotTheme.current.accent else SpotInk.copy(alpha = 0.10f))
                        .clickable { channel = c }.padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        c, color = if (c == channel) LocalSpotTheme.current.onAccent else SpotInk,
                        fontSize = 13.sp, fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
    if (d.subject != null) {
        Spacer(Modifier.height(10.dp))
        GlassField(subject, { subject = it }, "Objet", 1)
    }
    Spacer(Modifier.height(10.dp))
    val a = ai
    when {
        a is DraftWrite.Ok -> Text("Rédigé par l'IA · ${a.by}", color = LocalSpotTheme.current.accent, fontSize = 12.sp,
            fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 6.dp))
        a is DraftWrite.Failed -> Column(Modifier.padding(bottom = 6.dp)) {
            Text("Rédaction indisponible, texte brut", color = Color(0xFFFFB74D), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(a.reason, color = SpotInk.copy(alpha = 0.45f), fontSize = 11.sp)
        }
        d.writer != null -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
            CircularProgressIndicator(Modifier.size(14.dp), color = SpotInk, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Je rédige…", color = SpotInk.copy(alpha = 0.7f), fontSize = 12.sp)
        }
    }
    GlassField(body, { body = it; edited = true }, "Message", 4)
    if (edited && a is DraftWrite.Ok && body != a.text) {
        Text("Version IA prête · remplacer mon texte", color = LocalSpotTheme.current.accent, fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable { body = a.text; edited = false }.padding(top = 8.dp, bottom = 2.dp))
    }
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PopupButton("Annuler", onClose)
        PopupButton("Ouvrir dans $channel") {
            s.interact { d.send(channel, if (d.subject != null) subject else null, body) }
        }
    }
    Text("Rien n'est envoyé automatiquement : l'appli s'ouvre avec le texte, c'est toi qui valides.",
        color = SpotInk.copy(alpha = 0.45f), fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun GlassField(value: String, onChange: (String) -> Unit, label: String, minLines: Int) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, minLines = minLines,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = SpotInk, unfocusedTextColor = SpotInk, cursorColor = SpotInk,
            focusedBorderColor = LocalSpotTheme.current.accent, unfocusedBorderColor = SpotInk.copy(alpha = 0.3f),
            focusedLabelColor = LocalSpotTheme.current.accent, unfocusedLabelColor = SpotInk.copy(alpha = 0.5f)
        )
    )
}