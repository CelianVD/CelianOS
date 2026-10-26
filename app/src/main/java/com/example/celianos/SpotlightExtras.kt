package com.example.celianos

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.Settings
import android.text.Html
import android.net.Uri
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ============================================================
//  Extras Spotlight : Wiktionnaire, raccourcis de réglages,
//  réponse IA intégrée (API), agenda
// ============================================================

// ---------------- Wiktionnaire ----------------

class DictSection(val pos: String, val defs: List<String>)
class DictEntry(val word: String, val sections: List<DictSection>, val url: String)

object SpotlightDict {
    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 5000
        c.setRequestProperty("User-Agent", "CelianOS/1.0 (application Android personnelle)")
        return try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    /** Un seul mot : la page existe-t-elle sur le Wiktionnaire ? Si oui, renvoie toutes les définitions. */
    suspend fun lookup(raw: String): DictEntry? = withContext(Dispatchers.IO) {
        val w = raw.trim()
        if (w.contains(' ') || w.length < 2 || w.length > 30) return@withContext null
        runCatching {
            val j = JSONObject(
                get("https://fr.wiktionary.org/api/rest_v1/page/definition/" + Uri.encode(w.lowercase()))
            )
            val arr: JSONArray = j.optJSONArray("fr") ?: return@withContext null
            val sections = ArrayList<DictSection>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val defs = ArrayList<String>()
                val ds = o.optJSONArray("definitions") ?: continue
                for (k in 0 until ds.length()) {
                    val html = ds.getJSONObject(k).optString("definition")
                    @Suppress("DEPRECATION")
                    val txt = Html.fromHtml(html).toString().trim()
                    if (txt.isNotEmpty()) defs += txt
                    if (defs.size >= 8) break
                }
                if (defs.isNotEmpty()) sections += DictSection(o.optString("partOfSpeech"), defs)
            }
            if (sections.isEmpty()) null
            else DictEntry(w, sections, "https://fr.wiktionary.org/wiki/" + Uri.encode(w.lowercase()))
        }.getOrNull()
    }
}

// ---------------- Raccourcis de réglages Android ----------------

class SettingsShortcut(val title: String, val action: String, vararg val kw: String)

object SpotlightSettingsShortcuts {
    private val ALL = listOf(
        SettingsShortcut("Wi-Fi", Settings.ACTION_WIFI_SETTINGS, "wifi", "wi-fi", "internet"),
        SettingsShortcut("Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS, "bluetooth"),
        SettingsShortcut("Luminosité et affichage", Settings.ACTION_DISPLAY_SETTINGS, "luminosité", "luminosite", "écran", "affichage", "brightness", "mode sombre"),
        SettingsShortcut("Son et volume", Settings.ACTION_SOUND_SETTINGS, "son", "volume", "sonnerie"),
        SettingsShortcut("Batterie", Settings.ACTION_BATTERY_SAVER_SETTINGS, "batterie", "économie d'énergie"),
        SettingsShortcut("Localisation", Settings.ACTION_LOCATION_SOURCE_SETTINGS, "localisation", "gps", "position"),
        SettingsShortcut("Mode avion", Settings.ACTION_AIRPLANE_MODE_SETTINGS, "avion", "airplane"),
        SettingsShortcut("NFC", Settings.ACTION_NFC_SETTINGS, "nfc", "paiement"),
        SettingsShortcut("Données mobiles", Settings.ACTION_DATA_ROAMING_SETTINGS, "données", "donnees", "data", "roaming", "réseau mobile"),
        SettingsShortcut("Point d'accès", "android.settings.TETHER_SETTINGS", "hotspot", "point d'accès", "partage de connexion"),
        SettingsShortcut("Applications", Settings.ACTION_APPLICATION_SETTINGS, "applications", "applis"),
        SettingsShortcut("Stockage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "stockage", "mémoire", "espace"),
        SettingsShortcut("Accessibilité", Settings.ACTION_ACCESSIBILITY_SETTINGS, "accessibilité", "accessibilite"),
        SettingsShortcut("Date et heure", Settings.ACTION_DATE_SETTINGS, "date", "heure", "fuseau"),
        SettingsShortcut("Langue", Settings.ACTION_LOCALE_SETTINGS, "langue", "langues"),
        SettingsShortcut("VPN", Settings.ACTION_VPN_SETTINGS, "vpn"),
        SettingsShortcut("Sécurité", Settings.ACTION_SECURITY_SETTINGS, "sécurité", "securite", "verrouillage"),
        SettingsShortcut("Confidentialité", Settings.ACTION_PRIVACY_SETTINGS, "confidentialité", "confidentialite"),
        SettingsShortcut("Réglages", Settings.ACTION_SETTINGS, "réglages", "reglages", "paramètres", "parametres")
    )

    fun match(q: String): List<Hit.SettingHit> {
        if (q.length < 2) return emptyList()
        return ALL.filter { s ->
            s.kw.any { k ->
                val n = SpotlightSearch.norm(k)
                n.startsWith(q) || (q.length >= 4 && n.contains(q))
            }
        }.take(2).map { Hit.SettingHit(it.title, it.action) }
    }
}

// ---------------- Réponse IA intégrée (API du fournisseur choisi) ----------------

/** style : 0 = format OpenAI, 1 = Gemini, 2 = Anthropic */
class QuickAiProvider(val id: String, val name: String, val url: String, val defaultModel: String, val style: Int)

object SpotlightAiApi {
    val PROVIDERS = listOf(
        QuickAiProvider("gemini", "Gemini", "https://generativelanguage.googleapis.com/v1beta/models/", "gemini-flash-latest", 1),
        QuickAiProvider("mistral", "Mistral", "https://api.mistral.ai/v1/chat/completions", "mistral-small-latest", 0),
        QuickAiProvider("openai", "ChatGPT (OpenAI)", "https://api.openai.com/v1/chat/completions", "gpt-4o-mini", 0),
        QuickAiProvider("claude", "Claude", "https://api.anthropic.com/v1/messages", "claude-haiku-5-5", 2),
        QuickAiProvider("deepseek", "DeepSeek", "https://api.deepseek.com/chat/completions", "deepseek-chat", 0),
        QuickAiProvider("perplexity", "Perplexity", "https://api.perplexity.ai/chat/completions", "sonar", 0)
    )

    private const val SYSTEM =
        "Tu es l'assistant d'un lanceur Android. Réponds en français, de façon claire et très concise " +
                "(4 phrases maximum), sans mise en forme markdown."

    fun current(): QuickAiProvider = PROVIDERS[SpotlightState.aiProvider.coerceIn(0, PROVIDERS.lastIndex)]

    // clés et modèles stockés : une ligne « id<TAB>valeur »
    private fun read(raw: String): MutableMap<String, String> = raw.lines().mapNotNull {
        val i = it.indexOf('\t'); if (i < 0) null else it.substring(0, i) to it.substring(i + 1)
    }.toMap().toMutableMap()

    private fun write(m: Map<String, String>) = m.entries.joinToString("\n") { "${it.key}\t${it.value}" }
    private fun clean(v: String) = v.trim().replace('\n', ' ').replace('\t', ' ')

    // clés chiffrées avec l'Android Keystore (voir SecretStore) ; petit cache pour ne pas déchiffrer à chaque frappe
    private val keyCache = HashMap<String, Pair<String, String>>()

    fun key(id: String): String {
        val raw = read(SpotlightState.aiKeys)[id] ?: return ""
        keyCache[id]?.let { if (it.first == raw) return it.second }
        return SecretStore.decrypt(raw).also { keyCache[id] = raw to it }
    }

    fun setKey(id: String, v: String) {
        val m = read(SpotlightState.aiKeys); m[id] = SecretStore.encrypt(clean(v)); SpotlightState.aiKeys = write(m)
    }

    fun model(p: QuickAiProvider): String =
        read(SpotlightState.aiModels)[p.id]?.takeIf { it.isNotBlank() }
            ?: SpotlightAiRouter.chosen(p.id, AiTask.PLAN)
            ?: SpotlightAiRouter.rank(p.id, AiTask.PLAN, SpotlightAiRouter.cachedModels(p.id)).firstOrNull()
            ?: p.defaultModel
    fun setModel(id: String, v: String) {
        val m = read(SpotlightState.aiModels); m[id] = clean(v); SpotlightState.aiModels = write(m)
    }

    fun ready(): Boolean = SpotlightState.aiInline && key(current().id).isNotBlank()

    fun post(url: String, headers: Map<String, String>, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = 45_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) error("Erreur $code : ${txt.take(200)}")
            return txt
        } finally {
            c.disconnect()
        }
    }

    /** Une seule question (sans historique). */
    suspend fun ask(p: QuickAiProvider, query: String): String = ask(p, listOf("user" to query))

    /** Conversation : liste de paires (« user » | « assistant », texte), la dernière étant celle de l'utilisateur. */
    suspend fun ask(p: QuickAiProvider, history: List<Pair<String, String>>, system: String = SYSTEM): String = withContext(Dispatchers.IO) {
        val key = key(p.id)
        if (key.isBlank()) error("Clé API manquante (Réglages Spotlight > IA).")
        val model = model(p)
        val out = when (p.style) {
            1 -> {
                val contents = JSONArray()
                history.forEach { (role, text) ->
                    contents.put(
                        JSONObject().put("role", if (role == "user") "user" else "model")
                            .put("parts", JSONArray().put(JSONObject().put("text", text)))
                    )
                }
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                    .put("contents", contents)
                val r = JSONObject(post(p.url + model + ":generateContent", mapOf("x-goog-api-key" to key), body.toString()))
                r.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                    .getJSONArray("parts").getJSONObject(0).getString("text")
            }
            2 -> {
                val msgs = JSONArray()
                history.forEach { (role, text) -> msgs.put(JSONObject().put("role", role).put("content", text)) }
                val body = JSONObject().put("model", model).put("max_tokens", 600).put("system", system).put("messages", msgs)
                val r = JSONObject(
                    post(p.url, mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), body.toString())
                )
                r.getJSONArray("content").getJSONObject(0).getString("text")
            }
            else -> {
                val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
                history.forEach { (role, text) -> msgs.put(JSONObject().put("role", role).put("content", text)) }
                val body = JSONObject().put("model", model).put("max_tokens", 600).put("messages", msgs)
                val r = JSONObject(post(p.url, mapOf("Authorization" to "Bearer $key"), body.toString()))
                r.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            }
        }
        out.replace("**", "").trim()
    }
}

// ---------------- Agenda ----------------

class AgendaEvent(val title: String, val begin: Long, val allDay: Boolean)

object SpotlightAgenda {
    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    suspend fun upcoming(ctx: Context): List<AgendaEvent> = withContext(Dispatchers.IO) {
        if (!hasPermission(ctx)) return@withContext emptyList()
        runCatching {
            val now = System.currentTimeMillis()
            val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(b, now)
            ContentUris.appendId(b, now + 2 * 24 * 3600_000L)
            val out = ArrayList<AgendaEvent>()
            ctx.contentResolver.query(
                b.build(),
                arrayOf(
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.ALL_DAY
                ),
                null, null, CalendarContract.Instances.BEGIN + " ASC"
            )?.use { c ->
                while (c.moveToNext() && out.size < 4) {
                    out += AgendaEvent(c.getString(0) ?: "(sans titre)", c.getLong(1), c.getInt(2) == 1)
                }
            }
            out
        }.getOrDefault(emptyList())
    }

    fun label(e: AgendaEvent): String {
        if (e.allDay) return "Journée"
        val cal = Calendar.getInstance().apply { timeInMillis = e.begin }
        val today = Calendar.getInstance()
        val sameDay = cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        return SimpleDateFormat(if (sameDay) "HH:mm" else "EEE HH:mm", Locale.getDefault()).format(Date(e.begin))
    }
}