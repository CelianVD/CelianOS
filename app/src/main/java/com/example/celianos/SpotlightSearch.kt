package com.example.celianos

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import android.provider.ContactsContract
import android.util.Patterns
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.MathContext
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.pow

// ============================================================
//  Moteur de recherche : applis, contacts, calcul, web
// ============================================================

class AppEntry(val label: String, val pkg: String, val intent: Intent, val icon: ImageBitmap)

sealed interface Hit {
    class CalcHit(val expr: String, val value: String) : Hit
    class UrlHit(val url: String) : Hit
    class AppHit(val app: AppEntry) : Hit
    class ContactHit(val name: String, val uri: Uri) : Hit
    /** kind : 0 photo, 1 vidéo, 2 document */
    class FileHit(val name: String, val uri: Uri, val mime: String, val kind: Int, val thumb: ImageBitmap?) : Hit
    class DictHit(val entry: DictEntry) : Hit
    class SettingHit(val title: String, val action: String) : Hit
    class AiInlineHit(val query: String, val provider: QuickAiProvider) : Hit
    class CommandHit(val title: String, val subtitle: String, val action: String, val args: org.json.JSONObject) : Hit
    class AssistantHit(val query: String) : Hit
    class WikiHit(val title: String, val desc: String, val url: String) : Hit
    class AskHit(val query: String, val ai: AiAssistant) : Hit
    class WebHit(val query: String, val engine: String, val url: String) : Hit
}

object SpotlightSearch {

    private var appCache: List<AppEntry> = emptyList()
    private var appCacheAt = 0L

    fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /** 0 = commence par, 1 = un mot commence par, 2 = contient, -1 = non. */
    private fun score(name: String, q: String): Int {
        val n = norm(name)
        return when {
            n.startsWith(q) -> 0
            n.split(' ', '-', '.', '_').any { it.startsWith(q) } -> 1
            n.contains(q) -> 2
            else -> -1
        }
    }

    private suspend fun apps(ctx: Context): List<AppEntry> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (appCache.isNotEmpty() && now - appCacheAt < 5 * 60_000) return@withContext appCache
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val list = pm.queryIntentActivities(main, 0).mapNotNull { ri ->
            runCatching {
                val pkg = ri.activityInfo.packageName
                val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                AppEntry(
                    ri.loadLabel(pm).toString(), pkg, launch,
                    ri.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
                )
            }.getOrNull()
        }.filter { it.pkg != ctx.packageName }.distinctBy { it.pkg + it.intent.component?.className }
        appCache = list
        appCacheAt = now
        list
    }

    fun hasContactsPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED

    private suspend fun contacts(ctx: Context, q: String): List<Hit.ContactHit> =
        withContext(Dispatchers.IO) {
            if (!hasContactsPermission(ctx)) return@withContext emptyList()
            runCatching {
                val out = ArrayList<Hit.ContactHit>()
                ctx.contentResolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(
                        ContactsContract.Contacts._ID,
                        ContactsContract.Contacts.LOOKUP_KEY,
                        ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
                    ),
                    null, null, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
                )?.use { c ->
                    while (c.moveToNext() && out.size < 4) {
                        val name = c.getString(2) ?: continue
                        if (score(name, q) >= 0) {
                            out += Hit.ContactHit(
                                name,
                                ContactsContract.Contacts.getLookupUri(c.getLong(0), c.getString(1))
                            )
                        }
                    }
                }
                out
            }.getOrDefault(emptyList())
        }


    // ---------------- Photos, vidéos, documents (par titre, via MediaStore) ----------------

    fun hasMediaPermission(ctx: Context): Boolean {
        fun ok(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= 33) ok(Manifest.permission.READ_MEDIA_IMAGES)
        else ok(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun mediaPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    /** Les documents (PDF, Word…) ne sont visibles sur Android 11+ qu'avec « Accès à tous les fichiers ». */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    private const val DOC_FILTER =
        "(mime_type = 'application/pdf' OR mime_type LIKE 'text/%' OR mime_type = 'application/msword' " +
                "OR mime_type = 'application/rtf' OR mime_type LIKE 'application/vnd.%') " +
                "AND mime_type != 'application/vnd.android.package-archive'"

    private suspend fun files(ctx: Context, raw: String): List<Hit.FileHit> = withContext(Dispatchers.IO) {
        val out = ArrayList<Hit.FileHit>()
        val base = MediaStore.Files.getContentUri("external")
        val proj = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.MEDIA_TYPE
        )
        val like = "%" + raw.replace("\\", "").replace("%", "").replace("_", "") + "%"
        val sort = MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC"

        fun run(selection: String, max: Int, thumbs: Boolean) = runCatching {
            ctx.contentResolver.query(base, proj, selection, arrayOf(like), sort)?.use { c ->
                var n = 0
                while (c.moveToNext() && n < max) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: "*/*"
                    val mt = c.getInt(3)
                    val kind = when (mt) {
                        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> 0
                        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> 1
                        else -> 2
                    }
                    val uri = ContentUris.withAppendedId(
                        when (kind) {
                            0 -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            1 -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            else -> base
                        }, id
                    )
                    val thumb = if (thumbs && Build.VERSION.SDK_INT >= 29 && kind != 2)
                        runCatching { ctx.contentResolver.loadThumbnail(uri, Size(112, 112), null).asImageBitmap() }
                            .getOrNull()
                    else null
                    out += Hit.FileHit(name, uri, mime, kind, thumb)
                    n++
                }
            }
        }

        if (hasMediaPermission(ctx)) {
            run("media_type IN (1, 3) AND _display_name LIKE ?", 4, true)
        }
        if (hasAllFilesAccess() && hasMediaPermission(ctx)) {
            run("$DOC_FILTER AND _display_name LIKE ?", 4, false)
        }
        out
    }

    suspend fun search(ctx: Context, raw: String): List<Hit> {
        val q = norm(raw)
        val hits = ArrayList<Hit>()
        val s = SpotlightState

        // 1. routeur local : instantané, hors ligne, avant tout le reste
        if (s.cmdEnabled) hits += CommandRouter.match(raw)

        // 1 bis. message reconnu localement (« dis à X de… », « écris à X que… ») avec un contact trouvé :
        //        un seul appel IA de rédaction, sans passer par l'analyse (function calling).
        //        Seulement si l'assistant IA est activé ; sinon (ou contact introuvable) rien ne change.
        var messageRoute = false
        if (s.cmdEnabled && "messages" in ConnectorRegistry.enabledIds() && SpotlightBrain.available()) {
            CommandRouter.parseMessage(raw)?.let { m ->
                if (MessagesConnector.contactCount(ctx, m.who) > 0) {
                    messageRoute = true
                    hits += Hit.CommandHit(
                        "Message à ${m.who}", "Brouillon rédigé par l'IA · tu valides avant l'envoi", "draft_message",
                        org.json.JSONObject().put("contact", m.who).put("instruction", m.instruction).put("sentence", m.sentence)
                    )
                }
            }
        }

        if (s.searchCalc) SpotlightCalc.evaluate(raw)?.let { hits += Hit.CalcHit(raw, it) }

        if (s.searchWeb && !raw.contains(' ') && raw.contains('.') &&
            Patterns.WEB_URL.matcher(raw).matches()
        ) {
            hits += Hit.UrlHit(if (raw.startsWith("http")) raw else "https://$raw")
        }

        if (s.searchApps) {
            apps(ctx).map { it to score(it.label, q) }
                .filter { it.second >= 0 }
                .sortedWith(compareBy({ it.second }, { it.first.label.length }))
                .take(6).forEach { hits += Hit.AppHit(it.first) }
        }

        if (s.searchSettings) hits += SpotlightSettingsShortcuts.match(q)

        if (s.searchContacts) hits += contacts(ctx, q)

        if (s.searchFiles && raw.length >= 2) hits += files(ctx, raw)

        // 2. cerveau IA : proposé (jamais lancé tout seul) si la phrase ressemble à une commande,
        //    ou si rien d'autre ne correspond à une phrase de 3 mots ou plus
        if (SpotlightBrain.available()) {
            val nothing = hits.none { it is Hit.AppHit || it is Hit.ContactHit || it is Hit.FileHit ||
                    it is Hit.CalcHit || it is Hit.SettingHit || it is Hit.CommandHit }
            if (CommandRouter.looksLikeCommand(raw)) {
                if (!messageRoute) hits.add(hits.count { it is Hit.CommandHit }, Hit.AssistantHit(raw))
            } else if (nothing && raw.trim().split(' ').size >= 3) hits += Hit.AssistantHit(raw)
        }
        if (SpotlightAiApi.ready()) hits += Hit.AiInlineHit(raw, SpotlightAiApi.current())
        if (s.askAi) SpotlightAi.active(ctx).forEach { hits += Hit.AskHit(raw, it) }

        if (s.searchWeb) {
            val (name, base) = WEB_ENGINES[s.engine.coerceIn(0, WEB_ENGINES.lastIndex)]
            hits += Hit.WebHit(raw, name, base + Uri.encode(raw))
        }
        return hits
    }
}

// ------------------------------------------------------------
//  Calculatrice : + - * / ^ % ( ) sqrt sin cos tan ln log abs exp pi e
// ------------------------------------------------------------
object SpotlightCalc {
    private val allowed = Regex("^[0-9a-zπ+\\-*/^%().,\\s×÷]+$")

    fun evaluate(input: String): String? {
        val s = input.trim().lowercase()
        if (s.length < 2 || !allowed.matches(s)) return null
        if (s.none { it in "+-*/^%×÷" }) return null
        return try {
            val v = Parser(s.replace('×', '*').replace('÷', '/').replace(',', '.')).run()
            if (v.isNaN() || v.isInfinite()) null else format(v).takeIf { it != s }
        } catch (e: Exception) {
            null
        }
    }

    private fun format(v: Double): String {
        if (v != 0.0 && (abs(v) >= 1e15 || abs(v) < 1e-9)) return String.format("%.6e", v)
        return BigDecimal(v).round(MathContext(12)).stripTrailingZeros()
            .toPlainString().replace('.', ',')
    }

    private class Parser(val s: String) {
        var i = 0
        fun run(): Double {
            val v = expr()
            if (peek() != '\u0000') error("reste")
            return v
        }

        fun peek(): Char {
            while (i < s.length && s[i] == ' ') i++
            return if (i < s.length) s[i] else '\u0000'
        }

        fun expr(): Double {
            var v = term()
            while (true) {
                when (peek()) {
                    '+' -> { i++; v += term() }
                    '-' -> { i++; v -= term() }
                    else -> return v
                }
            }
        }

        fun term(): Double {
            var v = unary()
            while (true) {
                when (peek()) {
                    '*' -> { i++; v *= unary() }
                    '/' -> { i++; v /= unary() }
                    else -> return v
                }
            }
        }

        fun unary(): Double = when (peek()) {
            '-' -> { i++; -unary() }
            '+' -> { i++; unary() }
            else -> power()
        }

        fun power(): Double {
            val b = postfix()
            if (peek() == '^') { i++; return b.pow(unary()) }
            return b
        }

        fun postfix(): Double {
            var v = primary()
            while (peek() == '%') { i++; v /= 100.0 }
            return v
        }

        fun primary(): Double {
            val c = peek()
            if (c == '(') {
                i++
                val v = expr()
                if (peek() != ')') error("parenthèse")
                i++
                return v
            }
            if (c.isDigit() || c == '.') {
                val st = i
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                return s.substring(st, i).toDouble()
            }
            if (c.isLetter()) {
                val st = i
                while (i < s.length && s[i].isLetter()) i++
                val name = s.substring(st, i)
                when (name) {
                    "pi", "π" -> return Math.PI
                    "e" -> return Math.E
                }
                if (peek() != '(') error("fonction")
                i++
                val a = expr()
                if (peek() != ')') error("parenthèse")
                i++
                return when (name) {
                    "sqrt" -> Math.sqrt(a)
                    "sin" -> Math.sin(a)
                    "cos" -> Math.cos(a)
                    "tan" -> Math.tan(a)
                    "ln" -> Math.log(a)
                    "log" -> Math.log10(a)
                    "abs" -> abs(a)
                    "exp" -> Math.exp(a)
                    else -> error("inconnue")
                }
            }
            error("inattendu")
        }
    }
}