package com.example.celianos

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.foundation.ScrollState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay

/**
 * Spotlight : activité translucide plein écran. Le flou vient de FLAG_BLUR_BEHIND
 * (Android 12+, si le flou fenêtre est actif) ; sinon un voile plus sombre le remplace.
 */
class SpotlightActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        var blurOn = false
        // Brutaliste : jamais de flou (ni de transparence)
        val brutal = SpotlightState.glassStyle.coerceIn(0, STYLE_BRUTAL) == STYLE_BRUTAL
        if (Build.VERSION.SDK_INT >= 31 && windowManager.isCrossWindowBlurEnabled && !brutal) {
            blurOn = true
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.also { it.blurBehindRadius = SpotlightState.blurRadius.toInt() }
        }
        // icônes de la barre d'état / navigation lisibles sur le voile clair
        val sysDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        val darkUi = when (SpotlightState.themeMode) { 1 -> false; 2 -> true; else -> sysDark }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkUi
            isAppearanceLightNavigationBars = !darkUi
        }
        setContent {
            SpotlightTheme {
                SpotlightScreen(blurOn) { finish() }
            }
        }
    }

    override fun onDestroy() {
        // fermé à la main (retour, toucher à côté) pendant l'essai : l'assistant passe quand même à la suite
        if (isFinishing && SpotlightOnbDemo.active) SpotlightOnbDemo.done = true
        super.onDestroy()
    }
}

@Composable
fun SpotlightScreen(blurOn: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Hit>>(emptyList()) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (SpotlightState.autoKeyboard) { delay(200); runCatching { focus.requestFocus() } }
    }
    var wiki by remember { mutableStateOf<Hit.WikiHit?>(null) }
    LaunchedEffect(query) {
        wiki = null
        if (SpotlightState.searchWiki && query.isNotBlank()) {
            delay(450) // laisse finir la frappe avant d'interroger le réseau
            wiki = SpotlightWiki.lookup(query)
        }
    }
    var dict by remember { mutableStateOf<Hit.DictHit?>(null) }
    LaunchedEffect(query) {
        dict = null
        if (SpotlightState.searchDict && query.isNotBlank()) {
            delay(450)
            dict = SpotlightDict.lookup(query)?.let { Hit.DictHit(it) }
        }
    }
    val shown = remember(hits, wiki, dict) {
        val extras = listOfNotNull<Hit>(dict, wiki)
        if (extras.isEmpty()) return@remember hits
        val at = hits.indexOfFirst { it is Hit.AiInlineHit || it is Hit.AskHit || it is Hit.WebHit }
            .let { if (it < 0) hits.size else it }
        hits.toMutableList().also { it.addAll(at, extras) }.toList()
    }
    var popup by remember { mutableStateOf<PopupData?>(null) }
    val uiScope = rememberCoroutineScope()
    // après l'octroi d'une permission Android, l'action en attente reprend
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        (popup as? PopupData.Assistant)?.session?.resume()
    }
    LaunchedEffect(query) {
        if (query.isBlank()) { hits = emptyList(); return@LaunchedEffect }
        delay(60) // anti-rebond : annulé si on tape encore
        hits = SpotlightSearch.search(ctx, query.trim())
    }

    // Essai du geste dans l'assistant de configuration (SpotlightOnboardingScreen) :
    // une bulle explique, après une frappe Spotlight se referme tout seul, et rien n'est lancé.
    val demo = SpotlightOnbDemo.active
    LaunchedEffect(query) {
        if (demo && query.trim().length >= 2) {
            delay(2400) // le temps de voir les résultats
            SpotlightOnbDemo.done = true
            onClose()
        }
    }

    fun run(h: Hit) {
        if (demo) { SpotlightOnbDemo.done = true; onClose(); return }
        // Ces deux-là ouvrent une fenêtre dans Spotlight au lieu de le fermer
        when (h) {
            is Hit.DictHit -> { popup = PopupData.Dict(h.entry); return }
            is Hit.AiInlineHit -> { popup = PopupData.Answer(h.query, h.provider); return }
            is Hit.CommandHit -> {
                popup = PopupData.Assistant(AssistantSession(ctx, uiScope).also { it.runAction(h.action, h.args) }); return
            }
            is Hit.AssistantHit -> {
                popup = PopupData.Assistant(AssistantSession(ctx, uiScope).also { it.runBrain(h.query) }); return
            }
            else -> {}
        }
        try {
            when (h) {
                is Hit.AppHit -> ctx.startActivity(h.app.intent)
                is Hit.ContactHit -> ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, h.uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                is Hit.WikiHit -> SpotlightWiki.open(ctx, h.url)
                is Hit.DictHit, is Hit.AiInlineHit, is Hit.CommandHit, is Hit.AssistantHit -> {}
                is Hit.SettingHit -> try {
                    ctx.startActivity(Intent(h.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: ActivityNotFoundException) {
                    ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                is Hit.AskHit -> SpotlightAi.ask(ctx, h.ai, h.query)
                is Hit.FileHit -> ctx.startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(h.uri, h.mime)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                is Hit.UrlHit -> ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(h.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                is Hit.WebHit -> ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(h.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                is Hit.CalcHit -> ctx.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("calcul", h.value))
            }
        } catch (_: ActivityNotFoundException) {
        }
        onClose()
    }

    val scrim = LocalSpotTheme.current.scrim(blurOn)

    Box(
        Modifier.fillMaxSize().background(scrim)
            .pointerInput(Unit) { detectTapGestures { onClose() } }
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
        ) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                if (query.isBlank()) WidgetCarousel()
                else ResultList(shown, ::run)
            }
            SearchBar(
                query = query,
                onQuery = { query = it },
                focus = focus,
                onGo = { (hits.firstOrNull { it !is Hit.WebHit && it !is Hit.AskHit && it !is Hit.AiInlineHit } ?: hits.firstOrNull())?.let(::run) }
            )
        }
        if (demo) DemoCoach(query)
        popup?.let { pd ->
            BackHandler { popup = null }
            val scroll = rememberScrollState()
            val scope = rememberCoroutineScope()
            val chat = (pd as? PopupData.Answer)?.let { remember(it) { ChatState(it.provider, it.query, scope) } }
            GlassPopup(
                title = when (pd) {
                    is PopupData.Dict -> pd.entry.word
                    is PopupData.Answer -> pd.provider.name
                    is PopupData.Assistant -> "Assistant"
                },
                onClose = { popup = null },
                scroll = scroll,
                chat = chat
            ) {
                when (pd) {
                    is PopupData.Dict -> DictContent(pd.entry)
                    is PopupData.Answer -> if (chat != null) ChatContent(chat, scroll)
                    is PopupData.Assistant -> AssistantPanel(pd.session, { popup = null }) { perms ->
                        permLauncher.launch(perms.toTypedArray())
                    }
                }
            }
        }
    }
}

/** Bulle d'aide de l'essai du geste (assistant de configuration). */
@Composable
private fun BoxScope.DemoCoach(query: String) {
    val typed = query.trim().length >= 2
    Row(
        Modifier.align(Alignment.TopCenter).statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth()
            .spotGlass(spotShape(22), 0.16f)
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = SpotInk.copy(alpha = 0.8f))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(if (typed) "Bien joué !" else "Voilà Spotlight", color = SpotInk, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                if (typed) "Toucher un résultat l'ouvre. Là, je me referme et on continue."
                else "Tape un mot pour essayer : « wifi », « 12*(3+4) » ou le nom d'une appli.",
                color = SpotInk.copy(alpha = 0.7f), fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, focus: FocusRequester, onGo: () -> Unit) {
    val shape = spotShape(28)
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().height(56.dp)
            .spotGlass(shape, 0.16f)
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(start = 18.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Search, null, tint = SpotInk.copy(alpha = 0.7f))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Rechercher", color = SpotInk.copy(alpha = 0.45f), fontSize = 17.sp)
            BasicTextField(
                value = query, onValueChange = onQuery, singleLine = true,
                textStyle = TextStyle(color = SpotInk, fontSize = 17.sp),
                cursorBrush = SolidColor(SpotInk),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onGo() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
            )
        }
        if (query.isNotEmpty()) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable { onQuery("") },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.Close, "Effacer", tint = SpotInk.copy(alpha = 0.7f)) }
        }
    }
}

@Composable
private fun ResultList(hits: List<Hit>, onClick: (Hit) -> Unit) {
    // reverseLayout : le meilleur résultat est collé à la barre de recherche (près du pouce)
    // l'ombre dure du Brutaliste déborde en bas à droite : on la compense dans les marges
    val off = LocalSpotTheme.current.shadowOffset
    LazyColumn(
        reverseLayout = true,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp + off),
        verticalArrangement = Arrangement.spacedBy(8.dp + off)
    ) {
        itemsIndexed(hits) { _, h -> HitRow(h) { onClick(h) } }
    }
}

@Composable
private fun HitRow(h: Hit, onClick: () -> Unit) {
    val shape = spotShape(22)
    // Bêta : seulement ce qui comprend une phrase avec l'IA (assistant, message rédigé). Pas les raccourcis sans IA.
    val beta = h is Hit.AssistantHit || (h is Hit.CommandHit && h.action == "draft_message")
    Row(
        Modifier.fillMaxWidth().spotGlass(shape, 0.13f)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (h) {
            is Hit.AppHit -> Image(h.app.icon, null, Modifier.size(44.dp).clip(spotShape(11)))
            is Hit.ContactHit -> Box(
                Modifier.size(44.dp).clip(CircleShape).background(SpotInk.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) { Text(h.name.take(1).uppercase(), color = SpotInk, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
            is Hit.FileHit -> if (h.thumb != null)
                Image(h.thumb, null, Modifier.size(44.dp).clip(spotShape(11)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            else IconBubble(Icons.Rounded.Description)
            is Hit.CalcHit -> IconBubble(Icons.Rounded.Calculate)
            is Hit.UrlHit -> IconBubble(Icons.Rounded.Link)
            is Hit.WebHit -> IconBubble(Icons.Rounded.Public)
            is Hit.AskHit -> IconBubble(Icons.Rounded.AutoAwesome)
            is Hit.AiInlineHit -> IconBubble(Icons.Rounded.Bolt)
            is Hit.CommandHit -> IconBubble(Icons.Rounded.PlayArrow)
            is Hit.AssistantHit -> IconBubble(Icons.Rounded.AutoAwesome)
            is Hit.DictHit -> IconBubble(Icons.Rounded.MenuBook)
            is Hit.SettingHit -> IconBubble(Icons.Rounded.Settings)
            is Hit.WikiHit -> Box(
                Modifier.size(44.dp).clip(spotShape(12)).background(Color.White),
                contentAlignment = Alignment.Center
            ) { Text("W", color = Color.Black, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Serif) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            val (title, sub) = when (h) {
                is Hit.AppHit -> h.app.label to "Application"
                is Hit.ContactHit -> h.name to "Contact"
                is Hit.FileHit -> h.name to when (h.kind) {
                    0 -> "Photo"
                    1 -> "Vidéo"
                    else -> "Document · " + h.name.substringAfterLast('.', "fichier").lowercase()
                }
                is Hit.CalcHit -> "= ${h.value}" to "${h.expr}  ·  toucher pour copier"
                is Hit.UrlHit -> h.url.removePrefix("https://") to "Ouvrir le site"
                is Hit.WikiHit -> h.title to ("Wikipédia" + if (h.desc.isNotEmpty()) " · ${h.desc}" else "")
                is Hit.CommandHit -> h.title to h.subtitle
                is Hit.AssistantHit -> "Demander à l'assistant" to "« ${h.query} » · exécuté avec tes connecteurs"
                is Hit.AiInlineHit -> "Réponse rapide" to "${h.provider.name} · la réponse s'affiche ici"
                is Hit.DictHit -> h.entry.word to ("Définition · " + h.entry.sections.first().defs.first().take(70))
                is Hit.SettingHit -> h.title to "Réglages Android"
                is Hit.AskHit -> "Demander à ${h.ai.name}" to "« ${h.query} »"
                is Hit.WebHit -> "Rechercher « ${h.query} »" to "Sur ${h.engine}"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title, color = SpotInk, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontSize = if (h is Hit.CalcHit) 24.sp else 16.sp,
                    fontWeight = if (h is Hit.CalcHit) FontWeight.Light else FontWeight.Medium,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (beta) {
                    Spacer(Modifier.width(8.dp))
                    BetaBadge(LocalSpotTheme.current.accent, SpotInk, spotShape(8))
                }
            }
            Text(sub, color = SpotInk.copy(alpha = 0.55f), fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (beta) {
                Text(BETA_NOTE, color = SpotInk.copy(alpha = 0.45f), fontSize = 11.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun IconBubble(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(
        Modifier.size(44.dp).clip(spotShape(12)).background(SpotInk.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = SpotInk) }
}

// ---------------- Fenêtre « Spotlight » pour les définitions et les réponses IA ----------------

sealed interface PopupData {
    class Dict(val entry: DictEntry) : PopupData
    class Answer(val query: String, val provider: QuickAiProvider) : PopupData
    class Assistant(val session: AssistantSession) : PopupData
}

@Composable
private fun GlassPopup(
    title: String,
    onClose: () -> Unit,
    scroll: ScrollState,
    chat: ChatState?,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = spotShape(32)
    Box(
        Modifier.fillMaxSize().background(LocalSpotTheme.current.popupBackdrop)
            .pointerInput(Unit) { detectTapGestures { onClose() } }
            .statusBarsPadding().navigationBarsPadding().imePadding() // la fenêtre remonte au-dessus du clavier
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 600.dp)
                .spotGlass(shape, dark = true)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = SpotInk, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(SpotInk.copy(alpha = 0.12f))
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.Close, "Fermer", tint = SpotInk, modifier = Modifier.size(20.dp)) }
            }
            Spacer(Modifier.height(14.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(scroll), content = content)
            if (chat != null) ChatFooter(chat)
        }
    }
}

@Composable
internal fun PopupButton(label: String, onClick: () -> Unit) {
    val th = LocalSpotTheme.current
    val shape = spotShape(14)
    // Translucide : voile discret. Matériel : bouton plein (primaire). Brutaliste : aplat vif, bordure et ombre dure.
    val look = when (th.style) {
        STYLE_BRUTAL -> Modifier.spotBrutalBlock(shape, th.accent, th.brutalBorder, th.brutalShadow, 2.dp, 4.dp)
        STYLE_MATERIAL -> Modifier.clip(shape).background(th.accent)
        else -> Modifier.clip(shape).background(SpotInk.copy(alpha = 0.14f))
    }
    Box(look.clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            label, color = if (th.style == STYLE_TRANSLUCENT) SpotInk else th.onAccent,
            fontSize = 13.sp, fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun DictContent(e: DictEntry) {
    val ctx = LocalContext.current
    Text("Wiktionnaire", color = SpotInk.copy(alpha = 0.5f), fontSize = 12.sp)
    e.sections.forEach { s ->
        Spacer(Modifier.height(14.dp))
        Text(s.pos.uppercase(), color = LocalSpotTheme.current.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        s.defs.forEachIndexed { i, d ->
            Row(Modifier.padding(top = 6.dp)) {
                Text("${i + 1}.", color = SpotInk.copy(alpha = 0.5f), fontSize = 14.sp, modifier = Modifier.width(24.dp))
                Text(d, color = SpotInk.copy(alpha = 0.92f), fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
    }
    Spacer(Modifier.height(18.dp))
    PopupButton("Ouvrir sur le Wiktionnaire") {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

class ChatMsg(val role: String, val text: String, val error: Boolean = false)

/** Conversation avec l'IA : la 1re question part tout de suite, puis on peut répondre. */
class ChatState(val provider: QuickAiProvider, first: String, private val scope: CoroutineScope) {
    val msgs = mutableStateListOf<ChatMsg>()
    var loading by mutableStateOf(false)

    init { send(first) }

    fun send(text: String) {
        if (loading || text.isBlank()) return
        msgs += ChatMsg("user", text.trim())
        loading = true
        scope.launch {
            val r = runCatching { SpotlightAiApi.ask(provider, history()) }
            msgs += r.fold({ ChatMsg("assistant", it) }, { ChatMsg("assistant", it.message ?: "Erreur", true) })
            loading = false
        }
    }

    /** Historique envoyé à l'API : on écarte les erreurs et la question qui les a provoquées. */
    private fun history(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        msgs.forEachIndexed { i, m ->
            if (m.error) return@forEachIndexed
            if (m.role == "user" && msgs.getOrNull(i + 1)?.error == true) return@forEachIndexed
            out += m.role to m.text
        }
        return out
    }
}

@Composable
private fun ChatContent(chat: ChatState, scroll: ScrollState) {
    val ctx = LocalContext.current
    chat.msgs.forEach { m ->
        if (m.role == "user") {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                Text(
                    m.text, color = SpotInk, fontSize = 14.sp,
                    modifier = Modifier.widthIn(max = 380.dp).clip(spotShape(18))
                        .background(SpotInk.copy(alpha = 0.16f)).padding(horizontal = 14.dp, vertical = 9.dp)
                )
            }
        } else {
            Text(
                m.text, color = if (m.error) Color(0xFFFF6B6B) else SpotInk,
                fontSize = 16.sp, lineHeight = 23.sp, modifier = Modifier.padding(top = 12.dp)
            )
            if (!m.error) {
                Text(
                    "Copier", color = SpotInk.copy(alpha = 0.5f), fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp).clip(spotShape(8)).clickable {
                        ctx.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("réponse", m.text))
                    }.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }
    }
    if (chat.loading) {
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), color = SpotInk, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Réflexion…", color = SpotInk.copy(alpha = 0.7f), fontSize = 14.sp)
        }
    }
    // fait défiler vers le dernier message
    LaunchedEffect(chat.msgs.size, chat.loading) {
        delay(60)
        scroll.animateScrollTo(scroll.maxValue)
    }
}

@Composable
private fun ChatFooter(chat: ChatState) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { focus.requestFocus() } }
    fun go() {
        if (text.isNotBlank() && !chat.loading) { chat.send(text); text = "" }
    }
    val active = text.isNotBlank() && !chat.loading
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp)
            .spotGlass(spotShape(24), 0.14f).padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) Text("Répondre…", color = SpotInk.copy(alpha = 0.45f), fontSize = 15.sp)
            BasicTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                textStyle = TextStyle(color = SpotInk, fontSize = 15.sp),
                cursorBrush = SolidColor(SpotInk),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { go() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
            )
        }
        Box(
            Modifier.size(36.dp).clip(CircleShape)
                .background(if (active) LocalSpotTheme.current.accent else SpotInk.copy(alpha = 0.25f))
                .clickable { go() },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Rounded.ArrowUpward, "Envoyer", tint = LocalSpotTheme.current.onAccent, modifier = Modifier.size(20.dp)) }
    }
}